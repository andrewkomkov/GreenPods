package io.github.andrewkomkov.greenpods.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.SectionCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.SwitchRow
import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus

/**
 * Settings, head-gesture bindings, and the diagnostics that explain why a feature is
 * unavailable on this particular phone.
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
    onClearDiagnostics: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdate: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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

        ScanSection(settings = state.settings, onScanModeChanged = onScanModeChanged)

        GestureSection(
            settings = state.settings,
            onHeadGesturesChanged = onHeadGesturesChanged,
            onBindingChanged = onBindingChanged,
        )

        TransportSection(
            transports = state.transports,
            deviceName = state.deviceName,
            onRecheck = onRecheckTransports,
        )

        DiagnosticsSection(state = state, onClear = onClearDiagnostics)

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
        subtitle =
            "Driven by the advertisement AirPods broadcast, so this works on every phone — " +
                "no pairing and no root.",
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
            description = "Runs an ongoing notification and keeps scanning.",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanSection(
    settings: GreenPodsSettings,
    onScanModeChanged: (ScanMode) -> Unit,
) {
    SectionCard(
        title = "Scanning",
        subtitle = "How hard the radio looks for advertisements. Faster costs battery.",
        icon = Icons.Filled.Radar,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.scanMode == mode,
                    onClick = { onScanModeChanged(mode) },
                    label = { Text(mode.label()) },
                )
            }
        }
    }
}

@Composable
private fun GestureSection(
    settings: GreenPodsSettings,
    onHeadGesturesChanged: (Boolean) -> Unit,
    onBindingChanged: (HeadGestureBinding) -> Unit,
) {
    SectionCard(
        title = "Head gestures",
        subtitle =
            "Nod to accept, shake to reject. Needs head tracking, which only the Apple " +
                "protocol carries — so these stay inactive unless that channel opens.",
        icon = Icons.Filled.Face,
    ) {
        SwitchRow(
            title = "Act on head gestures",
            checked = settings.headGesturesEnabled,
            onCheckedChange = onHeadGesturesChanged,
        )
        settings.gestureBindings.forEach { binding ->
            GestureBindingRow(
                binding = binding,
                enabled = settings.headGesturesEnabled,
                onChanged = onBindingChanged,
            )
        }
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

@Composable
private fun TransportSection(
    transports: List<TransportStatus>,
    deviceName: String,
    onRecheck: () -> Unit,
) {
    SectionCard(
        title = "What this phone can do",
        subtitle = deviceName.ifBlank { "No accessory in range." },
        icon = Icons.Filled.Radar,
    ) {
        if (transports.isEmpty()) {
            Text(
                "Transport status appears once an accessory is nearby.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        transports.forEach { status ->
            Column {
                Text(
                    "${status.transport.displayName} — ${status.availability.label()}",
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        when (status.availability) {
                            TransportAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
                            TransportAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.error
                            TransportAvailability.NOT_PROBED -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    status.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onRecheck, enabled = transports.isNotEmpty()) { Text("Check again") }
    }
}

@Composable
private fun DiagnosticsSection(
    state: SettingsUiState,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "Diagnostics",
        subtitle =
            "Traffic GreenPods could not decode. This is how new protocol behaviour gets " +
                "found, so nothing is dropped silently.",
        icon = Icons.Filled.BugReport,
    ) {
        if (state.diagnostics.isEmpty()) {
            Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
        }
        state.diagnostics.take(DIAGNOSTICS_SHOWN).forEach { event ->
            Column {
                Text(
                    "[${event.category.name}] ${event.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (event.detail.isNotBlank()) {
                    Text(
                        event.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
        TextButton(onClick = onClear, enabled = state.diagnostics.isNotEmpty()) { Text("Clear") }
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

private fun ScanMode.label(): String =
    when (this) {
        ScanMode.LOW_POWER -> "Battery saver"
        ScanMode.BALANCED -> "Balanced"
        ScanMode.LOW_LATENCY -> "Fastest"
    }

private fun TransportAvailability.label(): String =
    when (this) {
        TransportAvailability.AVAILABLE -> "available"
        TransportAvailability.UNAVAILABLE -> "unavailable"
        TransportAvailability.NOT_PROBED -> "not checked"
    }

private fun GestureAction.label(): String = name.humanise()

private fun HeadGesture.label(): String = name.humanise()

/** `LOOK_UP` reads as "Look up". Enum names must never reach the screen verbatim. */
private fun String.humanise(): String =
    lowercase()
        .split('_')
        .joinToString(" ")
        .replaceFirstChar(Char::uppercase)

private const val DIAGNOSTICS_SHOWN = 20
