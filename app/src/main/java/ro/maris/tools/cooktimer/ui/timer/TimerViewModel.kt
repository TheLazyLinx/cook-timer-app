package ro.maris.tools.cooktimer.ui.timer

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
import ro.maris.tools.cooktimer.service.ActiveTimerManager
import ro.maris.tools.cooktimer.service.toActiveTimerInfoOrNull
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.Step
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import ro.maris.tools.cooktimer.ui.sound.SoundGestureDelegate
import javax.inject.Inject
import java.util.UUID

@HiltViewModel
class TimerViewModel @Inject constructor(
    timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository,
    private val soundDetector: SoundDetector,
    private val vibrationHelper: VibrationHelper,
    private val sfxPlayer: SfxPlayer,
    private val activeTimerManager: ActiveTimerManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val timerId = "timer-${System.identityHashCode(this)}"

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
        consumerIdPrefix = "timer",
        debugContextProvider = { "Timer" },
        onSoundTrigger = ::handleSoundTrigger
    )

    fun onVisibilityChange(visible: Boolean) = gestureDelegate.onVisibilityChange(visible)

    fun setDuration(seconds: Int) {
        val currentPreset = state.value ?: return
        val currentStep = currentPreset.steps.firstOrNull() ?: return
        if (currentStep.durationSec == seconds) return
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
        engine.lap()
        vibrationHelper.vibrateVirtualKey()
    }

    fun markHelpAsShown() {
        viewModelScope.launch {
            settingsRepository.setHasShownSoundHelpOverlay(true)
        }
    }

    init {
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
        gestureDelegate.startObserving()

        viewModelScope.launch {
            engine.state.collectLatest { runState ->
                activeTimerManager.update(timerId, runState?.toActiveTimerInfoOrNull())
            }
        }
    }

    /**
     * Quick Timer screen: PARSE is a no-op (reserved for future voice/command parsing).
     */
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
            SoundTriggerAction.NONE -> return false
            SoundTriggerAction.START_RESUME -> {
                when (currentState.phase) {
                    Phase.READY -> { start(); vibrationHelper.vibrateTick(); return true }
                    Phase.PAUSED -> { resume(); vibrationHelper.vibrateTick(); return true }
                    Phase.RUNNING -> { pause(); vibrationHelper.vibrateTick(); return true }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PAUSE -> {
                if (currentState.phase == Phase.RUNNING) {
                    pause(); vibrationHelper.vibrateTick(); return true
                }
                return false
            }
            SoundTriggerAction.LAP -> { lap(); return true }
            SoundTriggerAction.NEXT_STEP -> { next(); vibrationHelper.vibrateTick(); return true }
            SoundTriggerAction.TOGGLE_PLAY_PAUSE -> {
                when (currentState.phase) {
                    Phase.READY -> { start(); vibrationHelper.vibrateTick(); return true }
                    Phase.RUNNING -> { pause(); vibrationHelper.vibrateTick(); return true }
                    Phase.PAUSED -> { resume(); vibrationHelper.vibrateTick(); return true }
                    Phase.COMPLETED -> return false
                }
            }
            SoundTriggerAction.PARSE -> return false
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        gestureDelegate.clear()
        val stillActive = engine.state.value?.let {
            it.phase == Phase.RUNNING || it.phase == Phase.PAUSED || it.isStopwatchRunning
        } ?: false
        activeTimerManager.unregister(timerId, stillActive)
    }
}
