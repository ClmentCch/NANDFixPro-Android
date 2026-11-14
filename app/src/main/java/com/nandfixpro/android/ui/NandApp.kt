package com.nandfixpro.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.ui.screens.AboutScreen
import com.nandfixpro.android.ui.screens.AnalyzerScreen
import com.nandfixpro.android.ui.screens.BackupScreen
import com.nandfixpro.android.ui.screens.BootScreen
import com.nandfixpro.android.ui.screens.DiagnosticsScreen
import com.nandfixpro.android.ui.screens.FileBrowserScreen
import com.nandfixpro.android.ui.screens.FirmwareScreen
import com.nandfixpro.android.ui.screens.HomeScreen
import com.nandfixpro.android.ui.screens.LogsScreen
import com.nandfixpro.android.ui.screens.PartitionScreen
import com.nandfixpro.android.ui.screens.ProdinfoScreen
import com.nandfixpro.android.ui.screens.SettingsScreen
import com.nandfixpro.android.ui.screens.UsbScreen

object Routes {
    const val HOME = "home"
    const val USB = "usb"
    const val ANALYZER = "analyzer"
    const val BACKUPS = "backups"
    const val FILES = "files"
    const val DIAGNOSTICS = "diagnostics"
    const val LOGS = "logs"
    const val PRODINFO = "prodinfo"
    const val BOOT = "boot"
    const val PARTITIONS = "partitions"
    const val FIRMWARE = "firmware"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Accueil", Icons.Filled.Home),
    BottomDestination(Routes.USB, "USB", Icons.Filled.Usb),
    BottomDestination(Routes.ANALYZER, "Analyse", Icons.Filled.Search),
    BottomDestination(Routes.BACKUPS, "Sauvegardes", Icons.Filled.Save),
    BottomDestination(Routes.FILES, "Fichiers", Icons.Filled.Folder),
)

@Composable
fun NandApp(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME
    val mainRoutes = remember { bottomDestinations.map { it.route }.toSet() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute in mainRoutes) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) { HomeScreen(viewModel, navController) }
            composable(Routes.USB) { UsbScreen(viewModel) }
            composable(Routes.ANALYZER) { AnalyzerScreen(viewModel, navController) }
            composable(Routes.BACKUPS) { BackupScreen(viewModel) }
            composable(Routes.FILES) { FileBrowserScreen(viewModel, navController) }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.LOGS) {
                LogsScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.PRODINFO) {
                ProdinfoScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.BOOT) {
                BootScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.PARTITIONS) {
                PartitionScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.FIRMWARE) {
                FirmwareScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(viewModel) { navController.popBackStack() }
            }
            composable(Routes.ABOUT) {
                AboutScreen { navController.popBackStack() }
            }
        }
    }
}
