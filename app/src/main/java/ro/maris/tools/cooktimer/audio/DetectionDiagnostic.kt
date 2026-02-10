package ro.maris.tools.cooktimer.audio

/**
 * Structured diagnostic entry for a single detection event.
 * Captures all pipeline metrics at the point of rejection or acceptance.
 *
 * For monitoring-phase rejections (SUSTAIN, LATE_PEAK, REBOUND, DURATION, DECAY),
 * spectral data is partial (averaged over frames so far) and profile scores are unavailable.
 *
 * For post-analysis rejections and acceptances, all data is fully populated.
 */
data class DetectionDiagnostic(
    val id: Long,
    val timestamp: String,                // HH:mm:ss.SSS
    val outcome: String,                  // "ACCEPTED" or "REJECTED"
    val rejectedAt: String?,              // null = accepted. Stage name otherwise.
    val rejectionDetail: String?,         // Human-readable explanation

    // Environment at time of trigger
    val noiseFloor: Float,
    val threshold: Float,
    val preEventNoiseAvg: Float,

    // Trigger (Flux Gate)
    val triggerRms: Float,
    val attackRatio: Float,

    // Temporal
    val durationMs: Int,
    val peakRms: Float,
    val peakRaw: Float,
    val sustainRatio: Float?,             // Energy ratio at 50-60ms vs peak. null if too short.
    val decayRatio: Float?,               // Energy ratio at 60ms post-peak. null if too short.
    val peakFrameIndex: Int,              // Frame at which peak occurred

    // Chaos
    val preEventCV: Float? = null,        // Coefficient of variation before event
    val postEventRms: Float? = null,      // RMS during post-event quiet check
    val postEventQuietThreshold: Float? = null,

    // Spectral (averaged over event duration)
    val avgZcr: Float,
    val avgCentroid: Float,
    val avgTilt: Float,

    // Profile Scoring
    val bestProfileName: String? = null,
    val bestConfidence: Float = 0f,
    val allProfileScores: List<ProfileScore> = emptyList(),

    // User annotation
    val note: String = ""
) {
    /** Stage label for display; "ACCEPTED" if not rejected */
    val stageLabel: String get() = rejectedAt ?: "ACCEPTED"

    fun toCsvLine(): String {
        return listOf(
            timestamp,
            outcome,
            rejectedAt ?: "",
            "%.4f".format(noiseFloor),
            "%.4f".format(threshold),
            "%.4f".format(preEventNoiseAvg),
            "%.4f".format(triggerRms),
            "%.2f".format(attackRatio),
            durationMs.toString(),
            "%.4f".format(peakRms),
            "%.4f".format(peakRaw),
            sustainRatio?.let { "%.3f".format(it) } ?: "",
            decayRatio?.let { "%.3f".format(it) } ?: "",
            peakFrameIndex.toString(),
            preEventCV?.let { "%.3f".format(it) } ?: "",
            postEventRms?.let { "%.4f".format(it) } ?: "",
            postEventQuietThreshold?.let { "%.4f".format(it) } ?: "",
            "%.3f".format(avgZcr),
            "%.0f".format(avgCentroid),
            "%.2f".format(avgTilt),
            bestProfileName ?: "",
            "%.3f".format(bestConfidence),
            allProfileScores.joinToString(";") { "${it.name}=${it.confidence.let { c -> "%.2f".format(c) }}" },
            rejectionDetail ?: "",
            note.replace(",", ";").replace("\n", " ")
        ).joinToString(",")
    }

    companion object {
        fun csvHeader(): String {
            return "timestamp,outcome,rejectedAt,noiseFloor,threshold,preEventNoise,triggerRms,attackRatio,durationMs,peakRms,peakRaw,sustainRatio,decayRatio,peakFrame,preEventCV,postEventRms,postQuietThreshold,avgZcr,avgCentroid,avgTilt,bestProfile,bestConfidence,profileScores,detail,note"
        }
    }
}

/**
 * Per-profile score breakdown showing individual metric contributions.
 */
data class ProfileScore(
    val name: String,
    val confidence: Float,
    val durScore: Float,
    val zcrScore: Float,
    val centroidScore: Float,
    val tiltScore: Float
)
