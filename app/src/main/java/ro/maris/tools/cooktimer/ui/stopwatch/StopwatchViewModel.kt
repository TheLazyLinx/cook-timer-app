package ro.maris.tools.cooktimer.ui.stopwatch

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Lap
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
import kotlin.math.max

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository,
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
    private val _historyState = MutableStateFlow(loadPersistedHistoryState())
    val historyState: StateFlow<HistoryState> = _historyState

    private val gestureDelegate = SoundGestureDelegate(
        scope = viewModelScope,
        soundDetector = soundDetector,
        settingsRepository = settingsRepository,
        billingRepository = billingRepository,
        sfxPlayer = sfxPlayer,
        vibrationHelper = vibrationHelper,
        engineState = engine.state,
        defaultDoubleTapAction = SoundTriggerAction.TOGGLE_PLAY_PAUSE,
        defaultTripleTapAction = SoundTriggerAction.LAP,
        consumerIdPrefix = "StopwatchViewModel",
        debugContextProvider = { "Stopwatch" },
        onSoundTrigger = ::handleSoundTrigger
    )

    fun onVisibilityChange(visible: Boolean) = gestureDelegate.onVisibilityChange(visible)

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

    init {
        val defaultStopwatchPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = "Stopwatch",
            steps = listOf(
                Step(
                    id = UUID.randomUUID().toString(),
                    title = "Stopwatch",
                    durationSec = null,
                    startMode = StartMode.MANUAL
                )
            ),
            isBuiltIn = false
        )
        engine.loadPreset(defaultStopwatchPreset)
        soundDetector.debugContext = "Stopwatch"
        gestureDelegate.startObserving()
    }

    /**
     * Stopwatch screen: LAP = record lap (or reset when paused).
     * PARSE is a no-op (reserved for future voice/command parsing).
     */
    private fun handleSoundTrigger(action: SoundTriggerAction): Boolean {
        val currentState = state.value ?: return false

        when (action) {
            SoundTriggerAction.NONE -> {
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
            SoundTriggerAction.LAP -> {
                if (currentState.phase == Phase.PAUSED) {
                    reset()
                    vibrationHelper.vibrateTick()
                    return true
                }
                if (!currentState.isStopwatchRunning) return false
                lap()
                return true
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
                // No-op: reserved for future voice/command parsing
                return false
            }
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        gestureDelegate.clear()
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
