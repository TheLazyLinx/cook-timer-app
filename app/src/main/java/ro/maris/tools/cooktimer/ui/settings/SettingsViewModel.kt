package ro.maris.tools.cooktimer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.maris.tools.cooktimer.audio.SoundDetector
import ro.maris.tools.cooktimer.domain.model.AppSettings
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.model.ThemeMode
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val soundDetector: SoundDetector
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.observeSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    val detectorState = soundDetector.state

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

    fun setSoundTriggerAction(action: SoundTriggerAction) {
        viewModelScope.launch {
            settingsRepository.setSoundTriggerAction(action)
        }
    }

    enum class SoundTriggerType {
        SNAP, SLAP, KNOCK, ALL
    }

    private val profilesByTriggerType = mapOf(
        SoundTriggerType.SNAP to setOf("Snap (High)", "Snap (Mid)", "Snap (Low)"),
        SoundTriggerType.SLAP to setOf("Slap (Airy)", "Slap", "Slap (Wet)"),
        SoundTriggerType.KNOCK to setOf("Knock"),
        SoundTriggerType.ALL to setOf("Snap (High)", "Snap (Mid)", "Snap (Low)", "Slap (Airy)", "Slap", "Slap (Wet)", "Knock")
    )

    fun getSoundTriggerType(): SoundTriggerType {
        val current = settings.value.allowedSoundProfiles
        if (current.containsAll(profilesByTriggerType[SoundTriggerType.ALL]!!)) return SoundTriggerType.ALL
        if (current.containsAll(profilesByTriggerType[SoundTriggerType.SNAP]!!)) return SoundTriggerType.SNAP
        if (current.containsAll(profilesByTriggerType[SoundTriggerType.SLAP]!!)) return SoundTriggerType.SLAP
        if (current.containsAll(profilesByTriggerType[SoundTriggerType.KNOCK]!!)) return SoundTriggerType.KNOCK
        return SoundTriggerType.ALL
    }

    fun setSoundTriggerType(type: SoundTriggerType) {
        viewModelScope.launch {
            settingsRepository.setAllowedSoundProfiles(profilesByTriggerType[type] ?: emptySet())
        }
    }

    fun toggleAllowedProfile(profile: String) {
        viewModelScope.launch {
            val current = settings.value.allowedSoundProfiles.toMutableSet()
            if (current.contains(profile)) {
                current.remove(profile)
            } else {
                current.add(profile)
            }
            settingsRepository.setAllowedSoundProfiles(current)
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

    fun startDetection(): Boolean {
        soundDetector.debugContext = "SETTINGS"
        return soundDetector.start()
    }
}
