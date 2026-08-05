package io.github.andrewkomkov.greenpods.feature.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.BatteryRing
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityRow
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityUi
import io.github.andrewkomkov.greenpods.core.model.PodFeature
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
    onOpenBluetoothSettings: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onPodSelected: (PodState) -> Unit = {},
    onOpenHeartRate: (PodState) -> Unit = {},
    onTurnOnHeartRate: () -> Unit = {},
) {
    if (state.isEmpty) {
        EmptyState(
            reason = state.emptyReason,
            onRequestPermission = onRequestPermission,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onRetryScan = onRetryScan,
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
            PodCard(
                pod = pod,
                heartRate = state.heartRateOf(pod),
                onCheckControl = { onPodSelected(pod) },
                onOpenHeartRate = { onOpenHeartRate(pod) },
                onTurnOnHeartRate = onTurnOnHeartRate,
            )
        }
    }
}

@Composable
private fun PodCard(
    pod: PodState,
    heartRate: HeartRateUi,
    modifier: Modifier = Modifier,
    onCheckControl: () -> Unit = {},
    onOpenHeartRate: () -> Unit = {},
    onTurnOnHeartRate: () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(pod)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BatteryRing(
                    label = "Left",
                    levelPercent = pod.battery.left.levelPercent,
                    charging = pod.battery.left.isCharging,
                    caption = pod.earDetection.left.caption(),
                )
                BatteryRing(
                    label = "Right",
                    levelPercent = pod.battery.right.levelPercent,
                    charging = pod.battery.right.isCharging,
                    caption = pod.earDetection.right.caption(),
                )
                BatteryRing(
                    label = "Case",
                    levelPercent = pod.battery.case.levelPercent,
                    charging = pod.battery.case.isCharging,
                    // The case only puts its charge in the advertisement when it has
                    // reason to — with the lid open, or the buds inside. A closed case in
                    // a pocket sends the "unknown" sentinel, and a bare dash reads as a
                    // broken app rather than as the protocol working as designed.
                    caption = if (pod.battery.case.levelPercent == null) "open the lid" else "",
                )
            }

            HeartRateCard(
                ui = heartRate,
                onOpen = onOpenHeartRate,
                onTurnOn = onTurnOnHeartRate,
            )

            // Gated features are shown alongside usable ones so the absence of a
            // control reads as a platform limit rather than a missing feature.
            CapabilityRow(capabilities = pod.headlineCapabilities())

            Button(onClick = onCheckControl, modifier = Modifier.fillMaxWidth()) {
                Text("What this phone can control")
            }
        }
    }
}

/**
 * The accessory's name, and where it is.
 *
 * Where the signal strength in dBm used to be. That number was the last debugging value
 * left on the main screen: it is meaningless without knowing that -40 is close and -90 is
 * nearly gone, it moves constantly for reasons no one can act on, and it was the only
 * thing here that needed the reader to know how radios work. What people were reading it
 * *for* — is this mine, is it near, is it charging — is a sentence.
 */
@Composable
private fun Header(
    pod: PodState,
    modifier: Modifier = Modifier,
) {
    val presence = pod.presence()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.padding(9.dp).size(22.dp),
            )
        }

        Column(Modifier.weight(1f)) {
            Text(pod.name, style = MaterialTheme.typography.titleLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    presence.icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = presence.tint(),
                )
                Text(
                    presence.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where the accessory is, as a phrase rather than as a measurement. */
private data class Presence(
    val label: String,
    val icon: ImageVector,
    val charging: Boolean = false,
) {
    @Composable
    fun tint(): Color =
        if (charging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
}

private fun PodState.presence(): Presence {
    val inCase =
        earDetection.left == WearState.IN_CASE || earDetection.right == WearState.IN_CASE
    val charging =
        battery.left.isCharging || battery.right.isCharging || battery.case.isCharging

    return when {
        charging && inCase -> Presence("Case charging", Icons.Filled.Bolt, charging = true)
        charging -> Presence("Charging", Icons.Filled.Bolt, charging = true)
        inCase -> Presence("In the case", Icons.Filled.Headphones)
        earDetection.anyInEar -> Presence("In your ears", Icons.Filled.WifiTethering)
        else -> Presence(rssi.proximity(), Icons.Filled.WifiTethering)
    }
}

/**
 * Distance in the only vocabulary the advertisement transport can honestly support.
 *
 * RSSI is noisy enough that three buckets is already generous; a number would imply a
 * precision the radio does not have, which is how a debug value ends up being read as a
 * measurement.
 */
private fun Int?.proximity(): String =
    when {
        this == null -> "Nearby"
        this > CLOSE_RSSI -> "Right here"
        this > NEARBY_RSSI -> "Nearby"
        else -> "Somewhere close"
    }

/**
 * The five things people came to check, and whether this phone can do them.
 *
 * Not every feature the hardware has. AirPods Pro 3 carry ten, and listing all ten turned
 * the card into a column of identical rows with the battery and the heart rate drowning
 * somewhere inside it — the exact shape of a debug dump, and unreadable at a glance for
 * the one question it is supposed to answer.
 *
 * This is a summary, not a truncation: the complete list, with a reason against every
 * locked entry, is on the Settings screen under "What works with this phone". What is
 * kept here is what the app is opened for, in the order it is thought about.
 */
private fun PodState.headlineCapabilities(): List<CapabilityUi> =
    buildList {
        // Battery is not a PodFeature — it rides the advertisement, so it is the one
        // thing no phone and no permission can take away. First, because it is why the
        // app gets opened.
        add(
            CapabilityUi(
                label = "Battery",
                available = true,
                reason = "Every phone can hear what AirPods broadcast, so this always works.",
            ),
        )

        listOfNotNull(
            PodFeature.EAR_DETECTION.takeIf { it in model.features },
            heartRateFeature,
            PodFeature.NOISE_CONTROL.takeIf { it in model.features },
            PodFeature.HEAD_TRACKING.takeIf { it in model.features },
        ).forEach { feature ->
            val usable = feature in usableFeatures
            add(
                CapabilityUi(
                    label = feature.displayName,
                    available = usable,
                    reason =
                        if (usable) {
                            feature.explanation
                        } else {
                            // The phone's limit, not the stack's account of it: the
                            // precise refusal is kept for the diagnostics log, where
                            // someone can act on it (see PodState.lockSentenceFor).
                            "${feature.explanation}\n\n${lockSentenceFor(feature)}"
                        },
                ),
            )
        }
    }

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
 * anything — they should do about it. Every reason that has something to press offers it
 * here rather than sending the reader to a settings screen to look for it.
 */
@Composable
private fun EmptyState(
    reason: PodsEmptyReason,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRetryScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        when (reason) {
            PodsEmptyReason.SEARCHING -> "Looking for your AirPods…"
            PodsEmptyReason.NO_PERMISSION -> "GreenPods needs to see nearby devices"
            PodsEmptyReason.BLUETOOTH_OFF -> "Bluetooth is off"
            PodsEmptyReason.SCAN_FAILED -> "GreenPods stopped listening"
        }

    val body =
        when (reason) {
            PodsEmptyReason.SEARCHING -> {
                "Open the case near your phone. GreenPods listens for what AirPods " +
                    "broadcast all the time, so there is nothing to pair."
            }

            PodsEmptyReason.NO_PERMISSION -> {
                "That is how it hears your AirPods. It never asks for your location."
            }

            PodsEmptyReason.BLUETOOTH_OFF -> {
                "Turn it on and GreenPods starts listening again by itself."
            }

            // Deliberately not the radio's own error text. `SCAN_FAILED_INTERNAL_ERROR`
            // is a true sentence about a Bluetooth stack and a useless one about a phone
            // in someone's hand; the recovery is the same either way, and the exact code
            // is in the diagnostics log for whoever can use it.
            PodsEmptyReason.SCAN_FAILED -> {
                "Your phone's Bluetooth needs a moment. Try again, or turn Bluetooth off " +
                    "and on."
            }
        }

    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyMark(reason)

        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        when (reason) {
            PodsEmptyReason.NO_PERMISSION -> {
                Button(onClick = onRequestPermission) { Text("Allow") }
            }

            PodsEmptyReason.BLUETOOTH_OFF -> {
                OutlinedButton(onClick = onOpenBluetoothSettings) { Text("Open Bluetooth settings") }
            }

            PodsEmptyReason.SCAN_FAILED -> {
                Button(onClick = onRetryScan) { Text("Try again") }
            }

            PodsEmptyReason.SEARCHING -> {
                Unit
            }
        }
    }
}

/** A spinner while it is working, and a badge — coloured by severity — when it is not. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyMark(reason: PodsEmptyReason) {
    if (reason == PodsEmptyReason.SEARCHING) {
        // Contained, and morphing through shapes rather than spinning: this is the one
        // empty state that is not a problem to fix, and the Expressive loader reads as
        // "working" where a spinner in the middle of a blank screen reads as "stuck".
        ContainedLoadingIndicator()
        return
    }

    val icon =
        when (reason) {
            PodsEmptyReason.NO_PERMISSION -> Icons.Filled.DevicesOther
            PodsEmptyReason.BLUETOOTH_OFF -> Icons.Filled.BluetoothDisabled
            else -> Icons.Filled.Warning
        }

    val container =
        when (reason) {
            PodsEmptyReason.NO_PERMISSION -> MaterialTheme.colorScheme.secondaryContainer
            PodsEmptyReason.BLUETOOTH_OFF -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.errorContainer
        }

    val ink =
        when (reason) {
            PodsEmptyReason.NO_PERMISSION -> MaterialTheme.colorScheme.onSecondaryContainer
            PodsEmptyReason.BLUETOOTH_OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onErrorContainer
        }

    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = ink) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

/** Above this the accessory is within arm's reach; below the second, it is merely around. */
private const val CLOSE_RSSI = -55
private const val NEARBY_RSSI = -75
