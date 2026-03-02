package ro.maris.tools.cooktimer.audio

import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

// ── Public Types ──

data class FrameFeatures(
    val rms: Float,
    val hpfRms: Float,
    val peak: Float,
    val zcr: Float,
    val centroid: Float,
    val tilt: Float,
    val flatness: Float
)

enum class FrameAction {
    CONTINUE,
    RESTART_RECORDER
}

internal interface PipelineHost {
    fun emitDiagnostic(diagnostic: DetectionDiagnostic)
    fun updateState(updater: (SoundDetectorState) -> SoundDetectorState)
    fun scheduleLitReset()
    val debugContext: String
}

// ── Pipeline ──

internal class DetectionPipeline(
    private val config: DetectionConfig,
    private val sfxPlayer: SfxPlayer,
    private val audioManager: AudioManager,
    private val host: PipelineHost
) {

    // ── Internal Types ──

    private data class Stage1EnvelopeFeatures(
        val earlyEnergyRatio: Float,
        val tailEnergyRatio: Float,
        val bounceRatio: Float,
        val monotonicRiseRatio: Float,
        val decayHalfFrames: Int,
        val decayQuarterFrames: Int
    )

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

    private data class RejectionResult(val stage: String, val reason: String)

    /**
     * Aggregated event data passed to each rejection check, avoiding
     * the need to pass 15+ parameters individually.
     */
    private data class AnalyzedEvent(
        val startTimestampMs: Long,
        val durationMs: Int,
        val avgZcr: Float,
        val avgCentroid: Float,
        val avgTilt: Float,
        val avgFlatness: Float,
        val attackRatio: Float,
        val peakRms: Float,
        val peakRaw: Float,
        val preEventNoiseAvg: Float,
        val envelope: Stage1EnvelopeFeatures,
        val bestMatch: SoundProfile?,
        val bestConfidence: Float,
        val allProfileScores: List<ProfileScore>,
        val sessionSimilarity: Float?,
        val hasRecentTapReference: Boolean,
        val isDiagnosticContext: Boolean,
        val isGestureControlContext: Boolean,
        val candidateAgeMs: Long,
        val acceptedAgeMs: Long,
        val rmsTrace: List<Float>
    ) {
        val bestFamily: String? = bestMatch?.name?.substringBefore(" (")?.trim()
        val isGestureFamily: Boolean = bestFamily == "Snap" || bestFamily == "Clap" || bestFamily == "Slap"
        val isSnapOrClapFamily: Boolean =
            bestMatch?.name?.startsWith("Snap") == true ||
                bestMatch?.name?.startsWith("Clap") == true ||
                bestMatch?.name?.startsWith("Slap") == true
        val preEventRatio: Float = if (peakRms > 0f) preEventNoiseAvg / peakRms else 1f
    }

    // ── Mutable State ──

    // Baseline
    var noiseFloor = 0.005f
        private set
    private var flatlineCount = 0
    private var warmUpFrames = config.warmUpFrames

    // Event Monitoring
    private var isMonitoringEvent = false
    private var eventStartTimestamp = 0L
    private var eventHighRmsFrames = 0
    private var eventAccumulatedZcr = 0f
    private var eventAccumulatedCentroid = 0f
    private var eventAccumulatedTilt = 0f
    private var eventAccumulatedFlatness = 0f
    private var eventPeakRms = 0f
    private var eventPeakRaw = 0f
    private var framesSincePeak = 0
    private var hasDippedFromPeak = false
    private val eventRmsTrace = mutableListOf<Float>()
    private var preEventNoiseAvg = 0f
    private var consecutiveLoudFrames = 0

    // Blanking
    private var lastPlaybackBlankDiagMs = 0L
    private var lastExternalAudioBlankDiagMs = 0L

    // Cadence
    private val cadenceProbeHistory = ArrayDeque<CadenceProbe>()
    private var cadenceSuppressUntilMs = 0L
    private var lastCadenceDiagMs = 0L
    private var lastCadencePatternStats: CadencePatternStats? = null

    // Frame tracking
    private var frameCounter = 0L
    private var lastDetectionEndFrame = -1000L
    private var lastDetectionDurationFrames = 0
    private var lastQualifiedEndFrame = -1000L
    private var lastQualifiedDurationFrames = 0

    // Candidate tracking
    private var lastCandidateType: String? = null
    private var lastCandidateTimestampMs = 0L
    private var lastCandidateDurationMs = 0
    private var lastCandidateCentroid = 0f
    private var lastCandidateTilt = 0f
    private var lastCandidateStrength = 0f

    // Accepted tracking
    private var lastAcceptedType: String? = null
    private var lastAcceptedTimestampMs = 0L
    private var lastAcceptedDurationMs = 0
    private var lastAcceptedCentroid = 0f
    private var lastAcceptedTilt = 0f
    private var lastAcceptedStrength = 0f

    // Post-Event Quiet Check
    private var postEventCheckCountdown = 0
    private var pendingEvent: SoundDetectionEvent? = null
    private var pendingEventLogEntry: String? = null
    private var thresholdAtEventEnd = 0f
    private var postEventMaxRmsDuringCheck = 0f
    private var pendingProfileScores: List<ProfileScore> = emptyList()
    private var pendingSessionSimilarity: Float? = null

    // Diagnostic capture
    private var eventTriggerRms = 0f
    private var eventAttackRatio = 0f
    private var eventNoiseFloorAtTrigger = 0f
    private var eventThresholdAtTrigger = 0f

    // History
    private val recentSamples = mutableListOf<Float>()
    private var lastUiEmitMs = 0L

    // Skip flag for blanking frames
    private var skipHistoryThisFrame = false

    // Session references
    private val sessionReferences = ConcurrentHashMap<String, SessionTapReference>()
    private val sessionNuisanceReferences = mutableListOf<SessionNuisanceReference>()

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Process one audio frame through the detection pipeline.
     * Call once per hop from the recorder loop.
     */
    fun processFrame(features: FrameFeatures): FrameAction {
        skipHistoryThisFrame = false

        // Flatline detection
        if (features.rms < 0.0001f && features.peak < 0.0001f) {
            flatlineCount++
            if (flatlineCount >= config.maxFlatlineFrames) {
                Log.w("SoundDetector", "Flatline detected, restarting recorder")
                return FrameAction.RESTART_RECORDER
            }
        } else {
            flatlineCount = 0
        }

        // Threshold calculation
        val currentMinRms = if (noiseFloor < 0.0025f) config.quietModeMinRms else config.baseMinRms
        val threshold = (noiseFloor * config.thresholdMultiplier).coerceAtLeast(currentMinRms)

        // Warm-up: fast-track noise floor, skip detection
        if (warmUpFrames > 0) {
            warmUpFrames--
            noiseFloor = (noiseFloor * (1f - config.fastNoiseAlpha)) + (features.rms * config.fastNoiseAlpha)
            appendToHistory(features.rms)
            host.updateState { it.copy(rms = features.rms, noiseFloor = noiseFloor, currentRms = features.rms, debugInfo = "Calibrating...") }
            return FrameAction.CONTINUE
        }

        val prevRms = if (recentSamples.isNotEmpty()) recentSamples.last() else 0f

        // Anti-latch: continuous noise adaptation
        handleAntiLatch(features, threshold)

        // Detection state machine
        if (isMonitoringEvent) {
            handleEventMonitoring(features, threshold)
        } else {
            handleIdle(features, threshold, prevRms)
        }

        // History + UI update (skipped when blanking fired)
        if (!skipHistoryThisFrame) {
            appendToHistory(features.rms)
            emitUiThrottled(features)
        }
        return FrameAction.CONTINUE
    }

    fun resetForRecorderRestart() {
        flatlineCount = 0
        warmUpFrames = config.warmUpFrames
        recentSamples.clear()
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

    @Synchronized
    fun clearSessionCalibration() {
        sessionReferences.clear()
        sessionNuisanceReferences.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    //  ANTI-LATCH
    // ═══════════════════════════════════════════════════════════════

    private fun handleAntiLatch(features: FrameFeatures, threshold: Float) {
        if (features.rms > threshold) {
            consecutiveLoudFrames++
            if (consecutiveLoudFrames > config.framesForMs(2000)) {
                noiseFloor = (noiseFloor * 0.95f) + (features.rms * 0.05f)
                if (isMonitoringEvent) {
                    isMonitoringEvent = false
                    Log.d("SoundDetector", "Forced Adaptation: Signal loud for too long. Resetting.")
                }
            }
        } else {
            consecutiveLoudFrames = 0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVENT MONITORING
    // ═══════════════════════════════════════════════════════════════

    private fun handleEventMonitoring(features: FrameFeatures, threshold: Float) {
        if (features.rms > threshold * 0.4f) {
            // Accumulate frame
            eventHighRmsFrames++
            eventAccumulatedZcr += features.zcr
            eventAccumulatedCentroid += features.centroid
            eventAccumulatedTilt += features.tilt
            eventAccumulatedFlatness += features.flatness
            eventRmsTrace.add(features.rms)

            if (features.rms > eventPeakRms) {
                eventPeakRms = features.rms
                framesSincePeak = 0
                hasDippedFromPeak = false
            } else {
                framesSincePeak++
            }
            if (features.peak > eventPeakRaw) eventPeakRaw = features.peak

            // In-flight checks (any can abort monitoring)
            if (checkSustain(features)) return
            if (checkLatePeak(features, threshold)) return
            if (checkRebound(features)) return
            if (checkDuration()) return
            if (checkDecay(features)) return
        } else {
            // Signal dropped → event ended → analyze
            analyzeCompletedEvent(features)
            isMonitoringEvent = false
        }
    }

    /** Sustain/Flux Check at T+50ms or T+60ms. Returns true if rejected. */
    private fun checkSustain(features: FrameFeatures): Boolean {
        if (eventHighRmsFrames != config.framesForMs(50) && eventHighRmsFrames != config.framesForMs(60)) return false
        val ratio = if (eventPeakRms > 0) features.rms / eventPeakRms else 0f
        val hasRecentTapRef = computeHasRecentTapReference()
        val sustainRejectRatio = if (hasRecentTapRef) 0.68f else 0.58f
        if (ratio > sustainRejectRatio) {
            if (isMonitoringEvent) {
                emitEventDiag("SUSTAIN", "Ratio ${"%.2f".format(ratio)} at ${config.durationForFrames(eventHighRmsFrames)}ms (>${"%.2f".format(sustainRejectRatio)})", sustainRatio = ratio)
            }
            Log.d("SoundDetector", "Rejected: Sustained/Speech (Ratio $ratio at ${config.durationForFrames(eventHighRmsFrames)}ms)")
            isMonitoringEvent = false
            return true
        }
        return false
    }

    /** Peak Late Check (Groan Rejection). */
    private fun checkLatePeak(features: FrameFeatures, threshold: Float): Boolean {
        val framesAtPeak = eventHighRmsFrames - framesSincePeak
        if (framesAtPeak > config.framesForMs(60) && features.rms > threshold * 2.0f) {
            if (isMonitoringEvent) emitEventDiag("LATE_PEAK", "Peak at frame $framesAtPeak (${config.durationForFrames(framesAtPeak)}ms into event)")
            Log.d("SoundDetector", "Rejected: Slow Intensity Rise (Peak at ${config.durationForFrames(framesAtPeak)}ms)")
            isMonitoringEvent = false
            return true
        }
        return false
    }

    /** Double-Peak / Rebound Check. */
    private fun checkRebound(features: FrameFeatures): Boolean {
        if (!hasDippedFromPeak && features.rms < eventPeakRms * 0.5f) {
            hasDippedFromPeak = true
        }
        if (hasDippedFromPeak && features.rms > eventPeakRms * 0.7f) {
            val currentDurationMs = config.durationForFrames(eventHighRmsFrames)
            if (currentDurationMs > 150) {
                if (isMonitoringEvent) emitEventDiag("REBOUND", "Double peak at ${currentDurationMs}ms")
                Log.d("SoundDetector", "Rejected: Bouncy signal (multiple peaks)")
                isMonitoringEvent = false
                return true
            }
        }
        return false
    }

    /** Duration Check (> 300ms). */
    private fun checkDuration(): Boolean {
        val currentDurationMs = config.durationForFrames(eventHighRmsFrames)
        if (currentDurationMs > 300) {
            if (isMonitoringEvent) emitEventDiag("DURATION", "Too long: ${currentDurationMs}ms > 300ms")
            Log.d("SoundDetector", "Rejected: Too long ($currentDurationMs ms)")
            isMonitoringEvent = false
            return true
        }
        return false
    }

    /** Decay Check at 60ms post-peak. */
    private fun checkDecay(features: FrameFeatures): Boolean {
        val decayCheckFrame = config.framesForMs(60)
        if (framesSincePeak != decayCheckFrame) return false
        val ratio = if (eventPeakRms > 0) features.rms / eventPeakRms else 0f
        val hasRecentTapRef = computeHasRecentTapReference()
        val decayRejectRatio = if (hasRecentTapRef) 0.34f else 0.26f
        if (ratio > decayRejectRatio) {
            if (isMonitoringEvent) {
                emitEventDiag("DECAY", "Ratio ${"%.2f".format(ratio)} > ${"%.2f".format(decayRejectRatio)} at 60ms post-peak", decayRatio = ratio)
            }
            Log.d("SoundDetector", "Rejected: Decay too slow (ratio $ratio at 60ms)")
            isMonitoringEvent = false
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVENT ANALYSIS (Profile Matching + Rejection Pipeline)
    // ═══════════════════════════════════════════════════════════════

    private fun analyzeCompletedEvent(features: FrameFeatures) {
        val frames = eventHighRmsFrames.coerceAtLeast(1)
        val durationMs = config.durationForFrames(frames)
        val avgZcr = eventAccumulatedZcr / frames
        val avgCentroid = eventAccumulatedCentroid / frames
        val avgTilt = eventAccumulatedTilt / frames
        val avgFlatness = eventAccumulatedFlatness / frames

        // ── Profile Matching ──
        var bestMatch: SoundProfile? = null
        var bestConfidence = 0f
        var bestSnapMatch: SoundProfile? = null
        var bestSnapConfidence = 0f
        var bestClapMatch: SoundProfile? = null
        var bestClapConfidence = 0f
        val allScores = mutableListOf<ProfileScore>()

        for (profile in config.profiles) {
            val sDur = getMetricScore(durationMs.toFloat(), profile.minDurMs.toFloat(), profile.maxDurMs.toFloat())
            val sZcr = getMetricScore(avgZcr, profile.minZcr, profile.maxZcr)
            val sCentroid = getMetricScore(avgCentroid, profile.minCentroid, profile.maxCentroid)
            val sTilt = getMetricScore(avgTilt, profile.minTilt, profile.maxTilt)
            val confidence = (sDur + sZcr + sCentroid + sTilt) / 4f

            allScores.add(ProfileScore(profile.name, confidence, sDur, sZcr, sCentroid, sTilt))
            if (confidence > bestConfidence) { bestConfidence = confidence; bestMatch = profile }
            if (profile.name.startsWith("Snap") && confidence > bestSnapConfidence) { bestSnapConfidence = confidence; bestSnapMatch = profile }
            if (profile.name.startsWith("Clap") && confidence > bestClapConfidence) { bestClapConfidence = confidence; bestClapMatch = profile }
        }

        // ── Profile Bias Adjustments ──
        val isDiagnosticContext = host.debugContext.equals("DIAGNOSTIC", ignoreCase = true)
        val isGestureControlContext = !isDiagnosticContext
        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
        val hasRecentTapReference = isGestureControlContext &&
            (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)

        // Snap bias for short bright transients
        val clapLikeFamily = bestMatch?.name?.startsWith("Clap") == true || bestMatch?.name?.startsWith("Slap") == true
        val snapLikeImpulse = durationMs <= 95 && avgCentroid >= 2800f && avgZcr >= 0.18f && avgTilt >= 0.00f && eventAttackRatio >= 8f
        if (!isDiagnosticContext && clapLikeFamily && snapLikeImpulse && bestSnapMatch != null) {
            if (bestSnapConfidence >= (bestConfidence - 0.10f)) {
                bestMatch = bestSnapMatch; bestConfidence = bestSnapConfidence
            }
        }

        // Counter-bias: keep clap label when envelope is clap-like
        val snapFamily = bestMatch?.name?.startsWith("Snap") == true
        val clapLikeImpulse = durationMs >= 85 && avgZcr >= 0.20f && avgCentroid in 2300f..4200f && avgTilt <= 0.08f
        if (snapFamily && bestClapMatch != null && bestClapConfidence >= (bestConfidence - 0.03f) && clapLikeImpulse) {
            bestMatch = bestClapMatch; bestConfidence = bestClapConfidence
        }

        // Diagnostic labeling assist
        val clapFamilyAfterBias = bestMatch?.name?.startsWith("Clap") == true
        val diagnosticLikelySnap = isDiagnosticContext && clapFamilyAfterBias && bestSnapMatch != null &&
            durationMs <= 120 && avgZcr >= 0.30f && avgCentroid >= 3200f && avgTilt >= -0.20f &&
            bestSnapConfidence >= (bestConfidence - 0.08f)
        if (diagnosticLikelySnap) { bestMatch = bestSnapMatch; bestConfidence = bestSnapConfidence!! }

        // Ultra-short snap rescue
        val preEventRatioAtMatch = if (eventPeakRms > 0f) preEventNoiseAvg / eventPeakRms else 1f
        val ultraShortSnapLike = durationMs in 15..35 && avgZcr >= 0.34f && avgCentroid >= 3200f &&
            avgTilt >= -0.20f && eventAttackRatio >= 10f && preEventRatioAtMatch <= 0.22f
        if (ultraShortSnapLike && bestMatch?.name?.startsWith("Snap") == true && bestConfidence >= 0.72f) {
            bestConfidence = bestConfidence.coerceAtLeast(0.80f)
        }

        // ── Confidence Gate ──
        val envelopeFeatures = extractEnvelopeFeatures(eventRmsTrace, eventPeakRms)
        val effectiveConfidenceThreshold = when {
            isDiagnosticContext -> 0.80f
            hasRecentTapReference -> config.followUpTapConfidenceThreshold
            else -> config.firstTapConfidenceThreshold
        }
        var isValid = bestMatch != null && bestConfidence >= effectiveConfidenceThreshold
        var rejectionReason: String? = null
        var sessionSimilarityValue: Float? = null

        // ── Build AnalyzedEvent ──
        val event = AnalyzedEvent(
            startTimestampMs = eventStartTimestamp,
            durationMs = durationMs,
            avgZcr = avgZcr,
            avgCentroid = avgCentroid,
            avgTilt = avgTilt,
            avgFlatness = avgFlatness,
            attackRatio = eventAttackRatio,
            peakRms = eventPeakRms,
            peakRaw = eventPeakRaw,
            preEventNoiseAvg = preEventNoiseAvg,
            envelope = envelopeFeatures,
            bestMatch = bestMatch,
            bestConfidence = bestConfidence,
            allProfileScores = allScores,
            sessionSimilarity = null, // updated below
            hasRecentTapReference = hasRecentTapReference,
            isDiagnosticContext = isDiagnosticContext,
            isGestureControlContext = isGestureControlContext,
            candidateAgeMs = candidateAgeMs,
            acceptedAgeMs = acceptedAgeMs,
            rmsTrace = eventRmsTrace.toList()
        )

        // ── Rejection Pipeline ──

        // 1. Session calibration gate
        if (isValid && bestMatch != null) {
            val ref = sessionReferenceForType(bestMatch.name)
            if (ref != null) {
                val similarity = sessionSimilarity(ref, avgCentroid, avgTilt, avgZcr, avgFlatness, durationMs, eventPeakRms)
                sessionSimilarityValue = similarity
                if (similarity < 0.62f) {
                    rejectionReason = "Session mismatch: ${"%.2f".format(similarity)} < 0.62"
                    Log.d("SoundDetector", "Rejected: $rejectionReason")
                    isValid = false
                    emitAnalysisDiag("SESSION_MISMATCH", rejectionReason!!, event.copy(sessionSimilarity = sessionSimilarityValue))
                }
            }
        }

        val eventWithSession = event.copy(sessionSimilarity = sessionSimilarityValue)

        // 2. Cadence probe capture (always, regardless of isValid)
        captureCadenceProbe(eventWithSession)

        // 3. Cadence suppression
        if (isValid && eventWithSession.isGestureFamily &&
            CadencePatternHeuristics.isSuppressed(eventStartTimestamp, cadenceSuppressUntilMs)
        ) {
            checkCadenceSuppression(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 4. Nuisance similarity
        if (isValid && !hasRecentTapReference && eventWithSession.isGestureFamily) {
            checkNuisanceSimilarity(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 5. Low flatness
        if (isValid && !hasRecentTapReference) {
            checkLowFlatness(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 6. Envelope complex
        if (isValid) {
            checkEnvelopeComplex(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 7. Low confidence catch
        if (!isValid && rejectionReason == null) {
            val topMatchName = bestMatch?.name ?: "None"
            val topScore = (bestConfidence * 100).toInt()
            rejectionReason = "Low Confidence: $topMatchName ($topScore%) D:${durationMs}ms Z:${"%.2f".format(avgZcr)} C:${"%.0f".format(avgCentroid)}"
            Log.d("SoundDetector", "Rejected: $rejectionReason")
            emitAnalysisDiag("LOW_CONFIDENCE", rejectionReason!!, eventWithSession)
        }

        // 8. Surface tap
        if (isValid) {
            checkSurfaceTap(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 9. Hard surface
        if (isValid) {
            checkHardSurface(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 10. Envelope ringing
        if (isValid && eventPeakRms > 0f && eventRmsTrace.size >= config.framesForMs(40)) {
            checkEnvelopeRinging(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 11. Metallic
        if (isValid) {
            checkMetallic(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 12. Resonant impact
        if (isValid && !hasRecentTapReference) {
            checkResonantImpact(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 13. Bright impact
        if (isValid && !hasRecentTapReference) {
            checkBrightImpact(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 14. Ultra bright ping
        if (isValid && !hasRecentTapReference) {
            checkUltraBrightPing(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 15. Attack shape
        if (isValid && !hasRecentTapReference) {
            checkAttackShape(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 16. Isolation / pre-event check
        if (isValid) {
            checkIsolation(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 17. Chaos pre-event
        if (isValid) {
            checkChaoticPreEvent(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // 18. Stage 2 verifier
        if (isValid) {
            checkStage2(eventWithSession)?.let { result ->
                rejectionReason = result.reason; isValid = false
                emitAnalysisDiag(result.stage, result.reason, eventWithSession)
            }
        }

        // ── Logging ──
        val typeLog = if (isValid && bestMatch != null) "PENDING_${bestMatch.name.replace(" ", "_").uppercase()}" else "REJECTED"
        val timestamp = formatTimestamp()
        val logEntry = "$timestamp,${host.debugContext},$typeLog,${"%.2f".format(bestConfidence)},$durationMs,${"%.4f".format(eventPeakRms)},${"%.4f".format(eventPeakRaw)},${"%.2f".format(avgZcr)},${"%.0f".format(avgCentroid)},${"%.2f".format(avgTilt)},${"%.3f".format(avgFlatness)},${sessionSimilarityValue?.let { "%.3f".format(it) } ?: ""},${"%.3f".format(if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f)}|$rejectionReason"
        Log.i("SoundAnalysis", logEntry)

        // ── Disposition ──
        if (isValid && bestMatch != null) {
            setupPendingEvent(bestMatch, bestConfidence, durationMs, avgZcr, avgCentroid, avgTilt, avgFlatness, allScores, sessionSimilarityValue, logEntry)
        } else if (rejectionReason != null) {
            host.updateState { current ->
                val updatedLogs = (current.logs + logEntry).takeLast(config.maxLogs)
                current.copy(lastConfidence = bestConfidence, lastRejectionReason = rejectionReason, debugInfo = "Last Fail: $rejectionReason", logs = updatedLogs)
            }
        }
    }

    private fun setupPendingEvent(
        bestMatch: SoundProfile, bestConfidence: Float,
        durationMs: Int, avgZcr: Float, avgCentroid: Float, avgTilt: Float, avgFlatness: Float,
        allScores: List<ProfileScore>, sessionSimilarityValue: Float?, logEntry: String
    ) {
        Log.d("SoundDetector", "Event passed pre-checks (${bestMatch.name}), waiting for post-event quiet...")
        val sampleWindowFrames = config.framesForMs(500)
        val sampleSnapshot = if (recentSamples.size > sampleWindowFrames) {
            recentSamples.subList(recentSamples.size - sampleWindowFrames, recentSamples.size).toList()
        } else {
            recentSamples.toList()
        }

        pendingEvent = SoundDetectionEvent(
            timestampMs = eventStartTimestamp, durationMs = durationMs, strength = eventPeakRms,
            peak = eventPeakRaw, type = bestMatch.name, centroid = avgCentroid, tilt = avgTilt,
            zcr = avgZcr, flatness = avgFlatness, confidence = bestConfidence, samples = sampleSnapshot
        )
        pendingEventLogEntry = logEntry.replace("PENDING_", "DETECTED_")
        postEventCheckCountdown = config.postEventCheckFrames
        thresholdAtEventEnd = (noiseFloor * config.thresholdMultiplier).coerceAtLeast(
            if (noiseFloor < 0.0025f) config.quietModeMinRms else config.baseMinRms
        )
        postEventMaxRmsDuringCheck = 0f
        pendingProfileScores = allScores
        pendingSessionSimilarity = sessionSimilarityValue

        lastQualifiedEndFrame = frameCounter
        lastQualifiedDurationFrames = config.framesForMs(durationMs) + config.postEventCheckFrames + config.framesForMs(30)
        lastCandidateType = bestMatch.name
        lastCandidateTimestampMs = eventStartTimestamp
        lastCandidateDurationMs = durationMs
        lastCandidateCentroid = avgCentroid
        lastCandidateTilt = avgTilt
        lastCandidateStrength = eventPeakRms
    }

    // ═══════════════════════════════════════════════════════════════
    //  IDLE / TRIGGER DETECTION
    // ═══════════════════════════════════════════════════════════════

    private fun handleIdle(features: FrameFeatures, threshold: Float, prevRms: Float) {
        // Post-event quiet check
        handlePostEventQuietCheck(features)

        // Trigger detection
        val primaryTrigger = features.hpfRms > threshold
        val broadbandTrigger = features.rms > threshold

        if (primaryTrigger || (broadbandTrigger && features.hpfRms > features.rms * 0.25f)) {
            val baseline = if (prevRms > noiseFloor) prevRms else noiseFloor
            val riseRatio = features.rms / baseline.coerceAtLeast(0.001f)

            if (riseRatio > config.attackThreshold) {
                val nowMs = SystemClock.elapsedRealtime()

                // External audio blanking
                if (audioManager.isMusicActive) {
                    if (nowMs - lastExternalAudioBlankDiagMs >= config.externalAudioDiagCooldownMs) {
                        lastExternalAudioBlankDiagMs = nowMs
                        host.emitDiagnostic(DetectionDiagnostic(
                            id = nowMs, timestamp = formatTimestamp(), eventTimestampMs = nowMs,
                            outcome = "REJECTED", rejectedAt = "EXTERNAL_AUDIO",
                            rejectionDetail = "Suppressed while external audio is playing",
                            noiseFloor = noiseFloor, threshold = threshold, preEventNoiseAvg = preEventNoiseAvg,
                            triggerRms = features.rms, attackRatio = riseRatio, durationMs = 0,
                            peakRms = features.rms, peakRaw = features.peak,
                            sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
                            avgZcr = features.zcr, avgCentroid = features.centroid,
                            avgTilt = features.tilt, avgFlatness = features.flatness
                        ))
                    }
                    skipHistoryThisFrame = true; return
                }

                // Playback blanking
                val sinceLastPlaybackMs = nowMs - sfxPlayer.lastPlaybackTimestampMs()
                if (sinceLastPlaybackMs in 0L..config.playbackBlankingMs) {
                    if (nowMs - lastPlaybackBlankDiagMs >= config.playbackBlankDiagCooldownMs) {
                        lastPlaybackBlankDiagMs = nowMs
                        host.emitDiagnostic(DetectionDiagnostic(
                            id = nowMs, timestamp = formatTimestamp(), eventTimestampMs = nowMs,
                            outcome = "REJECTED", rejectedAt = "PLAYBACK_BLANK",
                            rejectionDetail = "Suppressed ${sinceLastPlaybackMs}ms after app playback",
                            noiseFloor = noiseFloor, threshold = threshold, preEventNoiseAvg = preEventNoiseAvg,
                            triggerRms = features.rms, attackRatio = riseRatio, durationMs = 0,
                            peakRms = features.rms, peakRaw = features.peak,
                            sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
                            avgZcr = features.zcr, avgCentroid = features.centroid,
                            avgTilt = features.tilt, avgFlatness = features.flatness
                        ))
                    }
                    skipHistoryThisFrame = true; return
                }

                // Start monitoring
                startMonitoring(features, nowMs, riseRatio, threshold)
            }
        }

        // Noise floor update (only when not loud)
        if (features.rms < threshold * 0.8f) {
            val alpha = if (features.rms < noiseFloor) config.noiseAlphaFall else config.noiseAlphaRise
            noiseFloor = (noiseFloor * (1f - alpha)) + (features.rms * alpha)
        }
    }

    private fun startMonitoring(features: FrameFeatures, nowMs: Long, riseRatio: Float, threshold: Float) {
        isMonitoringEvent = true
        eventStartTimestamp = nowMs
        eventHighRmsFrames = 1
        eventAccumulatedZcr = features.zcr
        eventAccumulatedCentroid = features.centroid
        eventAccumulatedTilt = features.tilt
        eventAccumulatedFlatness = features.flatness
        eventPeakRms = features.rms
        eventPeakRaw = features.peak
        framesSincePeak = 0
        hasDippedFromPeak = false
        eventRmsTrace.clear()
        eventRmsTrace.add(features.rms)
        eventTriggerRms = features.rms
        eventAttackRatio = riseRatio
        eventNoiseFloorAtTrigger = noiseFloor
        eventThresholdAtTrigger = threshold

        // Pre-event noise level
        val lookback = config.framesForMs(120)
        val available = recentSamples.size
        val gapFrames = config.framesForMs(30)
        var noiseSum = 0f
        var count = 0
        for (j in gapFrames until lookback) {
            if (available - 1 - j >= 0) {
                noiseSum += recentSamples[available - 1 - j]
                count++
            }
        }
        preEventNoiseAvg = if (count > 0) noiseSum / count else 0f
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST-EVENT QUIET CHECK
    // ═══════════════════════════════════════════════════════════════

    private fun handlePostEventQuietCheck(features: FrameFeatures) {
        if (postEventCheckCountdown <= 0) return

        if (features.rms > postEventMaxRmsDuringCheck) {
            postEventMaxRmsDuringCheck = features.rms
        }
        postEventCheckCountdown--

        if (postEventCheckCountdown > 0) return

        val pending = pendingEvent
        val logEntryForEvent = pendingEventLogEntry
        if (pending == null || logEntryForEvent == null) {
            clearPendingEvent(); return
        }

        val postQuietLimit = thresholdAtEventEnd * config.postEventQuietRatio
        val isQuiet = postEventMaxRmsDuringCheck < postQuietLimit
        val postPeakRatio = if (pending.strength > 0f) postEventMaxRmsDuringCheck / pending.strength else 1f
        val allowedPostPeakRatio = 0.30f
        val isPostRingClean = postPeakRatio <= allowedPostPeakRatio

        if (isQuiet && isPostRingClean) {
            confirmDetection(pending, logEntryForEvent, features)
        } else {
            rejectPostEvent(pending, logEntryForEvent, isQuiet, postPeakRatio, allowedPostPeakRatio, postQuietLimit)
        }
        clearPendingEvent()
    }

    private fun confirmDetection(pending: SoundDetectionEvent, logEntry: String, features: FrameFeatures) {
        Log.i("SoundDetector", "Confirmed ${pending.type} (${(pending.confidence * 100).toInt()}%)! Post-event quiet.")

        lastDetectionEndFrame = frameCounter
        lastDetectionDurationFrames = config.framesForMs(pending.durationMs) + config.postEventCheckFrames + config.framesForMs(30)
        lastAcceptedType = pending.type
        lastAcceptedTimestampMs = pending.timestampMs
        lastAcceptedDurationMs = pending.durationMs
        lastAcceptedCentroid = pending.centroid
        lastAcceptedTilt = pending.tilt
        lastAcceptedStrength = pending.strength

        val savedScores = pendingProfileScores
        host.emitDiagnostic(DetectionDiagnostic(
            id = SystemClock.elapsedRealtime(), timestamp = formatTimestamp(),
            eventTimestampMs = pending.timestampMs, outcome = "ACCEPTED",
            rejectedAt = null, rejectionDetail = null,
            noiseFloor = eventNoiseFloorAtTrigger, threshold = eventThresholdAtTrigger,
            preEventNoiseAvg = preEventNoiseAvg,
            triggerRms = eventTriggerRms, attackRatio = eventAttackRatio,
            durationMs = pending.durationMs, peakRms = pending.strength, peakRaw = pending.peak,
            sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
            postEventRms = postEventMaxRmsDuringCheck,
            postEventQuietThreshold = thresholdAtEventEnd * config.postEventQuietRatio,
            avgZcr = pending.zcr, avgCentroid = pending.centroid, avgTilt = pending.tilt, avgFlatness = pending.flatness,
            sessionSimilarity = pendingSessionSimilarity,
            bestProfileName = pending.type, bestConfidence = pending.confidence, allProfileScores = savedScores
        ))

        host.updateState { current ->
            val updated = (listOf(pending) + current.events).take(config.maxEvents)
            val updatedLogs = (current.logs + logEntry).takeLast(config.maxLogs)
            current.copy(
                isLit = true, count = current.count + 1,
                lastStrength = pending.strength, lastPeak = pending.peak,
                lastConfidence = pending.confidence, rms = features.rms,
                noiseFloor = noiseFloor, currentRms = features.rms,
                lastRejectionReason = null,
                debugInfo = "${pending.type} ${(pending.confidence * 100).toInt()}%",
                events = updated, logs = updatedLogs
            )
        }
        host.scheduleLitReset()
    }

    private fun rejectPostEvent(
        pending: SoundDetectionEvent, logEntry: String,
        isQuiet: Boolean, postPeakRatio: Float, allowedPostPeakRatio: Float, postQuietLimit: Float
    ) {
        val postRejectReason = if (!isQuiet) {
            "Post-Event Noisy: ${"%.3f".format(postEventMaxRmsDuringCheck)} > ${"%.3f".format(postQuietLimit)}"
        } else {
            "Post-Event Ringing: ratio ${"%.2f".format(postPeakRatio)} > ${"%.2f".format(allowedPostPeakRatio)}"
        }
        Log.d("SoundDetector", "Rejected: $postRejectReason")

        if (!isQuiet || postPeakRatio > allowedPostPeakRatio) {
            if (postPeakRatio > allowedPostPeakRatio) {
                rememberNuisanceReference("CHAOS_POST_RING", pending.durationMs, pending.zcr, pending.centroid,
                    pending.tilt, pending.flatness, eventAttackRatio, pending.strength, pending.timestampMs)
            }
        }

        val savedScores = pendingProfileScores
        host.emitDiagnostic(DetectionDiagnostic(
            id = SystemClock.elapsedRealtime(), timestamp = formatTimestamp(),
            eventTimestampMs = pending.timestampMs, outcome = "REJECTED",
            rejectedAt = "CHAOS_POST", rejectionDetail = postRejectReason,
            noiseFloor = eventNoiseFloorAtTrigger, threshold = eventThresholdAtTrigger,
            preEventNoiseAvg = preEventNoiseAvg,
            triggerRms = eventTriggerRms, attackRatio = eventAttackRatio,
            durationMs = pending.durationMs, peakRms = pending.strength, peakRaw = pending.peak,
            sustainRatio = null, decayRatio = null, peakFrameIndex = 0,
            postEventRms = postEventMaxRmsDuringCheck,
            postEventQuietThreshold = thresholdAtEventEnd * config.postEventQuietRatio,
            avgZcr = pending.zcr, avgCentroid = pending.centroid, avgTilt = pending.tilt, avgFlatness = pending.flatness,
            sessionSimilarity = pendingSessionSimilarity,
            bestProfileName = pending.type, bestConfidence = pending.confidence, allProfileScores = savedScores
        ))

        val rejectedLog = logEntry.replace("DETECTED_", "REJECTED_POST_") + "|$postRejectReason"
        host.updateState { current ->
            val updatedLogs = (current.logs + rejectedLog).takeLast(config.maxLogs)
            current.copy(lastConfidence = pending.confidence, lastRejectionReason = postRejectReason,
                debugInfo = "Last Fail: $postRejectReason", logs = updatedLogs)
        }

        // Clear whitelist for failed pending
        lastQualifiedEndFrame = -1000L; lastQualifiedDurationFrames = 0
        lastCandidateType = null; lastCandidateTimestampMs = 0L; lastCandidateDurationMs = 0
        lastCandidateCentroid = 0f; lastCandidateTilt = 0f; lastCandidateStrength = 0f
    }

    private fun clearPendingEvent() {
        pendingEvent = null; pendingEventLogEntry = null
        postEventMaxRmsDuringCheck = 0f
        pendingProfileScores = emptyList(); pendingSessionSimilarity = null
    }

    // ═══════════════════════════════════════════════════════════════
    //  REJECTION CHECKS (each returns null=pass, non-null=reject)
    // ═══════════════════════════════════════════════════════════════

    private fun checkCadenceSuppression(event: AnalyzedEvent): RejectionResult? {
        val firstTapLike = CadencePatternHeuristics.isFirstTapLike(event.candidateAgeMs, event.acceptedAgeMs, config.cadenceFollowupWindowMs)
        if (!firstTapLike) return null

        val acceptedSignatureBypass = isStrongAcceptedSignatureMatchForCadenceBypass(
            bestType = event.bestMatch?.name, eventTimestampMs = event.startTimestampMs,
            durationMs = event.durationMs, centroid = event.avgCentroid, tilt = event.avgTilt, strength = event.peakRms,
            refType = lastAcceptedType, refTimestampMs = lastAcceptedTimestampMs,
            refDurationMs = lastAcceptedDurationMs, refCentroid = lastAcceptedCentroid,
            refTilt = lastAcceptedTilt, refStrength = lastAcceptedStrength, followupWindowMs = config.cadenceFollowupWindowMs
        )
        val shouldReject = shouldRejectCadencePattern(
            isSuppressed = true, isGestureFamily = event.isGestureFamily, firstTapLike = true,
            sessionSimilarity = event.sessionSimilarity,
            sessionBypassThreshold = config.nuisanceSimilaritySessionBypassThreshold,
            acceptedSignatureBypass = acceptedSignatureBypass
        )
        if (!shouldReject) return null

        val cadenceStats = lastCadencePatternStats
        val reason = if (cadenceStats != null) {
            "Cadence Pattern: n=${cadenceStats.eventCount} mean=${"%.0f".format(cadenceStats.meanIntervalMs)}ms cv=${"%.2f".format(cadenceStats.intervalCv)}"
        } else {
            "Cadence Pattern: suppression active"
        }
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("CADENCE_PATTERN", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("CADENCE_PATTERN", reason)
    }

    private fun checkNuisanceSimilarity(event: AnalyzedEvent): RejectionResult? {
        val nuisance = bestNuisanceSimilarity(event.durationMs, event.avgZcr, event.avgCentroid, event.avgTilt,
            event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        val protectedBySession = (event.sessionSimilarity ?: 0f) >= config.nuisanceSimilaritySessionBypassThreshold
        val hasEnoughHistory = nuisance.totalReferences >= config.nuisanceMinReferencesToGate &&
            nuisance.strongReferenceCount >= config.nuisanceMinStrongReferencesToGate
        val rejectThreshold = if (nuisance.sourceStage == "METALLIC") config.nuisanceSimilarityRejectThresholdMetallic else config.nuisanceSimilarityRejectThreshold
        if (protectedBySession || !hasEnoughHistory || nuisance.similarity < rejectThreshold) return null

        val reason = "Nuisance Similarity: ${"%.2f".format(nuisance.similarity)} >= ${"%.2f".format(rejectThreshold)} (${nuisance.sourceStage ?: "unknown"}) refs=${nuisance.totalReferences}/${nuisance.strongReferenceCount}"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("NUISANCE_SIMILARITY", reason)
    }

    private fun checkLowFlatness(event: AnalyzedEvent): RejectionResult? {
        if (!event.isGestureFamily || event.avgFlatness >= 0.16f) return null
        val reason = "Low flatness: ${"%.3f".format(event.avgFlatness)} < 0.160"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("LOW_FLATNESS", reason)
    }

    private fun checkEnvelopeComplex(event: AnalyzedEvent): RejectionResult? {
        val complex = !event.hasRecentTapReference && event.durationMs in 50..220 &&
            event.envelope.tailEnergyRatio >= 0.66f && event.envelope.decayHalfFrames >= 16 &&
            event.envelope.monotonicRiseRatio >= 0.30f
        if (!complex) return null
        val reason = "Envelope Complex: tail=${"%.2f".format(event.envelope.tailEnergyRatio)} rise=${"%.2f".format(event.envelope.monotonicRiseRatio)} d50=${event.envelope.decayHalfFrames}"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("ENVELOPE_COMPLEX", reason)
    }

    private fun checkSurfaceTap(event: AnalyzedEvent): RejectionResult? {
        val legacyWetClapSurfaceTap = event.bestMatch?.name == "Clap (Wet)" &&
            event.durationMs in 80..170 && event.avgZcr <= 0.12f && event.avgCentroid <= 1400f && event.avgTilt <= -0.85f
        val stronglyTableCoupled = event.isSnapOrClapFamily &&
            event.durationMs in 45..190 && event.avgZcr <= 0.16f && event.avgCentroid <= 1700f && event.avgTilt <= -1.20f
        val dampedContactImpact = event.isSnapOrClapFamily && !event.hasRecentTapReference &&
            event.durationMs in 55..210 && event.avgZcr <= 0.24f && event.avgCentroid <= 2300f &&
            event.avgTilt <= -0.95f && event.attackRatio <= 10f &&
            (event.envelope.tailEnergyRatio >= 0.30f || event.envelope.decayHalfFrames >= 12)
        if (!legacyWetClapSurfaceTap && !stronglyTableCoupled && !dampedContactImpact) return null

        val reason = "Surface Tap: D=${event.durationMs}ms Z=${"%.2f".format(event.avgZcr)} C=${"%.0f".format(event.avgCentroid)} T=${"%.2f".format(event.avgTilt)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("SURFACE_TAP", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("SURFACE_TAP", reason)
    }

    private fun checkHardSurface(event: AnalyzedEvent): RejectionResult? {
        val looksHardSurfaceImpact = event.isSnapOrClapFamily && !event.hasRecentTapReference &&
            event.durationMs in 55..180 && event.avgCentroid in 2400f..4200f &&
            event.avgTilt <= -0.20f && event.avgZcr <= 0.40f &&
            event.attackRatio <= 7.5f && event.peakRms <= 0.60f &&
            (event.envelope.tailEnergyRatio >= 0.26f || event.envelope.decayHalfFrames >= 10)
        if (!looksHardSurfaceImpact) return null

        val reason = "Hard Surface Impact: D=${event.durationMs}ms A=${"%.2f".format(event.attackRatio)} C=${"%.0f".format(event.avgCentroid)} T=${"%.2f".format(event.avgTilt)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("HARD_SURFACE", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("HARD_SURFACE", reason)
    }

    private fun checkEnvelopeRinging(event: AnalyzedEvent): RejectionResult? {
        if (event.hasRecentTapReference) return null
        val trace = event.rmsTrace
        var peakIndex = 0; var peakValue = trace[0]
        for (i in 1 until trace.size) { if (trace[i] > peakValue) { peakValue = trace[i]; peakIndex = i } }
        val postPeak = if (peakIndex + 1 < trace.size) trace.subList(peakIndex + 1, trace.size) else emptyList()

        var risingAfterPeak = 0
        for (i in 1 until postPeak.size) { if (postPeak[i] - postPeak[i - 1] > event.peakRms * 0.07f) risingAfterPeak++ }
        val bounceRatio = if (postPeak.size > 1) risingAfterPeak.toFloat() / (postPeak.size - 1).toFloat() else 0f

        val tailFrames = minOf(config.framesForMs(30), postPeak.size)
        var tailSum = 0f
        if (tailFrames > 0) { for (i in postPeak.size - tailFrames until postPeak.size) tailSum += postPeak[i] }
        val tailRatio = if (tailFrames > 0) (tailSum / tailFrames.toFloat()) / event.peakRms else 0f
        val firstPostRatio = if (postPeak.isNotEmpty()) postPeak.first() / event.peakRms else 0f
        val minPost = postPeak.minOrNull() ?: 0f
        val lastPost = postPeak.lastOrNull() ?: 0f
        val reboundFromLowRatio = if (postPeak.isNotEmpty()) (lastPost - minPost).coerceAtLeast(0f) / event.peakRms else 0f

        val shortFlatTail = event.durationMs <= 80 && firstPostRatio >= 0.60f && tailRatio >= 0.35f
        val bouncyTail = bounceRatio >= 0.34f || (tailRatio >= 0.26f && reboundFromLowRatio >= 0.18f)
        val envelopeLooksRinging = event.durationMs in 40..200 && event.avgCentroid >= 3400f &&
            event.avgTilt >= 0.10f && (shortFlatTail || bouncyTail)
        if (!envelopeLooksRinging) return null

        val reason = "Envelope Ringing: B=${"%.2f".format(bounceRatio)} T=${"%.2f".format(tailRatio)} R=${"%.2f".format(reboundFromLowRatio)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("ENVELOPE_RING", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("ENVELOPE_RING", reason)
    }

    private fun checkMetallic(event: AnalyzedEvent): RejectionResult? {
        val metallicRingingEnvelope = event.envelope.tailEnergyRatio >= 0.24f ||
            event.envelope.bounceRatio >= 0.18f || event.envelope.decayHalfFrames >= config.framesForMs(45)
        val metallicSignatureNoRef = event.isSnapOrClapFamily && metallicRingingEnvelope &&
            ((event.durationMs in 20..95 && event.avgCentroid >= 4100f && event.avgTilt >= 0.24f && event.avgFlatness >= 0.70f && event.avgZcr >= 0.40f) ||
                (event.durationMs in 30..160 && event.avgCentroid >= 4500f && event.avgTilt >= 0.20f && event.avgFlatness >= 0.60f && event.attackRatio <= 22f))
        val metallicSignatureWithRef = event.isSnapOrClapFamily && event.hasRecentTapReference && metallicRingingEnvelope &&
            event.durationMs in 25..110 && event.avgCentroid >= 4700f && event.avgTilt >= 0.30f && event.avgFlatness >= 0.74f && event.avgZcr >= 0.44f
        val likelySnapTransient = isLikelySnapTransient(event.durationMs, event.avgZcr, event.avgCentroid, event.avgTilt, event.avgFlatness, event.attackRatio, event.envelope)
        val looksMetallic = (metallicSignatureNoRef || metallicSignatureWithRef) && !likelySnapTransient
        if (!looksMetallic) return null

        val reason = "Metallic Ringing: C=${"%.0f".format(event.avgCentroid)} T=${"%.2f".format(event.avgTilt)} F=${"%.3f".format(event.avgFlatness)} Z=${"%.2f".format(event.avgZcr)}"
        Log.d("SoundDetector", "Rejected: $reason")
        val strongForMemory = (event.avgCentroid >= 4400f && event.avgTilt >= 0.20f && event.avgFlatness >= 0.66f && event.avgZcr >= 0.42f) ||
            (event.avgTilt >= 0.30f && event.avgFlatness >= 0.74f && event.avgZcr >= 0.46f)
        if (strongForMemory) {
            rememberNuisanceReference("METALLIC", event.durationMs, event.avgZcr, event.avgCentroid,
                event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        }
        return RejectionResult("METALLIC", reason)
    }

    private fun checkResonantImpact(event: AnalyzedEvent): RejectionResult? {
        if (!event.isSnapOrClapFamily) return null
        val resonantLike = isResonantImpactSignature(event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.envelope)
        if (!resonantLike) return null

        val reason = "Resonant Impact: D=${event.durationMs} C=${"%.0f".format(event.avgCentroid)} Z=${"%.2f".format(event.avgZcr)} T=${"%.2f".format(event.avgTilt)} F=${"%.3f".format(event.avgFlatness)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("RESONANT_IMPACT", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("RESONANT_IMPACT", reason)
    }

    private fun checkBrightImpact(event: AnalyzedEvent): RejectionResult? {
        val brightBroadImpactLike = event.isSnapOrClapFamily &&
            event.durationMs in 20..120 && event.avgCentroid >= 4000f && event.avgTilt >= 0.10f &&
            event.avgFlatness >= 0.60f && event.avgZcr >= 0.35f &&
            (event.envelope.tailEnergyRatio >= 0.26f || event.envelope.decayHalfFrames >= config.framesForMs(45)) &&
            event.attackRatio <= 24f
        if (!brightBroadImpactLike) return null

        val reason = "Bright Impact: C=${"%.0f".format(event.avgCentroid)} T=${"%.2f".format(event.avgTilt)} F=${"%.3f".format(event.avgFlatness)} Z=${"%.2f".format(event.avgZcr)}"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("BRIGHT_IMPACT", reason)
    }

    private fun checkUltraBrightPing(event: AnalyzedEvent): RejectionResult? {
        if (!event.isSnapOrClapFamily) return null
        val ultraBrightLike = isUltraBrightPingSignature(event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.envelope)
        if (!ultraBrightLike) return null

        val reason = "Ultra Bright Ping: D=${event.durationMs} C=${"%.0f".format(event.avgCentroid)} Z=${"%.2f".format(event.avgZcr)} T=${"%.2f".format(event.avgTilt)} F=${"%.3f".format(event.avgFlatness)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("ULTRA_BRIGHT_PING", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("ULTRA_BRIGHT_PING", reason)
    }

    private fun checkAttackShape(event: AnalyzedEvent): RejectionResult? {
        val glassSideAttackLike = event.isSnapOrClapFamily &&
            event.durationMs in 120..200 && event.avgCentroid >= 3900f && event.avgZcr >= 0.38f &&
            event.avgTilt >= 0.22f && event.avgFlatness in 0.24f..0.50f &&
            event.attackRatio >= 25f && event.peakRms >= 0.18f &&
            (event.envelope.tailEnergyRatio >= 0.20f || event.envelope.decayHalfFrames >= config.framesForMs(65))
        if (!glassSideAttackLike) return null

        val reason = "Attack Shape Impact: D=${event.durationMs} C=${"%.0f".format(event.avgCentroid)} Z=${"%.2f".format(event.avgZcr)} T=${"%.2f".format(event.avgTilt)} F=${"%.3f".format(event.avgFlatness)}"
        Log.d("SoundDetector", "Rejected: $reason")
        rememberNuisanceReference("ATTACK_SHAPE", event.durationMs, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.startTimestampMs)
        return RejectionResult("ATTACK_SHAPE", reason)
    }

    private fun checkIsolation(event: AnalyzedEvent): RejectionResult? {
        if (event.preEventNoiseAvg < 0.00001f) {
            val reason = "Artifact: Wake-up pop (Pre-noise is digital silence)"
            Log.d("SoundDetector", "Rejected: $reason")
            return RejectionResult("ARTIFACT", reason)
        }
        val allowedRatioBase = (0.65f - (event.peakRms * 2.5f)).coerceIn(0.25f, 0.65f)
        val allowedRatio = if (event.hasRecentTapReference) (allowedRatioBase + 0.08f).coerceAtMost(0.72f) else allowedRatioBase
        if (event.preEventRatio > allowedRatio) {
            val reason = "Isolation/PreNoise: ${"%.2f".format(event.preEventRatio)} > ${"%.2f".format(allowedRatio)}"
            Log.d("SoundDetector", "Rejected: $reason")
            return RejectionResult("ISOLATION", reason)
        }
        return null
    }

    private fun checkChaoticPreEvent(event: AnalyzedEvent): RejectionResult? {
        val clapLikeEvent = event.bestMatch?.name?.startsWith("Clap") == true
        val profileIsConfident = event.bestConfidence >= 0.88f
        val lookbackForVariance = if (event.isGestureControlContext) config.framesForMs(240) else config.framesForMs(300)
        val gapFrames = config.framesForMs(30)
        val available = recentSamples.size

        val framesAgoDetectionEnd = (frameCounter - lastDetectionEndFrame).toInt()
        val detectionExcludeStart = framesAgoDetectionEnd - lastDetectionDurationFrames
        val detectionExcludeEnd = framesAgoDetectionEnd
        val framesAgoQualifiedEnd = (frameCounter - lastQualifiedEndFrame).toInt()
        val qualifiedExcludeStart = framesAgoQualifiedEnd - lastQualifiedDurationFrames
        val qualifiedExcludeEnd = framesAgoQualifiedEnd

        val threshold = (noiseFloor * config.thresholdMultiplier).coerceAtLeast(
            if (noiseFloor < 0.0025f) config.quietModeMinRms else config.baseMinRms
        )
        val spikeThreshold = threshold * when {
            event.isGestureControlContext -> 0.60f
            clapLikeEvent && profileIsConfident -> 0.56f
            else -> 0.53f
        }
        val minFramesForChaos = if (event.isGestureControlContext) config.framesForMs(80) else config.framesForMs(100)
        val chaosRatioThreshold = when {
            event.isGestureControlContext -> 0.40f
            clapLikeEvent && profileIsConfident -> 0.34f
            else -> 0.28f
        }

        var spikeCount = 0; var totalChecked = 0
        for (j in gapFrames until lookbackForVariance) {
            if (available - 1 - j >= 0) {
                if (j in detectionExcludeStart..detectionExcludeEnd) continue
                if (j in qualifiedExcludeStart..qualifiedExcludeEnd) continue
                totalChecked++
                if (recentSamples[available - 1 - j] > spikeThreshold) spikeCount++
            }
        }

        val spikeRatio = if (totalChecked > 0) spikeCount.toFloat() / totalChecked else 0f
        if (totalChecked < minFramesForChaos || spikeRatio <= chaosRatioThreshold) return null

        // Whitelist checks
        val whitelistByCandidate = matchesRecentSignature(event, lastCandidateType, lastCandidateTimestampMs, lastCandidateDurationMs, lastCandidateCentroid, lastCandidateTilt, lastCandidateStrength, 1000L)
        val whitelistByAccepted = matchesRecentSignature(event, lastAcceptedType, lastAcceptedTimestampMs, lastAcceptedDurationMs, lastAcceptedCentroid, lastAcceptedTilt, lastAcceptedStrength, 1400L)
        val burstTapBypass = event.isGestureControlContext && event.hasRecentTapReference &&
            event.durationMs <= 130 && event.bestConfidence >= 0.86f && event.attackRatio >= 10f &&
            event.avgCentroid >= 1800f && event.preEventRatio <= 0.24f && spikeRatio <= 0.72f
        val diagnosticClapBypass = !event.isGestureControlContext && clapLikeEvent && profileIsConfident &&
            event.attackRatio >= 8f && event.preEventRatio <= 0.40f && spikeRatio <= 0.52f

        if (whitelistByCandidate || whitelistByAccepted || burstTapBypass || diagnosticClapBypass) {
            Log.d("SoundDetector", "Chaos gate bypassed by signature whitelist (candidate=$whitelistByCandidate accepted=$whitelistByAccepted burst=$burstTapBypass diagClap=$diagnosticClapBypass ratio=${"%.2f".format(spikeRatio)})")
            return null
        }

        val reason = "Chaotic Pre-Event: $spikeCount/$totalChecked frames above ${"%.4f".format(spikeThreshold)} (${(spikeRatio * 100).toInt()}%)"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("CHAOS_PRE", reason)
    }

    private fun checkStage2(event: AnalyzedEvent): RejectionResult? {
        val preEventRatioStage2 = if (event.peakRms > 0f) event.preEventNoiseAvg / event.peakRms else 1f
        val stage2Score = computeStage2AgreementScore(event.bestMatch, event.bestConfidence, event.durationMs,
            event.attackRatio, event.peakRms, preEventRatioStage2, event.avgZcr, event.avgCentroid,
            event.avgTilt, event.avgFlatness, event.envelope)
        val resonantLike = !event.hasRecentTapReference && isResonantImpactSignature(event.durationMs, event.avgZcr,
            event.avgCentroid, event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.envelope)
        val ultraBrightLike = !event.hasRecentTapReference && isUltraBrightPingSignature(event.durationMs, event.avgZcr,
            event.avgCentroid, event.avgTilt, event.avgFlatness, event.attackRatio, event.peakRms, event.envelope)
        val stage2Threshold = (when {
            event.hasRecentTapReference && event.isDiagnosticContext -> 0.56f
            event.hasRecentTapReference -> 0.56f
            event.isDiagnosticContext -> 0.60f
            else -> 0.60f
        }) + (if (resonantLike) 0.10f else 0f) + (if (ultraBrightLike) 0.10f else 0f)

        if (stage2Score >= stage2Threshold) return null
        val reason = "Stage2 Reject: score ${"%.2f".format(stage2Score)} < ${"%.2f".format(stage2Threshold)}"
        Log.d("SoundDetector", "Rejected: $reason")
        return RejectionResult("STAGE2", reason)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCORING & SIGNATURE HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun getMetricScore(value: Float, min: Float, max: Float): Float {
        if (value in min..max) {
            val range = max - min
            if (range < 0.001f) return 1.0f
            val relative = (value - min) / range
            val distFromCenter = abs(relative - 0.5f)
            return 1.0f - (distFromCenter * 0.4f)
        }
        val range = max - min
        val allowedDeviation = range * 0.5f
        if (allowedDeviation < 0.0001f) return 0f
        val dist = if (value < min) min - value else value - max
        val penalty = dist / allowedDeviation
        return (0.8f - (penalty * 0.8f)).coerceIn(0f, 0.8f)
    }

    private fun extractEnvelopeFeatures(trace: List<Float>, peakRms: Float): Stage1EnvelopeFeatures {
        if (trace.isEmpty() || peakRms <= 0f) return Stage1EnvelopeFeatures(0f, 0f, 0f, 0f, Int.MAX_VALUE, Int.MAX_VALUE)

        var totalEnergy = 0f
        val energy = FloatArray(trace.size)
        for (i in trace.indices) { val e = trace[i] * trace[i]; energy[i] = e; totalEnergy += e }
        val safeTotal = totalEnergy.coerceAtLeast(1e-8f)

        val earlyFrames = minOf(config.framesForMs(30), trace.size)
        var earlyEnergy = 0f
        for (i in 0 until earlyFrames) earlyEnergy += energy[i]

        val tailFrames = minOf(config.framesForMs(60), trace.size)
        var tailEnergy = 0f
        for (i in trace.size - tailFrames until trace.size) tailEnergy += energy[i]

        var peakIndex = 0; var peakValue = trace[0]
        for (i in 1 until trace.size) { if (trace[i] > peakValue) { peakValue = trace[i]; peakIndex = i } }

        val postPeakCount = (trace.size - peakIndex - 1).coerceAtLeast(0)
        var postRiseCount = 0; var postTransitions = 0
        if (postPeakCount > 1) {
            for (i in peakIndex + 2 until trace.size) { postTransitions++; if (trace[i] - trace[i - 1] > peakRms * 0.03f) postRiseCount++ }
        }
        val bounceRatio = if (postTransitions > 0) postRiseCount.toFloat() / postTransitions.toFloat() else 0f

        var globalTransitions = 0; var globalRiseCount = 0
        if (trace.size > 1) {
            for (i in 1 until trace.size) { globalTransitions++; if (trace[i] - trace[i - 1] > peakRms * 0.02f) globalRiseCount++ }
        }
        val monotonicRiseRatio = if (globalTransitions > 0) globalRiseCount.toFloat() / globalTransitions.toFloat() else 0f

        var decayHalfFrames = Int.MAX_VALUE; var decayQuarterFrames = Int.MAX_VALUE
        for (i in peakIndex until trace.size) {
            val fromPeak = i - peakIndex
            if (decayHalfFrames == Int.MAX_VALUE && trace[i] <= peakRms * 0.50f) decayHalfFrames = fromPeak
            if (decayQuarterFrames == Int.MAX_VALUE && trace[i] <= peakRms * 0.25f) decayQuarterFrames = fromPeak
        }

        return Stage1EnvelopeFeatures(
            earlyEnergyRatio = (earlyEnergy / safeTotal).coerceIn(0f, 1f),
            tailEnergyRatio = (tailEnergy / safeTotal).coerceIn(0f, 1f),
            bounceRatio = bounceRatio.coerceIn(0f, 1f),
            monotonicRiseRatio = monotonicRiseRatio.coerceIn(0f, 1f),
            decayHalfFrames = decayHalfFrames, decayQuarterFrames = decayQuarterFrames
        )
    }

    private fun computeStage2AgreementScore(
        bestMatch: SoundProfile?, profileConfidence: Float, durationMs: Int, attackRatio: Float,
        peakRms: Float, preEventRatio: Float, avgZcr: Float, avgCentroid: Float,
        avgTilt: Float, avgFlatness: Float, env: Stage1EnvelopeFeatures
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
        val decayHalfScore = when { env.decayHalfFrames == Int.MAX_VALUE -> 0f; env.decayHalfFrames <= 6 -> 1f; env.decayHalfFrames >= 24 -> 0f; else -> ((24 - env.decayHalfFrames).toFloat() / 18f).coerceIn(0f, 1f) }
        val decayQuarterScore = when { env.decayQuarterFrames == Int.MAX_VALUE -> 0f; env.decayQuarterFrames <= 12 -> 1f; env.decayQuarterFrames >= 36 -> 0f; else -> ((36 - env.decayQuarterFrames).toFloat() / 24f).coerceIn(0f, 1f) }
        val compactTailScore = (1f - env.tailEnergyRatio).coerceIn(0f, 1f)
        val stableEnvelopeScore = (1f - env.bounceRatio).coerceIn(0f, 1f)
        val lowPreNoiseScore = (1f - preEventRatio.coerceIn(0f, 1f))

        var score = 0.10f
        score += profileConfidence.coerceIn(0f, 1f) * 0.24f + attackScore * 0.08f + centroidScore * 0.07f
        score += zcrScore * 0.06f + tiltScore * 0.04f + flatnessScore * 0.10f
        score += decayHalfScore * 0.07f + decayQuarterScore * 0.06f + compactTailScore * 0.07f
        score += stableEnvelopeScore * 0.06f + lowPreNoiseScore * 0.05f

        if (durationMs > 180) score -= 0.08f
        if (durationMs < 20) score -= 0.05f
        if (peakRms < 0.04f) score -= 0.05f
        if (env.monotonicRiseRatio > 0.45f) score -= 0.10f
        if (env.tailEnergyRatio > 0.62f) score -= 0.10f
        if (preEventRatio > 0.55f) score -= 0.07f
        if (avgFlatness < 0.14f) score -= 0.08f

        val tableCoupledThud = avgTilt <= -1.20f && avgCentroid <= 1700f && avgZcr <= 0.18f && durationMs in 40..200
        val dampedContactLike = avgTilt <= -0.95f && avgCentroid <= 2200f && avgZcr <= 0.24f && env.tailEnergyRatio >= 0.30f && durationMs in 50..220
        val midBandContactLike = avgTilt <= -0.30f && avgCentroid in 2300f..3250f && avgZcr <= 0.38f && durationMs in 60..180
        val resonantImpactLike = isResonantImpactSignature(durationMs, avgZcr, avgCentroid, avgTilt, avgFlatness, attackRatio, peakRms, env)
        val ultraBrightPingLike = isUltraBrightPingSignature(durationMs, avgZcr, avgCentroid, avgTilt, avgFlatness, attackRatio, peakRms, env)
        if (tableCoupledThud) score -= 0.24f
        if (dampedContactLike) score -= 0.12f
        if (midBandContactLike) score -= 0.14f
        if (resonantImpactLike) score -= 0.18f
        if (ultraBrightPingLike) score -= 0.20f

        return score.coerceIn(0f, 1f)
    }

    private fun isLikelySnapTransient(durationMs: Int, avgZcr: Float, avgCentroid: Float, avgTilt: Float, avgFlatness: Float, attackRatio: Float, env: Stage1EnvelopeFeatures): Boolean {
        return durationMs in 35..95 && avgCentroid in 3200f..4200f && avgZcr in 0.34f..0.58f &&
            avgTilt in -0.12f..0.30f && avgFlatness in 0.52f..0.80f && attackRatio >= 8f &&
            env.decayHalfFrames <= config.framesForMs(55) && env.tailEnergyRatio <= 0.40f
    }

    private fun isResonantImpactSignature(durationMs: Int, avgZcr: Float, avgCentroid: Float, avgTilt: Float, avgFlatness: Float, attackRatio: Float, peakRms: Float, env: Stage1EnvelopeFeatures): Boolean {
        val longResonantBand = durationMs in 85..180 && avgCentroid in 3300f..4600f && avgZcr in 0.34f..0.54f && avgTilt in -0.08f..0.45f && avgFlatness in 0.24f..0.52f
        val longLowFlatMaterialBand = durationMs in 100..190 && avgCentroid in 3000f..4500f && avgZcr in 0.34f..0.50f && avgTilt in -0.60f..0.30f && avgFlatness in 0.22f..0.55f
        val shortBrightBand = durationMs in 45..95 && avgCentroid in 4100f..5200f && avgZcr in 0.42f..0.68f && avgTilt in 0.24f..0.80f && avgFlatness in 0.48f..0.85f
        val midBrightMaterialBand = durationMs in 90..150 && avgCentroid in 4000f..5200f && avgZcr in 0.38f..0.60f && avgTilt in 0.20f..0.70f && avgFlatness in 0.35f..0.58f
        val hasRingingEnvelope = env.tailEnergyRatio >= 0.24f || env.decayHalfFrames >= config.framesForMs(55) || env.bounceRatio >= 0.18f
        val likelySnapMidTransient = durationMs in 45..110 && avgCentroid in 3350f..4050f && avgZcr in 0.35f..0.48f && avgTilt in -0.12f..0.20f && avgFlatness in 0.34f..0.62f && attackRatio >= 6f && env.tailEnergyRatio <= 0.33f && env.decayHalfFrames <= config.framesForMs(70)
        val likelySnapTransient = isLikelySnapTransient(durationMs, avgZcr, avgCentroid, avgTilt, avgFlatness, attackRatio, env) || likelySnapMidTransient
        val strongEnough = peakRms >= 0.08f
        val resonantMaterialBand = longResonantBand || longLowFlatMaterialBand || shortBrightBand || midBrightMaterialBand
        return resonantMaterialBand && hasRingingEnvelope && !likelySnapTransient && strongEnough
    }

    private fun isUltraBrightPingSignature(durationMs: Int, avgZcr: Float, avgCentroid: Float, avgTilt: Float, avgFlatness: Float, attackRatio: Float, peakRms: Float, env: Stage1EnvelopeFeatures): Boolean {
        val shortHighBand = durationMs in 55..130 && avgCentroid >= 4500f && avgZcr >= 0.46f && avgTilt >= 0.30f && avgFlatness in 0.40f..0.62f && peakRms >= 0.06f
        val envelopeSuggestive = env.tailEnergyRatio >= 0.16f || env.decayHalfFrames >= config.framesForMs(40) || env.bounceRatio >= 0.12f
        val attackSuggestive = attackRatio <= 70f || env.monotonicRiseRatio >= 0.25f
        val likelyNatural = durationMs in 35..95 && avgCentroid <= 4550f && avgFlatness >= 0.62f && avgTilt <= 0.32f && avgZcr <= 0.58f
        return shortHighBand && envelopeSuggestive && attackSuggestive && !likelyNatural
    }

    private fun isStrongAcceptedSignatureMatchForCadenceBypass(
        bestType: String?, eventTimestampMs: Long, durationMs: Int, centroid: Float, tilt: Float, strength: Float,
        refType: String?, refTimestampMs: Long, refDurationMs: Int, refCentroid: Float, refTilt: Float, refStrength: Float,
        followupWindowMs: Long
    ): Boolean {
        if (bestType == null || refType == null) return false
        val ageMs = eventTimestampMs - refTimestampMs
        if (ageMs !in 20L..followupWindowMs) return false
        val bestFamily = bestType.substringBefore(" (").trim()
        val refFamily = refType.substringBefore(" (").trim()
        if (bestType != refType && bestFamily != refFamily) return false
        return abs(durationMs - refDurationMs) <= 45 &&
            abs(centroid - refCentroid) / refCentroid.coerceAtLeast(500f) <= 0.20f &&
            abs(tilt - refTilt) <= 0.35f &&
            strength / refStrength.coerceAtLeast(0.001f) in 0.35f..2.80f
    }

    // ═══════════════════════════════════════════════════════════════
    //  SESSION / NUISANCE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private fun calibrationFamily(type: String): String? {
        return when (type.substringBefore(" (").trim()) {
            "Snap" -> "Snap"; "Clap", "Slap" -> "Clap"; else -> null
        }
    }

    private fun sessionReferenceForType(type: String): SessionTapReference? {
        val family = calibrationFamily(type) ?: return null
        return sessionReferences[family]
    }

    private fun sessionSimilarity(ref: SessionTapReference, centroid: Float, tilt: Float, zcr: Float, flatness: Float, durationMs: Int, strength: Float): Float {
        val centroidScore = (1f - (abs(centroid - ref.centroid) / ref.centroid.coerceAtLeast(600f))).coerceIn(0f, 1f)
        val tiltScore = (1f - (abs(tilt - ref.tilt) / 6f)).coerceIn(0f, 1f)
        val zcrScore = (1f - (abs(zcr - ref.zcr) / 0.22f)).coerceIn(0f, 1f)
        val flatnessScore = (1f - (abs(flatness - ref.flatness) / 0.25f)).coerceIn(0f, 1f)
        val durationScore = (1f - (abs(durationMs - ref.durationMs).toFloat() / ref.durationMs.coerceAtLeast(20).toFloat())).coerceIn(0f, 1f)
        val strengthRatio = strength / ref.strength.coerceAtLeast(0.001f)
        val strengthScore = when { strengthRatio in 0.30f..3.00f -> 1f; strengthRatio < 0.30f -> (strengthRatio / 0.30f).coerceIn(0f, 1f); else -> (3.00f / strengthRatio).coerceIn(0f, 1f) }
        return (centroidScore * 0.26f + tiltScore * 0.18f + zcrScore * 0.18f + flatnessScore * 0.20f + durationScore * 0.12f + strengthScore * 0.06f).coerceIn(0f, 1f)
    }

    private fun nuisanceSimilarity(ref: SessionNuisanceReference, durationMs: Int, zcr: Float, centroid: Float, tilt: Float, flatness: Float, attackRatio: Float, strength: Float): Float {
        val centroidScore = (1f - (abs(centroid - ref.centroid) / ref.centroid.coerceAtLeast(700f))).coerceIn(0f, 1f)
        val tiltScore = (1f - (abs(tilt - ref.tilt) / 1.7f)).coerceIn(0f, 1f)
        val zcrScore = (1f - (abs(zcr - ref.zcr) / 0.18f)).coerceIn(0f, 1f)
        val flatnessScore = (1f - (abs(flatness - ref.flatness) / 0.16f)).coerceIn(0f, 1f)
        val durationScore = (1f - (abs(durationMs - ref.durationMs).toFloat() / ref.durationMs.coerceAtLeast(30).toFloat())).coerceIn(0f, 1f)
        val attackScore = (1f - (abs(attackRatio - ref.attackRatio) / ref.attackRatio.coerceAtLeast(6f))).coerceIn(0f, 1f)
        val strengthRatio = strength / ref.strength.coerceAtLeast(0.001f)
        val strengthScore = when { strengthRatio in 0.45f..2.20f -> 1f; strengthRatio < 0.45f -> (strengthRatio / 0.45f).coerceIn(0f, 1f); else -> (2.20f / strengthRatio).coerceIn(0f, 1f) }
        return (centroidScore * 0.24f + tiltScore * 0.16f + zcrScore * 0.16f + flatnessScore * 0.20f + durationScore * 0.14f + attackScore * 0.06f + strengthScore * 0.04f).coerceIn(0f, 1f)
    }

    @Synchronized
    private fun rememberNuisanceReference(stage: String, durationMs: Int, zcr: Float, centroid: Float, tilt: Float, flatness: Float, attackRatio: Float, strength: Float, timestampMs: Long) {
        if (durationMs <= 0 || strength <= 0f) return
        pruneOldNuisanceReferences(timestampMs)
        val duplicateSimilarity = sessionNuisanceReferences.maxOfOrNull { ref ->
            nuisanceSimilarity(ref, durationMs, zcr, centroid, tilt, flatness, attackRatio, strength)
        } ?: 0f
        if (duplicateSimilarity >= 0.90f) return
        sessionNuisanceReferences.add(SessionNuisanceReference(stage, centroid, tilt, zcr, flatness, durationMs, strength, attackRatio, timestampMs))
        if (sessionNuisanceReferences.size > config.nuisanceReferenceMaxCount) sessionNuisanceReferences.removeAt(0)
    }

    @Synchronized
    private fun bestNuisanceSimilarity(durationMs: Int, zcr: Float, centroid: Float, tilt: Float, flatness: Float, attackRatio: Float, strength: Float, nowMs: Long): NuisanceSimilarityResult {
        pruneOldNuisanceReferences(nowMs)
        val total = sessionNuisanceReferences.size
        val strong = sessionNuisanceReferences.count { isStrongNuisanceStage(it.stage) }
        var best = 0f; var source: String? = null
        for (ref in sessionNuisanceReferences) {
            val sim = nuisanceSimilarity(ref, durationMs, zcr, centroid, tilt, flatness, attackRatio, strength)
            if (sim > best) { best = sim; source = ref.stage }
        }
        return NuisanceSimilarityResult(best, source, total, strong)
    }

    private fun pruneOldNuisanceReferences(nowMs: Long) {
        sessionNuisanceReferences.removeAll { nowMs - it.timestampMs > config.nuisanceReferenceTtlMs }
    }

    private fun isStrongNuisanceStage(stage: String): Boolean {
        return stage == "SURFACE_TAP" || stage == "HARD_SURFACE" || stage == "CHAOS_POST_RING" ||
            stage == "METALLIC" || stage == "RESONANT_IMPACT" || stage == "BRIGHT_IMPACT" ||
            stage == "ATTACK_SHAPE" || stage == "CADENCE_PATTERN" || stage == "ULTRA_BRIGHT_PING"
    }

    // ═══════════════════════════════════════════════════════════════
    //  CADENCE
    // ═══════════════════════════════════════════════════════════════

    private fun captureCadenceProbe(event: AnalyzedEvent) {
        if (!event.isGestureFamily || event.bestConfidence < config.cadenceProbeConfidenceMin ||
            event.durationMs !in config.cadenceProbeDurationMinMs..config.cadenceProbeDurationMaxMs
        ) {
            CadencePatternHeuristics.pruneHistory(cadenceProbeHistory, event.startTimestampMs, config.cadenceWindowMs)
            return
        }

        CadencePatternHeuristics.pruneHistory(cadenceProbeHistory, event.startTimestampMs, config.cadenceWindowMs)
        cadenceProbeHistory.addLast(CadenceProbe(event.startTimestampMs, event.durationMs, event.avgCentroid, event.avgTilt, event.avgFlatness, event.avgZcr))
        CadencePatternHeuristics.pruneHistory(cadenceProbeHistory, event.startTimestampMs, config.cadenceWindowMs)

        val cadenceStats = CadencePatternHeuristics.detectPattern(
            cadenceProbeHistory.toList(), event.startTimestampMs, config.cadenceWindowMs, config.cadenceMinEvents,
            config.cadenceIntervalMinMs, config.cadenceIntervalMaxMs, config.cadenceIntervalCvMax,
            config.cadenceCentroidSpreadPctMax, config.cadenceTiltSpreadMax, config.cadenceFlatnessSpreadMax,
            config.cadenceZcrSpreadMax, config.cadenceDurationSpreadPctMax
        )
        if (cadenceStats != null) {
            cadenceSuppressUntilMs = maxOf(cadenceSuppressUntilMs, event.startTimestampMs + config.cadenceSuppressionMs)
            lastCadencePatternStats = cadenceStats
            if (event.startTimestampMs - lastCadenceDiagMs >= config.cadenceDiagCooldownMs) {
                lastCadenceDiagMs = event.startTimestampMs
                Log.d("SoundDetector", "Cadence suppression armed: n=${cadenceStats.eventCount} mean=${"%.0f".format(cadenceStats.meanIntervalMs)}ms cv=${"%.2f".format(cadenceStats.intervalCv)}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DIAGNOSTICS & UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun emitEventDiag(
        rejectedAt: String?, rejectionDetail: String?,
        sustainRatio: Float? = null, decayRatio: Float? = null,
        preEventCV: Float? = null, postEventRms: Float? = null, postEventQuietThreshold: Float? = null,
        bestProfileName: String? = null, bestConfidence: Float = 0f,
        allProfileScores: List<ProfileScore> = emptyList(),
        overrideDurationMs: Int? = null, overrideAvgZcr: Float? = null,
        overrideAvgCentroid: Float? = null, overrideAvgTilt: Float? = null,
        overrideAvgFlatness: Float? = null, sessionSimilarity: Float? = null,
        overridePreEventNoiseAvg: Float? = null
    ) {
        val frames = eventHighRmsFrames.coerceAtLeast(1)
        host.emitDiagnostic(DetectionDiagnostic(
            id = SystemClock.elapsedRealtime(), timestamp = formatTimestamp(),
            eventTimestampMs = eventStartTimestamp,
            outcome = if (rejectedAt == null) "ACCEPTED" else "REJECTED",
            rejectedAt = rejectedAt, rejectionDetail = rejectionDetail,
            noiseFloor = eventNoiseFloorAtTrigger, threshold = eventThresholdAtTrigger,
            preEventNoiseAvg = overridePreEventNoiseAvg ?: preEventNoiseAvg,
            triggerRms = eventTriggerRms, attackRatio = eventAttackRatio,
            durationMs = overrideDurationMs ?: config.durationForFrames(frames),
            peakRms = eventPeakRms, peakRaw = eventPeakRaw,
            sustainRatio = sustainRatio, decayRatio = decayRatio,
            peakFrameIndex = frames - framesSincePeak,
            preEventCV = preEventCV, postEventRms = postEventRms, postEventQuietThreshold = postEventQuietThreshold,
            avgZcr = overrideAvgZcr ?: (eventAccumulatedZcr / frames),
            avgCentroid = overrideAvgCentroid ?: (eventAccumulatedCentroid / frames),
            avgTilt = overrideAvgTilt ?: (eventAccumulatedTilt / frames),
            avgFlatness = overrideAvgFlatness ?: (eventAccumulatedFlatness / frames),
            sessionSimilarity = sessionSimilarity, bestProfileName = bestProfileName,
            bestConfidence = bestConfidence, allProfileScores = allProfileScores
        ))
    }

    private fun emitAnalysisDiag(stage: String, reason: String, event: AnalyzedEvent) {
        emitEventDiag(
            rejectedAt = stage, rejectionDetail = reason,
            overrideDurationMs = event.durationMs, overrideAvgZcr = event.avgZcr,
            overrideAvgCentroid = event.avgCentroid, overrideAvgTilt = event.avgTilt,
            overrideAvgFlatness = event.avgFlatness, bestProfileName = event.bestMatch?.name,
            bestConfidence = event.bestConfidence, allProfileScores = event.allProfileScores,
            sessionSimilarity = event.sessionSimilarity
        )
    }

    private fun computeHasRecentTapReference(): Boolean {
        val isGestureControlContext = !host.debugContext.equals("DIAGNOSTIC", ignoreCase = true)
        val candidateAgeMs = eventStartTimestamp - lastCandidateTimestampMs
        val acceptedAgeMs = eventStartTimestamp - lastAcceptedTimestampMs
        return isGestureControlContext && (candidateAgeMs in 20L..1000L || acceptedAgeMs in 20L..1400L)
    }

    private fun matchesRecentSignature(
        event: AnalyzedEvent, refType: String?, refTimestampMs: Long, refDurationMs: Int,
        refCentroid: Float, refTilt: Float, refStrength: Float, windowMs: Long
    ): Boolean {
        if (refType == null) return false
        val ageMs = event.startTimestampMs - refTimestampMs
        if (ageMs !in 20L..windowMs) return false
        val bestType = event.bestMatch?.name
        val typeMatches = bestType == refType
        val familyOfBest = bestType?.substringBefore(" (")?.trim()
        val familyOfRef = refType.substringBefore(" (").trim()
        val familyMatches = familyOfBest != null && familyOfBest == familyOfRef
        val acousticMatch = abs(event.durationMs - refDurationMs) <= 90 &&
            abs(event.avgCentroid - refCentroid) / refCentroid.coerceAtLeast(500f) <= 0.50f &&
            abs(event.avgTilt - refTilt) <= 14f &&
            event.peakRms / refStrength.coerceAtLeast(0.001f) in 0.20f..4.50f
        return typeMatches || familyMatches || acousticMatch
    }

    private fun appendToHistory(rms: Float) {
        recentSamples.add(rms)
        if (recentSamples.size > config.maxRecentSamples) recentSamples.removeAt(0)
        frameCounter++
    }

    private fun emitUiThrottled(features: FrameFeatures) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastUiEmitMs >= 100L) {
            lastUiEmitMs = nowMs
            host.updateState { it.copy(rms = features.rms, noiseFloor = noiseFloor, currentRms = features.rms) }
        }
    }

    private fun formatTimestamp(): String {
        return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
    }
}
