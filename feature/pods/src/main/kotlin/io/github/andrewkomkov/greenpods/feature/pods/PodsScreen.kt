package io.github.andrewkomkov.greenpods.feature.pods

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.BatteryRing
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityRow
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityUi
import io.github.andrewkomkov.greenpods.core.designsystem.component.HeartBeatIcon
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion
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
            PodCard(
                pod = pod,
                heartRate = state.heartRateOf(pod),
                onCheckControl = { onPodSelected(pod) },
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
                    // The case only puts its charge in the advertisement when it has
                    // reason to — with the lid open, or the buds inside. A closed case in
                    // a pocket sends the "unknown" sentinel, and a bare dash reads as a
                    // broken app rather than as the protocol working as designed.
                    caption = if (pod.battery.case.levelPercent == null) "open the lid" else "",
                )
            }

            HeartRateCard(ui = heartRate)

            // Gated features are listed alongside usable ones so the absence of a
            // control reads as a platform limit rather than a missing feature.
            CapabilityRow(capabilities = pod.capabilities())

            Button(onClick = onCheckControl, modifier = Modifier.fillMaxWidth()) {
                Text("Check what this phone can control")
            }
        }
    }
}

/**
 * The heart-rate card.
 *
 * Every state gets a **different shape**, not a different string in the same shape.
 * FR-008 is that a settling sensor must never be mistaken for a result, and two states
 * that differ only in wording are two states a glance cannot tell apart. So a trusted
 * reading is a number; settling is a progress form with no number in it at all; and
 * uncertain visibly withdraws the number rather than blanking the card.
 *
 * The card is present in every state including the locked and unsupported ones. A
 * missing card reads as a bug; a card that explains itself reads as the hardware
 * (Principle II).
 */
@Composable
private fun HeartRateCard(
    ui: HeartRateUi,
    modifier: Modifier = Modifier,
) {
    // The container weight follows the state, animated so a change of emphasis is a
    // transition rather than a repaint. Colour is additive here: every state also differs
    // in icon, copy and whether a number is present, so nothing is carried by hue alone.
    val container by animateColorAsState(
        targetValue =
            when (ui.kind.emphasis) {
                HeartRateUi.Emphasis.PROMINENT -> MaterialTheme.colorScheme.primaryContainer
                HeartRateUi.Emphasis.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
                HeartRateUi.Emphasis.QUIET -> MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = GreenPodsMotion.effects(),
        label = "heart-rate-container",
    )
    val onContainer by animateColorAsState(
        targetValue =
            when (ui.kind.emphasis) {
                HeartRateUi.Emphasis.PROMINENT -> MaterialTheme.colorScheme.onPrimaryContainer
                HeartRateUi.Emphasis.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
                HeartRateUi.Emphasis.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = GreenPodsMotion.effects(),
        label = "heart-rate-on-container",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = onContainer,
        // Expressive corner language: generous and consistent with the pod card that
        // contains it, rather than the tighter baseline default.
        shape = MaterialTheme.shapes.large,
    ) {
        HeartRateCardContent(ui = ui, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun HeartRateCardContent(
    ui: HeartRateUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    // TalkBack must hear a state, never a bare number: "81 beats per
                    // minute" read out of context is exactly the reading-as-fact this
                    // feature spends its whole design avoiding.
                    contentDescription = ui.spoken
                    // Announced as it changes, so a blind user learns that settling
                    // finished without having to go looking. Polite, not assertive: this
                    // is never urgent, and it must not interrupt what is being read.
                    liveRegion = LiveRegionMode.Polite
                },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The leading mark is three different things, and the transition between them is
        // where "the sensor found its footing" is expressed. A spinner that vanishes and
        // a number that appears in its place is the same information delivered as a jump.
        AnimatedContent(
            targetState = ui.kind,
            transitionSpec = {
                (fadeIn(GreenPodsMotion.effects()) + scaleIn(GreenPodsMotion.defaultSpatial(), initialScale = 0.7f))
                    .togetherWith(fadeOut(GreenPodsMotion.effects()))
            },
            label = "heart-rate-mark",
        ) { kind ->
            when {
                kind.showsProgress -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                // Beating at the rate it is showing — see HeartBeatIcon. This is the one
                // animation here whose timing is data.
                kind == HeartRateUi.Kind.MEASURING && ui.beatsPerMinute != null -> {
                    HeartBeatIcon(beatsPerMinute = ui.beatsPerMinute, tint = LocalContentColor.current)
                }

                else -> {
                    Icon(Icons.Filled.MonitorHeart, contentDescription = null)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            // Withdrawn, not blanked. `AnimatedVisibility` shrinking the number away is
            // the difference between "the reading is no longer trustworthy" and "the app
            // lost your reading" — FR-006 asks for the first, and a value that simply
            // disappears communicates the second.
            AnimatedVisibility(
                visible = ui.beatsPerMinute != null,
                enter = fadeIn(GreenPodsMotion.effects()) + expandVertically(GreenPodsMotion.defaultSpatial()),
                exit = fadeOut(GreenPodsMotion.effects()) + shrinkVertically(GreenPodsMotion.defaultSpatial()),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // Kept across recompositions so the number does not blink out during
                    // the exit animation it is the subject of.
                    val shown = remember(ui.beatsPerMinute) { ui.beatsPerMinute }
                    Text(
                        shown?.toString().orEmpty(),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        HeartRateCopy.UNIT,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            if (ui.beatsPerMinute == null) {
                Text(ui.title, style = MaterialTheme.typography.titleMedium)
            }

            Text(
                ui.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
