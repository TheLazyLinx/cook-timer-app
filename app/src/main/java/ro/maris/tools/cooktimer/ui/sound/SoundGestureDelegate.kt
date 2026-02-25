package ro.maris.tools.cooktimer.ui.sound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SfxId
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetectionEvent
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.haptics.VibrationHelper
import java.util.UUID

/**
 * Shared delegate that encapsulates all sound-gesture detection wiring common
 * to [RunViewModel], [TimerViewModel] and [StopwatchViewModel].
 *
 * Each ViewModel provides its own [onSoundTrigger] callback for screen-specific
 * action handling (e.g.  PARSE ➜ next step in RunVM, no-op in TimerVM).
 */
class SoundGestureDelegate(
    private val scope: CoroutineScope,
    private val soundDetector: SoundDetector,
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository,
    private val sfxPlayer: SfxPlayer,
    private val vibrationHelper: VibrationHelper,
    private val engineState: StateFlow<RunState?>,
    defaultDoubleTapAction: SoundTriggerAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
    defaultTripleTapAction: SoundTriggerAction = SoundTriggerAction.PARSE,
    consumerIdPrefix: String,
    private val debugContextProvider: () -> String,
    private val onSoundTrigger: (SoundTriggerAction) -> Boolean
) {

    // ---- internal state ----

    private var lastRejectionReason: String? = null
    private var lastHandledDetectionCount = 0

    val consumerId = "$consumerIdPrefix-${UUID.randomUUID()}"
    private val _isScreenVisible = MutableStateFlow(false)
    val isScreenVisible: StateFlow<Boolean> = _isScreenVisible

    private data class GestureMapping(
        val doubleTapAction: SoundTriggerAction,
        val tripleTapAction: SoundTriggerAction
    )

    private val defaultGestureMapping = GestureMapping(
        doubleTapAction = defaultDoubleTapAction,
        tripleTapAction = defaultTripleTapAction
    )

    private val gestureMapping = MutableStateFlow(defaultGestureMapping)

    private val tapRecognizer = SoundTapGestureRecognizer(scope) { tapCount ->
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
            tapCount.tapCount >= 3 -> onSoundTrigger(gestureMapping.value.tripleTapAction)
            tapCount.tapCount >= 2 -> onSoundTrigger(gestureMapping.value.doubleTapAction)
            else -> false
        }
        if (handled) {
            sfxPlayer.play(SfxId.DetectionSuccess)
        }
    }

    // ---- public API ----

    fun onVisibilityChange(visible: Boolean) {
        _isScreenVisible.value = visible
        if (!visible) {
            tapRecognizer.clear()
            lastHandledDetectionCount = soundDetector.state.value.count
        }
    }

    /** Call from ViewModel.onCleared(). */
    fun clear() {
        tapRecognizer.clear()
        soundDetector.stop(consumerId)
    }

    /**
     * Starts all observation coroutines.  Call once from the ViewModel `init` block.
     */
    fun startObserving() {
        observeDetectionStartStop()
        observeGestureMapping()
        observeDetectionEvents()
        observeStateChangesForHaptics()
    }

    // ---- observation wiring ----

    private fun observeDetectionStartStop() {
        scope.launch {
            combine(
                settingsRepository.observeSettings(),
                _isScreenVisible
            ) { settings, visible ->
                Pair(settings, visible)
            }.collectLatest { (settings, visible) ->
                if (settings.soundDetectionEnabled && visible) {
                    soundDetector.start(consumerId)
                    soundDetector.debugContext = debugContextProvider()
                } else {
                    soundDetector.stop(consumerId)
                }
            }
        }
    }

    private fun observeGestureMapping() {
        scope.launch {
            combine(
                settingsRepository.observeSettings(),
                billingRepository.entitlement
            ) { settings, entitlement ->
                if (entitlement.isPremium) {
                    GestureMapping(
                        doubleTapAction = settings.doubleTapAction,
                        tripleTapAction = settings.tripleTapAction
                    )
                } else {
                    defaultGestureMapping
                }
            }.collectLatest { mapping ->
                gestureMapping.value = mapping
            }
        }
    }

    private fun observeDetectionEvents() {
        scope.launch {
            combine(
                settingsRepository.observeSettings(),
                soundDetector.state,
                _isScreenVisible
            ) { settings, detectorState, isVisible ->
                Triple(settings, detectorState, isVisible)
            }.collectLatest { (settings, detectorState, isVisible) ->
                // Play failure sound if rejection reason changed
                if (detectorState.lastRejectionReason != null &&
                    detectorState.lastRejectionReason != lastRejectionReason
                ) {
                    if (isVisible && settings.soundDetectionEnabled) {
                        sfxPlayer.play(SfxId.DetectionFailed)
                    }
                }
                lastRejectionReason = detectorState.lastRejectionReason
                if (detectorState.count < lastHandledDetectionCount) {
                    lastHandledDetectionCount = detectorState.count
                }

                // Handle all newly accepted detections
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
    }

    private fun observeStateChangesForHaptics() {
        scope.launch {
            var lastState: RunState? = null
            engineState.collect { currentState ->
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

    // ---- gesture filtering ----

    companion object {
        // Unified confidence floor for all impulse families (Snap/Clap/Slap).
        // Using a single threshold prevents classification jitter between
        // near-identical profiles from breaking tap grouping.
        private const val MIN_IMPULSE_CONFIDENCE = 0.82f

        fun isActionableFreeGestureEvent(event: SoundDetectionEvent): Boolean {
            if (!isFreeGestureProfile(event.type)) return false
            if (event.type == "Snap (Low)") return false
            if (event.confidence < MIN_IMPULSE_CONFIDENCE) return false

            val family = event.type.substringBefore(" (").trim()
            return when (family) {
                "Snap" -> {
                    // Reject only events that are clearly non-impulsive:
                    // very low centroid AND strongly negative tilt together
                    // indicate a dull thud rather than a snap/clap.
                    !(event.centroid < 2800f && event.tilt <= -0.45f)
                }
                "Clap", "Slap" -> {
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

        fun isFreeGestureProfile(type: String): Boolean {
            return when (type.substringBefore(" (").trim()) {
                "Snap", "Clap", "Slap" -> true
                else -> false
            }
        }
    }
}
