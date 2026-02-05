package ro.maris.tools.cooktimer.domain.repository

import kotlinx.coroutines.flow.Flow
import ro.maris.tools.cooktimer.domain.model.AppSettings
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.model.ThemeMode

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun setSoundEffectsEnabled(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSoundDetectionEnabled(enabled: Boolean)
    suspend fun setSoundTriggerAction(action: SoundTriggerAction)
    suspend fun setAllowedSoundProfiles(profiles: Set<String>)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setHasShownSoundHelpOverlay(hasShown: Boolean)
}
