package io.github.andrewkomkov.greenpods.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode

/**
 * Noise control and adaptive audio.
 *
 * Every control here writes over AAP, so the whole screen is disabled when that
 * transport is unavailable — which is the common case on stock Android. The
 * explanation is shown inline rather than leaving dead buttons on screen.
 */
@Composable
fun ControlsScreen(
    mode: NoiseControlMode?,
    adaptiveStrength: Int,
    controlAvailable: Boolean,
    onModeSelected: (NoiseControlMode) -> Unit,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unavailableReason: String = "",
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!controlAvailable) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Controls unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(
                        unavailableReason.ifBlank {
                            "This device's Bluetooth stack refuses the L2CAP channel " +
                                "AirPods use for settings. Battery and ear detection still work."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Text("Noise control", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoiseControlMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = { onModeSelected(candidate) },
                    enabled = controlAvailable,
                    label = { Text(candidate.label()) },
                )
            }
        }

        Text("Adaptive audio noise", style = MaterialTheme.typography.titleMedium)
        Text(
            "How much noise is filtered in Adaptive mode. Apple's own UI offers only " +
                "three steps; the protocol accepts any value from 0 to 100.",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = adaptiveStrength.toFloat(),
            onValueChange = { onAdaptiveStrengthChanged(it.toInt()) },
            valueRange = 0f..100f,
            enabled = controlAvailable && mode == NoiseControlMode.ADAPTIVE,
        )
    }
}

private fun NoiseControlMode.label(): String =
    when (this) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.NOISE_CANCELLATION -> "ANC"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
    }
