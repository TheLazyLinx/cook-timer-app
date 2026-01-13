package ro.maris.tools.cooktimer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ro.maris.tools.cooktimer.ui.edit.EditPresetScreen
import ro.maris.tools.cooktimer.ui.edit.EditPresetViewModel
import ro.maris.tools.cooktimer.ui.presets.PresetsListScreen
import ro.maris.tools.cooktimer.ui.presets.PresetsListViewModel
import ro.maris.tools.cooktimer.ui.run.RunScreen
import ro.maris.tools.cooktimer.ui.run.RunViewModel

object Routes {
    const val PRESETS = "presets"
    const val EDIT = "edit"
    const val RUN = "run"
}

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.PRESETS,
        modifier = modifier
    ) {
        composable(Routes.PRESETS) {
            val vm: PresetsListViewModel = hiltViewModel()
            PresetsListScreen(
                vm = vm,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
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
    }
}
