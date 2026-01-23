package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class RunViewModel @Inject constructor(
    timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val soundDetector: SoundDetector,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state

    private val presetId: String? = savedStateHandle.get<String>("presetId")

    private var lastDetectionTimestamp = 0L
    private val minDetectionIntervalMs = 1000L

    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun next() = engine.next()
    fun back() = engine.back()
    fun reset() = engine.reset()

    init {
        if (presetId != null) {
            viewModelScope.launch {
                presetRepository.observePreset(presetId).collectLatest { preset ->
                    if (preset != null) {
                        engine.loadPreset(preset)
                    }
                }
            }
        }

        // Observe settings and sound detection
        viewModelScope.launch {
            combine(
                settingsRepository.observeSettings(),
                soundDetector.state
            ) { settings, detectorState ->
                Pair(settings, detectorState)
            }.collectLatest { (settings, detectorState) ->
                // Start/stop sound detection based on settings
                if (settings.soundDetectionEnabled) {
                    soundDetector.start()
                } else {
                    soundDetector.stop()
                }

                // Handle sound triggers
                if (settings.soundDetectionEnabled && 
                    settings.soundTriggerAction != SoundTriggerAction.NONE &&
                    detectorState.isLit) {
                    
                    val lastEvent = detectorState.events.firstOrNull()
                    val type = lastEvent?.type ?: "Unknown"

                    if (settings.allowedSoundProfiles.contains(type)) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDetectionTimestamp >= minDetectionIntervalMs) {
                            lastDetectionTimestamp = currentTime
                            handleSoundTrigger(settings.soundTriggerAction)
                        }
                    }
                }
            }
        }
    }

    private fun handleSoundTrigger(action: SoundTriggerAction) {
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
        soundDetector.stop()
    }
}
