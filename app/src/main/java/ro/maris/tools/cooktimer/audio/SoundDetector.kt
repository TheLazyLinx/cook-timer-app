package ro.maris.tools.cooktimer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class SoundDetectionEvent(
    val timestampMs: Long,
    val strength: Float,
    val peak: Float
)

data class SoundDetectorState(
    val isLit: Boolean = false,
    val count: Int = 0,
    val lastStrength: Float = 0f,
    val lastPeak: Float = 0f,
    val rms: Float = 0f,
    val noiseFloor: Float = 0f,
    val currentRms: Float = 0f,
    val events: List<SoundDetectionEvent> = emptyList()
)

@Singleton
class SoundDetector @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SoundDetectorState())
    val state: StateFlow<SoundDetectorState> = _state

    private val sampleRate = 16000
    private val windowMs = 100
    private val windowSamples = sampleRate * windowMs / 1000
    private val minAbsRms = 0.02f
    private val peakRatio = 2.0f
    private val thresholdMultiplier = 3.5f
    private val noiseAlpha = 0.05f
    private val maxEvents = 20

    private var running = false
    private var recordJob: Job? = null
    private var litJob: Job? = null

    fun start(): Boolean {
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

    private suspend fun runRecorderLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(windowSamples * 2)

        var recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            recorder.release()
            return
        }
        val buffer = ShortArray(windowSamples)
        var noiseFloor = 0.005f
        var flatlineCount = 0
        val maxFlatlineBeforeRestart = 30 // ~3 seconds at 100ms windows
        recorder.startRecording()
        Log.d("SoundDetector", "Recording started, buffer size: ${buffer.size}, minBuffer: $minBuffer")

        while (running) {
            // Check if recorder is still in a good state
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("SoundDetector", "Recorder stopped recording unexpectedly, state: ${recorder.recordingState}")
                recorder.startRecording()
                delay(100)
                continue
            }

            val read = recorder.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.e("SoundDetector", "Read error: $read")
                delay(50)
                continue
            }
            if (read == 0) {
                Log.w("SoundDetector", "Read 0 samples")
                delay(10)
                continue
            }
            var sum = 0.0
            var peak = 0.0
            for (i in 0 until read) {
                val v = buffer[i].toInt()
                val abs = kotlin.math.abs(v)
                if (abs > peak) peak = abs.toDouble()
                sum += v.toDouble() * v.toDouble()
            }
            val rms = sqrt(sum / read) / 32768.0
            val peakNorm = (peak / 32768.0).coerceAtMost(1.0)
            val rmsF = rms.toFloat()
            
            // Detect flatline (all zeros or extremely low values)
            if (rmsF < 0.0001f && peakNorm < 0.0001) {
                flatlineCount++
                if (flatlineCount >= maxFlatlineBeforeRestart) {
                    Log.w("SoundDetector", "Flatline detected for $flatlineCount frames, restarting recorder")
                    recorder.stop()
                    recorder.release()
                    
                    // Create a new recorder
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer
                    )
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.startRecording()
                        flatlineCount = 0
                        Log.d("SoundDetector", "Recorder restarted successfully")
                    } else {
                        recorder.release()
                        Log.e("SoundDetector", "Failed to reinitialize recorder")
                        running = false
                        return
                    }
                    continue
                }
            } else {
                flatlineCount = 0
            }
            val ratio = if (rmsF > 0f) (peakNorm / rmsF).toFloat() else 0f
            val threshold = (noiseFloor * thresholdMultiplier).coerceAtLeast(minAbsRms)
            val detected = rmsF > threshold && ratio >= peakRatio

            Log.d("SoundDetector", "RMS: ${"%.4f".format(rmsF)}, Peak: ${"%.4f".format(peakNorm)}, Threshold: ${"%.4f".format(threshold)}, Detected: $detected")

            if (!detected) {
                noiseFloor = (noiseFloor * (1f - noiseAlpha)) + (rmsF * noiseAlpha)
            } else {
                val event = SoundDetectionEvent(
                    timestampMs = SystemClock.elapsedRealtime(),
                    strength = rmsF,
                    peak = peakNorm.toFloat()
                )
                _state.update { current ->
                    val updated = (listOf(event) + current.events).take(maxEvents)
                    current.copy(
                        isLit = true,
                        count = current.count + 1,
                        lastStrength = rmsF,
                        lastPeak = peakNorm.toFloat(),
                        rms = rmsF,
                        noiseFloor = noiseFloor,
                        currentRms = rmsF,
                        events = updated
                    )
                }
                litJob?.cancel()
                litJob = scope.launch {
                    delay(windowMs.toLong())
                    _state.update { it.copy(isLit = false) }
                }
            }

            _state.update { current ->
                if (detected) current else current.copy(rms = rmsF, noiseFloor = noiseFloor, currentRms = rmsF)
            }
        }

        Log.d("SoundDetector", "Recording loop ended")
        recorder.stop()
        recorder.release()
    }
}
