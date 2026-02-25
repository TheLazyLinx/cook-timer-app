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
    val confidence: Float = 1.0f,
    val samples: List<Float> = emptyList() // RMS values over time (each 10ms)
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

@Singleton
class SoundDetector @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SoundDetectorState())
    val state: StateFlow<SoundDetectorState> = _state
    
    @Volatile
    var debugContext: String = "Unknown" 

    private val sampleRate = 16000
    private val windowMs = 10
    private val windowSamples = sampleRate * windowMs / 1000
    
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
    private val confidenceThreshold = 0.78f // Raised from 0.75 to 0.78 to cut marginal Speech FPs (like the 76% one)
    
    // Chaos Rejection (Pill Rattle, Continuous Noise)
    private val postEventCheckFrames = 5 // Wait 50ms after event ends to verify quiet
    private val postEventQuietRatio = 0.5f // Post-event RMS must drop below 50% of threshold
    
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

        val earlyFrames = minOf(3, trace.size)
        var earlyEnergy = 0f
        for (i in 0 until earlyFrames) earlyEnergy += energy[i]

        val tailFrames = minOf(6, trace.size)
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
        val decayHalfScore = when {
            env.decayHalfFrames == Int.MAX_VALUE -> 0f
            env.decayHalfFrames <= 3 -> 1f
            env.decayHalfFrames >= 12 -> 0f
            else -> ((12 - env.decayHalfFrames).toFloat() / 9f).coerceIn(0f, 1f)
        }
        val decayQuarterScore = when {
            env.decayQuarterFrames == Int.MAX_VALUE -> 0f
            env.decayQuarterFrames <= 6 -> 1f
            env.decayQuarterFrames >= 18 -> 0f
            else -> ((18 - env.decayQuarterFrames).toFloat() / 12f).coerceIn(0f, 1f)
        }
        val compactTailScore = (1f - env.tailEnergyRatio).coerceIn(0f, 1f)
        val stableEnvelopeScore = (1f - env.bounceRatio).coerceIn(0f, 1f)
        val lowPreNoiseScore = (1f - preEventRatio.coerceIn(0f, 1f))

        var score = 0.15f
        score += profileConfidence.coerceIn(0f, 1f) * 0.30f
        score += attackScore * 0.10f
        score += centroidScore * 0.08f
        score += zcrScore * 0.07f
        score += tiltScore * 0.05f
        score += decayHalfScore * 0.08f
        score += decayQuarterScore * 0.07f
        score += compactTailScore * 0.08f
        score += stableEnvelopeScore * 0.06f
        score += lowPreNoiseScore * 0.06f

        if (durationMs > 180) score -= 0.08f
        if (durationMs < 20) score -= 0.05f
        if (peakRms < 0.04f) score -= 0.05f
        if (env.monotonicRiseRatio > 0.45f) score -= 0.10f
        if (env.tailEnergyRatio > 0.62f) score -= 0.10f
        if (preEventRatio > 0.55f) score -= 0.07f

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
        if (tableCoupledThud) score -= 0.24f
        if (dampedContactLike) score -= 0.12f
        if (midBandContactLike) score -= 0.14f

        return score.coerceIn(0f, 1f)
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
        
        val buffer = ShortArray(windowSamples)
        val floatBuffer = FloatArray(windowSamples) // Check that allocation is outside loop
        var noiseFloor = 0.005f
        var flatlineCount = 0
        val maxFlatlineBeforeRestart = 100 // Reduced to ~1 second to catch dropouts faster
        
        // Data Normalization (Warm-up)
        // We start with a high "learning rate" to quickly snap to the room's noise level
        // preventing the "Latch-up" bug where noise > threshold prevents threshold from rising.
        var warmUpFrames = 50 // 500ms
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
        val maxRecentSamples = 150 // Increased to 1.5s history to Bridge Speech Gaps
        var lastUiEmitMs = 0L
        
        // Event Monitoring State
        var isMonitoringEvent = false
        var eventStartTimestamp = 0L
        var eventHighRmsFrames = 0
        var eventAccumulatedZcr = 0f
        var eventAccumulatedCentroid = 0f // Sum of centroids during event
        var eventAccumulatedTilt = 0f
        var eventPeakRms = 0f
        var eventPeakRaw = 0f
        var framesSincePeak = 0
        var hasDippedFromPeak = false
        val eventRmsTrace = mutableListOf<Float>()
        var preEventNoiseAvg = 0f
        var consecutiveLoudFrames = 0
        
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
                durationMs = overrideDurationMs ?: (frames * windowMs),
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
                bestProfileName = bestProfileName,
                bestConfidence = bestConfidence,
                allProfileScores = allProfileScores
            ))
        }
        
        try {
            currentRecorder.startRecording()
            Log.d("SoundDetector", "Recording started. Window: ${windowMs}ms")

            while (running) {
            if (currentRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("SoundDetector", "Recorder stopped recording unexpectedly")
                currentRecorder.startRecording()
                delay(100)
                continue
            }

            val read = currentRecorder.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.e("SoundDetector", "Read error: $read")
                delay(50)
                continue
            }
            if (read == 0) {
                delay(10)
                continue
            }

            // 1. Calculate Statistics for this Window
            // Remove DC Offset first!
            var dcSum = 0L
            for (i in 0 until read) {
                dcSum += buffer[i]
            }
            val dcOffset = (dcSum / read).toInt() // integer mean
            
            var sum = 0.0
            var hpfSum = 0.0
            var peak = 0.0
            var zeroCrossings = 0
            // Reuse pre-allocated buffer, but be careful if read < windowSamples.
            // We use 'read' as the limit for loops.
            
            for (i in 0 until read) {
                val v = (buffer[i] - dcOffset).toFloat()
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
            
            val rms = sqrt(sum / read) / 32768.0
            val hpfRms = sqrt(hpfSum / read) / 32768.0
            val peakNorm = (peak / 32768.0).coerceAtMost(1.0)
            val rmsF = rms.toFloat()
            val hpfRmsF = hpfRms.toFloat()
            val zcr = zeroCrossings.toFloat() / read.coerceAtLeast(1)
            
            // Calculate Spectral Features
            // If read mismatch (rare), copy. Otherwise use pre-allocated buffer.
            val inputForFft = if (read == floatBuffer.size) floatBuffer else floatBuffer.copyOfRange(0, read)
            val spectralFeatures = SoundUtils.calculateSpectralFeatures(inputForFft, sampleRate)
            val centroid = spectralFeatures.centroid
            val tilt = spectralFeatures.tilt

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
                        warmUpFrames = 50 // Reset warm-up to silence the "pop" of restarting and recalibrate noise floor
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
                if (consecutiveLoudFrames > 200) { // 2.0 seconds
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
                    if (eventHighRmsFrames == 5 || eventHighRmsFrames == 6) {
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
                                    "Ratio ${"%.2f".format(ratio)} at ${eventHighRmsFrames*windowMs}ms (>${"%.2f".format(sustainRejectRatio)})",
                                    sustainRatio = ratio
                                )
                            }
                            Log.d("SoundDetector", "Rejected: Sustained/Speech (Ratio $ratio at ${eventHighRmsFrames*10}ms)")
                            isMonitoringEvent = false
                        }
                    }

                    // Peak Late Check (Groan Rejection)
                    // If the peak happened > 60ms into the event, it's a swell/crescendo (impulse peaking is instant).
                    // Exception: Very long events might have multiple peaks, but we assume impulse snaps peak early.
                    val framesAtPeak = eventHighRmsFrames - framesSincePeak
                    if (framesAtPeak > 6 && rmsF > threshold * 2.0f) { // Only strict on loud sounds
                          if (isMonitoringEvent) emitEventDiag("LATE_PEAK", "Peak at frame $framesAtPeak (${framesAtPeak*windowMs}ms into event)")
                          Log.d("SoundDetector", "Rejected: Slow Intensity Rise (Peak at ${framesAtPeak*10}ms)")
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
                        val currentDurationMs = eventHighRmsFrames * windowMs
                        if (currentDurationMs > 150) {
                            if (isMonitoringEvent) emitEventDiag("REBOUND", "Double peak at ${currentDurationMs}ms")
                            Log.d("SoundDetector", "Rejected: Bouncy signal (multiple peaks)")
                            isMonitoringEvent = false
                        }
                    }

                    // Duration Check (Fail if too long)
                    val currentDurationMs = eventHighRmsFrames * windowMs
                    // Use the longest possible profile as the absolute max to stop checking early
                    if (currentDurationMs > 300) { 
                        if (isMonitoringEvent) emitEventDiag("DURATION", "Too long: ${currentDurationMs}ms > 300ms")
                        Log.d("SoundDetector", "Rejected: Too long ($currentDurationMs ms)")
                        isMonitoringEvent = false // Reset
                    }
                    
                    // Decay Check (Fail if tail is too loud after peak)
                    val decayCheckFrame = 6 // 60ms after peak
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
                    val durationMs = eventHighRmsFrames * windowMs
                    val avgZcr = eventAccumulatedZcr / eventHighRmsFrames.coerceAtLeast(1)
                    val avgCentroid = eventAccumulatedCentroid / eventHighRmsFrames.coerceAtLeast(1)
                    val avgTilt = eventAccumulatedTilt / eventHighRmsFrames.coerceAtLeast(1)
                    
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
                    val effectiveConfidenceThreshold = if (isDiagnosticContext) 0.75f else confidenceThreshold
                    var isValid = bestMatch != null && bestConfidence >= effectiveConfidenceThreshold
                    var rejectionReason: String? = null

                    // Stage 1 refinement: reject complex/ringing envelopes earlier.
                    if (isValid) {
                        val isGestureControlContext = !debugContext.equals("DIAGNOSTIC", ignoreCase = true)
                        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
                        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
                        val hasRecentTapReference = isGestureControlContext &&
                            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
                        val envelopeComplex =
                            !hasRecentTapReference &&
                                durationMs in 50..220 &&
                                envelopeFeatures.tailEnergyRatio >= 0.66f &&
                                envelopeFeatures.decayHalfFrames >= 8 &&
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
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
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
                             overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt,
                             bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                            allProfileScores = allScores)
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
                                (envelopeFeatures.tailEnergyRatio >= 0.30f || envelopeFeatures.decayHalfFrames >= 6)
                        val looksLikeSurfaceTap =
                            legacyWetClapSurfaceTap || stronglyTableCoupled || dampedContactImpact
                        if (looksLikeSurfaceTap) {
                            rejectionReason =
                                "Surface Tap: D=${durationMs}ms Z=${"%.2f".format(avgZcr)} C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "SURFACE_TAP",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
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
                                (envelopeFeatures.tailEnergyRatio >= 0.26f || envelopeFeatures.decayHalfFrames >= 5)
                        if (looksHardSurfaceImpact) {
                            rejectionReason =
                                "Hard Surface Impact: D=${durationMs}ms A=${"%.2f".format(eventAttackRatio)} C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "HARD_SURFACE",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
                            )
                        }
                    }

                    // Envelope-shape ringing check:
                    // Metallic/ceramic impacts often show bouncy or flat tails compared to snap/clap transients.
                    if (isValid && eventPeakRms > 0f && eventRmsTrace.size >= 4) {
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
                        val tailFrames = minOf(3, postPeak.size)
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
                            emitEventDiag(
                                "ENVELOPE_RING",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
                            )
                        }
                    }

                    // Metallic clinks (cutlery/keys) can look like sharp taps.
                    // Reject bright, positively tilted transients with weaker attack.
                    if (isValid) {
                        val isSnapOrClapFamily =
                            bestMatch?.name?.startsWith("Snap") == true ||
                                bestMatch?.name?.startsWith("Clap") == true ||
                                bestMatch?.name?.startsWith("Slap") == true
                        val looksMetallic =
                            isSnapOrClapFamily &&
                                durationMs in 30..180 &&
                                avgCentroid >= 4200f &&
                                avgTilt >= 0.18f &&
                                eventAttackRatio <= 18f
                        if (looksMetallic) {
                            rejectionReason =
                                "Metallic Ringing: C=${"%.0f".format(avgCentroid)} T=${"%.2f".format(avgTilt)} A=${"%.2f".format(eventAttackRatio)}"
                            Log.d("SoundDetector", "Rejected: $rejectionReason")
                            isValid = false
                            emitEventDiag(
                                "METALLIC",
                                rejectionReason,
                                overrideDurationMs = durationMs,
                                overrideAvgZcr = avgZcr,
                                overrideAvgCentroid = avgCentroid,
                                overrideAvgTilt = avgTilt,
                                bestProfileName = bestMatch.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
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
                                 overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt,
                                 bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                 allProfileScores = allScores)
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
                                    overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt,
                                    bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                    allProfileScores = allScores)
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
                        val lookbackForVariance = if (isGestureControlContext) 24 else 30
                        val gapFrames = 3
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
                        val minFramesForChaos = if (isGestureControlContext) 8 else 10
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
                                    overrideAvgCentroid = avgCentroid, overrideAvgTilt = avgTilt,
                                    bestProfileName = bestMatch?.name, bestConfidence = bestConfidence,
                                    allProfileScores = allScores)
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
                            env = envelopeFeatures
                        )
                        val stage2Threshold = when {
                            hasRecentTapReference && isDiagnosticContext -> 0.50f
                            hasRecentTapReference -> 0.56f
                            isDiagnosticContext -> 0.54f
                            else -> 0.60f
                        }
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
                                bestProfileName = bestMatch?.name,
                                bestConfidence = bestConfidence,
                                allProfileScores = allScores
                            )
                        }
                    }

                    // Log Data for Analysis
                    val typeLog = if (isValid && bestMatch != null) "PENDING_${bestMatch.name.replace(" ", "_").uppercase()}" else "REJECTED"
                    val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                    val logEntry = "$timestamp,$debugContext,$typeLog,${"%.2f".format(bestConfidence)},$durationMs,${"%.4f".format(eventPeakRms)},${"%.4f".format(eventPeakRaw)},${"%.2f".format(avgZcr)},${"%.0f".format(avgCentroid)},${"%.2f".format(avgTilt)},${"%.3f".format(if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f)}|$rejectionReason"
                    Log.i("SoundAnalysis", logEntry)

                    if (isValid && bestMatch != null) {
                        // DEFER FINAL CONFIRMATION - Wait for post-event quiet check
                        Log.d("SoundDetector", "Event passed pre-checks (${bestMatch.name}), waiting for post-event quiet...")
                        
                        // Only copy the samples needed for visualization (last 50 frames = 500ms)
                        val sampleSnapshot = if (recentSamples.size > 50) {
                            recentSamples.subList(recentSamples.size - 50, recentSamples.size).toList()
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
                            confidence = bestConfidence,
                            samples = sampleSnapshot
                        )
                        pendingEventLogEntry = logEntry.replace("PENDING_", "DETECTED_")
                        postEventCheckCountdown = postEventCheckFrames
                        thresholdAtEventEnd = threshold
                        postEventMaxRmsDuringCheck = 0f
                        pendingProfileScores = allScores

                        // Store a recent pre-qualified signature so a rapid second tap can
                        // bypass CHAOS_PRE if it matches the first tap profile.
                        lastQualifiedEndFrame = frameCounter
                        lastQualifiedDurationFrames = (durationMs / windowMs) + postEventCheckFrames + 3
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
                                lastDetectionDurationFrames = (pending.durationMs / windowMs) + postEventCheckFrames + 3 // event + post-check + margin
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
                                    avgZcr = 0f, avgCentroid = pending.centroid, avgTilt = pending.tilt,
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
                                    avgZcr = 0f, avgCentroid = pending.centroid, avgTilt = pending.tilt,
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
                         // Start Monitoring
                        isMonitoringEvent = true
                        eventStartTimestamp = SystemClock.elapsedRealtime()
                        eventHighRmsFrames = 1
                        eventAccumulatedZcr = zcr
                        eventAccumulatedCentroid = centroid // Reset Accumulator!
                        eventAccumulatedTilt = tilt
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
                        val lookback = 12
                        val available = recentSamples.size
                        
                        // We skip the immediate 3 frames (30ms) before the event to ignore the "Silence before the Storm"
                        val gapFrames = 3 
                        
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

            // Throttle UI-state emissions to ~10 fps (every 100ms) instead of every 10ms frame
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastUiEmitMs >= 100L) {
                lastUiEmitMs = nowMs
                _state.update { current ->
                    current.copy(rms = rmsF, noiseFloor = noiseFloor, currentRms = rmsF)
                }
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
