package ro.maris.tools.cooktimer.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.engine.TimeProvider
import ro.maris.tools.cooktimer.domain.engine.TimerEngine
import ro.maris.tools.cooktimer.domain.model.RunState
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import javax.inject.Inject

@HiltViewModel
class RunViewModel @Inject constructor(
    timeProvider: TimeProvider,
    private val presetRepository: PresetRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val engine = TimerEngine(
        timeProvider = timeProvider,
        scope = viewModelScope
    )

    val state: StateFlow<RunState?> = engine.state

    private val presetId: String? = savedStateHandle.get<String>("presetId")

    fun start() = engine.start()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun next() = engine.next()
    fun back() = engine.back()
    fun reset() = engine.reset()

    init {
        if (presetId != null) {
            viewModelScope.launch {
                presetRepository.observePreset(presetId).collectLatest { preset ->
                    if (preset != null) {
                        engine.loadPreset(preset)
                    }
                }
            }
        }
    }
}
