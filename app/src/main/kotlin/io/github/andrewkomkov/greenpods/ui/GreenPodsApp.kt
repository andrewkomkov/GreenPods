package io.github.andrewkomkov.greenpods.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.feature.controls.ControlsScreen
import io.github.andrewkomkov.greenpods.feature.controls.ControlsViewModel
import io.github.andrewkomkov.greenpods.feature.pods.HeartRateScreen
import io.github.andrewkomkov.greenpods.feature.pods.HeartRateUi
import io.github.andrewkomkov.greenpods.feature.pods.PodsScreen
import io.github.andrewkomkov.greenpods.feature.pods.PodsViewModel
import io.github.andrewkomkov.greenpods.feature.settings.HeadGestureScreen
import io.github.andrewkomkov.greenpods.feature.settings.HeadGestureViewModel
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val onHeadGestures = current?.hierarchy?.any { it.route == HEAD_GESTURES_ROUTE } == true

    /**
     * Anything that is not one of the toolbar's destinations was pushed on top of one, and
     * therefore needs a way back.
     *
     * Derived rather than listed. This used to enumerate the pushed routes by hand, which
     * meant a screen added later got no back arrow until somebody remembered to come back
     * and edit this line — and nothing would have said so. Asking "is this a tab?" cannot
     * drift, because adding a tab is what changes the answer.
     */
    val pushed =
        current != null &&
            GreenPodsDestination.entries.none { destination ->
                current.hierarchy.any { it.route == destination.route }
            }

    val title =
        when {
            onHeartRate -> {
                "Heart rate"
            }

            onHeadGestures -> {
                "Head gestures"
            }

            else -> {
                GreenPodsDestination.entries
                    .firstOrNull { destination ->
                        current?.hierarchy?.any { it.route == destination.route } == true
                    }?.let { if (it == GreenPodsDestination.PODS) "GreenPods" else it.label }
                    ?: "GreenPods"
            }
        }

    // The title collapses as the content scrolls under it, which is what gives a screen a
    // top rather than a label floating above it.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Edge to edge, properly. The bars used to be opaque bands the content stopped
        // at; now the app paints the whole display and the system bars sit over it, which
        // is what makes a phone feel like it is running one app rather than framing one.
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val back: @Composable () -> Unit = {
                if (pushed) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
            // Both, not just the container. `scrolledContainerColor` defaults to a tinted
            // surface, so once the content scrolled the bar painted an opaque band with a
            // hard edge across the screen — the opposite of edge-to-edge, and it appeared
            // only after scrolling, which is why it survived the first look.
            val colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                )

            // Large where the screen is a destination, small where it is pushed on top of
            // one. A pushed screen is somewhere you already are and came from; giving it
            // the same weight as a destination flattens that difference and wastes the
            // space the content came for.
            if (pushed) {
                TopAppBar(
                    title = { Text(title) },
                    colors = colors,
                    navigationIcon = back,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(title) },
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->

        // Material motion, rather than the framework default.
        //
        // Two patterns, because two kinds of move. Switching tabs is a *fade through* —
        // the destinations are peers, nothing contains anything else, and sliding them
        // would invent a spatial relationship that is not there. Opening the heart rate
        // or the gesture trainer is a *shared axis*: those are pushed on top of what you
        // were reading, so they come in from the side and leave the way they arrived,
        // which is what makes Back feel like undoing rather than like navigating.
        //
        // Specs are read here and captured: the transition lambdas are not composable, so
        // the motion scheme cannot be reached from inside them.
        val fade = GreenPodsMotion.effects<Float>()
        val scale = GreenPodsMotion.defaultSpatial<Float>()
        val slide = GreenPodsMotion.defaultSpatial<IntOffset>()

        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = GreenPodsDestination.PODS.route,
                // Bottom room for the floating bar. Reserved here, once, rather than in five
                // screens' content padding — a screen that forgot would put its last control
                // under the toolbar, which is the failure this shape invites.
                modifier =
                    Modifier
                        .padding(padding)
                        .padding(bottom = FLOATING_BAR_SPACE)
                        .consumeWindowInsets(padding),
                enterTransition = { fadeIn(fade) + scaleIn(scale, initialScale = FADE_THROUGH_SCALE) },
                exitTransition = { fadeOut(fade) },
                popEnterTransition = { fadeIn(fade) + scaleIn(scale, initialScale = FADE_THROUGH_SCALE) },
                popExitTransition = { fadeOut(fade) },
            ) {
                composable(GreenPodsDestination.PODS.route) {
                    val viewModel: PodsViewModel = viewModel(factory = GreenPodsViewModels.pods())
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    PodsScreen(
                        state = state,
                        onRequestPermission = onRequestPermission,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                        onRetryScan = viewModel::retryScan,
                        onOpenHeartRate = {
                            navController.navigate(HEART_RATE_ROUTE) { launchSingleTop = true }
                        },
                        onTurnOnHeartRate = { viewModel.setHeartRateEnabled(true) },
                    )
                }

                composable(
                    HEART_RATE_ROUTE,
                    enterTransition = { slideInHorizontally(slide) { it / SHARED_AXIS_FRACTION } + fadeIn(fade) },
                    popExitTransition = { slideOutHorizontally(slide) { it / SHARED_AXIS_FRACTION } + fadeOut(fade) },
                ) {
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

                composable(
                    HEAD_GESTURES_ROUTE,
                    enterTransition = { slideInHorizontally(slide) { it / SHARED_AXIS_FRACTION } + fadeIn(fade) },
                    popExitTransition = { slideOutHorizontally(slide) { it / SHARED_AXIS_FRACTION } + fadeOut(fade) },
                ) {
                    val viewModel: HeadGestureViewModel = viewModel(factory = GreenPodsViewModels.headGestures())
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    HeadGestureScreen(state = state)
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
                        onTryHeadGestures = {
                            navController.navigate(HEAD_GESTURES_ROUTE) { launchSingleTop = true }
                        },
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

            // The destinations, floating clear of the content rather than sitting in a
            // band under it. Material 3 Expressive's toolbar is the shape for a small set
            // of persistent actions, and letting the list scroll underneath it is what
            // buys back the strip of screen a docked bar reserves forever.
            HorizontalFloatingToolbar(
                expanded = true,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                        .shadow(FLOATING_BAR_ELEVATION, CircleShape, clip = false),
                // Standard rather than vibrant, and lifted off the page.
                //
                // The vibrant container is a solid slab of colour the width of the bar; on
                // a page this pale it reads as a background stuck behind the buttons rather
                // than as something floating over them. A quieter container plus a shadow
                // is what actually says "this is above the content" — the shadow is the
                // part that does the work, and the vibrant fill was standing in for it.
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
            ) {
                GreenPodsDestination.entries.forEach { destination ->
                    val selected = current?.hierarchy?.any { it.route == destination.route } == true
                    ToggleButton(
                        checked = selected,
                        onCheckedChange = {
                            navController.navigate(destination.route) {
                                // Keep one entry per destination so Back always leaves the
                                // app from the start destination rather than replaying tabs.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    ) {
                        Icon(destination.icon, contentDescription = null, Modifier.size(20.dp))
                        Text(destination.label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Not a tab: the heart-rate card at full size, pushed over the pods list. */
private const val HEART_RATE_ROUTE = "heart-rate"

/** Not a tab either: the gesture trainer, pushed from the head-gesture settings. */
private const val HEAD_GESTURES_ROUTE = "head-gestures"

/** A fade through starts slightly small, so peers cross-dissolve with a little life. */
private const val FADE_THROUGH_SCALE = 0.92f

/** Enough to read as lifted off the page without casting a slab of shadow. */
private val FLOATING_BAR_ELEVATION = 6.dp

/** A shared axis travels a fraction of the width, not all of it — this is a push, not a page turn. */
private const val SHARED_AXIS_FRACTION = 5

/** Room kept under every screen so the floating bar never covers a control. */
private val FLOATING_BAR_SPACE = 92.dp
