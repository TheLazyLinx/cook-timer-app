package ro.maris.tools.cooktimer.domain.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.RunState

class TimerEngine(
    private val timeProvider: TimeProvider,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state

    private val tickerMutex = Mutex()
    private var tickerJob: Job? = null

    fun loadPreset(preset: Preset) {
        val now = timeProvider.nowEpochSec()
        _state.value = RunState.initial(preset, now)
        scope.launch { ensureTicker() }
    }

    fun start() {
        val now = timeProvider.nowEpochSec()
        dispatchAction(TimerAction.StartPressed(now), now)
    }

    fun pause() {
        val now = timeProvider.nowEpochSec()
        dispatchAction(TimerAction.PausePressed(now), now)
    }

    fun resume() {
        val now = timeProvider.nowEpochSec()
        dispatchAction(TimerAction.ResumePressed(now), now)
    }
    fun next() = dispatchAction(TimerAction.NextPressed)
    fun back() = dispatchAction(TimerAction.BackPressed)
    fun lap() = dispatchAction(TimerAction.LapPressed)
    fun reset() {
        val now = timeProvider.nowEpochSec()
        dispatchAction(TimerAction.ResetPressed(now), now)
    }

    private fun dispatchAction(action: TimerAction, nowEpochSec: Long = timeProvider.nowEpochSec()) {
        // Atomic read-modify-write: reduce the action, then immediately tick
        _state.update { current ->
            current ?: return
            val reduced = TimerReducer.reduce(current, action)
            TimerReducer.reduce(reduced, TimerAction.Tick(nowEpochSec))
        }
        scope.launch { ensureTicker() }
    }

    private fun tickNow(nowEpochSec: Long) {
        _state.update { current ->
            current ?: return
            TimerReducer.reduce(current, TimerAction.Tick(nowEpochSec))
        }
    }

    private suspend fun ensureTicker() = tickerMutex.withLock {
        val currentState = _state.value

        // Don't start a ticker if there's no state or timer is completed
        if (currentState == null || currentState.phase == Phase.COMPLETED) {
            stopTickerLocked()
            return
        }

        // Check if we actually need the ticker (timer is running or stopwatch is active)
        val needsTicker = currentState.phase == Phase.RUNNING || currentState.isStopwatchRunning
        if (!needsTicker) {
            stopTickerLocked()
            return
        }

        if (tickerJob?.isActive == true) return

        tickerJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                val s = _state.value ?: break

                // Stop the ticker if we've reached a terminal state or no longer need ticking
                if (s.phase == Phase.COMPLETED) break
                if (s.phase != Phase.RUNNING && !s.isStopwatchRunning) break

                tickNow(timeProvider.nowEpochSec())
            }
            // Clear reference when loop exits naturally
            tickerMutex.withLock { tickerJob = null }
        }
    }

    /** Must be called while holding [tickerMutex]. */
    private fun stopTickerLocked() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
