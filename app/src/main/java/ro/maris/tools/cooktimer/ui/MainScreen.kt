package ro.maris.tools.cooktimer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import ro.maris.tools.cooktimer.ui.navigation.AppNavHost
import ro.maris.tools.cooktimer.ui.navigation.Routes

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    
    val items = listOf(
        Screen.Presets,
        Screen.Timer,
        Screen.Stopwatch,
        Screen.Settings,
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    // Only show bottom bar on top-level screens
    val bottomBarVisible = items.any { it.route == currentDestination?.route }

    Scaffold(
        bottomBar = {
            if (bottomBarVisible) {
                NavigationBar {
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Presets : Screen(Routes.PRESETS, "Presets", Icons.Filled.Home)
    object Timer : Screen(Routes.TIMER, "Timer", Icons.Filled.PlayArrow)
    object Stopwatch : Screen(Routes.STOPWATCH, "Stopwatch", Icons.Filled.Info)
    object Settings : Screen(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
}
