package ro.maris.tools.cooktimer.audio

/**
 * All tunable thresholds and constants for the sound detection pipeline.
 * Extracted from the original monolithic runRecorderLoop() to make
 * tuning explicit, searchable, and testable.
 */
internal class DetectionConfig(
    val sampleRate: Int = 16000,
    val fftWindowMs: Int = 10,
    val hopMs: Int = 5
) {
    val windowSamples: Int = sampleRate * fftWindowMs / 1000
    val hopSamples: Int = sampleRate * hopMs / 1000

    fun framesForMs(ms: Int): Int = ((ms + hopMs - 1) / hopMs).coerceAtLeast(1)
    fun durationForFrames(frames: Int): Int = frames * hopMs

    // ── Dynamic Thresholds ──
    val baseMinRms = 0.035f
    val quietModeMinRms = 0.025f
    val thresholdMultiplier = 4.0f
    val noiseAlphaRise = 0.005f
    val noiseAlphaFall = 0.050f
    val attackThreshold = 2.0f

    // ── History / UI Limits ──
    val maxEvents = 50
    val maxLogs = 750
    val maxDiagnostics = 100
    val maxRecentSamples = 150

    // ── Confidence ──
    val firstTapConfidenceThreshold = 0.82f
    val followUpTapConfidenceThreshold = 0.74f

    // ── Chaos Rejection ──
    val postEventCheckFrames: Int = framesForMs(50)
    val postEventQuietRatio = 0.5f
    val playbackBlankingMs = 200L
    val playbackBlankDiagCooldownMs = 250L
    val externalAudioDiagCooldownMs = 500L

    // ── Nuisance Similarity ──
    val nuisanceSimilarityRejectThreshold = 0.82f
    val nuisanceSimilarityRejectThresholdMetallic = 0.88f
    val nuisanceSimilaritySessionBypassThreshold = 0.78f
    val nuisanceReferenceTtlMs = 8L * 60 * 1000
    val nuisanceReferenceMaxCount = 18
    val nuisanceMinReferencesToGate = 3
    val nuisanceMinStrongReferencesToGate = 1

    // ── Cadence Pattern ──
    val cadenceWindowMs = CadencePatternHeuristics.DEFAULT_WINDOW_MS
    val cadenceMinEvents = CadencePatternHeuristics.DEFAULT_MIN_EVENTS
    val cadenceSuppressionMs = CadencePatternHeuristics.DEFAULT_SUPPRESSION_MS
    val cadenceProbeConfidenceMin = CadencePatternHeuristics.DEFAULT_PROBE_CONFIDENCE_MIN
    val cadenceProbeDurationMinMs = CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MIN_MS
    val cadenceProbeDurationMaxMs = CadencePatternHeuristics.DEFAULT_PROBE_DURATION_MAX_MS
    val cadenceIntervalMinMs = CadencePatternHeuristics.DEFAULT_INTERVAL_MIN_MS
    val cadenceIntervalMaxMs = CadencePatternHeuristics.DEFAULT_INTERVAL_MAX_MS
    val cadenceIntervalCvMax = CadencePatternHeuristics.DEFAULT_INTERVAL_CV_MAX
    val cadenceCentroidSpreadPctMax = CadencePatternHeuristics.DEFAULT_CENTROID_SPREAD_PCT_MAX
    val cadenceTiltSpreadMax = CadencePatternHeuristics.DEFAULT_TILT_SPREAD_MAX
    val cadenceFlatnessSpreadMax = CadencePatternHeuristics.DEFAULT_FLATNESS_SPREAD_MAX
    val cadenceZcrSpreadMax = CadencePatternHeuristics.DEFAULT_ZCR_SPREAD_MAX
    val cadenceDurationSpreadPctMax = CadencePatternHeuristics.DEFAULT_DURATION_SPREAD_PCT_MAX
    val cadenceDiagCooldownMs = 300L
    val cadenceFollowupWindowMs = CadencePatternHeuristics.DEFAULT_FOLLOWUP_WINDOW_MS

    // ── Warm-up ──
    val fastNoiseAlpha = 0.25f
    val warmUpFrames: Int = framesForMs(500)
    val maxFlatlineFrames: Int = framesForMs(1000)

    // ── Sound Profiles ──
    val profiles = listOf(
        SoundProfile("Snap (High)", minDurMs = 45, maxDurMs = 130, minZcr = 0.3f, maxZcr = 0.99f, minCentroid = 3500f, maxCentroid = 12000f, minTilt = -5f),
        SoundProfile("Snap (Mid)", minDurMs = 45, maxDurMs = 130, minZcr = 0.1f, maxZcr = 0.8f, minCentroid = 1000f, maxCentroid = 4000f, minTilt = -12f),
        SoundProfile("Snap (Low)", minDurMs = 45, maxDurMs = 80, minZcr = 0.12f, maxZcr = 0.5f, minCentroid = 900f, maxCentroid = 2500f, minTilt = -0.90f),
        SoundProfile("Clap (Airy)", minDurMs = 50, maxDurMs = 200, minZcr = 0.40f, maxZcr = 0.90f, minCentroid = 3000f, maxCentroid = 10000f, minTilt = -8f),
        SoundProfile("Clap", minDurMs = 45, maxDurMs = 200, minZcr = 0.08f, maxZcr = 0.60f, minCentroid = 2800f, maxCentroid = 5000f, minTilt = -0.45f, maxTilt = 0.5f),
        SoundProfile("Clap (Wet)", minDurMs = 40, maxDurMs = 180, minZcr = 0.08f, maxZcr = 0.40f, minCentroid = 500f, maxCentroid = 1500f, minTilt = -1.00f),
        SoundProfile("Knock", minDurMs = 15, maxDurMs = 120, minZcr = 0.0f, maxZcr = 0.3f, minCentroid = 50f, maxCentroid = 3000f, minTilt = -25f, maxTilt = -5f)
    )
}

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
