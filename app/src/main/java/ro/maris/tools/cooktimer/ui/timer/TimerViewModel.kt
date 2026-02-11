package ro.maris.tools.cooktimer.ui.timer

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
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.Step
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import ro.maris.tools.cooktimer.ui.sound.SoundTapGestureRecognizer
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class TimerViewModel @Inject constructor(
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

    // Removed presetId dependency

    private val tapRecognizer = SoundTapGestureRecognizer(viewModelScope) { tapCount ->
        tapCount.tapTimestamps.forEachIndexed { index, timestampMs ->
            soundDetector.annotateGestureForEvent(
                eventTimestampMs = timestampMs,
                groupId = tapCount.groupId,
                tapIndex = index + 1,
                tapCount = tapCount.tapCount,
                unpairedReason = tapCount.unpairedReason
            )
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

    fun setDuration(seconds: Int) {
        val currentPreset = state.value ?: return
        val currentStep = currentPreset.steps.firstOrNull() ?: return
        if (currentStep.durationSec == seconds) return
        // Create a new preset with updated duration
        val newStep = currentStep.copy(durationSec = seconds)
        val newPreset = Preset(
            id = currentPreset.presetId,
            name = currentPreset.presetName,
            steps = listOf(newStep),
            isBuiltIn = false
        )
        engine.loadPreset(newPreset)
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

    private val consumerId = "TimerViewModel-${UUID.randomUUID()}"

    init {
        // Initialize with default Timer Preset (00:00:00)
        val defaultTimerPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = "Timer",
            steps = listOf(
                Step(
                    id = UUID.randomUUID().toString(),
                    title = "Timer",
                    durationSec = 0,
                    startMode = StartMode.MANUAL
                )
            ),
            isBuiltIn = false
        )
        engine.loadPreset(defaultTimerPreset)
        soundDetector.debugContext = "Timer"

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
                    soundDetector.debugContext = "Timer"
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
                                    tapRecognizer.onTap(event.timestampMs)
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
        val hasPositiveDuration = (currentState.activeStep?.durationSec ?: 0) > 0
        val shouldBlockStartAtZero = currentState.phase == Phase.READY &&
            !currentState.stepCompleted &&
            (action == SoundTriggerAction.START_RESUME || action == SoundTriggerAction.TOGGLE_PLAY_PAUSE)

        if (shouldBlockStartAtZero && !hasPositiveDuration) {
            return false
        }

        when (action) {
            SoundTriggerAction.NONE -> {
                // Do nothing
                return false
            }
            SoundTriggerAction.START_RESUME -> {
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
                if (currentState.phase == Phase.RUNNING) {
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
                // Future integration for Voice/Command parsing
                return false
            }
        }
        return false
    }

    private fun handleTripleTap(): Boolean {
        // Timer has no triple-tap command.
        return false
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
