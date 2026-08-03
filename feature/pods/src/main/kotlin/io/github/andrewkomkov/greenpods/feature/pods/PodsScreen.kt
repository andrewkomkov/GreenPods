package io.github.andrewkomkov.greenpods.feature.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.BatteryRing
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityRow
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityUi
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.WearState

/**
 * The main screen: what is nearby, how charged it is, and what this phone can
 * actually control.
 */
@Composable
fun PodsScreen(
    state: PodsUiState,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit = {},
    onPodSelected: (PodState) -> Unit = {},
) {
    if (state.isEmpty) {
        EmptyState(
            reason = state.emptyReason,
            detail = state.scanFailure.orEmpty(),
            onRequestPermission = onRequestPermission,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        items(state.pods, key = PodState::address) { pod ->
            PodCard(pod = pod, onCheckControl = { onPodSelected(pod) })
        }
    }
}

@Composable
private fun PodCard(
    pod: PodState,
    modifier: Modifier = Modifier,
    onCheckControl: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Headphones, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(pod.name, style = MaterialTheme.typography.titleLarge)
                    pod.rssi?.let { rssi ->
                        Text(
                            "$rssi dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BatteryRing(
                    label = "Left",
                    levelPercent = pod.battery.left.levelPercent,
                    charging = pod.battery.left.isCharging,
                    caption = pod.earDetection.primary.caption(),
                )
                BatteryRing(
                    label = "Right",
                    levelPercent = pod.battery.right.levelPercent,
                    charging = pod.battery.right.isCharging,
                    caption = pod.earDetection.secondary.caption(),
                )
                BatteryRing(
                    label = "Case",
                    levelPercent = pod.battery.case.levelPercent,
                    charging = pod.battery.case.isCharging,
                )
            }

            pod.heartRate?.let { sample ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.MonitorHeart, contentDescription = null)
                    Text("${sample.beatsPerMinute} bpm", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Gated features are listed alongside usable ones so the absence of a
            // control reads as a platform limit rather than a missing feature.
            CapabilityRow(capabilities = pod.capabilities())

            Button(onClick = onCheckControl, modifier = Modifier.fillMaxWidth()) {
                Text("Check what this phone can control")
            }
        }
    }
}

/** Usable capabilities first, then locked ones, each carrying its own explanation. */
private fun PodState.capabilities(): List<CapabilityUi> =
    (
        usableFeatures.map { feature ->
            CapabilityUi(feature.displayName, available = true, reason = feature.explanation)
        } +
            gatedFeatures.map { feature ->
                CapabilityUi(
                    label = feature.displayName,
                    available = false,
                    reason = "${feature.explanation}\n\n${reasonFor(feature)}",
                )
            }
    ).sortedWith(compareByDescending<CapabilityUi> { it.available }.thenBy { it.label })

private fun WearState.caption(): String =
    when (this) {
        WearState.IN_EAR -> "in ear"
        WearState.OUT_OF_EAR -> "out"
        WearState.IN_CASE -> "in case"
        WearState.UNKNOWN -> ""
    }

/**
 * The empty state carries the whole explanation.
 *
 * Most users will open this app with nothing nearby, so this is the screen that has to
 * make sense on its own: what the app is doing, why nothing is showing, and what — if
 * anything — they should do about it.
 */
@Composable
private fun EmptyState(
    reason: PodsEmptyReason,
    detail: String,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector? =
        when (reason) {
            PodsEmptyReason.SEARCHING -> null
            PodsEmptyReason.BLUETOOTH_OFF -> Icons.Filled.BluetoothDisabled
            else -> Icons.Filled.Warning
        }

    val title =
        when (reason) {
            PodsEmptyReason.SEARCHING -> "Looking for nearby AirPods…"
            PodsEmptyReason.NO_PERMISSION -> "Nearby-devices permission needed"
            PodsEmptyReason.BLUETOOTH_OFF -> "Bluetooth is off"
            PodsEmptyReason.SCAN_FAILED -> "Scanning stopped"
        }

    val body =
        when (reason) {
            PodsEmptyReason.SEARCHING -> {
                "Open the case near your phone. GreenPods reads the advertisement AirPods " +
                    "broadcast continuously, so nothing needs to be paired."
            }

            PodsEmptyReason.NO_PERMISSION -> {
                "GreenPods needs the Bluetooth scan permission to hear those broadcasts. It " +
                    "never asks for your location — the scan is declared as location-free."
            }

            PodsEmptyReason.BLUETOOTH_OFF -> {
                "Turn Bluetooth on and GreenPods will start listening again by itself."
            }

            PodsEmptyReason.SCAN_FAILED -> {
                detail.ifBlank { "The Bluetooth radio refused to start a scan." }
            }
        }

    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon == null) {
            CircularProgressIndicator()
        } else {
            Icon(icon, contentDescription = null)
        }

        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)

        if (reason == PodsEmptyReason.NO_PERMISSION) {
            Button(onClick = onRequestPermission) { Text("Grant permission") }
        }
    }
}
