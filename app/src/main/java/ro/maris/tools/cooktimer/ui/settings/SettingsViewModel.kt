package ro.maris.tools.cooktimer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.model.AppSettings
import ro.maris.tools.cooktimer.domain.model.ThemeMode
import ro.maris.tools.cooktimer.domain.repository.BillingRepository
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val billingRepository: BillingRepository,
    private val soundDetector: SoundDetector
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.observeSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    val detectorState = soundDetector.state

    val isPremium: StateFlow<Boolean> = billingRepository.entitlement
        .map { it.isPremium }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun setSoundEffectsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSoundEffectsEnabled(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticsEnabled(enabled)
        }
    }

    fun setSoundDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSoundDetectionEnabled(enabled)
        }
    }

    fun setDoubleTapAction(action: ro.maris.tools.cooktimer.domain.model.SoundTriggerAction) {
        viewModelScope.launch {
            settingsRepository.setDoubleTapAction(action)
        }
    }

    fun setTripleTapAction(action: ro.maris.tools.cooktimer.domain.model.SoundTriggerAction) {
        viewModelScope.launch {
            settingsRepository.setTripleTapAction(action)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setSegmentedDisplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSegmentedDisplayEnabled(enabled)
        }
    }

    fun setAccentColorHex(hex: String) {
        viewModelScope.launch {
            settingsRepository.setAccentColorHex(hex)
        }
    }

    fun startPremiumPurchase(activity: android.app.Activity?) {
        if (activity == null) return
        billingRepository.launchPurchaseFlow(activity)
    }

    fun startDetection(): Boolean {
        soundDetector.debugContext = "SETTINGS"
        return soundDetector.start()
    }
}
