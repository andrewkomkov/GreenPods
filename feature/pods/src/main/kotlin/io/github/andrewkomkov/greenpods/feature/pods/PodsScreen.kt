package io.github.andrewkomkov.greenpods.feature.pods

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.BatteryRing
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityRow
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityUi
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
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
                    // The case only puts its charge in the advertisement when it has
                    // reason to — with the lid open, or the buds inside. A closed case in
                    // a pocket sends the "unknown" sentinel, and a bare dash reads as a
                    // broken app rather than as the protocol working as designed.
                    caption = if (pod.battery.case.levelPercent == null) "open the lid" else "",
                )
            }

            HeartRateCard(state = pod.heartRate, sensing = pod.heartRateSensing)

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
    state: HeartRateState,
    sensing: HeartRateSensing,
    modifier: Modifier = Modifier,
) {
    val reading = state.trustedReading

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    // TalkBack must hear a state, never a bare number: "81 beats per
                    // minute" read out of context is exactly the reading-as-fact this
                    // feature spends its whole design avoiding.
                    contentDescription = HeartRateCopy.spoken(state, sensing)
                },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is HeartRateState.Settling || state is HeartRateState.Starting) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.MonitorHeart, contentDescription = null)
        }

        Column(Modifier.weight(1f)) {
            if (reading != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        reading.beatsPerMinute.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        HeartRateCopy.UNIT,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            } else {
                Text(HeartRateCopy.title(state), style = MaterialTheme.typography.titleMedium)
            }

            Text(
                HeartRateCopy.body(state, sensing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every word the heart-rate card can say, in one object.
 *
 * Here rather than inline so a unit test can read all of it at once and assert what it
 * does **not** contain. FR-010 forbids presenting this as a medical measurement, and the
 * way that requirement decays is one well-meant sentence at a time — "normal", "resting
 * rate", a range, a comparison. A list a test can walk is the only version of that rule
 * that survives the next person adding a state.
 */
internal object HeartRateCopy {
    const val UNIT = "bpm"

    fun title(state: HeartRateState): String =
        when (state) {
            is HeartRateState.Measuring -> "${state.reading.beatsPerMinute} $UNIT"
            is HeartRateState.Settling -> "Measuring…"
            is HeartRateState.Starting -> "Starting the sensor…"
            is HeartRateState.Uncertain -> "Reading uncertain"
            is HeartRateState.Off -> "Heart rate is off"
            is HeartRateState.Unavailable -> "Not measuring"
            is HeartRateState.Locked -> "Heart rate is locked"
            is HeartRateState.Unsupported -> "No heart-rate sensor"
        }

    fun body(
        state: HeartRateState,
        sensing: HeartRateSensing = HeartRateSensing.Idle,
    ): String =
        when (state) {
            is HeartRateState.Measuring -> {
                route(state.reading.source)
            }

            is HeartRateState.Settling -> {
                "The sensor is still settling. No number is shown until it is worth showing."
            }

            is HeartRateState.Starting -> {
                "Waiting for the first report from the earbuds."
            }

            is HeartRateState.Uncertain -> {
                "The earbuds report low confidence, so the last number has been withdrawn. " +
                    "Still measuring."
            }

            is HeartRateState.Off -> {
                "Turn it on in Settings. It draws on the earbuds' battery."
            }

            is HeartRateState.Unavailable -> {
                state.reason.ifBlank { "Sensing stopped." }
            }

            is HeartRateState.Locked -> {
                state.reason
            }

            is HeartRateState.Unsupported -> {
                state.reason
            }
        }.let { text ->
            val stop = sensing.lastStopReason
            if (state is HeartRateState.Unavailable && !stop.isNullOrBlank()) stop else text
        }

    /**
     * Which route produced the number.
     *
     * Named rather than hidden: the two routes reach the phone completely differently
     * and only one of them publishes a confidence value, so a user comparing readings
     * deserves to know which they are looking at (FR-026, R-10).
     */
    fun route(source: HeartRateReading.Source): String =
        when (source) {
            HeartRateReading.Source.AAP -> "From the earbuds, over Apple's protocol."
            HeartRateReading.Source.GATT -> "From the earbuds, over the Bluetooth heart-rate profile."
        }

    /** What TalkBack says: the state first, the number only inside it. */
    fun spoken(
        state: HeartRateState,
        sensing: HeartRateSensing = HeartRateSensing.Idle,
    ): String =
        when (state) {
            is HeartRateState.Measuring -> "Heart rate, ${state.reading.beatsPerMinute} beats per minute"
            is HeartRateState.Settling -> "Heart rate, measuring in progress, no reading yet"
            is HeartRateState.Starting -> "Heart rate, starting the sensor"
            else -> "Heart rate, ${title(state).lowercase()}"
        } + ". " + body(state, sensing)

    /** Everything the card can say, for the test that checks none of it is clinical. */
    fun everySentence(): List<String> {
        val reading =
            HeartRateReading(
                beatsPerMinute = 81,
                confidence = 205,
                source = HeartRateReading.Source.AAP,
                measuredAtEpochMillis = 0L,
            )
        val states =
            listOf(
                HeartRateState.Measuring(reading),
                HeartRateState.Measuring(reading.copy(source = HeartRateReading.Source.GATT, confidence = null)),
                HeartRateState.Settling(0L),
                HeartRateState.Starting(0L),
                HeartRateState.Uncertain(0L),
                HeartRateState.Off,
                HeartRateState.Unavailable("The earbuds are not being worn."),
                HeartRateState.Locked("This phone cannot open the Apple protocol channel.", Transport.AAP_L2CAP),
                HeartRateState.Unsupported("These earbuds have no heart-rate sensor."),
            )
        return states.flatMap { state -> listOf(title(state), body(state), spoken(state)) } + UNIT
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
