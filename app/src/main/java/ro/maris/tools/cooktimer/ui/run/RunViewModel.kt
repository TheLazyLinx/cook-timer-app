package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
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

    /**
     * Preset Run screen: PARSE = advance to the next step.
     */
    private fun handleSoundTrigger(action: SoundTriggerAction): Boolean {
        val currentState = state.value ?: return false
        val runningStopwatchStep = currentState.isStopwatchRunning && currentState.activeStep?.durationSec == null

        when (action) {
            SoundTriggerAction.NONE -> {
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
            SoundTriggerAction.LAP -> {
                lap()
                return true
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

    override fun onCleared() {
        super.onCleared()
        gestureDelegate.clear()
    }
}
