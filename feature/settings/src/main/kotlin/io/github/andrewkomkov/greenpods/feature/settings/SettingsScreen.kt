package io.github.andrewkomkov.greenpods.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityGate
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedSurface
import io.github.andrewkomkov.greenpods.core.designsystem.component.SectionCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.SegmentedChoice
import io.github.andrewkomkov.greenpods.core.designsystem.component.SwitchRow
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsSpacing
import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode

/**
 * Settings, head-gesture bindings, and a plain answer to "what works on my phone".
 *
 * There is no diagnostics section. Undecoded traffic still reaches the log — it is how
 * new protocol behaviour gets found — but it is read from adb by whoever can act on it,
 * not from a card under someone's auto-pause switch. Nothing on this screen asks the
 * reader to know how Bluetooth works.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onAutoPauseChanged: (Boolean) -> Unit = {},
    onAutoResumeChanged: (Boolean) -> Unit = {},
    onPauseOnlyBothOutChanged: (Boolean) -> Unit = {},
    onBackgroundMonitoringChanged: (Boolean) -> Unit = {},
    onLowBatteryWarningChanged: (Boolean) -> Unit = {},
    onLowBatteryThresholdChanged: (Int) -> Unit = {},
    onScanModeChanged: (ScanMode) -> Unit = {},
    onHeadGesturesChanged: (Boolean) -> Unit = {},
    onBindingChanged: (HeadGestureBinding) -> Unit = {},
    onRecheckTransports: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onTryHeadGestures: () -> Unit = {},
    onOpenUpdate: (String) -> Unit = {},
    onHeartRateChanged: (Boolean) -> Unit = {},
    onHeartRateHealthConnectChanged: (Boolean) -> Unit = {},
    onHeartRateIntervalChanged: (Int) -> Unit = {},
    onRequestHealthPermission: () -> Unit = {},
    onDeleteHealthRecords: () -> Unit = {},
    onLiveActivityChanged: (Boolean) -> Unit = {},
    onLiveActivityShowHeartRateChanged: (Boolean) -> Unit = {},
    /**
     * Re-read the live surface's availability.
     *
     * Called on every resume because the state this screen sends the user away to change
     * is changed *off* this screen. Coming back to the same locked card after turning
     * promoted notifications on would read as the fix having failed.
     */
    onLiveActivityResumed: () -> Unit = {},
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onLiveActivityResumed() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // Content runs under the floating bar; this is what keeps the
                // last control reachable above it.
                .padding(bottom = GreenPodsSpacing.FloatingBarSpace),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EarDetectionSection(
            settings = state.settings,
            onAutoPauseChanged = onAutoPauseChanged,
            onAutoResumeChanged = onAutoResumeChanged,
            onPauseOnlyBothOutChanged = onPauseOnlyBothOutChanged,
        )

        MonitoringSection(
            settings = state.settings,
            onBackgroundMonitoringChanged = onBackgroundMonitoringChanged,
            onLowBatteryWarningChanged = onLowBatteryWarningChanged,
            onLowBatteryThresholdChanged = onLowBatteryThresholdChanged,
        )

        LiveActivitySection(
            settings = state.settings,
            live = state.liveActivity,
            onLiveActivityChanged = onLiveActivityChanged,
            onShowHeartRateChanged = onLiveActivityShowHeartRateChanged,
        )

        HeartRateSection(
            settings = state.settings,
            onHeartRateChanged = onHeartRateChanged,
            onHeartRateIntervalChanged = onHeartRateIntervalChanged,
        )

        HealthConnectSection(
            settings = state.settings,
            health = state.health,
            onHealthConnectChanged = onHeartRateHealthConnectChanged,
            onRequestPermission = onRequestHealthPermission,
            onDeleteRecords = onDeleteHealthRecords,
        )

        ScanSection(settings = state.settings, onScanModeChanged = onScanModeChanged)

        GestureSection(
            settings = state.settings,
            locked = state.capabilities.headGesturesLocked,
            onHeadGesturesChanged = onHeadGesturesChanged,
            onBindingChanged = onBindingChanged,
            onTry = onTryHeadGestures,
        )

        CapabilitiesSection(
            capabilities = state.capabilities,
            deviceName = state.deviceName,
            onRecheck = onRecheckTransports,
        )

        UpdateSection(
            state = state,
            onCheckForUpdates = onCheckForUpdates,
            onOpenUpdate = onOpenUpdate,
        )
    }
}

@Composable
private fun EarDetectionSection(
    settings: GreenPodsSettings,
    onAutoPauseChanged: (Boolean) -> Unit,
    onAutoResumeChanged: (Boolean) -> Unit,
    onPauseOnlyBothOutChanged: (Boolean) -> Unit,
) {
    SectionCard(
        title = "Ear detection",
        subtitle = "Works on every phone — nothing to pair, nothing to allow.",
        icon = Icons.Filled.Pause,
    ) {
        SwitchRow(
            title = "Pause when a bud comes out",
            checked = settings.autoPauseEnabled,
            onCheckedChange = onAutoPauseChanged,
        )
        SwitchRow(
            title = "Resume when it goes back in",
            description = "Only resumes playback GreenPods paused itself.",
            checked = settings.autoResumeEnabled,
            onCheckedChange = onAutoResumeChanged,
            enabled = settings.autoPauseEnabled,
        )
        SwitchRow(
            title = "Only pause when both are out",
            description = "For listening with a single bud.",
            checked = settings.pauseOnlyWhenBothOut,
            onCheckedChange = onPauseOnlyBothOutChanged,
            enabled = settings.autoPauseEnabled,
        )
    }
}

@Composable
private fun MonitoringSection(
    settings: GreenPodsSettings,
    onBackgroundMonitoringChanged: (Boolean) -> Unit,
    onLowBatteryWarningChanged: (Boolean) -> Unit,
    onLowBatteryThresholdChanged: (Int) -> Unit,
) {
    SectionCard(
        title = "Background monitoring",
        subtitle = "Keeps battery readings fresh while the app is closed.",
        icon = Icons.Filled.Notifications,
    ) {
        SwitchRow(
            title = "Keep watching in the background",
            description = "Shows an ongoing notification.",
            checked = settings.backgroundMonitoringEnabled,
            onCheckedChange = onBackgroundMonitoringChanged,
        )
        SwitchRow(
            title = "Warn me when a bud runs low",
            checked = settings.lowBatteryWarningEnabled,
            onCheckedChange = onLowBatteryWarningChanged,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.BatteryAlert, contentDescription = null)
            Text("Warn below ${settings.lowBatteryThresholdPercent}%")
        }
        Slider(
            value = settings.lowBatteryThresholdPercent.toFloat(),
            onValueChange = { onLowBatteryThresholdChanged(it.toInt()) },
            valueRange = GreenPodsSettings.MIN_THRESHOLD.toFloat()..GreenPodsSettings.MAX_THRESHOLD.toFloat(),
            enabled = settings.lowBatteryWarningEnabled,
        )
    }
}

/**
 * Every word the live-surface section says.
 *
 * In one object for the same reason [HeartRateSettingsCopy] is: [HEART_RATE_DESCRIPTION]
 * carries FR-018a, and a boundary written into one sentence in the middle of a screen
 * decays the first time somebody tidies the wording. Here a unit test can walk it.
 *
 * The *reasons* are not here. Those are string resources (`live_unavailable_*`), whose ids
 * the host module hands over in [LiveActivityUiState.reasonRes] and which this screen
 * resolves as it draws — a feature module carries no resources and cannot see `app`'s.
 */
internal object LiveActivitySettingsCopy {
    const val TITLE = "Live status surface"
    const val SUMMARY = "Show battery, wear and listening mode on the lock screen."

    const val ENABLE_TITLE = "Show the live surface"

    /**
     * Says what it replaces, because that is the whole reason it defaults to on.
     *
     * It is not a second persistent thing on the phone; it is the monitoring notification
     * the user already has, promoted.
     */
    const val ENABLE_DESCRIPTION = "Promotes the monitoring notification GreenPods already shows. Nothing new appears."

    const val HEART_RATE_TITLE = "Show heart rate on the live surface"

    /**
     * The asymmetry, stated where the switch is (FR-018a).
     *
     * A user may decline to display their heart rate on a screen anyone can read. They may
     * not have the sensor run without being told it is running, so this switch is written
     * as hiding a *number* rather than as hiding heart rate.
     */
    const val HEART_RATE_DESCRIPTION =
        "Turning this off hides the number. It never hides the fact that the sensor is running."

    /** The one unavailable state with somewhere to send the user (FR-014a). */
    const val ROUTE_BACK = "Open notification settings"

    /** Every sentence above, for the test that walks them. */
    fun everySentence(): List<String> =
        listOf(
            TITLE,
            SUMMARY,
            ENABLE_TITLE,
            ENABLE_DESCRIPTION,
            HEART_RATE_TITLE,
            HEART_RATE_DESCRIPTION,
            ROUTE_BACK,
        )
}

/**
 * The live status surface — and, on most phones, why it will not appear.
 *
 * Locked rather than hidden, even though FR-014 deliberately gives those phones nothing
 * new. A scope decision is not licence to leave someone unable to tell a feature their
 * phone lacks from a feature that is broken (FR-014a, Principle II), so the switches stay
 * on screen, legible and inert, underneath the sentence that explains them.
 *
 * On [LiveActivityUiState.hasRouteBack] there is one more thing to press: the user turned
 * promoted notifications off themselves, and the system screen where they turn them back
 * on is a place this app can open.
 */
@Composable
private fun LiveActivitySection(
    settings: GreenPodsSettings,
    live: LiveActivityUiState,
    onLiveActivityChanged: (Boolean) -> Unit,
    onShowHeartRateChanged: (Boolean) -> Unit,
) {
    if (!live.isAvailable) {
        val context = LocalContext.current

        LockedCard(
            // The same title as when it works: the card's own texture, lock and sentence
            // already say it does not, and renaming the feature when it is locked would
            // leave the user hunting for a section that changed its name.
            title = LiveActivitySettingsCopy.TITLE,
            // Resolved here, at draw time, against the configuration in force now — see
            // LiveActivityUiState. A zero id cannot happen alongside an unavailable state,
            // and an empty body is a quieter failure than a crash if one ever does.
            body =
                if (live.reasonRes == 0) {
                    ""
                } else {
                    stringResource(live.reasonRes, *live.reasonArgs.toTypedArray())
                },
            icon = Icons.Filled.Smartphone,
        ) {
            Column(
                modifier = Modifier.alpha(LOCKED_ALPHA),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiveActivityRows(settings, enabled = false, onLiveActivityChanged, onShowHeartRateChanged)
            }
            if (live.hasRouteBack) {
                OutlinedButton(
                    onClick = { context.openPromotedNotificationSettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(LiveActivitySettingsCopy.ROUTE_BACK)
                }
            }
        }
        return
    }

    SectionCard(
        title = LiveActivitySettingsCopy.TITLE,
        subtitle = LiveActivitySettingsCopy.SUMMARY,
        icon = Icons.Filled.Smartphone,
    ) {
        LiveActivityRows(settings, enabled = true, onLiveActivityChanged, onShowHeartRateChanged)
    }
}

@Composable
private fun LiveActivityRows(
    settings: GreenPodsSettings,
    enabled: Boolean,
    onLiveActivityChanged: (Boolean) -> Unit,
    onShowHeartRateChanged: (Boolean) -> Unit,
) {
    SwitchRow(
        title = LiveActivitySettingsCopy.ENABLE_TITLE,
        description = LiveActivitySettingsCopy.ENABLE_DESCRIPTION,
        // Locked, the switch shows the state of the world rather than the stored
        // preference: nothing is being surfaced, whatever the setting says.
        checked = settings.liveActivityEnabled && enabled,
        onCheckedChange = onLiveActivityChanged,
        enabled = enabled,
    )
    SwitchRow(
        title = LiveActivitySettingsCopy.HEART_RATE_TITLE,
        description = LiveActivitySettingsCopy.HEART_RATE_DESCRIPTION,
        checked = settings.liveActivityShowHeartRate && enabled,
        onCheckedChange = onShowHeartRateChanged,
        // Greyed by the switch above it, which is the one case where grey is the honest
        // signal: it is another switch on this screen that turns it back on.
        enabled = enabled && settings.liveActivityEnabled,
    )
}

/**
 * Opens the system screen where promoted notifications are turned back on.
 *
 * `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` arrived with the feature itself in
 * Android 16, and `minSdk` here is 26, so it is checked at runtime rather than at compile
 * time. The check is belt and braces: `PromotionRefused` cannot be reached below API 36 —
 * the gate answers `PlatformTooOld` first — so the button that calls this does not exist
 * on a phone that could not open the screen.
 *
 * The fallback is the app's own notification settings, which has existed since API 26. A
 * device is allowed to ship without either activity, and being unable to open a settings
 * screen is not a crash: the last resort is that nothing happens, never a stack trace.
 *
 * (The spec names this action `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`. No such constant
 * exists in any SDK — the one Android 16 shipped is the name used here.)
 */
private fun Context.openPromotedNotificationSettings() {
    val candidates =
        buildList {
            if (Build.VERSION.SDK_INT >= LiveActivityGate.MIN_SDK) {
                add(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            }
            add(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }

    for (action in candidates) {
        val intent = Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // This device does not have that screen. Try the next, and if there is no
            // next, leave the reason on the card rather than crashing on top of it.
        }
    }
}

/**
 * The heart-rate switch, and the cost stated **before** it can be moved.
 *
 * FR-012 says the user is told that continuous sensing draws the accessory's battery
 * before they enable it, and the ordering here is the requirement: the sentence is the
 * section's subtitle, above the switch, not a caption underneath it. A cost discovered
 * after the fact is not a cost that was disclosed.
 *
 * The same subtitle carries the other consequence — enabling this starts the monitoring
 * service. That default is off because a service the user did not ask for is hostile, so
 * overriding it silently would be worse than not shipping the feature (AD-8).
 */
@Composable
private fun HeartRateSection(
    settings: GreenPodsSettings,
    onHeartRateChanged: (Boolean) -> Unit,
    onHeartRateIntervalChanged: (Int) -> Unit,
) {
    SectionCard(
        title = HeartRateSettingsCopy.SECTION_TITLE,
        subtitle = HeartRateSettingsCopy.SECTION_SUBTITLE,
        icon = Icons.Filled.MonitorHeart,
    ) {
        SwitchRow(
            title = HeartRateSettingsCopy.ENABLE_TITLE,
            description = HeartRateSettingsCopy.ENABLE_DESCRIPTION,
            checked = settings.heartRateEnabled,
            onCheckedChange = onHeartRateChanged,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Timer, contentDescription = null)
            Text("A reading every ${settings.heartRateIntervalMillis / 1000f} s")
        }
        Slider(
            value = settings.heartRateIntervalMillis.toFloat(),
            onValueChange = { onHeartRateIntervalChanged(it.toInt()) },
            // A slower cadence costs the earbuds less. The range exists so that trade-off
            // can be measured rather than assumed (SC-006).
            valueRange = 1_000f..10_000f,
            steps = 8,
            enabled = settings.heartRateEnabled,
        )

        Text(
            HeartRateSettingsCopy.CONFIDENCE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // FR-013 wants active sensing discoverable, and the notification is how. Where
        // POST_NOTIFICATIONS is denied the service still runs — saying so here is what
        // keeps that from being a silent hole in the requirement.
        Text(
            HeartRateSettingsCopy.NOTIFICATION_CAVEAT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The health store, its three availability states, and delete-my-data.
 *
 * Separately switchable from the reading itself (FR-017), because seeing a number and
 * putting it into someone's health history are two decisions. The section is present
 * even where Health Connect is not, carrying the reason — an absent section reads as a
 * bug (Principle II, FR-020).
 *
 * **This module links no Health Connect type.** The permission request arrives as an
 * `ActivityResultContract<Set<String>, Set<String>>`, whose parameters are plain strings,
 * and is launched by the host Activity (AD-11).
 */
@Composable
private fun HealthConnectSection(
    settings: GreenPodsSettings,
    health: HealthUiState,
    onHealthConnectChanged: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onDeleteRecords: () -> Unit,
) {
    SectionCard(
        title = HeartRateSettingsCopy.HEALTH_TITLE,
        subtitle = health.availabilitySentence.ifBlank { HeartRateSettingsCopy.HEALTH_CHECKING },
        icon = Icons.Filled.Favorite,
    ) {
        SwitchRow(
            title = HeartRateSettingsCopy.HEALTH_SWITCH_TITLE,
            description = HeartRateSettingsCopy.HEALTH_SWITCH_DESCRIPTION,
            checked = settings.heartRateHealthConnectEnabled,
            onCheckedChange = onHealthConnectChanged,
            enabled = health.isAvailable,
        )

        when {
            !health.isAvailable -> {
                Unit
            }

            health.hasWritePermission -> {
                Text(
                    HeartRateSettingsCopy.PERMISSION_GRANTED,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Asked once, refused, and not asked again — the requirement is that the app
            // does not nag, so it explains where the switch lives instead (FR-018).
            health.permissionRefused -> {
                Text(
                    HeartRateSettingsCopy.PERMISSION_REFUSED,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Button(onClick = onRequestPermission) { Text(HeartRateSettingsCopy.PERMISSION_BUTTON) }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text(
            HeartRateSettingsCopy.DELETE_TITLE,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            HeartRateSettingsCopy.DELETE_DESCRIPTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDeleteRecords, enabled = health.isAvailable) {
            Text(HeartRateSettingsCopy.DELETE_BUTTON)
        }
        if (health.deletionMessage.isNotBlank()) {
            Text(health.deletionMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScanSection(
    settings: GreenPodsSettings,
    onScanModeChanged: (ScanMode) -> Unit,
) {
    SectionCard(
        title = "Looking for earbuds",
        subtitle = "How often GreenPods checks. Faster costs phone battery.",
        icon = Icons.Filled.Radar,
    ) {
        SegmentedChoice(
            options = ScanMode.entries,
            selected = { it == settings.scanMode },
            label = ScanMode::label,
            onSelect = onScanModeChanged,
        )
    }
}

/**
 * Head gestures, and the fact that most phones will never carry them.
 *
 * Locked, this section wears the same outline-and-hatch as every other locked surface in
 * the app rather than simply greying out. Greyed-out is what a switch looks like when
 * some *other* switch above it is off — a state the user can fix by looking harder. This
 * one they cannot fix at all, and saying so is kinder than letting them hunt.
 */
@Composable
private fun GestureSection(
    settings: GreenPodsSettings,
    locked: Boolean,
    onHeadGesturesChanged: (Boolean) -> Unit,
    onBindingChanged: (HeadGestureBinding) -> Unit,
    onTry: () -> Unit,
) {
    val subtitle =
        if (locked) {
            "Nod to accept a call, shake to reject. This phone can't read head movement " +
                "from your earbuds, so these stay off."
        } else {
            "Nod to accept a call, shake to reject."
        }

    if (locked) {
        LockedSurface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Filled.Face, contentDescription = null)
                    Column {
                        Text(
                            "Head gestures",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(
                    modifier = Modifier.alpha(LOCKED_ALPHA),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GestureRows(settings, enabled = false, onHeadGesturesChanged, onBindingChanged)
                }
            }
        }
        return
    }

    SectionCard(title = "Head gestures", subtitle = subtitle, icon = Icons.Filled.Face) {
        GestureRows(settings, enabled = true, onHeadGesturesChanged, onBindingChanged)
        // A gesture that does not fire teaches nothing about why. This is where that is
        // answered — live movement, and the threshold drawn where it actually is.
        OutlinedButton(onClick = onTry, enabled = settings.headGesturesEnabled) {
            Text("Practise a nod")
        }
    }
}

@Composable
private fun GestureRows(
    settings: GreenPodsSettings,
    enabled: Boolean,
    onHeadGesturesChanged: (Boolean) -> Unit,
    onBindingChanged: (HeadGestureBinding) -> Unit,
) {
    SwitchRow(
        title = "Act on head gestures",
        checked = settings.headGesturesEnabled && enabled,
        onCheckedChange = onHeadGesturesChanged,
        enabled = enabled,
    )
    settings.gestureBindings.forEach { binding ->
        GestureBindingRow(
            binding = binding,
            enabled = enabled && settings.headGesturesEnabled,
            onChanged = onBindingChanged,
        )
    }
}

@Composable
private fun GestureBindingRow(
    binding: HeadGestureBinding,
    enabled: Boolean,
    onChanged: (HeadGestureBinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            binding.gesture.label(),
            modifier = Modifier.weight(0.3f),
            style = MaterialTheme.typography.bodyMedium,
        )

        Box(Modifier.weight(0.5f)) {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(binding.action.label(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label()) },
                        onClick = {
                            expanded = false
                            onChanged(binding.copy(action = action))
                        },
                    )
                }
            }
        }

        Switch(
            checked = binding.enabled,
            onCheckedChange = { onChanged(binding.copy(enabled = it)) },
            enabled = enabled,
        )
    }
}

/**
 * What works with this phone, said once, in features.
 *
 * The three transports and their availability used to be listed here. That told the
 * reader which of GreenPods' three ways of talking to the earbuds had succeeded — a fact
 * about the app's plumbing, not about their phone. This answers the question they came
 * with: what can I do, what can I not do, and is anything wrong with my earbuds.
 *
 * The re-check stays because a probe can genuinely change its answer: a channel another
 * app was holding gets released, a pairing completes. It is one button, and it says what
 * it does.
 */
@Composable
private fun CapabilitiesSection(
    capabilities: CapabilitiesUiState,
    deviceName: String,
    onRecheck: () -> Unit,
) {
    SectionCard(
        title = "What works with this phone",
        subtitle = deviceName.ifBlank { "No earbuds in range." },
        icon = Icons.Filled.Headphones,
    ) {
        if (!capabilities.known) {
            Text(
                "Open your case nearby and this fills in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        (capabilities.alwaysWorks + capabilities.available).forEach { feature ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(feature, style = MaterialTheme.typography.bodyMedium)
            }
        }

        capabilities.locked.forEach { group ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        group.features.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        group.sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = onRecheck) { Text("Check again") }
    }
}

@Composable
private fun UpdateSection(
    state: SettingsUiState,
    onCheckForUpdates: () -> Unit,
    onOpenUpdate: (String) -> Unit,
) {
    SectionCard(
        title = "Updates",
        subtitle = "GreenPods ${state.appVersion}",
        icon = Icons.Filled.SystemUpdate,
    ) {
        if (state.updateSummary.isNotBlank()) {
            Text(state.updateSummary, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onCheckForUpdates, enabled = !state.checkingUpdate) {
                Text("Check for updates")
            }
            if (state.checkingUpdate) CircularProgressIndicator(Modifier.padding(4.dp))
            state.updateUrl?.let { url ->
                TextButton(onClick = { onOpenUpdate(url) }) { Text("Download") }
            }
        }
    }
}

internal fun ScanMode.label(): String =
    when (this) {
        ScanMode.LOW_POWER -> "Battery saver"
        ScanMode.BALANCED -> "Balanced"
        ScanMode.LOW_LATENCY -> "Fastest"
    }

private fun GestureAction.label(): String = name.humanise()

private fun HeadGesture.label(): String = name.humanise()

/** `LOOK_UP` reads as "Look up". Enum names must never reach the screen verbatim. */
private fun String.humanise(): String =
    lowercase()
        .split('_')
        .joinToString(" ")
        .replaceFirstChar(Char::uppercase)

/** Legible, and plainly not a switch this phone will move. */
private const val LOCKED_ALPHA = 0.55f
