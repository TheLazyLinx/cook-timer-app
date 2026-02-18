package ro.maris.tools.cooktimer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import androidx.core.content.ContextCompat

import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.math.abs

data class SoundDetectionEvent(
    val timestampMs: Long,
    val durationMs: Int,
    val strength: Float,
    val peak: Float,
    val type: String = "Unknown",
    val centroid: Float = 0f,
    val tilt: Float = 0f,
    val zcr: Float = 0f,
    val flatness: Float = 0f,
    val confidence: Float = 1.0f,
    val samples: List<Float> = emptyList() // RMS trace over time (hop-based)
)

data class SoundDetectorState(
    val isLit: Boolean = false,
    val count: Int = 0,
    val lastStrength: Float = 0f,
    val lastPeak: Float = 0f,
    val lastConfidence: Float = 0f,
    val rms: Float = 0f,
    val noiseFloor: Float = 0f,
    val currentRms: Float = 0f,
    val lastRejectionReason: String? = null,
    val debugInfo: String = "",
    val events: List<SoundDetectionEvent> = emptyList(),
    val logs: List<String> = emptyList(),
    val diagnostics: List<DetectionDiagnostic> = emptyList()
)

internal data class CadenceProbe(
    val timestampMs: Long,
    val durationMs: Int,
    val centroid: Float,
    val tilt: Float,
    val flatness: Float,
    val zcr: Float
)

internal data class CadencePatternStats(
    val eventCount: Int,
    val meanIntervalMs: Float,
    val intervalCv: Float
)

internal object CadencePatternHeuristics {
    const val DEFAULT_WINDOW_MS = 1600L
    const val DEFAULT_MIN_EVENTS = 4
    const val DEFAULT_SUPPRESSION_MS = 1200L
    const val DEFAULT_PROBE_CONFIDENCE_MIN = 0.80f
    const val DEFAULT_PROBE_DURATION_MIN_MS = 40
    const val DEFAULT_PROBE_DURATION_MAX_MS = 180
    const val DEFAULT_INTERVAL_MIN_MS = 140L
    const val DEFAULT_INTERVAL_MAX_MS = 520L
    const val DEFAULT_INTERVAL_CV_MAX = 0.22f
    const val DEFAULT_CENTROID_SPREAD_PCT_MAX = 0.18f
    const val DEFAULT_TILT_SPREAD_MAX = 0.40f
    const val DEFAULT_FLATNESS_SPREAD_MAX = 0.16f
    const val DEFAULT_ZCR_SPREAD_MAX = 0.16f
    const val DEFAULT_DURATION_SPREAD_PCT_MAX = 0.55f
    const val DEFAULT_FOLLOWUP_WINDOW_MS = 420L

    fun isSuppressed(nowMs: Long, suppressUntilMs: Long): Boolean {
        return nowMs < suppressUntilMs
    }

    fun isFirstTapLike(
        candidateAgeMs: Long,
        acceptedAgeMs: Long,
        followupWindowMs: Long = DEFAULT_FOLLOWUP_WINDOW_MS
    ): Boolean {
        val hasTightFollowup =
            candidateAgeMs in 20L..followupWindowMs ||
                acceptedAgeMs in 20L..followupWindowMs
        return !hasTightFollowup
    }

    fun pruneHistory(
        history: ArrayDeque<CadenceProbe>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        maxCount: Int = 24
    ) {
        while (history.isNotEmpty() && nowMs - history.first().timestampMs > windowMs) {
            history.removeFirst()
        }
        while (history.size > maxCount) {
            history.removeFirst()
        }
    }

    fun detectPattern(
        history: List<CadenceProbe>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minEvents: Int = DEFAULT_MIN_EVENTS,
        intervalMinMs: Long = DEFAULT_INTERVAL_MIN_MS,
        intervalMaxMs: Long = DEFAULT_INTERVAL_MAX_MS,
        intervalCvMax: Float = DEFAULT_INTERVAL_CV_MAX,
        centroidSpreadPctMax: Float = DEFAULT_CENTROID_SPREAD_PCT_MAX,
        tiltSpreadMax: Float = DEFAULT_TILT_SPREAD_MAX,
        flatnessSpreadMax: Float = DEFAULT_FLATNESS_SPREAD_MAX,
        zcrSpreadMax: Float = DEFAULT_ZCR_SPREAD_MAX,
        durationSpreadPctMax: Float = DEFAULT_DURATION_SPREAD_PCT_MAX
    ): CadencePatternStats? {
        if (history.size < minEvents) return null
        val windowed = history.filter { nowMs - it.timestampMs in 0..windowMs }
        if (windowed.size < minEvents) return null

        val maxSpan = minOf(windowed.size, 8)
        for (span in maxSpan downTo minEvents) {
            val events = windowed.takeLast(span)
            val stats = evaluateWindow(
                events = events,
                intervalMinMs = intervalMinMs,
                intervalMaxMs = intervalMaxMs,
                intervalCvMax = intervalCvMax,
                centroidSpreadPctMax = centroidSpreadPctMax,
                tiltSpreadMax = tiltSpreadMax,
                flatnessSpreadMax = flatnessSpreadMax,
                zcrSpreadMax = zcrSpreadMax,
                durationSpreadPctMax = durationSpreadPctMax
            )
            if (stats != null) return stats
        }
        return null
    }

    private fun evaluateWindow(
        events: List<CadenceProbe>,
        intervalMinMs: Long,
        intervalMaxMs: Long,
        intervalCvMax: Float,
        centroidSpreadPctMax: Float,
        tiltSpreadMax: Float,
        flatnessSpreadMax: Float,
        zcrSpreadMax: Float,
        durationSpreadPctMax: Float
    ): CadencePatternStats? {
        if (events.size < 4) return null
        val intervals = mutableListOf<Long>()
        for (i in 1 until events.size) {
            val interval = events[i].timestampMs - events[i - 1].timestampMs
            if (interval !in intervalMinMs..intervalMaxMs) return null
            intervals += interval
        }
        if (intervals.isEmpty()) return null

        val meanInterval = intervals.average().toFloat()
        if (meanInterval <= 0f) return null
        var variance = 0f
        intervals.forEach { interval ->
            val d = interval.toFloat() - meanInterval
            variance += d * d
        }
        variance /= intervals.size.toFloat()
        val stdDev = sqrt(variance)
        val intervalCv = stdDev / meanInterval
        if (intervalCv > intervalCvMax) return null

        val centroidMax = events.maxOf { it.centroid }
        val centroidMin = events.minOf { it.centroid }
        val centroidAvg = events.map { it.centroid }.average().toFloat().coerceAtLeast(500f)
        val centroidSpreadPct = (centroidMax - centroidMin) / centroidAvg
        if (centroidSpreadPct > centroidSpreadPctMax) return null

        val tiltSpread = events.maxOf { it.tilt } - events.minOf { it.tilt }
        if (tiltSpread > tiltSpreadMax) return null

        val flatnessSpread = events.maxOf { it.flatness } - events.minOf { it.flatness }
        if (flatnessSpread > flatnessSpreadMax) return null

        val zcrSpread = events.maxOf { it.zcr } - events.minOf { it.zcr }
        if (zcrSpread > zcrSpreadMax) return null

        val durationMax = events.maxOf { it.durationMs }.toFloat().coerceAtLeast(1f)
        val durationMin = events.minOf { it.durationMs }.toFloat()
        val durationSpreadPct = (durationMax - durationMin) / durationMax
        if (durationSpreadPct > durationSpreadPctMax) return null

        return CadencePatternStats(
            eventCount = events.size,
            meanIntervalMs = meanInterval,
            intervalCv = intervalCv
        )
    }
}

internal fun shouldRejectCadencePattern(
    isSuppressed: Boolean,
    isGestureFamily: Boolean,
    firstTapLike: Boolean,
    sessionSimilarity: Float?,
    sessionBypassThreshold: Float,
    acceptedSignatureBypass: Boolean
): Boolean {
    if (!isSuppressed || !isGestureFamily || !firstTapLike) return false
    val sessionBypass = (sessionSimilarity ?: 0f) >= sessionBypassThreshold
    return !sessionBypass && !acceptedSignatureBypass
}

@Singleton
class SoundDetector @Inject constructor(
    @ApplicationContext context: Context,
    private val sfxPlayer: SfxPlayer
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SoundDetectorState())
    val state: StateFlow<SoundDetectorState> = _state
    
    var debugContext: String = "Unknown" 

    private val sampleRate = 16000
    private val fftWindowMs = 10
    private val hopMs = 5
    private val windowSamples = sampleRate * fftWindowMs / 1000
    private val hopSamples = sampleRate * hopMs / 1000
    private fun framesForMs(ms: Int): Int = ((ms + hopMs - 1) / hopMs).coerceAtLeast(1)
    private fun durationForFrames(frames: Int): Int = frames * hopMs
    
    // Dynamic Thresholds
    // Lowered back down - 0.025 was obstructing -30dB slaps (~0.032).
    private val baseMinRms = 0.035f 
    private val quietModeMinRms = 0.025f 
    private val thresholdMultiplier = 4.0f 
    // Rise Slow (0.005) to avoid learning speech/music as noise.
    private val noiseAlphaRise = 0.005f 
    private val noiseAlphaFall = 0.050f // Increased fall speed to recover faster from "Snap Sprees"
    private val maxEvents = 50 // Increased history size
    private val maxLogs = 750 // Limit logs to prevent memory bloat
    private val maxDiagnostics = 100 // Diagnostic entries for tuning screen
    private val firstTapConfidenceThreshold = 0.82f
    private val followUpTapConfidenceThreshold = 0.74f
    
    // Chaos Rejection (Pill Rattle, Continuous Noise)
    private val postEventCheckFrames = framesForMs(50) // Wait 50ms after event ends to verify quiet
    private val postEventQuietRatio = 0.5f // Post-event RMS must drop below 50% of threshold
    private val playbackBlankingMs = 200L
    private val playbackBlankDiagCooldownMs = 250L
    private val nuisanceSimilarityRejectThreshold = 0.82f
    private val nuisanceSimilarityRejectThresholdMetallic = 0.88f
    private val nuisanceSimilaritySessionBypassThreshold = 0.78f
    private val nuisanceReferenceTtlMs = 8 * 60 * 1000L
    private val nuisanceReferenceMaxCount = 18
    private val nuisanceMinReferencesToGate = 3
    private val nuisanceMinStrongReferencesToGate = 1
    private val cadenceWindowMs = CadencePatternHeuristics.DEFAULT_WINDOW_MS
    private val cadenceMinEvents = CadencePatternHeuristics.DEFAULT_MIN_EVENTS
    private val cadenceSuppressionMs = CadencePatternHeuristics.DEFAULT_SUPPRESSION_MS
    private val cadenceProbeConfidenceMin = CadencePatternHeuristics.DEFAULT_PROBE_CONFIDENCE_MIN
    private val cadenceProbeDurationMinMs = CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MIN_MS
    private val cadenceProbeDurationMaxMs = CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MAX_MS
    private val cadenceIntervalMinMs = CadencePatternHeuristics.DEFAULT_INTERVAL_MIN_MS
    private val cadenceIntervalMaxMs = CadencePatternHeuristics.DEFAULT_INTERVAL_MAX_MS
    private val cadenceIntervalCvMax = CadencePatternHeuristics.DEFAULT_INTERVAL_CV_MAX
    private val cadenceCentroidSpreadPctMax = CadencePatternHeuristics.DEFAULT_CENTROID_SPREAD_PCT_MAX
    private val cadenceTiltSpreadMax = CadencePatternHeuristics.DEFAULT_TILT_SPREAD_MAX
    private val cadenceFlatnessSpreadMax = CadencePatternHeuristics.DEFAULT_FLATNESS_SPREAD_MAX
    private val cadenceZcrSpreadMax = CadencePatternHeuristics.DEFAULT_ZCR_SPREAD_MAX
    private val cadenceDurationSpreadPctMax = CadencePatternHeuristics.DEFAULT_DURATION_SPREAD_PCT_MAX
    private val cadenceDiagCooldownMs = 300L
    private val cadenceFollowupWindowMs = CadencePatternHeuristics.DEFAULT_FOLLOWUP_WINDOW_MS

    private data class SessionTapReference(
        val family: String,
        val centroid: Float,
        val tilt: Float,
        val zcr: Float,
        val flatness: Float,
        val durationMs: Int,
        val strength: Float,
        val timestampMs: Long
    )

    private val sessionReferences = ConcurrentHashMap<String, SessionTapReference>()

    private data class SessionNuisanceReference(
        val stage: String,
        val centroid: Float,
        val tilt: Float,
        val zcr: Float,
        val flatness: Float,
        val durationMs: Int,
        val strength: Float,
        val attackRatio: Float,
        val timestampMs: Long
    )

    private data class NuisanceSimilarityResult(
        val similarity: Float,
        val sourceStage: String?,
        val totalReferences: Int,
        val strongReferenceCount: Int
    )

    private val sessionNuisanceReferences = mutableListOf<SessionNuisanceReference>()
    
    // Profiles
    data class SoundProfile(
        val name: String,
        val minDurMs: Int,
        val maxDurMs: Int,
        val minZcr: Float,
        val maxZcr: Float,
        val minCentroid: Float,
        val maxCentroid: Float,
        val minTilt: Float = -100f,
        val maxTilt: Float = 100f
    )
    
    private val profiles = listOf(
        // Notes on Tilt:
        // Snaps have flat or rising spectra (Tilt > -10). Space/Voice drops off (Tilt < -20).
        // Enforcing minTilt excludes speech and dull impacts.
        
        // 1. SNAP HIGH - Adjusted to catch the 4400Hz snaps the user was seeing
        // Lowered minCentroid (4000->3500) because 4404Hz was on the edge.
        // Relaxed maxDur (110->130) because reverb/tail was making snaps appear as 90ms-100ms.
        SoundProfile("Snap (High)", minDurMs = 45, maxDurMs = 130, minZcr = 0.3f, maxZcr = 0.99f, minCentroid = 3500f, maxCentroid = 12000f, minTilt = -5f),
        
        // 2. SNAP MID - Standard snap
        SoundProfile("Snap (Mid)", minDurMs = 45, maxDurMs = 130, minZcr = 0.1f, maxZcr = 0.8f, minCentroid = 1000f, maxCentroid = 4000f, minTilt = -12f),
        
        // 3. SNAP LOW - tightened to avoid table-coupled low-frequency impacts.
        SoundProfile("Snap (Low)", minDurMs = 45, maxDurMs = 80, minZcr = 0.12f, maxZcr = 0.5f, minCentroid = 900f, maxCentroid = 2500f, minTilt = -0.90f), 
        
        // 4. CLAP (AIRY) - The culprit for speech FPs
        // Raised minDur (10->45ms) so short clicks/snaps don't match this.
        // Raised minZcr (0.2->0.4) because speech "bla" is rarely 40% noise, but air friction is.
        SoundProfile("Clap (Airy)", minDurMs = 50, maxDurMs = 200, minZcr = 0.40f, maxZcr = 0.90f, minCentroid = 3000f, maxCentroid = 10000f, minTilt = -8f),

        // 5. CLAP STANDARD
        // Tightened to reduce object-contact false positives in kitchen environments.
        SoundProfile("Clap", minDurMs = 45, maxDurMs = 200, minZcr = 0.08f, maxZcr = 0.60f, minCentroid = 2800f, maxCentroid = 5000f, minTilt = -0.45f, maxTilt = 0.5f),
        
        // 6. CLAP WET (Bass)
        SoundProfile("Clap (Wet)", minDurMs = 40, maxDurMs = 180, minZcr = 0.08f, maxZcr = 0.40f, minCentroid = 500f, maxCentroid = 1500f, minTilt = -1.00f), 
        
        // 7. KNOCK
        // Enforce maxTilt = -5f to ensure it's a "thud" (bass-heavy) and not a flat "click" or "snap" (-1.19).
        SoundProfile("Knock", minDurMs = 15, maxDurMs = 120, minZcr = 0.0f, maxZcr = 0.3f, minCentroid = 50f, maxCentroid = 3000f, minTilt = -25f, maxTilt = -5f)
    )

    private val attackThreshold = 2.0f // Increased from 1.4 to 2.0 to reject soft onsets (groans/sighs)

    private fun calibrationFamily(type: String): String? {
        return when (type.substringBefore(" (").trim()) {
            "Snap" -> "Snap"
            "Clap", "Slap" -> "Clap"
            else -> null
        }
    }

    @Synchronized
    private fun clearSessionCalibration() {
        sessionReferences.clear()
        sessionNuisanceReferences.clear()
    }

    private fun sessionReferenceForType(type: String): SessionTapReference? {
        val family = calibrationFamily(type) ?: return null
        return sessionReferences[family]
    }

    fun onGestureResolvedForCalibration(events: List<SoundDetectionEvent>, tapCount: Int) {
        if (tapCount < 2 || events.isEmpty()) return
        val firstFamily = calibrationFamily(events.first().type) ?: return
        if (sessionReferences.containsKey(firstFamily)) return

        val familyEvents = events.filter { calibrationFamily(it.type) == firstFamily }
        if (familyEvents.isEmpty()) return

        val sortedByStrength = familyEvents.sortedBy { it.strength }
        val median = sortedByStrength[sortedByStrength.size / 2]

        sessionReferences.putIfAbsent(
            firstFamily,
            SessionTapReference(
                family = firstFamily,
                centroid = median.centroid,
                tilt = median.tilt,
                zcr = median.zcr,
                flatness = median.flatness,
                durationMs = median.durationMs,
                strength = median.strength,
                timestampMs = median.timestampMs
            )
        )
    }

    private fun sessionSimilarity(
        ref: SessionTapReference,
        centroid: Float,
        tilt: Float,
        zcr: Float,
        flatness: Float,
        durationMs: Int,
        strength: Float
    ): Float {
        val centroidScore = (1f - (abs(centroid - ref.centroid) / ref.centroid.coerceAtLeast(600f))).coerceIn(0f, 1f)
        val tiltScore = (1f - (abs(tilt - ref.tilt) / 6f)).coerceIn(0f, 1f)
        val zcrScore = (1f - (abs(zcr - ref.zcr) / 0.22f)).coerceIn(0f, 1f)
        val flatnessScore = (1f - (abs(flatness - ref.flatness) / 0.25f)).coerceIn(0f, 1f)
        val durationScore =
            (1f - (abs(durationMs - ref.durationMs).toFloat() / ref.durationMs.coerceAtLeast(20).toFloat())).coerceIn(0f, 1f)
        val strengthRatio = strength / ref.strength.coerceAtLeast(0.001f)
        val strengthScore = when {
            strengthRatio in 0.30f..3.00f -> 1f
            strengthRatio < 0.30f -> (strengthRatio / 0.30f).coerceIn(0f, 1f)
            else -> (3.00f / strengthRatio).coerceIn(0f, 1f)
        }
        return (
            centroidScore * 0.26f +
                tiltScore * 0.18f +
                zcrScore * 0.18f +
                flatnessScore * 0.20f +
                durationScore * 0.12f +
                strengthScore * 0.06f
            ).coerceIn(0f, 1f)
    }

    @Synchronized
    private fun rememberNuisanceReference(
        stage: String,
        durationMs: Int,
        zcr: Float,
        centroid: Float,
        tilt: Float,
        flatness: Float,
        attackRatio: Float,
        strength: Float,
        timestampMs: Long
    ) {
        if (durationMs <= 0 || strength <= 0f) return
        pruneOldNuisanceReferences(timestampMs)

        val duplicateSimilarity = sessionNuisanceReferences.maxOfOrNull { ref ->
            nuisanceSimilarity(
                ref = ref,
                durationMs = durationMs,
                zcr = zcr,
                centroid = centroid,
                tilt = tilt,
                flatness = flatness,
                attackRatio = attackRatio,
                strength = strength
            )
        } ?: 0f
        if (duplicateSimilarity >= 0.90f) return

        sessionNuisanceReferences.add(
            SessionNuisanceReference(
                stage = stage,
                centroid = centroid,
                tilt = tilt,
                zcr = zcr,
                flatness = flatness,
                durationMs = durationMs,
                strength = strength,
                attackRatio = attackRatio,
                timestampMs = timestampMs
            )
        )
        if (sessionNuisanceReferences.size > nuisanceReferenceMaxCount) {
            sessionNuisanceReferences.removeAt(0)
        }
    }

    @Synchronized
    private fun bestNuisanceSimilarity(
        durationMs: Int,
        zcr: Float,
        centroid: Float,
        tilt: Float,
        flatness: Float,
        attackRatio: Float,
        strength: Float,
        nowMs: Long
    ): NuisanceSimilarityResult {
        pruneOldNuisanceReferences(nowMs)
        val totalReferences = sessionNuisanceReferences.size
        val strongReferenceCount = sessionNuisanceReferences.count { isStrongNuisanceStage(it.stage) }
        var best = 0f
        var source: String? = null
        for (ref in sessionNuisanceReferences) {
            val similarity = nuisanceSimilarity(
                ref = ref,
                durationMs = durationMs,
                zcr = zcr,
                centroid = centroid,
                tilt = tilt,
                flatness = flatness,
                attackRatio = attackRatio,
                strength = strength
            )
            if (similarity > best) {
                best = similarity
                source = ref.stage
            }
        }
        return NuisanceSimilarityResult(
            similarity = best,
            sourceStage = source,
            totalReferences = totalReferences,
            strongReferenceCount = strongReferenceCount
        )
    }

    private fun isStrongNuisanceStage(stage: String): Boolean {
        return stage == "SURFACE_TAP" ||
            stage == "HARD_SURFACE" ||
            stage == "CHAOS_POST_RING" ||
            stage == "METALLIC" ||
            stage == "RESONANT_IMPACT" ||
            stage == "BRIGHT_IMPACT" ||
            stage == "ATTACK_SHAPE" ||
            stage == "CADENCE_PATTERN" ||
            stage == "ULTRA_BRIGHT_PING"
    }

    private fun isStrongAcceptedSignatureMatchForCadenceBypass(
        bestType: String?,
        eventTimestampMs: Long,
        durationMs: Int,
        centroid: Float,
        tilt: Float,
        strength: Float,
        refType: String?,
        refTimestampMs: Long,
        refDurationMs: Int,
        refCentroid: Float,
        refTilt: Float,
        refStrength: Float,
        followupWindowMs: Long
    ): Boolean {
        if (bestType == null || refType == null) return false
        val ageMs = eventTimestampMs - refTimestampMs
        if (ageMs !in 20L..followupWindowMs) return false

        val bestFamily = bestType.substringBefore(" (").trim()
        val refFamily = refType.substringBefore(" (").trim()
        val typeOrFamilyMatch = bestType == refType || bestFamily == refFamily
        if (!typeOrFamilyMatch) return false

        val durationDelta = abs(durationMs - refDurationMs)
        val centroidDeltaPct = abs(centroid - refCentroid) / refCentroid.coerceAtLeast(500f)
        val tiltDelta = abs(tilt - refTilt)
        val strengthRatio = strength / refStrength.coerceAtLeast(0.001f)

        return durationDelta <= 45 &&
            centroidDeltaPct <= 0.20f &&
            tiltDelta <= 0.35f &&
            strengthRatio in 0.35f..2.80f
    }

    private fun nuisanceSimilarity(
        ref: SessionNuisanceReference,
        durationMs: Int,
        zcr: Float,
        centroid: Float,
        tilt: Float,
        flatness: Float,
        attackRatio: Float,
        strength: Float
    ): Float {
        val centroidScore = (1f - (abs(centroid - ref.centroid) / ref.centroid.coerceAtLeast(700f))).coerceIn(0f, 1f)
        val tiltScore = (1f - (abs(tilt - ref.tilt) / 1.7f)).coerceIn(0f, 1f)
        val zcrScore = (1f - (abs(zcr - ref.zcr) / 0.18f)).coerceIn(0f, 1f)
        val flatnessScore = (1f - (abs(flatness - ref.flatness) / 0.16f)).coerceIn(0f, 1f)
        val durationScore =
            (1f - (abs(durationMs - ref.durationMs).toFloat() / ref.durationMs.coerceAtLeast(30).toFloat())).coerceIn(0f, 1f)
        val attackScore =
            (1f - (abs(attackRatio - ref.attackRatio) / ref.attackRatio.coerceAtLeast(6f))).coerceIn(0f, 1f)
        val strengthRatio = strength / ref.strength.coerceAtLeast(0.001f)
        val strengthScore = when {
            strengthRatio in 0.45f..2.20f -> 1f
            strengthRatio < 0.45f -> (strengthRatio / 0.45f).coerceIn(0f, 1f)
            else -> (2.20f / strengthRatio).coerceIn(0f, 1f)
        }
        return (
            centroidScore * 0.24f +
                tiltScore * 0.16f +
                zcrScore * 0.16f +
                flatnessScore * 0.20f +
                durationScore * 0.14f +
                attackScore * 0.06f +
                strengthScore * 0.04f
            ).coerceIn(0f, 1f)
    }

    private fun pruneOldNuisanceReferences(nowMs: Long) {
        sessionNuisanceReferences.removeAll { nowMs - it.timestampMs > nuisanceReferenceTtlMs }
    }

    private fun getMetricScore(value: Float, min: Float, max: Float): Float {
        // Soft-Plateau Scoring:
        // Center of range = 1.0
        // Edges of range = 0.8 (Not 1.0, to prevent "barely-in" 100% scores)
        // Outside range = Rapid decay
        
        if (value in min..max) {
            val range = max - min
            if (range < 0.001f) return 1.0f
            
            // 0.0 (min) -> 0.5 (center) -> 1.0 (max)
            val relative = (value - min) / range
            val distFromCenter = abs(relative - 0.5f) // 0.0 to 0.5
            
            // Map 0.0..0.5 difficulty to 1.0..0.8 score
            // 0.0 -> 1.0
            // 0.5 -> 0.8
            return 1.0f - (distFromCenter * 0.4f)
        }
        
        val range = max - min
        
        // Outside range: Start from 0.8 and decay
        val allowedDeviation = range * 0.5f // Generous falloff
        
        if (allowedDeviation < 0.0001f) return 0f
            
        val dist = if (value < min) min - value else value - max
        val penalty = dist / allowedDeviation // 0 to 1
        
        // Start at 0.8 check
        val score = 0.8f - (penalty * 0.8f)
        
        return score.coerceIn(0f, 0.8f)
    }

    private data class Stage1EnvelopeFeatures(
        val earlyEnergyRatio: Float,
        val tailEnergyRatio: Float,
        val bounceRatio: Float,
        val monotonicRiseRatio: Float,
        val decayHalfFrames: Int,
        val decayQuarterFrames: Int
    )

    private fun extractEnvelopeFeatures(
        trace: List<Float>,
        peakRms: Float
    ): Stage1EnvelopeFeatures {
        if (trace.isEmpty() || peakRms <= 0f) {
            return Stage1EnvelopeFeatures(
                earlyEnergyRatio = 0f,
                tailEnergyRatio = 0f,
                bounceRatio = 0f,
                monotonicRiseRatio = 0f,
                decayHalfFrames = Int.MAX_VALUE,
                decayQuarterFrames = Int.MAX_VALUE
            )
        }

        var totalEnergy = 0f
        val energy = FloatArray(trace.size)
        for (i in trace.indices) {
            val e = trace[i] * trace[i]
            energy[i] = e
            totalEnergy += e
        }
        val safeTotal = totalEnergy.coerceAtLeast(1e-8f)

        val earlyFrames = minOf(framesForMs(30), trace.size)
        var earlyEnergy = 0f
        for (i in 0 until earlyFrames) earlyEnergy += energy[i]

        val tailFrames = minOf(framesForMs(60), trace.size)
        var tailEnergy = 0f
        for (i in trace.size - tailFrames until trace.size) tailEnergy += energy[i]

        var peakIndex = 0
        var peakValue = trace[0]
        for (i in 1 until trace.size) {
            if (trace[i] > peakValue) {
                peakValue = trace[i]
                peakIndex = i
            }
        }

        val postPeakCount = (trace.size - peakIndex - 1).coerceAtLeast(0)
        var postRiseCount = 0
        var postTransitions = 0
        if (postPeakCount > 1) {
            for (i in peakIndex + 2 until trace.size) {
                postTransitions++
                if (trace[i] - trace[i - 1] > peakRms * 0.03f) {
                    postRiseCount++
                }
            }
        }
        val bounceRatio = if (postTransitions > 0) {
            postRiseCount.toFloat() / postTransitions.toFloat()
        } else {
            0f
        }

        var globalTransitions = 0
        var globalRiseCount = 0
        if (trace.size > 1) {
            for (i in 1 until trace.size) {
                globalTransitions++
                if (trace[i] - trace[i - 1] > peakRms * 0.02f) {
                    globalRiseCount++
                }
            }
        }
        val monotonicRiseRatio = if (globalTransitions > 0) {
            globalRiseCount.toFloat() / globalTransitions.toFloat()
        } else {
            0f
        }

        var decayHalfFrames = Int.MAX_VALUE
        var decayQuarterFrames = Int.MAX_VALUE
        for (i in peakIndex until trace.size) {
            val fromPeakFrames = i - peakIndex
            if (decayHalfFrames == Int.MAX_VALUE && trace[i] <= peakRms * 0.50f) {
                decayHalfFrames = fromPeakFrames
            }
            if (decayQuarterFrames == Int.MAX_VALUE && trace[i] <= peakRms * 0.25f) {
                decayQuarterFrames = fromPeakFrames
            }
        }

        return Stage1EnvelopeFeatures(
            earlyEnergyRatio = (earlyEnergy / safeTotal).coerceIn(0f, 1f),
            tailEnergyRatio = (tailEnergy / safeTotal).coerceIn(0f, 1f),
            bounceRatio = bounceRatio.coerceIn(0f, 1f),
            monotonicRiseRatio = monotonicRiseRatio.coerceIn(0f, 1f),
            decayHalfFrames = decayHalfFrames,
            decayQuarterFrames = decayQuarterFrames
        )
    }

    private fun computeStage2AgreementScore(
        bestMatch: SoundProfile?,
        profileConfidence: Float,
        durationMs: Int,
        attackRatio: Float,
        peakRms: Float,
        preEventRatio: Float,
        avgZcr: Float,
        avgCentroid: Float,
        avgTilt: Float,
        avgFlatness: Float,
        env: Stage1EnvelopeFeatures
    ): Float {
        if (bestMatch == null) return 0f

        val family = bestMatch.name.substringBefore(" (").trim()
        val isGestureFamily = family == "Snap" || family == "Clap" || family == "Slap"
        if (!isGestureFamily) return profileConfidence.coerceIn(0f, 1f)

        val attackScore = ((attackRatio - 2f) / 14f).coerceIn(0f, 1f)
        val centroidScore = ((avgCentroid - 1000f) / 4000f).coerceIn(0f, 1f)
        val zcrScore = ((avgZcr - 0.08f) / 0.45f).coerceIn(0f, 1f)
        val tiltScore = ((avgTilt + 1.0f) / 1.8f).coerceIn(0f, 1f)
        val flatnessScore = ((avgFlatness - 0.10f) / 0.50f).coerceIn(0f, 1f)
        val decayHalfScore = when {
            env.decayHalfFrames == Int.MAX_VALUE -> 0f
            env.decayHalfFrames <= 6 -> 1f
            env.decayHalfFrames >= 24 -> 0f
            else -> ((24 - env.decayHalfFrames).toFloat() / 18f).coerceIn(0f, 1f)
        }
        val decayQuarterScore = when {
            env.decayQuarterFrames == Int.MAX_VALUE -> 0f
            env.decayQuarterFrames <= 12 -> 1f
            env.decayQuarterFrames >= 36 -> 0f
            else -> ((36 - env.decayQuarterFrames).toFloat() / 24f).coerceIn(0f, 1f)
        }
        val compactTailScore = (1f - env.tailEnergyRatio).coerceIn(0f, 1f)
        val stableEnvelopeScore = (1f - env.bounceRatio).coerceIn(0f, 1f)
        val lowPreNoiseScore = (1f - preEventRatio.coerceIn(0f, 1f))

        var score = 0.10f
        score += profileConfidence.coerceIn(0f, 1f) * 0.24f
        score += attackScore * 0.08f
        score += centroidScore * 0.07f
        score += zcrScore * 0.06f
        score += tiltScore * 0.04f
        score += flatnessScore * 0.10f
        score += decayHalfScore * 0.07f
        score += decayQuarterScore * 0.06f
        score += compactTailScore * 0.07f
        score += stableEnvelopeScore * 0.06f
        score += lowPreNoiseScore * 0.05f

        if (durationMs > 180) score -= 0.08f
        if (durationMs < 20) score -= 0.05f
        if (peakRms < 0.04f) score -= 0.05f
        if (env.monotonicRiseRatio > 0.45f) score -= 0.10f
        if (env.tailEnergyRatio > 0.62f) score -= 0.10f
        if (preEventRatio > 0.55f) score -= 0.07f
        if (avgFlatness < 0.14f) score -= 0.08f

        // Penalize table/contact-like impulses that can still score high on profile overlap.
        val tableCoupledThud =
            avgTilt <= -1.20f &&
                avgCentroid <= 1700f &&
                avgZcr <= 0.18f &&
                durationMs in 40..200
        val dampedContactLike =
            avgTilt <= -0.95f &&
                avgCentroid <= 2200f &&
                avgZcr <= 0.24f &&
                env.tailEnergyRatio >= 0.30f &&
                durationMs in 50..220
        val midBandContactLike =
            avgTilt <= -0.30f &&
                avgCentroid in 2300f..3250f &&
                avgZcr <= 0.38f &&
                durationMs in 60..180
        val resonantImpactLike = isResonantImpactSignature(
            durationMs = durationMs,
            avgZcr = avgZcr,
            avgCentroid = avgCentroid,
            avgTilt = avgTilt,
            avgFlatness = avgFlatness,
            attackRatio = attackRatio,
            peakRms = peakRms,
            env = env
        )
        val ultraBrightPingLike = isUltraBrightPingSignature(
            durationMs = durationMs,
            avgZcr = avgZcr,
            avgCentroid = avgCentroid,
            avgTilt = avgTilt,
            avgFlatness = avgFlatness,
            attackRatio = attackRatio,
            peakRms = peakRms,
            env = env
        )
        if (tableCoupledThud) score -= 0.24f
        if (dampedContactLike) score -= 0.12f
        if (midBandContactLike) score -= 0.14f
        if (resonantImpactLike) score -= 0.18f
        if (ultraBrightPingLike) score -= 0.20f

        return score.coerceIn(0f, 1f)
    }

    private fun isLikelySnapTransient(
        durationMs: Int,
        avgZcr: Float,
        avgCentroid: Float,
        avgTilt: Float,
        avgFlatness: Float,
        attackRatio: Float,
        env: Stage1EnvelopeFeatures
    ): Boolean {
        return durationMs in 35..95 &&
            avgCentroid in 3200f..4200f &&
            avgZcr in 0.34f..0.58f &&
            avgTilt in -0.12f..0.30f &&
            avgFlatness in 0.52f..0.80f &&
            attackRatio >= 8f &&
            env.decayHalfFrames <= framesForMs(55) &&
            env.tailEnergyRatio <= 0.40f
    }

    // Dedicated nuisance signature for resonant object-on-object impacts
    // (knife-on-glass, cutlery-on-ceramic, key clinks). Kept first-tap oriented.
    private fun isResonantImpactSignature(
        durationMs: Int,
        avgZcr: Float,
        avgCentroid: Float,
        avgTilt: Float,
        avgFlatness: Float,
        attackRatio: Float,
        peakRms: Float,
        env: Stage1EnvelopeFeatures
    ): Boolean {
        val longResonantBand =
            durationMs in 85..180 &&
                avgCentroid in 3300f..4600f &&
                avgZcr in 0.34f..0.54f &&
                avgTilt in -0.08f..0.45f &&
                avgFlatness in 0.24f..0.52f
        val longLowFlatMaterialBand =
            durationMs in 100..190 &&
                avgCentroid in 3000f..4500f &&
                avgZcr in 0.34f..0.50f &&
                avgTilt in -0.60f..0.30f &&
                avgFlatness in 0.22f..0.55f
        val shortBrightBand =
            durationMs in 45..95 &&
                avgCentroid in 4100f..5200f &&
                avgZcr in 0.42f..0.68f &&
                avgTilt in 0.24f..0.80f &&
                avgFlatness in 0.48f..0.85f
        val midBrightMaterialBand =
            durationMs in 90..150 &&
                avgCentroid in 4000f..5200f &&
                avgZcr in 0.38f..0.60f &&
                avgTilt in 0.20f..0.70f &&
                avgFlatness in 0.35f..0.58f
        val hasRingingEnvelope =
            env.tailEnergyRatio >= 0.24f ||
                env.decayHalfFrames >= framesForMs(55) ||
                env.bounceRatio >= 0.18f
        val likelySnapTransientCore = isLikelySnapTransient(
            durationMs = durationMs,
            avgZcr = avgZcr,
            avgCentroid = avgCentroid,
            avgTilt = avgTilt,
            avgFlatness = avgFlatness,
            attackRatio = attackRatio,
            env = env
        )
        val likelySnapMidTransient =
            durationMs in 45..110 &&
                avgCentroid in 3350f..4050f &&
                avgZcr in 0.35f..0.48f &&
                avgTilt in -0.12f..0.20f &&
                avgFlatness in 0.34f..0.62f &&
                attackRatio >= 6f &&
                env.tailEnergyRatio <= 0.33f &&
                env.decayHalfFrames <= framesForMs(70)
        val likelySnapTransient = likelySnapTransientCore || likelySnapMidTransient
        val strongEnough = peakRms >= 0.08f
        val resonantMaterialBand =
            longResonantBand || longLowFlatMaterialBand || shortBrightBand || midBrightMaterialBand
        return resonantMaterialBand && hasRingingEnvelope && !likelySnapTransient && strongEnough
    }

    private fun isUltraBrightPingSignature(
        durationMs: Int,
        avgZcr: Float,
        avgCentroid: Float,
        avgTilt: Float,
        avgFlatness: Float,
        attackRatio: Float,
        peakRms: Float,
        env: Stage1EnvelopeFeatures
    ): Boolean {
        val shortHighBand =
            durationMs in 55..130 &&
                avgCentroid >= 4500f &&
                avgZcr >= 0.46f &&
                avgTilt >= 0.30f &&
                avgFlatness in 0.40f..0.62f &&
                peakRms >= 0.06f
        val envelopeSuggestive =
            env.tailEnergyRatio >= 0.16f ||
                env.decayHalfFrames >= framesForMs(40) ||
                env.bounceRatio >= 0.12f
        val attackSuggestive =
            attackRatio <= 70f || env.monotonicRiseRatio >= 0.25f
        val likelyNaturalSnapOutlier =
            durationMs in 35..95 &&
                avgCentroid <= 4550f &&
                avgFlatness >= 0.62f &&
                avgTilt <= 0.32f &&
                avgZcr <= 0.58f
        return shortHighBand && envelopeSuggestive && attackSuggestive && !likelyNaturalSnapOutlier
    }

    @Volatile private var running = false
    private var recordJob: Job? = null
    private var litJob: Job? = null
    private var recorder: AudioRecord? = null
    
    private val consumers = mutableSetOf<String>()

    @Synchronized
    fun start(consumerId: String = "Global"): Boolean {
        consumers.add(consumerId)
        if (running) return true
        val permission = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        clearSessionCalibration()
        running = true
        recordJob = scope.launch {
            runRecorderLoop()
        }
        return true
    }

    @Synchronized
    fun stop(consumerId: String = "Global") {
        consumers.remove(consumerId)
        if (consumers.isNotEmpty()) return
        
        running = false
        recordJob?.cancel()
        recordJob = null
        litJob?.cancel()
        litJob = null

        // Force release to break any blocking read
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e("SoundDetector", "Error stopping recorder in stop()", e)
        }
        recorder = null
        clearSessionCalibration()

        // Preserve history (logs, events, rejection reason) so user can inspect them after stopping.
        _state.update { it.copy(isLit = false, rms = 0f, currentRms = 0f, noiseFloor = 0f) }
    }

    fun clearHistory() {
        _state.update { it.copy(events = emptyList(), count = 0, lastConfidence = 0f, lastRejectionReason = null, debugInfo = "", logs = emptyList(), diagnostics = emptyList()) }
    }

    fun clearDiagnostics() {
        _state.update { it.copy(diagnostics = emptyList()) }
    }

    fun updateDiagnosticNote(id: Long, note: String) {
        _state.update { current ->
            current.copy(
                diagnostics = current.diagnostics.map { diag ->
                    if (diag.id == id) diag.copy(note = note) else diag
                }
            )
        }
    }

    fun annotateGestureForEvent(
        eventTimestampMs: Long,
        groupId: Long,
        tapIndex: Int,
        tapCount: Int,
        unpairedReason: String?
    ) {
        _state.update { current ->
            current.copy(
                diagnostics = current.diagnostics.map { diag ->
                    if (diag.eventTimestampMs == eventTimestampMs && diag.outcome == "ACCEPTED") {
                        diag.copy(
                            gestureGroupId = groupId,
                            gestureTapIndex = tapIndex,
                            gestureTapCount = tapCount,
                            gestureUnpairedReason = unpairedReason
                        )
                    } else {
                        diag
                    }
                }
            )
        }
    }

    private fun emitDiagnostic(diagnostic: DetectionDiagnostic) {
        _state.update { current ->
            current.copy(diagnostics = (listOf(diagnostic) + current.diagnostics).take(maxDiagnostics))
        }
    }

    /**
     * Release all resources and cancel the coroutine scope.
     * Call this when the app is being destroyed to prevent memory leaks.
     * After calling release(), this SoundDetector instance should not be used again.
     */
    fun release() {
        synchronized(this) {
            consumers.clear()
        }
        stop("Global") // Force stop
        scope.cancel()
    }

    private suspend fun runRecorderLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(windowSamples * 2)

        val newRecorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
        } catch (e: SecurityException) {
            Log.e("SoundDetector", "Permission denied for audio recording", e)
            running = false
            return
        }
        // Store in field for stop() access
        recorder = newRecorder

        if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            newRecorder.release()
            recorder = null
            return
        }
        
        // Use local variable for loop logic to avoid race conditions if field is nulled
        var currentRecorder = newRecorder
        
        var aec: AcousticEchoCanceler? = null
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(currentRecorder.audioSessionId)
                if (aec != null) {
                    aec.enabled = true
                    Log.d("SoundDetector", "Acoustic Echo Canceler enabled")
                } else {
                    Log.e("SoundDetector", "Acoustic Echo Canceler creation failed")
                }
            } catch (e: Exception) {
                Log.e("SoundDetector", "Error initializing AEC", e)
            }
        }
        
        val hopBuffer = ShortArray(hopSamples)
        val analysisBuffer = ShortArray(windowSamples)
        val floatBuffer = FloatArray(windowSamples)
        var hasPrimedAnalysisWindow = false
        var noiseFloor = 0.005f
        var flatlineCount = 0
        val maxFlatlineBeforeRestart = framesForMs(1000)
        
        // Data Normalization (Warm-up)
        // We start with a high "learning rate" to quickly snap to the room's noise level
        // preventing the "Latch-up" bug where noise > threshold prevents threshold from rising.
        var warmUpFrames = framesForMs(500)
        val fastNoiseAlpha = 0.25f // Very fast adaptation during warmup
        
        // HPF State (Simple 1st Order) for ~2000Hz cutoff at 16000Hz sample rate
        // RC = 1/(2*pi*2000) = 7.95e-5
        // dt = 1/16000 = 6.25e-5
        // alpha = RC / (RC + dt) = 0.56
        val hpfAlpha = 0.56f
        var hpfPrevIn = 0f
        var hpfPrevOut = 0f
        
        // History for UI and Analysis
        val recentSamples = mutableListOf<Float>() 
        val maxRecentSamples = framesForMs(1500)
        
        // Event Monitoring State
        var isMonitoringEvent = false
        var eventStartTimestamp = 0L
        var eventHighRmsFrames = 0
        var eventAccumulatedZcr = 0f
        var eventAccumulatedCentroid = 0f // Sum of centroids during event
        var eventAccumulatedTilt = 0f
        var eventAccumulatedFlatness = 0f
        var eventPeakRms = 0f
        var eventPeakRaw = 0f
        var framesSincePeak = 0
        var hasDippedFromPeak = false
        val eventRmsTrace = mutableListOf<Float>()
        var preEventNoiseAvg = 0f
        var consecutiveLoudFrames = 0
        var lastPlaybackBlankDiagMs = 0L
        val cadenceProbeHistory = ArrayDeque<CadenceProbe>()
        var cadenceSuppressUntilMs = 0L
        var lastCadenceDiagMs = 0L
        var lastCadencePatternStats: CadencePatternStats? = null
        
        // Frame counter for chaos-gate exclusion
        var frameCounter = 0L
        var lastDetectionEndFrame = -1000L  // Frame at which last confirmed detection ended
        var lastDetectionDurationFrames = 0  // How many frames the last detection occupied
        var lastQualifiedEndFrame = -1000L   // Frame at which last pre-qualified detection ended
        var lastQualifiedDurationFrames = 0  // Duration for pre-qualified exclusion

        // Signature whitelist for rapid follow-up taps (double/triple tap patterns).
        var lastCandidateType: String? = null
        var lastCandidateTimestampMs = 0L
        var lastCandidateDurationMs = 0
        var lastCandidateCentroid = 0f
        var lastCandidateTilt = 0f
        var lastCandidateStrength = 0f

        var lastAcceptedType: String? = null
        var lastAcceptedTimestampMs = 0L
        var lastAcceptedDurationMs = 0
        var lastAcceptedCentroid = 0f
        var lastAcceptedTilt = 0f
        var lastAcceptedStrength = 0f
        
        // Post-Event Quiet Check State (for chaos rejection)
        var postEventCheckCountdown = 0
        var pendingEvent: SoundDetectionEvent? = null
        var pendingEventLogEntry: String? = null
        var thresholdAtEventEnd = 0f
        var postEventMaxRmsDuringCheck = 0f
        var pendingProfileScores: List<ProfileScore> = emptyList()
        var pendingSessionSimilarity: Float? = null
        
        // Diagnostic capture vars (saved at trigger time for use at rejection/acceptance)
        var eventTriggerRms = 0f
        var eventAttackRatio = 0f
        var eventNoiseFloorAtTrigger = 0f
        var eventThresholdAtTrigger = 0f
        
        // Local diagnostic helper — captures event-scope vars by reference
        fun emitEventDiag(
            rejectedAt: String?,
            rejectionDetail: String?,
            sustainRatio: Float? = null,
            decayRatio: Float? = null,
            preEventCV: Float? = null,
            postEventRms: Float? = null,
            postEventQuietThreshold: Float? = null,
            bestProfileName: String? = null,
            bestConfidence: Float = 0f,
            allProfileScores: List<ProfileScore> = emptyList(),
            overrideDurationMs: Int? = null,
            overrideAvgZcr: Float? = null,
            overrideAvgCentroid: Float? = null,
            overrideAvgTilt: Float? = null,
            overrideAvgFlatness: Float? = null,
            sessionSimilarity: Float? = null,
            overridePreEventNoiseAvg: Float? = null
        ) {
            val frames = eventHighRmsFrames.coerceAtLeast(1)
            emitDiagnostic(DetectionDiagnostic(
                id = SystemClock.elapsedRealtime(),
                timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
                eventTimestampMs = eventStartTimestamp,
                outcome = if (rejectedAt == null) "ACCEPTED" else "REJECTED",
                rejectedAt = rejectedAt,
                rejectionDetail = rejectionDetail,
                noiseFloor = eventNoiseFloorAtTrigger,
                threshold = eventThresholdAtTrigger,
                preEventNoiseAvg = overridePreEventNoiseAvg ?: preEventNoiseAvg,
                triggerRms = eventTriggerRms,
                attackRatio = eventAttackRatio,
                durationMs = overrideDurationMs ?: durationForFrames(frames),
                peakRms = eventPeakRms,
                peakRaw = eventPeakRaw,
                sustainRatio = sustainRatio,
                decayRatio = decayRatio,
                peakFrameIndex = frames - framesSincePeak,
                preEventCV = preEventCV,
                postEventRms = postEventRms,
                postEventQuietThreshold = postEventQuietThreshold,
                avgZcr = overrideAvgZcr ?: (eventAccumulatedZcr / frames),
                avgCentroid = overrideAvgCentroid ?: (eventAccumulatedCentroid / frames),
                avgTilt = overrideAvgTilt ?: (eventAccumulatedTilt / frames),
                avgFlatness = overrideAvgFlatness ?: (eventAccumulatedFlatness / frames),
                sessionSimilarity = sessionSimilarity,
                bestProfileName = bestProfileName,
                bestConfidence = bestConfidence,
                allProfileScores = allProfileScores
            ))
        }
        
        try {
            currentRecorder.startRecording()
            Log.d("SoundDetector", "Recording started. Window: ${fftWindowMs}ms Hop: ${hopMs}ms")

            while (running) {
            if (currentRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("SoundDetector", "Recorder stopped recording unexpectedly")
                currentRecorder.startRecording()
                delay(100)
                continue
            }

            if (!hasPrimedAnalysisWindow) {
                val readPrime = currentRecorder.read(analysisBuffer, 0, analysisBuffer.size)
                if (readPrime < 0) {
                    Log.e("SoundDetector", "Read error while priming analysis window: $readPrime")
                    delay(50)
                    continue
                }
                if (readPrime < analysisBuffer.size) {
                    delay(10)
                    continue
                }
                hasPrimedAnalysisWindow = true
            } else {
                val readHop = currentRecorder.read(hopBuffer, 0, hopBuffer.size)
                if (readHop < 0) {
                    Log.e("SoundDetector", "Read error: $readHop")
                    delay(50)
                    continue
                }
                if (readHop < hopSamples) {
                    delay(10)
                    continue
                }
                System.arraycopy(
                    analysisBuffer,
                    hopSamples,
                    analysisBuffer,
                    0,
                    windowSamples - hopSamples
                )
                System.arraycopy(
                    hopBuffer,
                    0,
                    analysisBuffer,
                    windowSamples - hopSamples,
                    hopSamples
                )
            }

            val analysisRead = windowSamples

            // 1. Calculate Statistics for this Window
            // Remove DC Offset first!
            var dcSum = 0L
            for (i in 0 until analysisRead) {
                dcSum += analysisBuffer[i]
            }
            val dcOffset = (dcSum / analysisRead).toInt() // integer mean
            
            var sum = 0.0
            var hpfSum = 0.0
            var peak = 0.0
            var zeroCrossings = 0
            // Reuse pre-allocated buffer.
            
            for (i in 0 until analysisRead) {
                val v = (analysisBuffer[i] - dcOffset).toFloat()
                floatBuffer[i] = v
                val absV = abs(v)
                if (absV > peak) peak = absV.toDouble()
                sum += v * v
                
                // High Pass Filter (2000Hz)
                val hpfOut = hpfAlpha * (hpfPrevOut + v - hpfPrevIn)
                hpfPrevIn = v
                hpfPrevOut = hpfOut
                hpfSum += hpfOut * hpfOut
                
                // ZCR check on DC-removed signal
                if (i > 0) {
                    val prev = floatBuffer[i-1]
                    if ((prev > 0 && v <= 0) || (prev < 0 && v >= 0)) {
                        zeroCrossings++
                    }
                }
            }
            
            val rms = sqrt(sum / analysisRead) / 32768.0
            val hpfRms = sqrt(hpfSum / analysisRead) / 32768.0
            val peakNorm = (peak / 32768.0).coerceAtMost(1.0)
            val rmsF = rms.toFloat()
            val hpfRmsF = hpfRms.toFloat()
            val zcr = zeroCrossings.toFloat() / analysisRead.coerceAtLeast(1)
            
            // Calculate Spectral Features
            val spectralFeatures = SoundUtils.calculateSpectralFeatures(floatBuffer, sampleRate)
            val centroid = spectralFeatures.centroid
            val tilt = spectralFeatures.tilt
            val flatness = spectralFeatures.flatness

            // Flatline detection
            if (rmsF < 0.0001f && peakNorm < 0.0001) {
                flatlineCount++
                if (flatlineCount >= maxFlatlineBeforeRestart) {
                    Log.w("SoundDetector", "Flatline detected, restarting recorder")
                    currentRecorder.stop() 
                    currentRecorder.release()
                    val restarted = try {
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuffer
                        )
                    } catch (e: SecurityException) {
                        Log.e("SoundDetector", "Permission denied during recorder restart", e)
                        running = false
                        recorder = null
                        return
                    }
                    currentRecorder = restarted
                    recorder = restarted

                    if (currentRecorder.state == AudioRecord.STATE_INITIALIZED) {
                        currentRecorder.startRecording()
                        aec?.release()
                        try {
                            if (AcousticEchoCanceler.isAvailable()) {
                                aec = AcousticEchoCanceler.create(currentRecorder.audioSessionId)
                                aec?.enabled = true
                            }
                        } catch (e: Exception) {
                            Log.e("SoundDetector", "Error recreating AEC", e)
                        }
                        flatlineCount = 0
                        warmUpFrames = framesForMs(500) // Reset warm-up to silence the "pop" of restarting and recalibrate noise floor
                        hasPrimedAnalysisWindow = false
                        recentSamples.clear() // Clear dead history so it doesn't skew statistics
                        continue
                    } else {
                        running = false
                        aec?.release()
                        return
                    }
                }
            } else {
                flatlineCount = 0
            }

            // 2. Threshold Calculation (Reactive Gate)
            // If the room is very quiet (noiseFloor < 0.002), we lower the minimum barrier to catch soft snaps
            val currentMinRms = if (noiseFloor < 0.0025f) quietModeMinRms else baseMinRms
            val threshold = (noiseFloor * thresholdMultiplier).coerceAtLeast(currentMinRms)
            
            // 3. Detection State Machine
            val prevRms = if (recentSamples.isNotEmpty()) recentSamples.last() else 0f

            // WARM UP CHECK
            if (warmUpFrames > 0) {
                warmUpFrames--
                // Fast track noise level
                noiseFloor = (noiseFloor * (1f - fastNoiseAlpha)) + (rmsF * fastNoiseAlpha)
                
                // Skip detection logic
                recentSamples.add(rmsF)
                if (recentSamples.size > maxRecentSamples) recentSamples.removeAt(0)
                _state.update { it.copy(rms = rmsF, noiseFloor = noiseFloor, currentRms = rmsF, debugInfo = "Calibrating...") }
                continue
            }
            
            // ANTI-LATCH CHECK (Continuous Noise Adaptation)
            // If the signal has been "Loud" (> threshold) for too long (> 2 seconds),
            // it's not a snap/slap anymore, it's the new environment level.
            if (rmsF > threshold) {
                consecutiveLoudFrames++
                if (consecutiveLoudFrames > framesForMs(2000)) {
                    // Force Adapt: Drag noise floor UP towards the current RMS
                    // We use a stronger alpha here to escape the latch-up quickly
                    noiseFloor = (noiseFloor * 0.95f) + (rmsF * 0.05f)
                    
                    // Reset monitoring if we were stuck in an "event"
                    if (isMonitoringEvent) {
                         isMonitoringEvent = false
                         Log.d("SoundDetector", "Forced Adaptation: Signal loud for too long. Resetting.")
                    }
                }
            } else {
                consecutiveLoudFrames = 0
            }

            if (isMonitoringEvent) {
                // We are tracking a potential event
                if (rmsF > threshold * 0.4f) { // Hysteresis: keep tracking until it drops significantly
                    eventHighRmsFrames++
                    eventAccumulatedZcr += zcr
                    eventAccumulatedCentroid += centroid
                    eventAccumulatedTilt += tilt
                    eventAccumulatedFlatness += flatness
                    eventRmsTrace.add(rmsF)
                    
                    if (rmsF > eventPeakRms) {
                        eventPeakRms = rmsF
                        framesSincePeak = 0
                        hasDippedFromPeak = false // Reset geometry check on new peak
                    } else {
                        framesSincePeak++
                    }
                    if (peakNorm > eventPeakRaw) eventPeakRaw = peakNorm.toFloat()
                    
                    // Sustain/Flux Check (User Requirement)
                    // Check at T+50ms or T+60ms
                    // If energy is still > 50% of peak, it's likely speech (vowel sustain) or noise.
                    // Snaps and Slaps are transient and should have decayed significantly by 50ms.
                    if (eventHighRmsFrames == framesForMs(50) || eventHighRmsFrames == framesForMs(60)) {
                        val ratio = if (eventPeakRms > 0) rmsF / eventPeakRms else 0f
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val sustainRejectRatio = when {
                            hasRecentTapReference -> 0.68f
                            else -> 0.58f
                        }
                        if (ratio > sustainRejectRatio) {
                            if (isMonitoringEvent) {
                                emitEventDiag(
                                    "SUSTAIN",
                                    "Ratio ${"%.2f".format(ratio)} at ${durationForFrames(eventHighRmsFrames)}ms (>${"%.2f".format(sustainRejectRatio)})",
                                    sustainRatio = ratio
                                )
                            }
                            Log.d("SoundDetector", "Rejected: Sustained/Speech (Ratio $ratio at ${durationForFrames(eventHighRmsFrames)}ms)")
                            isMonitoringEvent = false
                        }
                    }

                    // Peak Late Check (Groan Rejection)
                    // If the peak happened > 60ms into the event, it's a swell/crescendo (impulse peaking is instant).
                    // Exception: Very long events might have multiple peaks, but we assume impulse snaps peak early.
                    val framesAtPeak = eventHighRmsFrames - framesSincePeak
                    if (framesAtPeak > framesForMs(60) && rmsF > threshold * 2.0f) { // Only strict on loud sounds
                          if (isMonitoringEvent) emitEventDiag("LATE_PEAK", "Peak at frame $framesAtPeak (${durationForFrames(framesAtPeak)}ms into event)")
                          Log.d("SoundDetector", "Rejected: Slow Intensity Rise (Peak at ${durationForFrames(framesAtPeak)}ms)")
                          isMonitoringEvent = false
                    }
                    
                    // Double-Peak / Rebound Check
                    // If we dipped significantly below peak, we shouldn't rise again significantly
                    if (!hasDippedFromPeak && rmsF < eventPeakRms * 0.5f) {
                        hasDippedFromPeak = true
                    }
                    
                    if (hasDippedFromPeak && rmsF > eventPeakRms * 0.7f) {
                        // Rebound detected! (Structure: Peak -> Dip -> Rise again)
                        // Ignore rebound check for very short events (like snaps) which are messy by nature
                        val currentDurationMs = durationForFrames(eventHighRmsFrames)
                        if (currentDurationMs > 150) {
                            if (isMonitoringEvent) emitEventDiag("REBOUND", "Double peak at ${currentDurationMs}ms")
                            Log.d("SoundDetector", "Rejected: Bouncy signal (multiple peaks)")
                            isMonitoringEvent = false
                        }
                    }

                    // Duration Check (Fail if too long)
                    val currentDurationMs = durationForFrames(eventHighRmsFrames)
                    // Use the longest possible profile as the absolute max to stop checking early
                    if (currentDurationMs > 300) { 
                        if (isMonitoringEvent) emitEventDiag("DURATION", "Too long: ${currentDurationMs}ms > 300ms")
                        Log.d("SoundDetector", "Rejected: Too long ($currentDurationMs ms)")
                        isMonitoringEvent = false // Reset
                    }
                    
                    // Decay Check (Fail if tail is too loud after peak)
                    val decayCheckFrame = framesForMs(60)
                    if (framesSincePeak == decayCheckFrame) {
                        val ratio = if (eventPeakRms > 0) rmsF / eventPeakRms else 0f
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val decayRejectRatio = when {
                            hasRecentTapReference -> 0.34f
                            else -> 0.26f
                        }
                        if (ratio > decayRejectRatio) {
                            if (isMonitoringEvent) {
                                emitEventDiag(
                                    "DECAY",
                                    "Ratio ${"%.2f".format(ratio)} > ${"%.2f".format(decayRejectRatio)} at 60ms post-peak",
                                    decayRatio = ratio
                                )
                            }
                            Log.d("SoundDetector", "Rejected: Decay too slow (ratio $ratio at 60ms)")
                            isMonitoringEvent = false
                        }
                    }
                } else {
                    // Signal dropped -> End of Event -> Analyze it
                    val durationMs = durationForFrames(eventHighRmsFrames)
                    val avgZcr = eventAccumulatedZcr / eventHighRmsFrames.coerceAtLeast(1)
                    val avgCentroid = eventAccumulatedCentroid / eventHighRmsFrames.coerceAtLeast(1)
                    val avgTilt = eventAccumulatedTilt / eventHighRmsFrames.coerceAtLeast(1)
                    val avgFlatness = eventAccumulatedFlatness / eventHighRmsFrames.coerceAtLeast(1)
                    
                    var bestMatch: SoundProfile? = null
                    var bestConfidence = 0f
                    var bestSnapMatch: SoundProfile? = null
                    var bestSnapConfidence = 0f
                    var bestClapMatch: SoundProfile? = null
                    var bestClapConfidence = 0f
                    val allScores = mutableListOf<ProfileScore>()
                    
                    // Profile Matching (Confidence Based)
                    for (profile in profiles) {
                        // Calculate score for each metric (0.0 - 1.0)
                        val sDur = getMetricScore(durationMs.toFloat(), profile.minDurMs.toFloat(), profile.maxDurMs.toFloat())
                        val sZcr = getMetricScore(avgZcr, profile.minZcr, profile.maxZcr)
                        val sCentroid = getMetricScore(avgCentroid, profile.minCentroid, profile.maxCentroid)
                        val sTilt = getMetricScore(avgTilt, profile.minTilt, profile.maxTilt)
                        
                        // Weighted Average (Equal weights for now)
                        val confidence = (sDur + sZcr + sCentroid + sTilt) / 4f
                        
                        allScores.add(ProfileScore(profile.name, confidence, sDur, sZcr, sCentroid, sTilt))
                        
                        if (confidence > bestConfidence) {
                            bestConfidence = confidence
                            bestMatch = profile
                        }
                        if (profile.name.startsWith("Snap") && confidence > bestSnapConfidence) {
                            bestSnapConfidence = confidence
                            bestSnapMatch = profile
                        }
                        if (profile.name.startsWith("Clap") && confidence > bestClapConfidence) {
                            bestClapConfidence = confidence
                            bestClapMatch = profile
                        }
                    }

                    // Bias toward Snap for short, bright transients when scores are close.
                    val isDiagnosticContext = debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                    val isGestureControlContext = !isDiagnosticContext
                    val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                    val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                    val hasRecentTapReference = isGestureControlContext &&
                        (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                    val clapLikeFamily = bestMatch?.name?.startsWith("Clap") == true || bestMatch?.name?.startsWith("Slap") == true
                    val snapLikeImpulse =
                        durationMs <= 95 &&
                            avgCentroid >= 2800f &&
                            avgZcr >= 0.18f &&
                            avgTilt >= 0.00f &&
                            eventAttackRatio >= 8f
                    if (!isDiagnosticContext && clapLikeFamily && snapLikeImpulse && bestSnapMatch != null) {
                        val isCloseScore = bestSnapConfidence >= (bestConfidence - 0.10f)
                        if (isCloseScore) {
                            bestMatch = bestSnapMatch
                            bestConfidence = bestSnapConfidence
                        }
                    }

                    // Counter-bias: if Snap and Clap are nearly tied but envelope/spectral shape
                    // looks clap-like, keep it as Clap to improve label quality in clap sessions.
                    val snapFamily = bestMatch?.name?.startsWith("Snap") == true
                    val clapLikeImpulse =
                        durationMs >= 85 &&
                            avgZcr >= 0.20f &&
                            avgCentroid in 2300f..4200f &&
                            avgTilt <= 0.08f
                    val shouldPreferClap =
                        snapFamily &&
                            bestClapMatch != null &&
                            bestClapConfidence >= (bestConfidence - 0.03f) &&
                            clapLikeImpulse
                    if (shouldPreferClap) {
                        bestMatch = bestClapMatch
                        bestConfidence = bestClapConfidence
                    }

                    // Diagnostic labeling assist:
                    // In snap/clap overlap, prefer Snap when the event shape is strongly snap-like.
                    // This improves tuning logs without changing production gesture behavior.
                    val clapFamilyAfterBias = bestMatch?.name?.startsWith("Clap") == true
                    val diagnosticLikelySnap =
                        isDiagnosticContext &&
                            clapFamilyAfterBias &&
                            bestSnapMatch != null &&
                            durationMs <= 120 &&
                            avgZcr >= 0.30f &&
                            avgCentroid >= 3200f &&
                            avgTilt >= -0.20f &&
                            bestSnapConfidence >= (bestConfidence - 0.08f)
                    if (diagnosticLikelySnap) {
                        bestMatch = bestSnapMatch
                        bestConfidence = bestSnapConfidence
                    }

                    // Rescue very short but clean snap-like transients that otherwise miss
                    // confidence mostly due duration scoring.
                    val preEventRatioAtMatch = if (eventPeakRms > 0f) preEventNoiseAvg / eventPeakRms else 1f
                    val ultraShortSnapLike =
                        durationMs in 15..35 &&
                            avgZcr >= 0.34f &&
                            avgCentroid >= 3200f &&
                            avgTilt >= -0.20f &&
                            eventAttackRatio >= 10f &&
                            preEventRatioAtMatch <= 0.22f
                    if (ultraShortSnapLike && bestMatch?.name?.startsWith("Snap") == true && bestConfidence >= 0.72f) {
                        bestConfidence = bestConfidence.coerceAtLeast(0.80f)
                    }

                    val envelopeFeatures = extractEnvelopeFeatures(eventRmsTrace, eventPeakRms)
                    val effectiveConfidenceThreshold = when {
                        isDiagnosticContext -> 0.80f
                        hasRecentTapReference -> followUpTapConfidenceThreshold
                        else -> firstTapConfidenceThreshold
                    }
                    var isValid = bestMatch != null && bestConfidence >= effectiveConfidenceThreshold
                    var rejectionReason: String? = null
                    var sessionSimilarityValue: Float? = null

                    // Per-session family calibration gate.
                    if (isValid && bestMatch != null) {
                        val ref = sessionReferenceForType(bestMatch.name)
                        if (ref != null) {
                            val similarity = sessionSimilarity(
                                ref = ref,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                zcr = avgZcr,
                                flatness = avgFlatness,
                                durationMs = durationMs,
                                strength = eventPeakRms
                            )
                            sessionSimilarityValue = similarity
                            if (similarity < 0.62f) {
                                rejectionReason =
                                    "Session mismatch: ${"%.2f".format(similarity)} < 0.62"
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                                emitEventDiag(
                                    "SESSION_MISMATCH",
                                    rejectionReason,
                                    overrideDurationMs = durationMs,
                                    overrideAvgZcr = avgZcr,
                                    overrideAvgCentroid = avgCentroid,
                                    overrideAvgTilt = avgTilt,
                                    overrideAvgFlatness = avgFlatness,
                                    bestProfileName = bestMatch.name,
                                    bestConfidence = bestConfidence,
                                    allProfileScores = allScores,
                                    sessionSimilarity = sessionSimilarityValue
                                )
                            }
                        }
                    }

                    val bestFamily = bestMatch?.name?.substringBefore(" (")?.trim()
                    val isGestureFamily = bestFamily == "Snap" || bestFamily == "Clap" || bestFamily == "Slap"

                    // Cadence probe capture:
                    // Learn periodic nuisance streams even from near-miss events so suppression
                    // can engage before they become actionable multi-taps.
                    if (isGestureFamily &&
                        bestConfidence >= cadenceProbeConfidenceMin &&
                        durationMs in cadenceProbeDurationMinMs..cadenceProbeDurationMaxMs
                    ) {
                        CadencePatternHeuristics.pruneHistory(
                            history = cadenceProbeHistory,
                            nowMs = eventStartTimestamp,
                            windowMs = cadenceWindowMs
                        )
                        cadenceProbeHistory.addLast(
                            CadenceProbe(
                                timestampMs = eventStartTimestamp,
                                durationMs = durationMs,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                zcr = avgZcr
                            )
                        )
                        CadencePatternHeuristics.pruneHistory(
                            history = cadenceProbeHistory,
                            nowMs = eventStartTimestamp,
                            windowMs = cadenceWindowMs
                        )
                        val cadenceStats = CadencePatternHeuristics.detectPattern(
                            history = cadenceProbeHistory.toList(),
                            nowMs = eventStartTimestamp,
                            windowMs = cadenceWindowMs,
                            minEvents = cadenceMinEvents,
                            intervalMinMs = cadenceIntervalMinMs,
                            intervalMaxMs = cadenceIntervalMaxMs,
                            intervalCvMax = cadenceIntervalCvMax,
                            centroidSpreadPctMax = cadenceCentroidSpreadPctMax,
                            tiltSpreadMax = cadenceTiltSpreadMax,
                            flatnessSpreadMax = cadenceFlatnessSpreadMax,
                            zcrSpreadMax = cadenceZcrSpreadMax,
                            durationSpreadPctMax = cadenceDurationSpreadPctMax
                        )
                        if (cadenceStats != null) {
                            cadenceSuppressUntilMs = maxOf(cadenceSuppressUntilMs, eventStartTimestamp + cadenceSuppressionMs)
                            lastCadencePatternStats = cadenceStats
                            if (eventStartTimestamp - lastCadenceDiagMs >= cadenceDiagCooldownMs) {
                                lastCadenceDiagMs = eventStartTimestamp
                                Log.d(
                                    "SoundDetector",
                                    "Cadence suppression armed: n=${cadenceStats.eventCount} mean=${"%.0f".format(cadenceStats.meanIntervalMs)}ms cv=${"%.2f".format(cadenceStats.intervalCv)}"
                                )
                            }
                        }
                    } else {
                        CadencePatternHeuristics.pruneHistory(
                            history = cadenceProbeHistory,
                            nowMs = eventStartTimestamp,
                            windowMs = cadenceWindowMs
                        )
                    }

                    // Periodic cadence suppression:
                    // while active, reject first-tap-like candidates only.
                    if (isValid &&
                        isGestureFamily &&
                        CadencePatternHeuristics.isSuppressed(eventStartTimestamp, cadenceSuppressUntilMs)
                    ) {
                        val firstTapLike = CadencePatternHeuristics.isFirstTapLike(
                            candidateAgeMs = candidateAgeMs,
                            acceptedAgeMs = acceptedAgeMs,
                            followupWindowMs = cadenceFollowupWindowMs
                        )
                        if (firstTapLike) {
                            val acceptedSignatureBypass = isStrongAcceptedSignatureMatchForCadenceBypass(
                                bestType = bestMatch?.name,
                                eventTimestampMs = eventStartTimestamp,
                                durationMs = durationMs,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                strength = eventPeakRms,
                                refType = lastAcceptedType,
                                refTimestampMs = lastAcceptedTimestampMs,
                                refDurationMs = lastAcceptedDurationMs,
                                refCentroid = lastAcceptedCentroid,
                                refTilt = lastAcceptedTilt,
                                refStrength = lastAcceptedStrength,
                                followupWindowMs = cadenceFollowupWindowMs
                            )
                            val shouldRejectCadence = shouldRejectCadencePattern(
                                isSuppressed = true,
                                isGestureFamily = isGestureFamily,
                                firstTapLike = true,
                                sessionSimilarity = sessionSimilarityValue,
                                sessionBypassThreshold = nuisanceSimilaritySessionBypassThreshold,
                                acceptedSignatureBypass = acceptedSignatureBypass
                            )
                            if (shouldRejectCadence) {
                                val cadenceStats = lastCadencePatternStats
                                rejectionReason = if (cadenceStats != null) {
                                    "Cadence Pattern: n=${cadenceStats.eventCount} mean=${"%.0f".format(cadenceStats.meanIntervalMs)}ms cv=${"%.2f".format(cadenceStats.intervalCv)}"
                                } else {
                                    "Cadence Pattern: suppression active"
                                }
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                                rememberNuisanceReference(
                                    stage = "CADENCE_PATTERN",
                                    durationMs = durationMs,
                                    zcr = avgZcr,
                                    centroid = avgCentroid,
                                    tilt = avgTilt,
                                    flatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    strength = eventPeakRms,
                                    timestampMs = eventStartTimestamp
                                )
                                emitEventDiag(
                                    "CADENCE_PATTERN",
                                    rejectionReason,
                                    overrideDurationMs = durationMs,
                                    overrideAvgZcr = avgZcr,
                                    overrideAvgCentroid = avgCentroid,
                                    overrideAvgTilt = avgTilt,
                                    overrideAvgFlatness = avgFlatness,
                                    bestProfileName = bestMatch?.name,
                                    bestConfidence = bestConfidence,
                                    allProfileScores = allScores,
                                    sessionSimilarity = sessionSimilarityValue
                                )
                            }
                        }
                    }

                    // Session nuisance memory gate:
                    // if this looks highly similar to previously rejected impact signatures,
                    // block it early (first-tap path only).
                    if (isValid && !hasRecentTapReference) {
                        if (isGestureFamily) {
                            val nuisance = bestNuisanceSimilarity(
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                nowMs = eventStartTimestamp
                            )
                            val protectedBySession =
                                (sessionSimilarityValue ?: 0f) >= nuisanceSimilaritySessionBypassThreshold
                            val hasEnoughNuisanceHistory =
                                nuisance.totalReferences >= nuisanceMinReferencesToGate &&
                                    nuisance.strongReferenceCount >= nuisanceMinStrongReferencesToGate
                            val nuisanceRejectThreshold = when {
                                nuisance.sourceStage == "METALLIC" -> nuisanceSimilarityRejectThresholdMetallic
                                else -> nuisanceSimilarityRejectThreshold
                            }
                            if (!protectedBySession && hasEnoughNuisanceHistory && nuisance.similarity >= nuisanceRejectThreshold) {
                                rejectionReason =
                                    "Nuisance Similarity: ${"%.2f".format(nuisance.similarity)} >= ${"%.2f".format(nuisanceRejectThreshold)} (${nuisance.sourceStage ?: "unknown"}) refs=${nuisance.totalReferences}/${nuisance.strongReferenceCount}"
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                                emitEventDiag(
                                    "NUISANCE_SIMILARITY",
                                    rejectionReason,
                                    overrideDurationMs = durationMs,
                                    overrideAvgZcr = avgZcr,
                                    overrideAvgCentroid = avgCentroid,
                                    overrideAvgTilt = avgTilt,
                                    overrideAvgFlatness = avgFlatness,
                                    bestProfileName = bestMatch?.name,
                                    bestConfidence = bestConfidence,
                                    allProfileScores = allScores,
                                    sessionSimilarity = sessionSimilarityValue
                                )
                            }
                        }
                    }

                    // Additional low-flatness reject for gesture families.
                    if (isValid && !hasRecentTapReference) {
                        val family = bestMatch?.name?.substringBefore(" (")?.trim()
                        val isGestureFamily = family == "Snap" || family == "Clap" || family == "Slap"
                        if (isGestureFamily && avgFlatness < 0.16f) {
                            rejectionReason = "Low flatness: ${"%.3f".format(avgFlatness)} < 0.160"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "LOW_FLATNESS",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Stage 1 refinement: reject complex/ringing envelopes earlier.
                    if (isValid) {
                        val envelopeComplex =
                            !hasRecentTapReference &&
                                durationMs in 50..220 &&
                                envelopeFeatures.tailEnergyRatio >= 0.66f &&
                                envelopeFeatures.decayHalfFrames >= 16 &&
                                envelopeFeatures.monotonicRiseRatio >= 0.30f
                        if (envelopeComplex) {
                            rejectionReason =
                                "Envelope Complex: tail=${"%.2f".format(envelopeFeatures.tailEnergyRatio)} rise=${"%.2f".format(envelopeFeatures.monotonicRiseRatio)} d50=${envelopeFeatures.decayHalfFrames}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "ENVELOPE_COMPLEX",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }
                    
                    if (!isValid && rejectionReason == null) { 
                         val topMatchName = bestMatch?.name ?: "None"
                         val topScore = (bestConfidence * 100).toInt()
                         rejectionReason = "Low Confidence: $topMatchName ($topScore%) D:${durationMs}ms Z:${"%.2f".format(avgZcr)} C:${"%.0f".format(avgCentroid)}"
                         Log.d("SoundDetector", "Rejected: $rejectionReason")
                         emitEventDiag("LOW_CONFIDENCE", rejectionReason,
                             overrideDurationMs = durationMs, overrideAvgZcr = avgZcr,
                             overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt, overrideAvgFlatness = avgFlatness,
                             bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                            allProfileScores = allScores, sessionSimilarity = sessionSimilarityValue)
                    }

                    // Table/surface taps near the mic can score as Snap/Clap variants despite being non-gesture thuds.
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val snapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val legacyWetClapSurfaceTap =
                            bestMatch?.name == "Clap (Wet)" &&
                                durationMs in 80..170 &&
                                avgZcr <= 0.12f &&
                                avgCentroid <= 1400f &&
                                avgTilt <= -0.85f
                        val stronglyTableCoupled =
                            snapOrClapFamily &&
                                durationMs in 45..190 &&
                                avgZcr <= 0.16f &&
                                avgCentroid <= 1700f &&
                                avgTilt <= -1.20f
                        val dampedContactImpact =
                            snapOrClapFamily &&
                                !hasRecentTapReference &&
                                durationMs in 55..210 &&
                                avgZcr <= 0.24f &&
                                avgCentroid <= 2300f &&
                                avgTilt <= -0.95f &&
                                eventAttackRatio <= 10f &&
                                (envelopeFeatures.tailEnergyRatio >= 0.30f || envelopeFeatures.decayHalfFrames >= 12)
                        val looksLikeSurfaceTap =
                            legacyWetClapSurfaceTap || stronglyTableCoupled || dampedContactImpact
                        if (looksLikeSurfaceTap) {
                            rejectionReason =
                                "Surface Tap: D=${durationMs}ms Z=${"%.2f".format(avgZcr)} C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "SURFACE_TAP",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "SURFACE_TAP",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Hard-surface collision reject (glass/metal/ceramic contact near mic).
                    // Keeps gesture sequences untouched by requiring no recent accepted candidate.
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val snapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val looksHardSurfaceImpact =
                            snapOrClapFamily &&
                                !hasRecentTapReference &&
                                durationMs in 55..180 &&
                                avgCentroid in 2400f..4200f &&
                                avgTilt <= -0.20f &&
                                avgZcr <= 0.40f &&
                                eventAttackRatio <= 7.5f &&
                                eventPeakRms <= 0.60f &&
                                (envelopeFeatures.tailEnergyRatio >= 0.26f || envelopeFeatures.decayHalfFrames >= 10)
                        if (looksHardSurfaceImpact) {
                            rejectionReason =
                                "Hard Surface Impact: D=${durationMs}ms A=${"%.2f".format(eventAttackRatio)} C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "HARD_SURFACE",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "HARD_SURFACE",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Envelope-shape ringing check:
                    // Metallic/ceramic impacts often show bouncy or flat tails compared to snap/clap transients.
                    if (isValid && eventPeakRms > 0f && eventRmsTrace.size >= framesForMs(40)) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)

                        var peakIndex = 0
                        var peakValue = eventRmsTrace[0]
                        for (i in 1 until eventRmsTrace.size) {
                            val v = eventRmsTrace[i]
                            if (v > peakValue) {
                                peakValue = v
                                peakIndex = i
                            }
                        }
                        val postPeak =
                            if (peakIndex + 1 < eventRmsTrace.size) {
                                eventRmsTrace.subList(peakIndex + 1, eventRmsTrace.size)
                            } else {
                                emptyList()
                            }
                        var risingAfterPeak = 0
                        for (i in 1 until postPeak.size) {
                            if (postPeak[i] - postPeak[i - 1] > eventPeakRms * 0.07f) {
                                risingAfterPeak++
                            }
                        }
                        val bounceRatio =
                            if (postPeak.size > 1) {
                                risingAfterPeak.toFloat() / (postPeak.size - 1).toFloat()
                            } else {
                                0f
                            }
                        val tailFrames = minOf(framesForMs(30), postPeak.size)
                        var tailSum = 0f
                        if (tailFrames > 0) {
                            for (i in postPeak.size - tailFrames until postPeak.size) {
                                tailSum += postPeak[i]
                            }
                        }
                        val tailRatio =
                            if (tailFrames > 0) {
                                (tailSum / tailFrames.toFloat()) / eventPeakRms
                            } else {
                                0f
                            }
                        val firstPostRatio =
                            if (postPeak.isNotEmpty()) {
                                postPeak.first() / eventPeakRms
                            } else {
                                0f
                            }
                        val minPost = postPeak.minOrNull() ?: 0f
                        val lastPost = postPeak.lastOrNull() ?: 0f
                        val reboundFromLowRatio =
                            if (postPeak.isNotEmpty()) {
                                (lastPost - minPost).coerceAtLeast(0f) / eventPeakRms
                            } else {
                                0f
                            }
                        val shortFlatTail =
                            durationMs <= 80 &&
                                firstPostRatio >= 0.60f &&
                                tailRatio >= 0.35f
                        val bouncyTail =
                            bounceRatio >= 0.34f ||
                                (tailRatio >= 0.26f && reboundFromLowRatio >= 0.18f)
                        val envelopeLooksRinging =
                            !hasRecentTapReference &&
                                durationMs in 40..200 &&
                                avgCentroid >= 3400f &&
                                avgTilt >= 0.10f &&
                                (shortFlatTail || bouncyTail)
                        if (envelopeLooksRinging) {
                            rejectionReason =
                                "Envelope Ringing: B=${"%.2f".format(bounceRatio)} T=${"%.2f".format(tailRatio)} R=${"%.2f".format(reboundFromLowRatio)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "ENVELOPE_RING",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "ENVELOPE_RING",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Metallic clinks (cutlery/keys) can look like sharp taps.
                    // Reject bright, positively tilted broadband impacts (glass/metal/ceramic).
                    if (isValid) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val metallicRingingEnvelope =
                            envelopeFeatures.tailEnergyRatio >= 0.24f ||
                                envelopeFeatures.bounceRatio >= 0.18f ||
                                envelopeFeatures.decayHalfFrames >= framesForMs(45)
                        val metallicSignatureNoRef =
                            isSnapOrClapFamily &&
                                metallicRingingEnvelope &&
                                (
                                    (
                                        durationMs in 20..95 &&
                                            avgCentroid >= 4100f &&
                                            avgTilt >= 0.24f &&
                                            avgFlatness >= 0.70f &&
                                            avgZcr >= 0.40f
                                        ) ||
                                        (
                                            durationMs in 30..160 &&
                                                avgCentroid >= 4500f &&
                                                avgTilt >= 0.20f &&
                                                avgFlatness >= 0.60f &&
                                                eventAttackRatio <= 22f
                                        )
                                    )
                        val metallicSignatureWithRef =
                            isSnapOrClapFamily &&
                                hasRecentTapReference &&
                                metallicRingingEnvelope &&
                                durationMs in 25..110 &&
                                avgCentroid >= 4700f &&
                                avgTilt >= 0.30f &&
                                avgFlatness >= 0.74f &&
                                avgZcr >= 0.44f
                        val likelySnapTransient = isLikelySnapTransient(
                            durationMs = durationMs,
                            avgZcr = avgZcr,
                            avgCentroid = avgCentroid,
                            avgTilt = avgTilt,
                            avgFlatness = avgFlatness,
                            attackRatio = eventAttackRatio,
                            env = envelopeFeatures
                        )
                        val looksMetallic = (metallicSignatureNoRef || metallicSignatureWithRef) && !likelySnapTransient
                        if (looksMetallic) {
                            rejectionReason =
                                "Metallic Ringing: C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)} F=${"%.3f".format(avgFlatness)} Z=${"%.2f".format(avgZcr)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            val strongMetallicForMemory =
                                (
                                    avgCentroid >= 4400f &&
                                        avgTilt >= 0.20f &&
                                        avgFlatness >= 0.66f &&
                                        avgZcr >= 0.42f
                                    ) || (
                                    avgTilt >= 0.30f &&
                                        avgFlatness >= 0.74f &&
                                        avgZcr >= 0.46f
                                    )
                            if (strongMetallicForMemory) {
                                rememberNuisanceReference(
                                    stage = "METALLIC",
                                    durationMs = durationMs,
                                    zcr = avgZcr,
                                    centroid = avgCentroid,
                                    tilt = avgTilt,
                                    flatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    strength = eventPeakRms,
                                    timestampMs = eventStartTimestamp
                                )
                            }
                            emitEventDiag(
                                "METALLIC",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Resonant object-contact signature reject.
                    // Targets repeated knife/glass-like taps that can pass profile scoring.
                    if (isValid && !hasRecentTapReference) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val resonantImpactLike =
                            isSnapOrClapFamily &&
                                isResonantImpactSignature(
                                    durationMs = durationMs,
                                    avgZcr = avgZcr,
                                    avgCentroid = avgCentroid,
                                    avgTilt = avgTilt,
                                    avgFlatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    peakRms = eventPeakRms,
                                    env = envelopeFeatures
                                )
                        if (resonantImpactLike) {
                            rejectionReason =
                                "Resonant Impact: D=${durationMs} C=${"%.0f".format(avgCentroid)} Z=${"%.2f".format(avgZcr)} T=${"%.2f".format(avgTilt)} F=${"%.3f".format(avgFlatness)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "RESONANT_IMPACT",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "RESONANT_IMPACT",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // First-tap fallback reject for bright broadband impact signatures.
                    // This catches object contacts that still slip past profile + metallic checks.
                    if (isValid && !hasRecentTapReference) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val brightBroadImpactLike =
                            isSnapOrClapFamily &&
                                durationMs in 20..120 &&
                                avgCentroid >= 4000f &&
                                avgTilt >= 0.10f &&
                                avgFlatness >= 0.60f &&
                                avgZcr >= 0.35f &&
                                (envelopeFeatures.tailEnergyRatio >= 0.26f || envelopeFeatures.decayHalfFrames >= framesForMs(45)) &&
                                eventAttackRatio <= 24f
                        if (brightBroadImpactLike) {
                            rejectionReason =
                                "Bright Impact: C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)} F=${"%.3f".format(avgFlatness)} Z=${"%.2f".format(avgZcr)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "BRIGHT_IMPACT",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Very bright short ping reject:
                    // catches crisp metal/glass edge contacts that can still fit Snap (High).
                    if (isValid && !hasRecentTapReference) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val ultraBrightPingLike =
                            isSnapOrClapFamily &&
                                isUltraBrightPingSignature(
                                    durationMs = durationMs,
                                    avgZcr = avgZcr,
                                    avgCentroid = avgCentroid,
                                    avgTilt = avgTilt,
                                    avgFlatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    peakRms = eventPeakRms,
                                    env = envelopeFeatures
                                )
                        if (ultraBrightPingLike) {
                            rejectionReason =
                                "Ultra Bright Ping: D=${durationMs} C=${"%.0f".format(avgCentroid)} Z=${"%.2f".format(avgZcr)} T=${"%.2f".format(avgTilt)} F=${"%.3f".format(avgFlatness)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "ULTRA_BRIGHT_PING",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "ULTRA_BRIGHT_PING",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Attack-shape reject for flat-side material impacts:
                    // long bright onset with low-mid flatness and lingering envelope
                    // (common for glass/ceramic/metal face contact).
                    if (isValid && !hasRecentTapReference) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val glassSideAttackLike =
                            isSnapOrClapFamily &&
                                durationMs in 120..200 &&
                                avgCentroid >= 3900f &&
                                avgZcr >= 0.38f &&
                                avgTilt >= 0.22f &&
                                avgFlatness in 0.24f..0.50f &&
                                eventAttackRatio >= 25f &&
                                eventPeakRms >= 0.18f &&
                                (envelopeFeatures.tailEnergyRatio >= 0.20f || envelopeFeatures.decayHalfFrames >= framesForMs(65))
                        if (glassSideAttackLike) {
                            rejectionReason =
                                "Attack Shape Impact: D=${durationMs} C=${"%.0f".format(avgCentroid)} Z=${"%.2f".format(avgZcr)} T=${"%.2f".format(avgTilt)} F=${"%.3f".format(avgFlatness)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            rememberNuisanceReference(
                                stage = "ATTACK_SHAPE",
                                durationMs = durationMs,
                                zcr = avgZcr,
                                centroid = avgCentroid,
                                tilt = avgTilt,
                                flatness = avgFlatness,
                                attackRatio = eventAttackRatio,
                                strength = eventPeakRms,
                                timestampMs = eventStartTimestamp
                            )
                            emitEventDiag(
                                "ATTACK_SHAPE",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }
                    
                    // 3. Pre-Event Isolation Check (Global for all profiles)
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)

                        // SANITY CHECK: "Digital Silence" or "Wake Up" Artifacts
                        // If the sound PRIOR to the event was absolute zero (0.0), it means the microphone
                        // wasn't delivering data (lag/mute/emulator bug). A real room is never 0.0.
                        // We reject this because we can't measure isolation against 0.0.
                        if (preEventNoiseAvg < 0.00001f) {
                             rejectionReason = "Artifact: Wake-up pop (Pre-noise is digital silence)"
                             Log.d("SoundDetector", "Rejected: $rejectionReason")
                             isValid = false
                             emitEventDiag("ARTIFACT", rejectionReason,
                                 overrideDurationMs = durationMs, overrideAvgZcr = avgZcr,
                                 overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt, overrideAvgFlatness = avgFlatness,
                                 bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                 allProfileScores = allScores, sessionSimilarity = sessionSimilarityValue)
                        } else {
                            val preEventRatio = if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f
                            
                            // Dynamic Isolation Threshold
                            // Smooth transition: Quiet sounds tolerate 0.6 noise ratio. Loud sounds (>0.2) tighten to 0.25.
                            // This prevents the "Dead Zone" where a medium snap fails because it's slightly too loud for the easy check.
                            val allowedRatioBase = (0.65f - (eventPeakRms * 2.5f)).coerceIn(0.25f, 0.65f)
                            val allowedRatio = if (hasRecentTapReference) {
                                (allowedRatioBase + 0.08f).coerceAtMost(0.72f)
                            } else {
                                allowedRatioBase
                            }
                            
                            if (preEventRatio > allowedRatio) {
                                rejectionReason = "Isolation/PreNoise: ${"%.2f".format(preEventRatio)} > ${"%.2f".format(allowedRatio)}"
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                                emitEventDiag("ISOLATION", rejectionReason,
                                    overrideDurationMs = durationMs, overrideAvgZcr = avgZcr,
                                    overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt, overrideAvgFlatness = avgFlatness,
                                    bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                    allProfileScores = allScores, sessionSimilarity = sessionSimilarityValue)
                            }
                        }
                    }

                    fun matchesRecentSignature(
                        refType: String?,
                        refTimestampMs: Long,
                        refDurationMs: Int,
                        refCentroid: Float,
                        refTilt: Float,
                        refStrength: Float,
                        windowMs: Long
                    ): Boolean {
                        if (refType == null) return false
                        val ageMs = eventStartTimestamp - refTimestampMs
                        if (ageMs !in 20L..windowMs) return false

                        val bestType = bestMatch?.name
                        val typeMatches = bestType == refType
                        val familyOfBest = bestType?.substringBefore(" (")?.trim()
                        val familyOfRef = refType.substringBefore(" (").trim()
                        val familyMatches = familyOfBest != null && familyOfBest == familyOfRef
                        val durationDelta = abs(durationMs - refDurationMs)
                        val centroidDeltaPct = abs(avgCentroid - refCentroid) / refCentroid.coerceAtLeast(500f)
                        val tiltDelta = abs(avgTilt - refTilt)
                        val strengthRatio = eventPeakRms / refStrength.coerceAtLeast(0.001f)
                        val acousticMatch =
                            durationDelta <= 90 &&
                            centroidDeltaPct <= 0.50f &&
                            tiltDelta <= 14f &&
                            strengthRatio in 0.20f..4.50f

                        return typeMatches || familyMatches || acousticMatch
                    }
                    
                    // 4. Pre-Event Spike Check (Chaos/Rattle Rejection)
                    // Reject if the 300ms before the event had multiple spikes above 
                    // the detection threshold. This catches pill bottles, key jangling, etc.
                    // Does NOT use CV (scale-invariant variance) because quiet-room 
                    // micro-jitter inflates CV to 2.0+ even in total silence.
                    // Frames belonging to a prior confirmed detection are excluded
                    // so that double-tap patterns (snap-snap) aren't blocked.
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val clapLikeEvent = bestMatch?.name?.startsWith("Clap") == true
                        val profileIsConfident = bestConfidence >= 0.88f
                        val lookbackForVariance = if (isGestureControlContext) framesForMs(240) else framesForMs(300)
                        val gapFrames = framesForMs(30)
                        val available = recentSamples.size
                        
                        // How many frames ago did the last confirmed detection end?
                        val framesAgoDetectionEnd = (frameCounter - lastDetectionEndFrame).toInt()
                        // The range of frames (as indices from current) that belong to the last detection
                        val detectionExcludeStart = framesAgoDetectionEnd - lastDetectionDurationFrames
                        val detectionExcludeEnd = framesAgoDetectionEnd
                        // Also exclude the most recent pre-qualified (pending) detection frames.
                        val framesAgoQualifiedEnd = (frameCounter - lastQualifiedEndFrame).toInt()
                        val qualifiedExcludeStart = framesAgoQualifiedEnd - lastQualifiedDurationFrames
                        val qualifiedExcludeEnd = framesAgoQualifiedEnd
                        
                        // Count how many frames in the pre-event window exceeded 
                        // a fraction of the detection threshold (actual loud noise)
                        val spikeThreshold = threshold * when {
                            isGestureControlContext -> 0.60f
                            clapLikeEvent && profileIsConfident -> 0.56f
                            else -> 0.53f
                        }
                        val minFramesForChaos = if (isGestureControlContext) framesForMs(80) else framesForMs(100)
                        val chaosRatioThreshold = when {
                            isGestureControlContext -> 0.40f
                            clapLikeEvent && profileIsConfident -> 0.34f
                            else -> 0.28f
                        }
                        var spikeCount = 0
                        var totalChecked = 0
                        
                        for (j in gapFrames until lookbackForVariance) {
                            if (available - 1 - j >= 0) {
                                // Skip frames that were part of the last confirmed detection
                                if (j in detectionExcludeStart..detectionExcludeEnd) continue
                                if (j in qualifiedExcludeStart..qualifiedExcludeEnd) continue
                                
                                val sample = recentSamples[available - 1 - j]
                                totalChecked++
                                if (sample > spikeThreshold) spikeCount++
                            }
                        }
                        
                        // If >25% of pre-event frames had spikes above half-threshold,
                        // the environment was actively noisy — reject.
                        val spikeRatio = if (totalChecked > 0) spikeCount.toFloat() / totalChecked else 0f
                        val whitelistByCandidate = matchesRecentSignature(
                            refType = lastCandidateType,
                            refTimestampMs = lastCandidateTimestampMs,
                            refDurationMs = lastCandidateDurationMs,
                            refCentroid = lastCandidateCentroid,
                            refTilt = lastCandidateTilt,
                            refStrength = lastCandidateStrength,
                            windowMs = 1000L
                        )
                        val whitelistByAccepted = matchesRecentSignature(
                            refType = lastAcceptedType,
                            refTimestampMs = lastAcceptedTimestampMs,
                            refDurationMs = lastAcceptedDurationMs,
                            refCentroid = lastAcceptedCentroid,
                            refTilt = lastAcceptedTilt,
                            refStrength = lastAcceptedStrength,
                            windowMs = 1400L
                        )
                        val preEventRatioForBypass = if (eventPeakRms > 0f) {
                            preEventNoiseAvg / eventPeakRms
                        } else {
                            1f
                        }
                        val burstTapBypass = isGestureControlContext &&
                            hasRecentTapReference &&
                            durationMs <= 130 &&
                            bestConfidence >= 0.86f &&
                            eventAttackRatio >= 10f &&
                            avgCentroid >= 1800f &&
                            preEventRatioForBypass <= 0.24f &&
                            spikeRatio <= 0.72f
                        val diagnosticClapBypass =
                            !isGestureControlContext &&
                                clapLikeEvent &&
                                profileIsConfident &&
                                eventAttackRatio >= 8f &&
                                preEventRatioForBypass <= 0.40f &&
                                spikeRatio <= 0.52f
                        if (totalChecked >= minFramesForChaos && spikeRatio > chaosRatioThreshold) {
                            if (whitelistByCandidate || whitelistByAccepted || burstTapBypass || diagnosticClapBypass) {
                                Log.d(
                                    "SoundDetector",
                                    "Chaos gate bypassed by signature whitelist " +
                                        "(candidate=$whitelistByCandidate accepted=$whitelistByAccepted burst=$burstTapBypass diagClap=$diagnosticClapBypass ratio=${"%.2f".format(spikeRatio)})"
                                )
                            } else {
                                rejectionReason = "Chaotic Pre-Event: $spikeCount/$totalChecked frames above ${"%.4f".format(spikeThreshold)} (${(spikeRatio * 100).toInt()}%)"
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                                emitEventDiag("CHAOS_PRE", rejectionReason,
                                    preEventCV = spikeRatio,
                                    overrideDurationMs = durationMs, overrideAvgZcr = avgZcr,
                                    overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt, overrideAvgFlatness = avgFlatness,
                                    bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                    allProfileScores = allScores, sessionSimilarity = sessionSimilarityValue)
                            }
                        }
                    }

                    // Stage 2 verifier: independent agreement scorer.
                    // Action proceeds only when Stage 1 and Stage 2 both pass.
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val preEventRatioStage2 =
                            if (eventPeakRms > 0f) preEventNoiseAvg / eventPeakRms else 1f
                        val stage2Score = computeStage2AgreementScore(
                            bestMatch = bestMatch,
                            profileConfidence = bestConfidence,
                            durationMs = durationMs,
                            attackRatio = eventAttackRatio,
                            peakRms = eventPeakRms,
                            preEventRatio = preEventRatioStage2,
                            avgZcr = avgZcr,
                            avgCentroid = avgCentroid,
                            avgTilt = avgTilt,
                            avgFlatness = avgFlatness,
                            env = envelopeFeatures
                        )
                        val resonantImpactLike =
                            !hasRecentTapReference &&
                                isResonantImpactSignature(
                                    durationMs = durationMs,
                                    avgZcr = avgZcr,
                                    avgCentroid = avgCentroid,
                                    avgTilt = avgTilt,
                                    avgFlatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    peakRms = eventPeakRms,
                                    env = envelopeFeatures
                                )
                        val ultraBrightPingLike =
                            !hasRecentTapReference &&
                                isUltraBrightPingSignature(
                                    durationMs = durationMs,
                                    avgZcr = avgZcr,
                                    avgCentroid = avgCentroid,
                                    avgTilt = avgTilt,
                                    avgFlatness = avgFlatness,
                                    attackRatio = eventAttackRatio,
                                    peakRms = eventPeakRms,
                                    env = envelopeFeatures
                                )
                        val stage2Threshold = when {
                            hasRecentTapReference && isDiagnosticContext -> 0.56f
                            hasRecentTapReference -> 0.56f
                            isDiagnosticContext -> 0.60f
                            else -> 0.60f
                        } + (if (resonantImpactLike) 0.10f else 0f) +
                            (if (ultraBrightPingLike) 0.10f else 0f)
                        if (stage2Score < stage2Threshold) {
                            rejectionReason =
                                "Stage2 Reject: score ${"%.2f".format(stage2Score)} < ${"%.2f".format(stage2Threshold)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "STAGE2",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                overrideAvgFlatness = avgFlatness,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores,
                                sessionSimilarity = sessionSimilarityValue
                            )
                        }
                    }

                    // Log Data for Analysis
                    val typeLog = if (isValid && bestMatch != null) "PENDING_${bestMatch.name.replace(" ", "_").uppercase()}" else "REJECTED"
                    val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                    val logEntry = "$timestamp,$debugContext,$typeLog,${"%.2f".format(bestConfidence)},$durationMs,${"%.4f".format(eventPeakRms)},${"%.4f".format(eventPeakRaw)},${"%.2f".format(avgZcr)},${"%.0f".format(avgCentroid)},${"%.2f".format(avgTilt)},${"%.3f".format(avgFlatness)},${sessionSimilarityValue?.let { "%.3f".format(it) } ?: ""},${"%.3f".format(if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f)}|$rejectionReason"
                    Log.i("SoundAnalysis", logEntry)

                    if (isValid && bestMatch != null) {
                        // DEFER FINAL CONFIRMATION - Wait for post-event quiet check
                        Log.d("SoundDetector", "Event passed pre-checks (${bestMatch.name}), waiting for post-event quiet...")
                        
                        // Only copy samples needed for visualization (last ~500ms).
                        val sampleWindowFrames = framesForMs(500)
                        val sampleSnapshot = if (recentSamples.size > sampleWindowFrames) {
                            recentSamples.subList(recentSamples.size - sampleWindowFrames, recentSamples.size).toList()
                        } else {
                            recentSamples.toList()
                        }
                        
                        pendingEvent = SoundDetectionEvent(
                            timestampMs = eventStartTimestamp,
                            durationMs = durationMs,
                            strength = eventPeakRms,
                            peak = eventPeakRaw,
                            type = bestMatch.name,
                            centroid = avgCentroid,
                            tilt = avgTilt,
                            zcr = avgZcr,
                            flatness = avgFlatness,
                            confidence = bestConfidence,
                            samples = sampleSnapshot
                        )
                        pendingEventLogEntry = logEntry.replace("PENDING_", "DETECTED_")
                        postEventCheckCountdown = postEventCheckFrames
                        thresholdAtEventEnd = threshold
                        postEventMaxRmsDuringCheck = 0f
                        pendingProfileScores = allScores
                        pendingSessionSimilarity = sessionSimilarityValue

                        // Store a recent pre-qualified signature so a rapid second tap can
                        // bypass CHAOS_PRE if it matches the first tap profile.
                        lastQualifiedEndFrame = frameCounter
                        lastQualifiedDurationFrames = framesForMs(durationMs) + postEventCheckFrames + framesForMs(30)
                        lastCandidateType = bestMatch.name
                        lastCandidateTimestampMs = eventStartTimestamp
                        lastCandidateDurationMs = durationMs
                        lastCandidateCentroid = avgCentroid
                        lastCandidateTilt = avgTilt
                        lastCandidateStrength = eventPeakRms
                    } else if (rejectionReason != null) {
                         // Update state with rejection reason to help debugging
                        _state.update { current ->
                            val updatedLogs = (current.logs + logEntry).takeLast(maxLogs)
                            current.copy(
                                lastConfidence = bestConfidence,
                                lastRejectionReason = rejectionReason,
                                debugInfo = "Last Fail: $rejectionReason",
                                logs = updatedLogs
                            )
                        }
                    }
                    
                    isMonitoringEvent = false
                }
            } else {
                // IDLE - Check for trigger
                
                // === POST-EVENT QUIET CHECK ===
                if (postEventCheckCountdown > 0) {
                    if (rmsF > postEventMaxRmsDuringCheck) {
                        postEventMaxRmsDuringCheck = rmsF
                    }
                    postEventCheckCountdown--
                    
                    if (postEventCheckCountdown == 0) {
                        // Time to evaluate post-event quiet
                        val pending = pendingEvent
                        val logEntryForEvent = pendingEventLogEntry

                        if (pending != null && logEntryForEvent != null) {
                            // Check if current RMS is quiet enough
                            val postQuietLimit = thresholdAtEventEnd * postEventQuietRatio
                            val isQuiet = postEventMaxRmsDuringCheck < postQuietLimit
                            val postPeakRatio = if (pending.strength > 0f) {
                                postEventMaxRmsDuringCheck / pending.strength
                            } else {
                                1f
                            }
                            val allowedPostPeakRatio = 0.30f
                            val isPostRingClean = postPeakRatio <= allowedPostPeakRatio

                            if (isQuiet && isPostRingClean) {
                                // CONFIRMED DETECTION
                                Log.i("SoundDetector", "Confirmed ${pending.type} (${(pending.confidence*100).toInt()}%)! Post-event quiet.")
                                
                                // Record when this detection ended so the chaos gate
                                // can skip these frames for the next event
                                lastDetectionEndFrame = frameCounter
                                lastDetectionDurationFrames = framesForMs(pending.durationMs) + postEventCheckFrames + framesForMs(30) // event + post-check + margin
                                lastAcceptedType = pending.type
                                lastAcceptedTimestampMs = pending.timestampMs
                                lastAcceptedDurationMs = pending.durationMs
                                lastAcceptedCentroid = pending.centroid
                                lastAcceptedTilt = pending.tilt
                                lastAcceptedStrength = pending.strength
                                
                                // Emit accepted diagnostic
                                val savedScores = pendingProfileScores
                                emitDiagnostic(DetectionDiagnostic(
                                    id = SystemClock.elapsedRealtime(),
                                    timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
                                    eventTimestampMs = pending.timestampMs,
                                    outcome = "ACCEPTED", rejectedAt = null, rejectionDetail = null,
                                    noiseFloor = eventNoiseFloorAtTrigger, threshold = eventThresholdAtTrigger,
                                    preEventNoiseAvg = preEventNoiseAvg,
                                    triggerRms = eventTriggerRms, attackRatio = eventAttackRatio,
                                    durationMs = pending.durationMs, peakRms = pending.strength, peakRaw = pending.peak,
                                    sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
                                    postEventRms = postEventMaxRmsDuringCheck,
                                    postEventQuietThreshold = thresholdAtEventEnd * postEventQuietRatio,
                                    avgZcr = pending.zcr, avgCentroid = pending.centroid, avgTilt = pending.tilt, avgFlatness = pending.flatness,
                                    sessionSimilarity = pendingSessionSimilarity,
                                    bestProfileName = pending.type, bestConfidence = pending.confidence,
                                    allProfileScores = savedScores
                                ))
                                
                                _state.update { current ->
                                    val updated = (listOf(pending) + current.events).take(maxEvents)
                                    val updatedLogs = (current.logs + logEntryForEvent).takeLast(maxLogs)
                                    current.copy(
                                        isLit = true,
                                        count = current.count + 1,
                                        lastStrength = pending.strength,
                                        lastPeak = pending.peak,
                                        lastConfidence = pending.confidence,
                                        rms = rmsF,
                                        noiseFloor = noiseFloor,
                                        currentRms = rmsF,
                                        lastRejectionReason = null,
                                        debugInfo = "${pending.type} ${(pending.confidence*100).toInt()}%",
                                        events = updated,
                                        logs = updatedLogs
                                    )
                                }
                                litJob?.cancel()
                                litJob = scope.launch {
                                    delay(100)
                                    _state.update { it.copy(isLit = false) }
                                }
                            } else {
                                // REJECTED - Post-event still noisy (chaotic/rattling source)
                                val postRejectReason = if (!isQuiet) {
                                    "Post-Event Noisy: ${"%.3f".format(postEventMaxRmsDuringCheck)} > ${"%.3f".format(postQuietLimit)}"
                                } else {
                                    "Post-Event Ringing: ratio ${"%.2f".format(postPeakRatio)} > ${"%.2f".format(allowedPostPeakRatio)}"
                                }
                                Log.d("SoundDetector", "Rejected: $postRejectReason")
                                if (!isPostRingClean) {
                                    rememberNuisanceReference(
                                        stage = "CHAOS_POST_RING",
                                        durationMs = pending.durationMs,
                                        zcr = pending.zcr,
                                        centroid = pending.centroid,
                                        tilt = pending.tilt,
                                        flatness = pending.flatness,
                                        attackRatio = eventAttackRatio,
                                        strength = pending.strength,
                                        timestampMs = pending.timestampMs
                                    )
                                }
                                
                                // Emit post-event rejection diagnostic
                                val savedScores = pendingProfileScores
                                emitDiagnostic(DetectionDiagnostic(
                                    id = SystemClock.elapsedRealtime(),
                                    timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
                                    eventTimestampMs = pending.timestampMs,
                                    outcome = "REJECTED", rejectedAt = "CHAOS_POST", rejectionDetail = postRejectReason,
                                    noiseFloor = eventNoiseFloorAtTrigger, threshold = eventThresholdAtTrigger,
                                    preEventNoiseAvg = preEventNoiseAvg,
                                    triggerRms = eventTriggerRms, attackRatio = eventAttackRatio,
                                    durationMs = pending.durationMs, peakRms = pending.strength, peakRaw = pending.peak,
                                    sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
                                    postEventRms = postEventMaxRmsDuringCheck,
                                    postEventQuietThreshold = thresholdAtEventEnd * postEventQuietRatio,
                                    avgZcr = pending.zcr, avgCentroid = pending.centroid, avgTilt = pending.tilt, avgFlatness = pending.flatness,
                                    sessionSimilarity = pendingSessionSimilarity,
                                    bestProfileName = pending.type, bestConfidence = pending.confidence,
                                    allProfileScores = savedScores
                                ))
                                
                                val rejectedLog = logEntryForEvent.replace("DETECTED_", "REJECTED_POST_") + "|$postRejectReason"
                                _state.update { current ->
                                    val updatedLogs = (current.logs + rejectedLog).takeLast(maxLogs)
                                    current.copy(
                                        lastConfidence = pending.confidence,
                                        lastRejectionReason = postRejectReason,
                                        debugInfo = "Last Fail: $postRejectReason",
                                        logs = updatedLogs
                                    )
                                }

                                // Pending signature failed post-check; do not keep it whitelisted.
                                lastQualifiedEndFrame = -1000L
                                lastQualifiedDurationFrames = 0
                                lastCandidateType = null
                                lastCandidateTimestampMs = 0L
                                lastCandidateDurationMs = 0
                                lastCandidateCentroid = 0f
                                lastCandidateTilt = 0f
                                lastCandidateStrength = 0f
                            }
                        }
                        
                        pendingEvent = null
                        pendingEventLogEntry = null
                        postEventMaxRmsDuringCheck = 0f
                        pendingProfileScores = emptyList()
                        pendingSessionSimilarity = null
                    }
                }
                
                // We use HPF RMS for the primary trigger to ignore low-frequency speech mud.
                // However, we must allow "Broadband" triggers if they are potential Knocks (Very low ZCR).
                // Or we effectively say "Snap/Slap detection requires HF energy".
                // Given the user issues, we will prioritize HF triggering.
                
                val primaryTrigger = hpfRmsF > threshold
                val broadbandTrigger = rmsF > threshold
                
                // Flux Gate: Only allow broadband trigger if it has decent HF content 
                // OR if it looks like a Knock (Low ZCR check is hard on single frame, but we can check if hpf isn't ZERO)
                
                if (primaryTrigger || (broadbandTrigger && hpfRmsF > rmsF * 0.25f)) {
                    // LOUDNESS CHECK PASSED
                    
                    // ATTACK CHECK: Check if rise was sudden
                    val baseline = if (prevRms > noiseFloor) prevRms else noiseFloor
                    val riseRatio = rmsF / baseline.coerceAtLeast(0.001f) // Use full RMS for attack ratio to capture full onset
                    
                    if (riseRatio > attackThreshold) {
                        val nowMs = SystemClock.elapsedRealtime()
                        val sinceLastPlaybackMs = nowMs - sfxPlayer.lastPlaybackTimestampMs()
                        if (sinceLastPlaybackMs in 0L..playbackBlankingMs) {
                            if (nowMs - lastPlaybackBlankDiagMs >= playbackBlankDiagCooldownMs) {
                                lastPlaybackBlankDiagMs = nowMs
                                emitDiagnostic(
                                    DetectionDiagnostic(
                                        id = nowMs,
                                        timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
                                        eventTimestampMs = nowMs,
                                        outcome = "REJECTED",
                                        rejectedAt = "PLAYBACK_BLANK",
                                        rejectionDetail = "Suppressed ${sinceLastPlaybackMs}ms after app playback",
                                        noiseFloor = noiseFloor,
                                        threshold = threshold,
                                        preEventNoiseAvg = preEventNoiseAvg,
                                        triggerRms = rmsF,
                                        attackRatio = riseRatio,
                                        durationMs = 0,
                                        peakRms = rmsF,
                                        peakRaw = peakNorm.toFloat(),
                                        sustainRatio = null,
                                        decayRatio = null,
                                        peakFrameIndex = 0,
                                        avgZcr = zcr,
                                        avgCentroid = centroid,
                                        avgTilt = tilt,
                                        avgFlatness = flatness
                                    )
                                )
                            }
                            continue
                        }

                         // Start Monitoring
                        isMonitoringEvent = true
                        eventStartTimestamp = nowMs
                        eventHighRmsFrames = 1
                        eventAccumulatedZcr = zcr
                        eventAccumulatedCentroid = centroid // Reset Accumulator!
                        eventAccumulatedTilt = tilt
                        eventAccumulatedFlatness = flatness
                        eventPeakRms = rmsF
                        eventPeakRaw = peakNorm.toFloat()
                        framesSincePeak = 0
                        hasDippedFromPeak = false
                        eventRmsTrace.clear()
                        eventRmsTrace.add(rmsF)
                        eventTriggerRms = rmsF
                        eventAttackRatio = riseRatio
                        eventNoiseFloorAtTrigger = noiseFloor
                        eventThresholdAtTrigger = threshold
                        
                        // Capture pre-event noise level
                        // Reverted deep lookback to 120ms to keep it snappy.
                        // We will rely on stricter spectral profiling (Tilt/Centroid) to kill speech.
                        val lookback = framesForMs(120)
                        val available = recentSamples.size
                        
                        // Skip the immediate pre-trigger window to ignore local onset dip.
                        val gapFrames = framesForMs(30)
                        
                        var noiseSum = 0f
                        var count = 0
                        for (j in gapFrames until lookback) {
                           if (available - 1 - j >= 0) {
                               val s = recentSamples[available - 1 - j]
                               noiseSum += s
                               count++
                           }
                        }
                        preEventNoiseAvg = if (count > 0) noiseSum / count else 0f
                    }
                }
                
                // Update Noise Floor (only when not loud)
                if (rmsF < threshold * 0.8f) {
                     // Asymmetric Update
                     val alpha = if (rmsF < noiseFloor) noiseAlphaFall else noiseAlphaRise
                     noiseFloor = (noiseFloor * (1f - alpha)) + (rmsF * alpha)
                }
            }

            // Update UI State
            recentSamples.add(rmsF)
            if (recentSamples.size > maxRecentSamples) {
                recentSamples.removeAt(0)
            }
            frameCounter++

            _state.update { current ->
                current.copy(rms = rmsF, noiseFloor = noiseFloor, currentRms = rmsF)
            }
        } 
        } finally {
            Log.d("SoundDetector", "Recording loop ended")
            try {
                if (currentRecorder.state == AudioRecord.STATE_INITIALIZED) {
                    currentRecorder.stop()
                }
            } catch (e: Exception) {
                Log.e("SoundDetector", "Error stopping recorder", e)
            }
            try {
                currentRecorder.release()
            } catch (e: Exception) {
                Log.e("SoundDetector", "Error releasing recorder", e)
            }
            if (recorder == currentRecorder) {
                recorder = null
            }

            try {
                aec?.release()
                Log.d("SoundDetector", "AEC released")
            } catch (e: Exception) {
                Log.e("SoundDetector", "Error releasing AEC", e)
            }
        }
    }
}
