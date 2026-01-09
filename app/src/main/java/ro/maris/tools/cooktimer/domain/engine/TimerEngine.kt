package ro.maris.tools.cooktimer.domain.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.model.Phase
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.RunState

class TimerEngine(
    private val timeProvider: TimeProvider,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state

    private var tickerJob: Job? = null

    fun loadPreset(preset: Preset) {
        val now = timeProvider.nowEpochSec()
        _state.value = RunState.initial(preset, now)
        ensureTicker()
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
    fun reset() {
        val now = timeProvider.nowEpochSec()
        dispatchAction(TimerAction.ResetPressed(now), now)
    }

    private fun dispatchAction(action: TimerAction, nowEpochSec: Long = timeProvider.nowEpochSec()) {
        val current = _state.value ?: return

        val reduced = TimerReducer.reduce(current, action)
        _state.value = reduced

        tickNow(nowEpochSec)
        ensureTicker()
    }

    private fun tickNow(nowEpochSec: Long) {
        val current = _state.value ?: return
        _state.value = TimerReducer.reduce(current, TimerAction.Tick(nowEpochSec))
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return

        tickerJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                val s = _state.value ?: continue
                if (s.phase == Phase.COMPLETED) continue
                tickNow(timeProvider.nowEpochSec())
            }
        }
    }
}
