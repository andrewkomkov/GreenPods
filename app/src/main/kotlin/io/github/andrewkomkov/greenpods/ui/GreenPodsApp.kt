package io.github.andrewkomkov.greenpods.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.feature.controls.ControlsScreen
import io.github.andrewkomkov.greenpods.feature.controls.ControlsViewModel
import io.github.andrewkomkov.greenpods.feature.pods.HeartRateScreen
import io.github.andrewkomkov.greenpods.feature.pods.HeartRateUi
import io.github.andrewkomkov.greenpods.feature.pods.PodsScreen
import io.github.andrewkomkov.greenpods.feature.pods.PodsViewModel
import io.github.andrewkomkov.greenpods.feature.settings.SettingsScreen
import io.github.andrewkomkov.greenpods.feature.settings.SettingsViewModel

/** The three places the app can be. */
enum class GreenPodsDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    PODS("pods", "Pods", Icons.Filled.Headphones),
    CONTROLS("controls", "Controls", Icons.Filled.Tune),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * The app shell.
 *
 * Three destinations rather than one scrolling screen, because the three answer
 * different questions — what is nearby, what can I change, and why can I not change
 * more — and mixing them is what makes a gated feature look like a bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenPodsApp(
    onRequestPermission: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenBluetoothSettings: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    // The heart-rate view is pushed on top of the pods tab rather than being a fourth
    // one: it is the card the user just tapped, made bigger, and a tab would imply a
    // place they can go without a reading to look at.
    val onHeartRate = current?.hierarchy?.any { it.route == HEART_RATE_ROUTE } == true

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            onHeartRate -> {
                                "Heart rate"
                            }

                            else -> {
                                GreenPodsDestination.entries
                                    .firstOrNull { destination ->
                                        current?.hierarchy?.any { it.route == destination.route } == true
                                    }?.let { if (it == GreenPodsDestination.PODS) "GreenPods" else it.label }
                                    ?: "GreenPods"
                            }
                        },
                    )
                },
                navigationIcon = {
                    if (onHeartRate) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                GreenPodsDestination.entries.forEach { destination ->
                    val selected = current?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep one entry per destination so Back always leaves the
                                // app from the start destination rather than replaying tabs.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = GreenPodsDestination.PODS.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(GreenPodsDestination.PODS.route) {
                val viewModel: PodsViewModel = viewModel(factory = GreenPodsViewModels.pods())
                val state by viewModel.state.collectAsStateWithLifecycle()
                PodsScreen(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onRetryScan = viewModel::retryScan,
                    onPodSelected = { pod ->
                        viewModel.probeTransports(pod.address)
                        navController.navigate(GreenPodsDestination.CONTROLS.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenHeartRate = {
                        navController.navigate(HEART_RATE_ROUTE) { launchSingleTop = true }
                    },
                    onTurnOnHeartRate = { viewModel.setHeartRateEnabled(true) },
                )
            }

            composable(HEART_RATE_ROUTE) {
                val viewModel: PodsViewModel = viewModel(factory = GreenPodsViewModels.pods())
                val state by viewModel.state.collectAsStateWithLifecycle()
                val pod = state.pods.firstOrNull()

                HeartRateScreen(
                    ui = pod?.let(state::heartRateOf) ?: HeartRateUi.of(HeartRateState.Off),
                    intervalMillis = state.heartRateIntervalMillis,
                    onStop = { viewModel.setHeartRateEnabled(false) },
                    onStart = { viewModel.setHeartRateEnabled(true) },
                )
            }

            composable(GreenPodsDestination.CONTROLS.route) {
                val viewModel: ControlsViewModel = viewModel(factory = GreenPodsViewModels.controls())
                val state by viewModel.state.collectAsStateWithLifecycle()
                ControlsScreen(
                    state = state,
                    onProbe = { viewModel.probe(force = true) },
                    onModeSelected = viewModel::selectMode,
                    onAdaptiveStrengthChanged = viewModel::setAdaptiveStrength,
                    onAdaptiveStrengthCommitted = viewModel::commitAdaptiveStrength,
                    onConversationalAwarenessChanged = viewModel::setConversationalAwareness,
                    onCycleModeToggled = viewModel::toggleCycleMode,
                )
            }

            composable(GreenPodsDestination.SETTINGS.route) {
                val viewModel: SettingsViewModel = viewModel(factory = GreenPodsViewModels.settings())
                val state by viewModel.state.collectAsStateWithLifecycle()

                // The health permission request comes back from `core/data` as an
                // ActivityResultContract typed on plain strings, so nothing here — and
                // nothing in feature/settings — links Health Connect (AD-11).
                val healthPermissionContract = remember { GreenPodsApplication.instance.healthConnectLink }
                val launcher =
                    healthPermissionContract.permissionRequestContract()?.let { contract ->
                        rememberLauncherForActivityResult(contract, viewModel::onHealthPermissionResult)
                    }

                SettingsScreen(
                    state = state,
                    onAutoPauseChanged = viewModel::setAutoPause,
                    onAutoResumeChanged = viewModel::setAutoResume,
                    onPauseOnlyBothOutChanged = viewModel::setPauseOnlyWhenBothOut,
                    onBackgroundMonitoringChanged = viewModel::setBackgroundMonitoring,
                    onLowBatteryWarningChanged = viewModel::setLowBatteryWarning,
                    onLowBatteryThresholdChanged = viewModel::setLowBatteryThreshold,
                    onScanModeChanged = viewModel::setScanMode,
                    onHeadGesturesChanged = viewModel::setHeadGesturesEnabled,
                    onBindingChanged = viewModel::updateBinding,
                    onRecheckTransports = viewModel::recheckTransports,
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onOpenUpdate = onOpenUrl,
                    onHeartRateChanged = viewModel::setHeartRateEnabled,
                    onHeartRateHealthConnectChanged = viewModel::setHeartRateHealthConnect,
                    onHeartRateIntervalChanged = viewModel::setHeartRateInterval,
                    onRequestHealthPermission = { launcher?.launch(viewModel.healthPermissions()) },
                    onDeleteHealthRecords = viewModel::deleteHealthRecords,
                )
            }
        }
    }
}

/** Not a tab: the heart-rate card at full size, pushed over the pods list. */
private const val HEART_RATE_ROUTE = "heart-rate"
