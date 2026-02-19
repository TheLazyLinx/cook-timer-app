package ro.maris.tools.cooktimer.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.domain.model.Preset
import ro.maris.tools.cooktimer.domain.model.Step
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.PresetRepository
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PresetsListViewModel @Inject constructor(
    private val repository: PresetRepository,
    private val billingRepository: BillingRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PresetsUiState())
    val state: StateFlow<PresetsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedBuiltInsIfEmpty()
        }
        viewModelScope.launch {
            combine(
                repository.observePresets(),
                billingRepository.entitlement
            ) { presets, entitlement ->
                val builtIns = presets.filter { it.isBuiltIn }.sortedBy { it.name }
                val custom = presets.filterNot { it.isBuiltIn }.sortedBy { it.name }
                val isPremium = entitlement.isPremium
                PresetsUiState(
                    builtIns = builtIns,
                    customPresets = custom,
                    isCustomLimitReached = !isPremium && custom.isNotEmpty(),
                    showUpgradeHint = !isPremium && _state.value.showUpgradeHint,
                    isPremium = isPremium
                )
            }.collect { _state.value = it }
        }
    }

    fun onNewPresetClick(onNavigate: () -> Unit) {
        if (_state.value.isCustomLimitReached) {
            _state.value = _state.value.copy(showUpgradeHint = true)
            return
        }
        onNavigate()
    }

    fun startPremiumPurchase(activity: android.app.Activity?) {
        if (activity == null) return
        billingRepository.launchPurchaseFlow(activity)
    }

    fun dismissUpgradeHint() {
        _state.value = _state.value.copy(showUpgradeHint = false)
    }

    fun duplicatePreset(
        source: Preset,
        replaceExisting: Boolean,
        onCompleted: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (replaceExisting) {
                _state.value.customPresets.forEach { preset ->
                    repository.deletePreset(preset.id)
                }
            }
            val duplicate = source.toDuplicate()
            repository.savePreset(duplicate)
            onCompleted(duplicate.id)
        }
    }

    fun deletePreset(preset: Preset) {
        viewModelScope.launch {
            repository.deletePreset(preset.id)
        }
    }

    private fun Preset.toDuplicate(): Preset {
        fun newId() = UUID.randomUUID().toString()
        val newSteps = steps.map { it.copy(id = newId()) }
        return Preset(
            id = newId(),
            name = "${name} Copy",
            steps = newSteps,
            isBuiltIn = false
        )
    }
}

data class PresetsUiState(
    val builtIns: List<Preset> = emptyList(),
    val customPresets: List<Preset> = emptyList(),
    val isCustomLimitReached: Boolean = false,
    val showUpgradeHint: Boolean = false,
    val isPremium: Boolean = false
)
