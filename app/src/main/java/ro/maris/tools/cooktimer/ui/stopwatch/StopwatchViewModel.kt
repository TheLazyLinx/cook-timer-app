package ro.maris.tools.cooktimer.ui.stopwatch

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
import ro.maris.tools.cooktimer.domain.model.Lap
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
import kotlin.math.max

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val soundDetector: SoundDetector,
    private val vibrationHelper: VibrationHelper,
    private val sfxPlayer: SfxPlayer,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    enum class HistorySlot {
        RECENT,
        SAVED
    }

    data class SessionHistory(
        val totalElapsedSec: Int,
        val laps: List<Lap>
    )

    data class HistoryState(
        val histories: Map<HistorySlot, SessionHistory?>
    ) {
        val recent: SessionHistory? get() = histories[HistorySlot.RECENT]
        val saved: SessionHistory? get() = histories[HistorySlot.SAVED]
    }

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
    private val _historyState = MutableStateFlow(loadPersistedHistoryState())
    val historyState: StateFlow<HistoryState> = _historyState

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

    fun reset() {
        val snapshot = buildCurrentSessionHistorySnapshot()
        if (snapshot != null) {
            setHistory(HistorySlot.RECENT, snapshot)
        } else {
            clearHistory(HistorySlot.RECENT)
        }
        engine.reset()
    }

    fun saveRecentHistory() {
        val recent = _historyState.value.recent ?: return
        setHistory(HistorySlot.SAVED, recent)
    }

    fun clearSavedHistory() {
        clearHistory(HistorySlot.SAVED)
    }

    fun lap() {
        vibrationHelper.vibrateVirtualKey()
        engine.lap()
    }

    fun markHelpAsShown() {
        viewModelScope.launch {
            settingsRepository.setHasShownSoundHelpOverlay(true)
        }
    }

    private val consumerId = "StopwatchViewModel-${UUID.randomUUID()}"

    init {
        // Initialize with default Stopwatch Preset (duration = null)
        val defaultStopwatchPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = "Stopwatch",
            steps = listOf(
                Step(
                    id = UUID.randomUUID().toString(),
                    title = "Stopwatch",
                    durationSec = null, // Infinite duration (gate step / stopwatch)
                    startMode = StartMode.MANUAL
                )
            ),
            isBuiltIn = false
        )
        engine.loadPreset(defaultStopwatchPreset)
        soundDetector.debugContext = "Stopwatch"

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
                    soundDetector.debugContext = "Stopwatch"
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

        when (action) {
            SoundTriggerAction.NONE -> {
                // Do nothing
                return false
            }
            SoundTriggerAction.START_RESUME -> {
                if (currentState.isStopwatchRunning) {
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
                if (currentState.phase == Phase.RUNNING || currentState.isStopwatchRunning) {
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
                if (currentState.isStopwatchRunning) {
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
                // Future integration for Voice/Command parsing
                return false
            }
        }
        return false
    }

    private fun handleTripleTap(): Boolean {
        val currentState = state.value ?: return false
        val canLap = currentState.isStopwatchRunning || currentState.phase == Phase.PAUSED
        if (!canLap) return false
        engine.lap()
        vibrationHelper.vibrateTick()
        return true
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

    private fun buildCurrentSessionHistorySnapshot(): SessionHistory? {
        val currentState = state.value ?: return null
        val step = currentState.activeStep ?: return null
        if (step.durationSec != null) return null

        val totalElapsedSec = computeCurrentElapsedSeconds(currentState)
        if (totalElapsedSec <= 0 && currentState.laps.isEmpty()) return null

        return SessionHistory(
            totalElapsedSec = totalElapsedSec,
            laps = currentState.laps
        )
    }

    private fun computeCurrentElapsedSeconds(state: RunState): Int {
        val startedAt = state.stopwatchStartedAtEpochSec
        val runningElapsed = if (startedAt != null) {
            (timeProvider.nowEpochSec() - startedAt).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val computed = state.stopwatchAccumulatedSec + runningElapsed
        val stateElapsed = state.stopwatchElapsedSec ?: 0
        return max(computed, stateElapsed)
    }

    private fun loadPersistedHistoryState(): HistoryState {
        val persisted = HistorySlot.values().associateWith { slot ->
            loadPersistedHistory(slot)
        }
        return HistoryState(histories = persisted)
    }

    private fun loadPersistedHistory(slot: HistorySlot): SessionHistory? {
        val totalElapsedSec = savedStateHandle.get<Int>(historyTotalKey(slot)) ?: return null
        val rawLaps = savedStateHandle.get<String>(historyLapsKey(slot)).orEmpty()
        return SessionHistory(
            totalElapsedSec = totalElapsedSec,
            laps = decodeLaps(rawLaps)
        )
    }

    private fun setHistory(slot: HistorySlot, history: SessionHistory) {
        val updated = _historyState.value.histories.toMutableMap()
        updated[slot] = history
        _historyState.value = HistoryState(updated)
        savedStateHandle[historyTotalKey(slot)] = history.totalElapsedSec
        savedStateHandle[historyLapsKey(slot)] = encodeLaps(history.laps)
    }

    private fun clearHistory(slot: HistorySlot) {
        val updated = _historyState.value.histories.toMutableMap()
        updated[slot] = null
        _historyState.value = HistoryState(updated)
        savedStateHandle.remove<Int>(historyTotalKey(slot))
        savedStateHandle.remove<String>(historyLapsKey(slot))
    }

    private fun historyTotalKey(slot: HistorySlot): String {
        return "stopwatch.history.${slot.name.lowercase()}.totalElapsedSec"
    }

    private fun historyLapsKey(slot: HistorySlot): String {
        return "stopwatch.history.${slot.name.lowercase()}.laps"
    }

    private fun encodeLaps(laps: List<Lap>): String {
        return laps.joinToString(separator = ";") { lap ->
            "${lap.index},${lap.splitTimeSec},${lap.lapTimeSec}"
        }
    }

    private fun decodeLaps(raw: String): List<Lap> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size != 3) return@mapNotNull null
            val index = parts[0].toIntOrNull() ?: return@mapNotNull null
            val splitTimeSec = parts[1].toIntOrNull() ?: return@mapNotNull null
            val lapTimeSec = parts[2].toIntOrNull() ?: return@mapNotNull null
            Lap(
                index = index,
                splitTimeSec = splitTimeSec,
                lapTimeSec = lapTimeSec
            )
        }
    }
}
