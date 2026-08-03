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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.CapabilityRow
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.WearState

/**
 * The main screen: what is nearby, how charged it is, and what this phone can
 * actually control.
 */
@Composable
fun PodsScreen(
    pods: List<PodState>,
    modifier: Modifier = Modifier,
    scanning: Boolean = true,
) {
    if (pods.isEmpty()) {
        EmptyState(scanning = scanning, modifier = modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        items(pods, key = PodState::address) { pod -> PodCard(pod) }
    }
}

@Composable
private fun PodCard(pod: PodState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(pod.name, style = MaterialTheme.typography.titleLarge)

            BatteryRow("Left", pod.battery.left, pod.earDetection.primary)
            BatteryRow("Right", pod.battery.right, pod.earDetection.secondary)
            BatteryRow("Case", pod.battery.case, WearState.UNKNOWN)

            pod.heartRate?.let { sample ->
                Text(
                    "${sample.beatsPerMinute} bpm  ·  ${sample.source}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Gated features are listed alongside usable ones so the absence of a
            // control reads as a platform limit rather than a missing feature.
            CapabilityRow(
                labels =
                    (
                        pod.usableFeatures.map { it.name to true } +
                            pod.gatedFeatures.map { it.name to false }
                    ).sortedBy { it.first },
            )
        }
    }
}

@Composable
private fun BatteryRow(
    label: String,
    battery: BatteryComponent,
    wear: WearState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.2f))

        val level = battery.levelPercent
        if (level == null) {
            Text("—", modifier = Modifier.weight(0.8f))
        } else {
            LinearProgressIndicator(
                progress = { level / 100f },
                modifier = Modifier.weight(0.5f),
            )
            Text(
                buildString {
                    append("$level%")
                    if (battery.isCharging) append(" ⚡")
                    if (wear == WearState.IN_EAR) append(" · in ear")
                    if (wear == WearState.IN_CASE) append(" · in case")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(0.5f),
            )
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (scanning) CircularProgressIndicator()
        Text(
            if (scanning) "Looking for nearby AirPods…" else "Scanning is off",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Open the case near your phone. GreenPods reads the advertisement " +
                "AirPods broadcast continuously, so no pairing is needed.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
