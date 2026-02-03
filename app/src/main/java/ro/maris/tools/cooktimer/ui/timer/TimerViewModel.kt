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

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state
    val detectorState = soundDetector.state
    private val _isScreenVisible = MutableStateFlow(false)

    // Removed presetId dependency

    private var lastDetectionTimestamp = 0L
    private val minDetectionIntervalMs = 1000L

    fun onVisibilityChange(visible: Boolean) {
        _isScreenVisible.value = visible
    }

    fun setDuration(seconds: Int) {
        val currentPreset = state.value ?: return
        // Create a new preset with updated duration
        val newStep = currentPreset.steps.first().copy(durationSec = seconds)
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

    private val consumerId = "TimerViewModel-${UUID.randomUUID()}"

    init {
        // Initialize with default Timer Preset (5 minutes)
        val defaultTimerPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = "Timer",
            steps = listOf(
                Step(
                    id = UUID.randomUUID().toString(),
                    title = "Timer",
                    durationSec = 300, // 5 minutes
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

                // Handle sound triggers
                if (isVisible && 
                    settings.soundDetectionEnabled && 
                    settings.soundTriggerAction != SoundTriggerAction.NONE &&
                    detectorState.isLit) {
                    
                    val lastEvent = detectorState.events.firstOrNull()
                    val type = lastEvent?.type ?: "Unknown"

                    if (settings.allowedSoundProfiles.contains(type)) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDetectionTimestamp >= minDetectionIntervalMs) {
                            lastDetectionTimestamp = currentTime
                            sfxPlayer.play(SfxId.DetectionSuccess)
                            handleSoundTrigger(settings.soundTriggerAction)
                        }
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

    private fun handleSoundTrigger(action: SoundTriggerAction) {
        vibrationHelper.vibrateTick()
        val currentState = state.value ?: return

        when (action) {
            SoundTriggerAction.NONE -> {
                // Do nothing
            }
            SoundTriggerAction.START_RESUME -> {
                when (currentState.phase) {
                    Phase.READY -> start()
                    Phase.PAUSED -> resume()
                    Phase.RUNNING -> { /* Already running */ }
                    Phase.COMPLETED -> { /* Do nothing */ }
                }
            }
            SoundTriggerAction.PAUSE -> {
                if (currentState.phase == Phase.RUNNING) {
                    pause()
                }
            }
            SoundTriggerAction.NEXT_STEP -> {
                next()
            }
            SoundTriggerAction.TOGGLE_PLAY_PAUSE -> {
                when (currentState.phase) {
                    Phase.READY -> start()
                    Phase.RUNNING -> pause()
                    Phase.PAUSED -> resume()
                    Phase.COMPLETED -> { /* Do nothing */ }
                }
            }
            SoundTriggerAction.PARSE -> {
                // Future integration for Voice/Command parsing
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        soundDetector.stop(consumerId)
    }
}
