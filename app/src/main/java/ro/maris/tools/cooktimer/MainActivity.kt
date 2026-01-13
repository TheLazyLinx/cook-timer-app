package ro.maris.tools.cooktimer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import ro.maris.tools.cooktimer.ui.navigation.AppNavHost
import ro.maris.tools.cooktimer.ui.theme.CookTimerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var userDarkOverride by remember { mutableStateOf<Boolean?>(null) }
            val isDarkTheme = userDarkOverride ?: systemDark
            CookTimerTheme(darkTheme = isDarkTheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavHost(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            userDarkOverride = !(userDarkOverride ?: systemDark)
                        }
                    )
                }
            }
        }
    }
}
