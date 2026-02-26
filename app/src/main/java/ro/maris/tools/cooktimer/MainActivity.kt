package ro.maris.tools.cooktimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.AndroidEntryPoint
import ro.maris.tools.cooktimer.audio.LocalSfxPlayer
import ro.maris.tools.cooktimer.audio.SfxPlayer
import ro.maris.tools.cooktimer.domain.model.ThemeMode
import ro.maris.tools.cooktimer.domain.repository.SettingsRepository
import ro.maris.tools.cooktimer.ui.MainScreen
import ro.maris.tools.cooktimer.ui.theme.CookTimerTheme
import ro.maris.tools.cooktimer.ui.theme.LocalAccentColor
import ro.maris.tools.cooktimer.ui.theme.SoftRed
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sfxPlayer: SfxPlayer
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            val settings by settingsRepository.observeSettings().collectAsState(initial = null)
            
            val isDarkTheme = when (settings?.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM, null -> systemDark
            }
            
            val accentColor = settings?.accentColorHex
                ?.let { runCatching { Color(it.toLong(16)) }.getOrNull() }
                ?: SoftRed

            CompositionLocalProvider(
                LocalSfxPlayer provides sfxPlayer,
                LocalAccentColor provides accentColor
            ) {
                CookTimerTheme(darkTheme = isDarkTheme) {
                    MainScreen()
                }
            }
        }
    }
}
