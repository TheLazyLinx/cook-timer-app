package ro.maris.tools.cooktimer.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.StartMode
import ro.maris.tools.cooktimer.domain.model.Step
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditPresetViewModel @Inject constructor(
    private val repository: PresetRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val presetId: String? = savedStateHandle.get<String>("presetId")
    private val _state = MutableStateFlow(EditPresetState())
    val state: StateFlow<EditPresetState> = _state.asStateFlow()

    init {
        if (presetId != null) {
            viewModelScope.launch {
                repository.observePreset(presetId).collectLatest { preset ->
                    if (preset != null) {
                        _state.value = EditPresetState(
                            presetId = preset.id,
                            name = preset.name,
                            isBuiltIn = preset.isBuiltIn,
                            steps = preset.steps.map { it.toEditItem() }
                        )
                    }
                }
            }
        }
    }

    fun updateName(value: String) {
        _state.value = _state.value.copy(name = value, warningMessage = null)
    }

    fun addStep() {
        val steps = _state.value.steps + EditStepItem(
            id = UUID.randomUUID().toString(),
            title = "",
            durationEnabled = false,
            durationSec = "",
            startMode = StartMode.MANUAL,
            notes = ""
        )
        _state.value = _state.value.copy(steps = steps, warningMessage = null)
    }

    fun updateStepTitle(stepId: String, value: String) {
        updateStep(stepId) { it.copy(title = value) }
    }

    fun updateStepNotes(stepId: String, value: String) {
        updateStep(stepId) { it.copy(notes = value) }
    }

    fun toggleDuration(stepId: String, enabled: Boolean) {
        updateStep(stepId) {
            val duration = if (enabled) it.durationSec else ""
            it.copy(durationEnabled = enabled, durationSec = duration)
        }
    }

    fun updateDuration(stepId: String, value: String) {
        updateStep(stepId) { it.copy(durationSec = value) }
    }

    fun updateStartMode(stepId: String, startMode: StartMode) {
        updateStep(stepId) { it.copy(startMode = startMode) }
    }

    fun moveStepUp(stepId: String) {
        val steps = _state.value.steps.toMutableList()
        val index = steps.indexOfFirst { it.id == stepId }
        if (index > 0) {
            val temp = steps[index - 1]
            steps[index - 1] = steps[index]
            steps[index] = temp
            _state.value = _state.value.copy(steps = steps, warningMessage = null)
        }
    }

    fun moveStepDown(stepId: String) {
        val steps = _state.value.steps.toMutableList()
        val index = steps.indexOfFirst { it.id == stepId }
        if (index in 0 until steps.lastIndex) {
            val temp = steps[index + 1]
            steps[index + 1] = steps[index]
            steps[index] = temp
            _state.value = _state.value.copy(steps = steps, warningMessage = null)
        }
    }

    fun removeStep(stepId: String) {
        val steps = _state.value.steps.filterNot { it.id == stepId }
        _state.value = _state.value.copy(steps = steps, warningMessage = null)
    }

    fun save(onSaved: () -> Unit) {
        val validation = validate(_state.value)
        if (validation != null) {
            _state.value = _state.value.copy(warningMessage = validation)
            return
        }
        viewModelScope.launch {
            repository.savePreset(_state.value.toDomainPreset())
            onSaved()
        }
    }

    private fun validate(state: EditPresetState): String? {
        if (state.name.isBlank()) return "Preset name is required"
        if (state.steps.isEmpty()) return "Add at least one step"
        state.steps.forEachIndexed { index, step ->
            if (step.title.isBlank()) return "Step ${index + 1} title is required"
            if (step.durationEnabled) {
                val value = step.durationSec.toIntOrNull()
                if (value == null || value <= 0) {
                    return "Step ${index + 1} duration must be > 0"
                }
            }
        }
        return null
    }

    private fun updateStep(stepId: String, transform: (EditStepItem) -> EditStepItem) {
        val steps = _state.value.steps.map { step ->
            if (step.id == stepId) transform(step) else step
        }
        _state.value = _state.value.copy(steps = steps, warningMessage = null)
    }

    private fun Step.toEditItem(): EditStepItem {
        return EditStepItem(
            id = id,
            title = title,
            durationEnabled = durationSec != null,
            durationSec = durationSec?.toString().orEmpty(),
            startMode = startMode,
            notes = notes.orEmpty()
        )
    }
}

data class EditPresetState(
    val presetId: String? = null,
    val name: String = "",
    val isBuiltIn: Boolean = false,
    val steps: List<EditStepItem> = emptyList(),
    val warningMessage: String? = null
) {
    fun toDomainPreset(): Preset {
        val id = presetId ?: UUID.randomUUID().toString()
        return Preset(
            id = id,
            name = name.trim(),
            isBuiltIn = isBuiltIn,
            steps = steps.map { it.toDomain() }
        )
    }
}

data class EditStepItem(
    val id: String,
    val title: String,
    val durationEnabled: Boolean,
    val durationSec: String,
    val startMode: StartMode,
    val notes: String
) {
    fun toDomain(): Step {
        val duration = if (durationEnabled) durationSec.toIntOrNull() else null
        return Step(
            id = id,
            title = title.trim(),
            durationSec = duration,
            startMode = startMode,
            notes = notes.trim().ifBlank { null }
        )
    }
}
