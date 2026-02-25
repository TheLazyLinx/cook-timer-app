package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import ro.maris.tools.cooktimer.ui.sound.SoundGestureDelegate
import javax.inject.Inject

@HiltViewModel
class RunViewModel @Inject constructor(
    timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository,
    private val soundDetector: SoundDetector,
    private val vibrationHelper: VibrationHelper,
    private val sfxPlayer: SfxPlayer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state
    val detectorState = soundDetector.state
    val settings = settingsRepository.observeSettings()
    val isPremium: StateFlow<Boolean> = billingRepository.entitlement
        .map { it.isPremium }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val presetId: String? = savedStateHandle.get<String>("presetId")

    private val gestureDelegate = SoundGestureDelegate(
        scope = viewModelScope,
        soundDetector = soundDetector,
        settingsRepository = settingsRepository,
        billingRepository = billingRepository,
        sfxPlayer = sfxPlayer,
        vibrationHelper = vibrationHelper,
        engineState = engine.state,
        defaultDoubleTapAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
        defaultTripleTapAction = SoundTriggerAction.PARSE,
        consumerIdPrefix = "run",
        debugContextProvider = { state.value?.presetName ?: "Run" },
        onSoundTrigger = ::handleSoundTrigger
    )

    fun onVisibilityChange(visible: Boolean) = gestureDelegate.onVisibilityChange(visible)

    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun next() = engine.next()
    fun back() = engine.back()
    fun reset() = engine.reset()

    fun lap() {
        vibrationHelper.vibrateVirtualKey()
    }

    fun markHelpAsShown() {
        viewModelScope.launch {
            settingsRepository.setHasShownSoundHelpOverlay(true)
        }
    }

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
        gestureDelegate.startObserving()
    }

    /**
     * Preset Run screen: PARSE = advance to the next step.
     */
    private fun handleSoundTrigger(action: SoundTriggerAction): Boolean {
        val currentState = state.value ?: return false
        val runningStopwatchStep = currentState.isStopwatchRunning && currentState.activeStep?.durationSec == null

        when (action) {
            SoundTriggerAction.NONE -> return false
            SoundTriggerAction.START_RESUME -> {
                if (runningStopwatchStep) { pause(); vibrationHelper.vibrateTick(); return true }
                when (currentState.phase) {
                    Phase.READY -> { start(); vibrationHelper.vibrateTick(); return true }
                    Phase.PAUSED -> { resume(); vibrationHelper.vibrateTick(); return true }
                    Phase.RUNNING -> { pause(); vibrationHelper.vibrateTick(); return true }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PAUSE -> {
                if (currentState.phase == Phase.RUNNING || runningStopwatchStep) {
                    pause(); vibrationHelper.vibrateTick(); return true
                }
                return false
            }
            SoundTriggerAction.LAP -> { lap(); return true }
            SoundTriggerAction.NEXT_STEP -> { next(); vibrationHelper.vibrateTick(); return true }
            SoundTriggerAction.TOGGLE_PLAY_PAUSE -> {
                if (runningStopwatchStep) { pause(); vibrationHelper.vibrateTick(); return true }
                when (currentState.phase) {
                    Phase.READY -> { start(); vibrationHelper.vibrateTick(); return true }
                    Phase.RUNNING -> { pause(); vibrationHelper.vibrateTick(); return true }
                    Phase.PAUSED -> { resume(); vibrationHelper.vibrateTick(); return true }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PARSE -> {
                if (currentState.phase == Phase.COMPLETED) return false
                next(); vibrationHelper.vibrateTick(); return true
            }
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        gestureDelegate.clear()
    }
}
