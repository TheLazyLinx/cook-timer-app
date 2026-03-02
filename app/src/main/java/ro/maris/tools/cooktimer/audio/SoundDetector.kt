package ro.maris.tools.cooktimer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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
    val zcr: Float = 0f,
    val flatness: Float = 0f,
    val confidence: Float = 1.0f,
    val samples: List<Float> = emptyList()
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

// ═══════════════════════════════════════════════════════════════════
//  SoundDetector — Audio I/O, lifecycle, and pipeline orchestration
// ═══════════════════════════════════════════════════════════════════

@Singleton
class SoundDetector @Inject constructor(
    @ApplicationContext context: Context,
    private val sfxPlayer: SfxPlayer
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
        ?: error("AudioManager missing")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SoundDetectorState())
    val state: StateFlow<SoundDetectorState> = _state

    @Volatile
    var debugContext: String = "Unknown"

    private val config = DetectionConfig()

    @Volatile private var pipeline: DetectionPipeline? = null
    @Volatile private var running = false
    private var recordJob: Job? = null
    private var litJob: Job? = null
    private var recorder: AudioRecord? = null
    private val consumers = mutableSetOf<String>()

    private val pipelineHost = object : PipelineHost {
        override fun emitDiagnostic(diagnostic: DetectionDiagnostic) {
            this@SoundDetector.emitDiagnostic(diagnostic)
        }
        override fun updateState(updater: (SoundDetectorState) -> SoundDetectorState) {
            _state.update(updater)
        }
        override fun scheduleLitReset() {
            litJob?.cancel()
            litJob = scope.launch {
                delay(100)
                _state.update { it.copy(isLit = false) }
            }
        }
        override val debugContext: String get() = this@SoundDetector.debugContext
    }

    // ── Lifecycle ──

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

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e("SoundDetector", "Error stopping recorder in stop()", e)
        }
        recorder = null
        pipeline?.clearSessionCalibration()
        pipeline = null

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
            current.copy(diagnostics = (listOf(diagnostic) + current.diagnostics).take(config.maxDiagnostics))
        }
    }

    fun onGestureResolvedForCalibration(events: List<SoundDetectionEvent>, tapCount: Int) {
        pipeline?.onGestureResolvedForCalibration(events, tapCount)
    }

    /**
     * Release all resources and cancel the coroutine scope.
     * After calling release(), this SoundDetector instance should not be used again.
     */
    fun release() {
        synchronized(this) {
            consumers.clear()
        }
        stop("Global")
        scope.cancel()
    }

    // ── Recording Loop ──

    private suspend fun runRecorderLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(config.windowSamples * 2)

        val newRecorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
        } catch (e: SecurityException) {
            Log.e("SoundDetector", "Permission denied for audio recording", e)
            running = false
            return
        }
        recorder = newRecorder

        if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            newRecorder.release()
            recorder = null
            return
        }

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

        // Audio buffers
        val hopBuffer = ShortArray(config.hopSamples)
        val analysisBuffer = ShortArray(config.windowSamples)
        val floatBuffer = FloatArray(config.windowSamples)
        var hasPrimedAnalysisWindow = false

        // HPF state: ~2000Hz cutoff at 16kHz (alpha = RC/(RC+dt) ≈ 0.56)
        val hpfAlpha = 0.56f
        var hpfPrevIn = 0f
        var hpfPrevOut = 0f

        // Create detection pipeline
        val detectionPipeline = DetectionPipeline(config, sfxPlayer, audioManager, pipelineHost)
        pipeline = detectionPipeline

        try {
            currentRecorder.startRecording()
            Log.d("SoundDetector", "Recording started. Window: ${config.fftWindowMs}ms Hop: ${config.hopMs}ms")

            while (running) {
                // Check recorder state
                if (currentRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e("SoundDetector", "Recorder stopped recording unexpectedly")
                    currentRecorder.startRecording()
                    delay(100)
                    continue
                }

                // Read audio into sliding window
                if (!hasPrimedAnalysisWindow) {
                    val readPrime = currentRecorder.read(analysisBuffer, 0, analysisBuffer.size)
                    if (readPrime < 0) { Log.e("SoundDetector", "Read error while priming: $readPrime"); delay(50); continue }
                    if (readPrime < analysisBuffer.size) { delay(10); continue }
                    hasPrimedAnalysisWindow = true
                } else {
                    val readHop = currentRecorder.read(hopBuffer, 0, hopBuffer.size)
                    if (readHop < 0) { Log.e("SoundDetector", "Read error: $readHop"); delay(50); continue }
                    if (readHop < config.hopSamples) { delay(10); continue }
                    System.arraycopy(analysisBuffer, config.hopSamples, analysisBuffer, 0, config.windowSamples - config.hopSamples)
                    System.arraycopy(hopBuffer, 0, analysisBuffer, config.windowSamples - config.hopSamples, config.hopSamples)
                }

                // ── Feature Computation ──
                val analysisRead = config.windowSamples

                // DC offset removal
                var dcSum = 0L
                for (i in 0 until analysisRead) dcSum += analysisBuffer[i]
                val dcOffset = (dcSum / analysisRead).toInt()

                var sum = 0.0; var hpfSum = 0.0; var peak = 0.0; var zeroCrossings = 0
                for (i in 0 until analysisRead) {
                    val v = (analysisBuffer[i] - dcOffset).toFloat()
                    floatBuffer[i] = v
                    val absV = abs(v)
                    if (absV > peak) peak = absV.toDouble()
                    sum += v * v

                    val hpfOut = hpfAlpha * (hpfPrevOut + v - hpfPrevIn)
                    hpfPrevIn = v; hpfPrevOut = hpfOut
                    hpfSum += hpfOut * hpfOut

                    if (i > 0) {
                        val prev = floatBuffer[i - 1]
                        if ((prev > 0 && v <= 0) || (prev < 0 && v >= 0)) zeroCrossings++
                    }
                }

                val rmsF = (sqrt(sum / analysisRead) / 32768.0).toFloat()
                val hpfRmsF = (sqrt(hpfSum / analysisRead) / 32768.0).toFloat()
                val peakF = ((peak / 32768.0).coerceAtMost(1.0)).toFloat()
                val zcr = zeroCrossings.toFloat() / analysisRead.coerceAtLeast(1)
                val spectral = SoundUtils.calculateSpectralFeatures(floatBuffer, config.sampleRate)

                val features = FrameFeatures(rmsF, hpfRmsF, peakF, zcr, spectral.centroid, spectral.tilt, spectral.flatness)

                // ── Pipeline Processing ──
                when (detectionPipeline.processFrame(features)) {
                    FrameAction.CONTINUE -> { /* normal */ }
                    FrameAction.RESTART_RECORDER -> {
                        // Restart recorder to escape flatline
                        currentRecorder.stop()
                        currentRecorder.release()
                        val restarted = try {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                config.sampleRate,
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
                            hasPrimedAnalysisWindow = false
                            hpfPrevIn = 0f; hpfPrevOut = 0f
                            detectionPipeline.resetForRecorderRestart()
                            continue
                        } else {
                            running = false
                            aec?.release()
                            return
                        }
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
