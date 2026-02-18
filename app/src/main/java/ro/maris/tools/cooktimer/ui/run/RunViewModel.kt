package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import ro.maris.tools.cooktimer.ui.sound.SoundTapGestureRecognizer
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RunViewModel @Inject constructor(
    timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val soundDetector: SoundDetector,
    private val vibrationHelper: VibrationHelper,
    private val sfxPlayer: SfxPlayer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var lastRejectionReason: String? = null
    private var lastHandledDetectionCount = 0

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state
    val detectorState = soundDetector.state
    val settings = settingsRepository.observeSettings()
    private val _isScreenVisible = MutableStateFlow(false)

    private val presetId: String? = savedStateHandle.get<String>("presetId")

    private val tapRecognizer = SoundTapGestureRecognizer(viewModelScope) { tapCount ->
        tapCount.taps.forEachIndexed { index, event ->
            soundDetector.annotateGestureForEvent(
                eventTimestampMs = event.timestampMs,
                groupId = tapCount.groupId,
                tapIndex = index + 1,
                tapCount = tapCount.tapCount,
                unpairedReason = tapCount.unpairedReason
            )
        }
        if (tapCount.tapCount >= 2) {
            soundDetector.onGestureResolvedForCalibration(tapCount.taps, tapCount.tapCount)
        }
        val handled = when {
            tapCount.tapCount >= 3 -> handleTripleTap()
            tapCount.tapCount >= 2 -> handleSoundTrigger(SoundTriggerAction.TOGGLE_PLAY_PAUSE)
            else -> false
        }
        if (handled) {
            sfxPlayer.play(SfxId.DetectionSuccess)
        }
    }

    fun onVisibilityChange(visible: Boolean) {
        _isScreenVisible.value = visible
        if (!visible) {
            tapRecognizer.clear()
            lastHandledDetectionCount = soundDetector.state.value.count
        }
    }

    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun next() = engine.next()
    fun back() = engine.back()
    fun reset() = engine.reset()

    fun lap() {
        vibrationHelper.vibrateVirtualKey()
        // Wiring for future lap logic
    }

    fun markHelpAsShown() {
        viewModelScope.launch {
            settingsRepository.setHasShownSoundHelpOverlay(true)
        }
    }

    private val consumerId = "RunViewModel-${UUID.randomUUID()}"

    init {
        if (presetId != null) {
            viewModelScope.launch {
                presetRepository.observePreset(presetId).collectLatest { preset ->
                    if (preset != null) {
                        engine.loadPreset(preset)
                        soundDetector.debugContext = preset.name
                    }
                }
            }
        }

        // Monitor settings to start/stop detection efficiently
        viewModelScope.launch {
            combine(
                settingsRepository.observeSettings(),
                _isScreenVisible
            ) { settings, visible ->
                Pair(settings, visible)
            }.collectLatest { (settings, visible) ->
                if (settings.soundDetectionEnabled && visible) {
                    soundDetector.start(consumerId)
                    val presetName = state.value?.presetName ?: "Run"
                    soundDetector.debugContext = presetName
                } else {
                    soundDetector.stop(consumerId)
                }
            }
        }

        // Observe events and feedback
        viewModelScope.launch {
            combine(
                settingsRepository.observeSettings(),
                soundDetector.state,
                _isScreenVisible
            ) { settings, detectorState, isVisible ->
                Triple(settings, detectorState, isVisible)
            }.collectLatest { (settings, detectorState, isVisible) ->                
                // Play failure sound if rejection reason changed
                if (detectorState.lastRejectionReason != null &&
                    detectorState.lastRejectionReason != lastRejectionReason) {
                    if (isVisible && settings.soundDetectionEnabled) {
                        sfxPlayer.play(SfxId.DetectionFailed)
                    }
                }
                lastRejectionReason = detectorState.lastRejectionReason
                if (detectorState.count < lastHandledDetectionCount) {
                    lastHandledDetectionCount = detectorState.count
                }

                // Handle all newly accepted detections, not just the latest one.
                if (isVisible && settings.soundDetectionEnabled) {
                    val newCount = (detectorState.count - lastHandledDetectionCount).coerceAtLeast(0)
                    if (newCount > 0) {
                        detectorState.events
                            .take(newCount)
                            .asReversed()
                            .forEach { event ->
                                if (isActionableFreeGestureEvent(event)) {
                                    tapRecognizer.onTap(event)
                                }
                            }
                        lastHandledDetectionCount = detectorState.count
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastState: RunState? = null
            engine.state.collect { currentState ->
                if (lastState != null && currentState != null) {
                    val phaseChanged = currentState.phase != lastState?.phase
                    val stepChanged = currentState.activeStepIndex != lastState?.activeStepIndex
                    if (phaseChanged || stepChanged) {
                        vibrationHelper.vibrateConfirm()
                    }
                }
                lastState = currentState
            }
        }
    }

    private fun handleSoundTrigger(action: SoundTriggerAction): Boolean {
        val currentState = state.value ?: return false
        val runningStopwatchStep = currentState.isStopwatchRunning && currentState.activeStep?.durationSec == null

        when (action) {
            SoundTriggerAction.NONE -> {
                // Do nothing
                return false
            }
            SoundTriggerAction.START_RESUME -> {
                if (runningStopwatchStep) {
                    pause()
                    vibrationHelper.vibrateTick()
                    return true
                }
                when (currentState.phase) {
                    Phase.READY -> {
                        start()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    Phase.PAUSED -> {
                        resume()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    // Backward-compatibility: old START_RESUME configs should still
                    // honor the newer double-tap toggle behavior while running.
                    Phase.RUNNING -> {
                        pause()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PAUSE -> {
                if (currentState.phase == Phase.RUNNING || runningStopwatchStep) {
                    pause()
                    vibrationHelper.vibrateTick()
                    return true
                }
                return false
            }
            SoundTriggerAction.NEXT_STEP -> {
                next()
                vibrationHelper.vibrateTick()
                return true
            }
            SoundTriggerAction.TOGGLE_PLAY_PAUSE -> {
                if (runningStopwatchStep) {
                    pause()
                    vibrationHelper.vibrateTick()
                    return true
                }
                when (currentState.phase) {
                    Phase.READY -> {
                        start()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    Phase.RUNNING -> {
                        pause()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    Phase.PAUSED -> {
                        resume()
                        vibrationHelper.vibrateTick()
                        return true
                    }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PARSE -> {
                if (currentState.phase == Phase.COMPLETED) return false
                next()
                vibrationHelper.vibrateTick()
                return true
            }
        }
        return false
    }

    private fun handleTripleTap(): Boolean {
        return handleSoundTrigger(SoundTriggerAction.PARSE)
    }

    private fun isActionableFreeGestureEvent(event: SoundDetectionEvent): Boolean {
        if (!isFreeGestureProfile(event.type)) return false
        val family = event.type.substringBefore(" (").trim()
        return when (family) {
            "Snap" -> {
                if (event.type == "Snap (Low)") return false
                if (event.confidence < 0.84f) return false
                !(event.centroid < 3300f && event.tilt <= -0.22f)
            }
            "Clap", "Slap" -> {
                if (event.confidence < 0.82f) return false
                val obviousObjectContact =
                    event.durationMs <= 55 &&
                        event.centroid <= 2900f &&
                        event.tilt <= -0.55f
                val lowBandThudLike =
                    event.centroid < 2400f &&
                        event.tilt <= -0.80f
                if (obviousObjectContact || lowBandThudLike) return false
                true
            }
            else -> false
        }
    }

    private fun isFreeGestureProfile(type: String): Boolean {
        return when (type.substringBefore(" (").trim()) {
            "Snap", "Clap", "Slap" -> true
            else -> false
        }
    }

    override fun onCleared() {
        super.onCleared()
        tapRecognizer.clear()
        soundDetector.stop(consumerId)
    }
}
