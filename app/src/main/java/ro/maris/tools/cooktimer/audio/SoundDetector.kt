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
    val rms: Float = 0f,
    val noiseFloor: Float = 0f,
    val currentRms: Float = 0f,
    val lastRejectionReason: String? = null,
    val debugInfo: String = "",
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
    private val confidenceThreshold = 0.78f // Raised from 0.75 to 0.78 to cut marginal Speech FPs (like the 76% one)
    private val rangeTolerance = 1.6f // Tightened from 1.8 to force stricter adherence to profile shapes
    
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
        
        // 3. SNAP LOW - Killer settings
        SoundProfile("Snap (Low)", minDurMs = 45, maxDurMs = 80, minZcr = 0.08f, maxZcr = 0.5f, minCentroid = 800f, maxCentroid = 2500f, minTilt = -10f), 
        
        // 4. SLAP (AIRY) - The culprit for speech FPs
        // Raised minDur (10->45ms) so short clicks/snaps don't match this.
        // Raised minZcr (0.2->0.4) because speech "bla" is rarely 40% noise, but air friction is.
        SoundProfile("Slap (Airy)", minDurMs = 50, maxDurMs = 200, minZcr = 0.40f, maxZcr = 0.90f, minCentroid = 3000f, maxCentroid = 10000f, minTilt = -8f),

        // 5. SLAP STANDARD
        SoundProfile("Slap", minDurMs = 25, maxDurMs = 200, minZcr = 0.05f, maxZcr = 0.60f, minCentroid = 1000f, maxCentroid = 5000f, minTilt = -10f),
        
        // 6. SLAP WET (Bass)
        SoundProfile("Slap (Wet)", minDurMs = 30, maxDurMs = 180, minZcr = 0.04f, maxZcr = 0.40f, minCentroid = 400f, maxCentroid = 1500f, minTilt = -18f), 
        
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
        
        if (value >= min && value <= max) {
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

    fun stop() {
        running = false
        recordJob?.cancel()
        recordJob = null
        litJob?.cancel()
        litJob = null
        _state.update { SoundDetectorState() }
    }

    fun clearHistory() {
        _state.update { it.copy(events = emptyList(), count = 0, lastRejectionReason = null, debugInfo = "") }
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
        
        var aec: AcousticEchoCanceler? = null
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(recorder.audioSessionId)
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
        var preEventNoiseAvg = 0f
        var consecutiveLoudFrames = 0
        
        recorder.startRecording()
        Log.d("SoundDetector", "Recording started. Window: ${windowMs}ms")

        while (running) {
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("SoundDetector", "Recorder stopped recording unexpectedly")
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
            val floatBuffer = FloatArray(read)
            
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
            val spectralFeatures = SoundUtils.calculateSpectralFeatures(floatBuffer, sampleRate)
            val centroid = spectralFeatures.centroid
            val tilt = spectralFeatures.tilt

            // Flatline detection
            if (rmsF < 0.0001f && peakNorm < 0.0001) {
                flatlineCount++
                if (flatlineCount >= maxFlatlineBeforeRestart) {
                    Log.w("SoundDetector", "Flatline detected, restarting recorder")
                    recorder.stop() 
                    recorder.release()
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer
                    )
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.startRecording()
                        aec?.release()
                        try {
                            if (AcousticEchoCanceler.isAvailable()) {
                                aec = AcousticEchoCanceler.create(recorder.audioSessionId)
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
                    continue
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
                        if (ratio > 0.5f) {
                            Log.d("SoundDetector", "Rejected: Sustained/Speech (Ratio $ratio at ${eventHighRmsFrames*10}ms)")
                            isMonitoringEvent = false
                        }
                    }

                    // Peak Late Check (Groan Rejection)
                    // If the peak happened > 60ms into the event, it's a swell/crescendo (impulse peaking is instant).
                    // Exception: Very long events might have multiple peaks, but we assume impulse snaps peak early.
                    val framesAtPeak = eventHighRmsFrames - framesSincePeak
                    if (framesAtPeak > 6 && rmsF > threshold * 2.0f) { // Only strict on loud sounds
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
                            Log.d("SoundDetector", "Rejected: Bouncy signal (multiple peaks)")
                            isMonitoringEvent = false
                        }
                    }

                    // Duration Check (Fail if too long)
                    val currentDurationMs = eventHighRmsFrames * windowMs
                    // Use the longest possible profile as the absolute max to stop checking early
                    if (currentDurationMs > 300) { 
                        Log.d("SoundDetector", "Rejected: Too long ($currentDurationMs ms)")
                        isMonitoringEvent = false // Reset
                    }
                    
                    // Decay Check (Fail if tail is too loud after peak)
                    val decayCheckFrame = 6 // 60ms after peak
                    if (framesSincePeak == decayCheckFrame) {
                        val ratio = if (eventPeakRms > 0) rmsF / eventPeakRms else 0f
                        if (ratio > 0.2f) { // Tail > 20% of peak
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
                    
                    // Profile Matching (Confidence Based)
                    for (profile in profiles) {
                        // Calculate score for each metric (0.0 - 1.0)
                        val sDur = getMetricScore(durationMs.toFloat(), profile.minDurMs.toFloat(), profile.maxDurMs.toFloat())
                        val sZcr = getMetricScore(avgZcr, profile.minZcr, profile.maxZcr)
                        val sCentroid = getMetricScore(avgCentroid, profile.minCentroid, profile.maxCentroid)
                        val sTilt = getMetricScore(avgTilt, profile.minTilt, profile.maxTilt)
                        
                        // Weighted Average (Equal weights for now)
                        val confidence = (sDur + sZcr + sCentroid + sTilt) / 4f
                        
                        if (confidence > bestConfidence) {
                            bestConfidence = confidence
                            bestMatch = profile
                        }
                    }
                    
                    var isValid = bestMatch != null && bestConfidence >= confidenceThreshold
                    var rejectionReason: String? = null
                    
                    if (!isValid) { 
                         val topMatchName = bestMatch?.name ?: "None"
                         val topScore = (bestConfidence * 100).toInt()
                         rejectionReason = "Low Confidence: $topMatchName ($topScore%) D:${durationMs}ms Z:${"%.2f".format(avgZcr)} C:${"%.0f".format(avgCentroid)}"
                         Log.d("SoundDetector", "Rejected: $rejectionReason")
                    }
                    
                    // 3. Pre-Event Isolation Check (Global for all profiles)
                    if (isValid) {
                        // SANITY CHECK: "Digital Silence" or "Wake Up" Artifacts
                        // If the sound PRIOR to the event was absolute zero (0.0), it means the microphone
                        // wasn't delivering data (lag/mute/emulator bug). A real room is never 0.0.
                        // We reject this because we can't measure isolation against 0.0.
                        if (preEventNoiseAvg < 0.00001f) {
                             rejectionReason = "Artifact: Wake-up pop (Pre-noise is digital silence)"
                             Log.d("SoundDetector", "Rejected: $rejectionReason")
                             isValid = false
                        } else {
                            val preEventRatio = if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f
                            
                            // Dynamic Isolation Threshold
                            // Smooth transition: Quiet sounds tolerate 0.6 noise ratio. Loud sounds (>0.2) tighten to 0.25.
                            // This prevents the "Dead Zone" where a medium snap fails because it's slightly too loud for the easy check.
                            val allowedRatio = (0.65f - (eventPeakRms * 2.5f)).coerceIn(0.25f, 0.65f)
                            
                            if (preEventRatio > allowedRatio) {
                                rejectionReason = "Isolation/PreNoise: ${"%.2f".format(preEventRatio)} > ${"%.2f".format(allowedRatio)}"
                                Log.d("SoundDetector", "Rejected: $rejectionReason")
                                isValid = false
                            }
                        }
                    }

                    // Log Data for Analysis
                    val typeLog = if (isValid && bestMatch != null) "DETECTED_${bestMatch.name.replace(" ", "_").uppercase()}" else "REJECTED"
                    Log.i("SoundAnalysis", "$typeLog,${"%.2f".format(bestConfidence)},$durationMs,${"%.4f".format(eventPeakRms)},${"%.4f".format(eventPeakRaw)},${"%.2f".format(avgZcr)},${"%.0f".format(avgCentroid)},${"%.2f".format(avgTilt)},${"%.3f".format(if (eventPeakRms > 0) preEventNoiseAvg / eventPeakRms else 1f)}|$rejectionReason")

                    if (isValid && bestMatch != null) {
                        // CONFIRMED DETECTION
                        Log.i("SoundDetector", "Detected ${bestMatch.name} (${(bestConfidence*100).toInt()}%)! Dur:$durationMs ms")
                        val event = SoundDetectionEvent(
                            timestampMs = eventStartTimestamp,
                            durationMs = durationMs,
                            strength = eventPeakRms,
                            peak = eventPeakRaw,
                            type = bestMatch.name,
                            centroid = avgCentroid,
                            tilt = avgTilt,
                            confidence = bestConfidence,
                            samples = recentSamples.toList()
                        )
                        _state.update { current ->
                            val updated = (listOf(event) + current.events).take(maxEvents)
                            current.copy(
                                isLit = true,
                                count = current.count + 1,
                                lastStrength = eventPeakRms,
                                lastPeak = eventPeakRaw,
                                rms = rmsF,
                                noiseFloor = noiseFloor,
                                currentRms = rmsF,
                                lastRejectionReason = null, 
                                debugInfo = "${bestMatch.name} ${(bestConfidence*100).toInt()}%",
                                events = updated
                            )
                        }
                        litJob?.cancel()
                        litJob = scope.launch {
                            delay(100) // Flash light for 100ms
                            _state.update { it.copy(isLit = false) }
                        }
                    } else if (rejectionReason != null) {
                         // Update state with rejection reason to help debugging
                        _state.update { current ->
                            current.copy(
                                lastRejectionReason = rejectionReason,
                                debugInfo = "Last Fail: $rejectionReason"
                            )
                        }
                    }
                    
                    isMonitoringEvent = false
                }
            } else {
                // IDLE - Check for trigger
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

            _state.update { current ->
                current.copy(rms = rmsF, noiseFloor = noiseFloor, currentRms = rmsF)
            }
        }

        Log.d("SoundDetector", "Recording loop ended")
        recorder.stop()
        recorder.release()
    }
}
