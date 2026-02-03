package ro.maris.tools.cooktimer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import ro.maris.tools.cooktimer.ui.timer.TimerScreen
import ro.maris.tools.cooktimer.ui.timer.TimerViewModel
import ro.maris.tools.cooktimer.ui.stopwatch.StopwatchScreen
import ro.maris.tools.cooktimer.ui.stopwatch.StopwatchViewModel
import ro.maris.tools.cooktimer.ui.edit.EditPresetScreen
import ro.maris.tools.cooktimer.ui.edit.EditPresetViewModel
import ro.maris.tools.cooktimer.ui.presets.PresetsListScreen
import ro.maris.tools.cooktimer.ui.presets.PresetsListViewModel
import ro.maris.tools.cooktimer.ui.run.RunScreen
import ro.maris.tools.cooktimer.ui.run.RunViewModel
import ro.maris.tools.cooktimer.ui.settings.SettingsScreen
import ro.maris.tools.cooktimer.ui.settings.SettingsViewModel
import ro.maris.tools.cooktimer.ui.sound.SoundTestScreen
import ro.maris.tools.cooktimer.ui.sound.SoundTestViewModel
import ro.maris.tools.cooktimer.ui.soundwave.SoundwaveScreen
import ro.maris.tools.cooktimer.ui.soundwave.SoundwaveViewModel

object Routes {
    const val PRESETS = "presets"
    const val TIMER = "timer"
    const val STOPWATCH = "stopwatch"
    const val EDIT = "edit"
    const val RUN = "run"
    const val SOUND_TEST = "sound_test"
    const val SETTINGS = "settings"
    const val SOUNDWAVE = "soundwave"
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.PRESETS,
        modifier = modifier
    ) {
        composable(Routes.PRESETS) {
            val vm: PresetsListViewModel = hiltViewModel()
            PresetsListScreen(
                vm = vm,
                // onSettings logic might be removed from PresetsListScreen UI or redirected
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onRun = { presetId ->
                    navController.navigate("${Routes.RUN}/$presetId") {
                        launchSingleTop = true
                    }
                },
                onEdit = { presetId ->
                    if (presetId == null) {
                        navController.navigate(Routes.EDIT) {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate("${Routes.EDIT}/$presetId") {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(
            route = "${Routes.EDIT}/{presetId}",
            arguments = listOf(navArgument("presetId") { type = NavType.StringType })
        ) { entry ->
            val vm: EditPresetViewModel = hiltViewModel()
            EditPresetScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(Routes.EDIT) {
            val vm: EditPresetViewModel = hiltViewModel()
            EditPresetScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = "${Routes.RUN}/{presetId}",
            arguments = listOf(navArgument("presetId") { type = NavType.StringType })
        ) {
            val vm: RunViewModel = hiltViewModel()
            RunScreen(
                vm = vm,
                onBack = {
                    navController.navigate(Routes.PRESETS) {
                        popUpTo(Routes.PRESETS) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.SOUND_TEST) {
            val vm: SoundTestViewModel = hiltViewModel()
            SoundTestScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = hiltViewModel()
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onSoundwave = { navController.navigate(Routes.SOUNDWAVE) }
            )
        }
        composable(Routes.SOUNDWAVE) {
            val vm: SoundwaveViewModel = hiltViewModel()
            SoundwaveScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.TIMER) {
            val vm: TimerViewModel = hiltViewModel()
            TimerScreen(vm = vm)
        }
        composable(Routes.STOPWATCH) {
            val vm: StopwatchViewModel = hiltViewModel()
            StopwatchScreen(vm = vm)
        }
    }
}

