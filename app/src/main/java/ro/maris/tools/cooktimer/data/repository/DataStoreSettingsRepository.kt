package ro.maris.tools.cooktimer.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ro.maris.tools.cooktimer.domain.model.AppSettings
import ro.maris.tools.cooktimer.domain.model.SoundTriggerAction
import ro.maris.tools.cooktimer.domain.model.ThemeMode
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val SOUND_EFFECTS_ENABLED = booleanPreferencesKey("sound_effects_enabled")
        val SOUND_DETECTION_ENABLED = booleanPreferencesKey("sound_detection_enabled")
        val SOUND_TRIGGER_ACTION = stringPreferencesKey("sound_trigger_action")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    override fun observeSettings(): Flow<AppSettings> {
        return context.dataStore.data.map { preferences ->
            AppSettings(
                soundEffectsEnabled = preferences[Keys.SOUND_EFFECTS_ENABLED] ?: true,
                soundDetectionEnabled = preferences[Keys.SOUND_DETECTION_ENABLED] ?: false,
                soundTriggerAction = preferences[Keys.SOUND_TRIGGER_ACTION]
                    ?.let { runCatching { SoundTriggerAction.valueOf(it) }.getOrNull() }
                    ?: SoundTriggerAction.TOGGLE_PLAY_PAUSE,
                themeMode = preferences[Keys.THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM
            )
        }
    }

    override suspend fun getSettings(): AppSettings {
        return observeSettings().first()
    }

    override suspend fun setSoundEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SOUND_EFFECTS_ENABLED] = enabled
        }
    }

    override suspend fun setSoundDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SOUND_DETECTION_ENABLED] = enabled
        }
    }

    override suspend fun setSoundTriggerAction(action: SoundTriggerAction) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SOUND_TRIGGER_ACTION] = action.name
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }
}
