package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerAction
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.model.Step
import java.util.UUID

class RunViewModel : ViewModel() {

    private val engine = TimerEngine(
        timeProvider = TimeProvider { System.currentTimeMillis() / 1000L },
        scope = viewModelScope
    )

    val state: StateFlow<ro.maris.tools.cooktimer.domain.model.RunState?> = engine.state

    init {
        engine.loadPreset(samplePreset())
    }

    fun start() = engine.dispatch(TimerAction.StartPressed)
    fun pause() = engine.dispatch(TimerAction.PausePressed)
    fun resume() = engine.dispatch(TimerAction.ResumePressed)
    fun next() = engine.dispatch(TimerAction.NextPressed)
    fun back() = engine.dispatch(TimerAction.BackPressed)

    private fun samplePreset(): Preset {
        fun id() = UUID.randomUUID().toString()
        return Preset(
            id = id(),
            name = "Boil (sample)",
            isBuiltIn = true,
            steps = listOf(
                Step(id(), "Wait for boil", null, StartMode.MANUAL, "Start when water begins boiling"),
                Step(id(), "Boil", 60, StartMode.AUTO, "Keep rolling boil"),
                Step(id(), "Rest", 20, StartMode.AUTO, "Take off heat and wait")
            )
        )
    }
}
