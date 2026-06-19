package cn.wch.ch341pardemo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.wch.ch341pardemo.ui.devices.DevicesScreen
import cn.wch.ch341pardemo.ui.home.HomeScreen
import cn.wch.ch341pardemo.ui.settings.SettingsScreen
import cn.wch.ch341pardemo.ui.terminal.TerminalScreen

sealed class Destinations(
    val route: String,
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector
) {
    data object Home : Destinations(
        "home", "Home",
        Icons.Outlined.Home, Icons.Rounded.Home
    )
    data object Terminal : Destinations(
        "terminal", "Terminal",
        Icons.Outlined.Terminal, Icons.Rounded.Terminal
    )
    data object Devices : Destinations(
        "devices", "Devices",
        Icons.Outlined.Memory, Icons.Rounded.Memory
    )
    data object Settings : Destinations(
        "settings", "Settings",
        Icons.Outlined.Settings, Icons.Rounded.Settings
    )

    companion object {
        val all = listOf(Home, Terminal, Devices, Settings)
    }
}

@Composable
fun CH341App() {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destinations.all.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) dest.filled else dest.outlined,
                                contentDescription = dest.label
                            )
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Destinations.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Destinations.Home.route) { HomeScreen(
                onOpenTerminal = { nav.navigate(Destinations.Terminal.route) },
                onOpenDevices = { nav.navigate(Destinations.Devices.route) },
                onOpenSettings = { nav.navigate(Destinations.Settings.route) }
            ) }
            composable(Destinations.Terminal.route) { TerminalScreen() }
            composable(Destinations.Devices.route) { DevicesScreen() }
            composable(Destinations.Settings.route) { SettingsScreen() }
        }
    }
}
