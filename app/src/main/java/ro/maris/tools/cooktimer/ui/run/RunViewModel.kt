package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.model.Step
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RunViewModel @Inject constructor(
    timeProvider: TimeProvider
) : ViewModel() {

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state

    init {
        engine.loadPreset(samplePreset())
    }

    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun next() = engine.next()
    fun back() = engine.back()
    fun reset() = engine.reset()

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
