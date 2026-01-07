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

    fun dispatch(action: TimerAction) {
        val current = _state.value ?: return
        val now = timeProvider.nowEpochSec()

        // Special handling: Start/Resume should set startedAtEpochSec to now on first tick.
        val reduced = TimerReducer.reduce(current, action)
        _state.value = reduced

        // Drive a tick immediately after actions to apply timeProvider time.
        tickNow(now)
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
