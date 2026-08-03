package io.github.andrewkomkov.greenpods.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoiseAware
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.SectionCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.SwitchRow
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode

/**
 * Noise control and adaptive audio.
 *
 * Every control here writes over the Apple protocol, so the whole screen is disabled
 * when that transport is unavailable — which is the common case on stock Android. The
 * controls stay visible and the reason is stated inline: a missing button reads as a
 * bug, a locked one explains the platform.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(
    state: ControlsUiState,
    modifier: Modifier = Modifier,
    onProbe: () -> Unit = {},
    onModeSelected: (NoiseControlMode) -> Unit = {},
    onAdaptiveStrengthChanged: (Int) -> Unit = {},
    onAdaptiveStrengthCommitted: () -> Unit = {},
    onConversationalAwarenessChanged: (Boolean) -> Unit = {},
    onCycleModeToggled: (NoiseControlMode) -> Unit = {},
) {
    val enabled = state.controlAvailable

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GateCard(state = state, onProbe = onProbe)

        state.notice?.let { notice ->
            SectionCard(title = "Not applied", tonal = true) { Text(notice) }
        }

        if (state.supportsNoiseControl) {
            SectionCard(
                title = "Noise control",
                subtitle = "Off, cancellation, transparency or adaptive.",
                icon = Icons.Filled.NoiseAware,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoiseControlMode.entries.forEach { candidate ->
                        FilterChip(
                            selected = state.mode == candidate,
                            onClick = { onModeSelected(candidate) },
                            enabled = enabled,
                            label = { Text(candidate.label()) },
                        )
                    }
                }
            }
        }

        if (state.supportsAdaptiveAudio) {
            SectionCard(
                title = "Adaptive audio noise",
                subtitle =
                    "How much noise is filtered in Adaptive mode. Apple's own UI offers three " +
                        "steps; the protocol accepts any value from 0 to 100.",
                icon = Icons.Filled.GraphicEq,
            ) {
                Text("${state.adaptiveStrength}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = state.adaptiveStrength.toFloat(),
                    onValueChange = { onAdaptiveStrengthChanged(it.toInt()) },
                    onValueChangeFinished = onAdaptiveStrengthCommitted,
                    valueRange = 0f..100f,
                    enabled = enabled && state.mode == NoiseControlMode.ADAPTIVE,
                )
                if (enabled && state.mode != NoiseControlMode.ADAPTIVE) {
                    Text(
                        "Only has an effect while Adaptive is selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.supportsConversationalAwareness) {
            SectionCard(
                title = "Conversational awareness",
                icon = Icons.Filled.RecordVoiceOver,
            ) {
                SwitchRow(
                    title = "Lower the volume when I speak",
                    checked = state.conversationalAwareness,
                    onCheckedChange = onConversationalAwarenessChanged,
                    enabled = enabled,
                    description = "The buds detect your own voice and duck the music.",
                )
            }
        }

        if (state.supportsNoiseControl) {
            SectionCard(
                title = "Stem long-press cycle",
                subtitle = "Which modes a long press moves between. At least one is required.",
                icon = Icons.Filled.Hearing,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoiseControlMode.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate in state.longPressCycle,
                            onClick = { onCycleModeToggled(candidate) },
                            enabled = enabled,
                            label = { Text(candidate.label()) },
                        )
                    }
                }
            }
        }

        if (state.pod != null && !state.supportsNoiseControl) {
            SectionCard(title = "Nothing to control here") {
                Text("${state.pod.model.displayName} has no adjustable listening modes.")
            }
        }
    }
}

/**
 * The gate, stated plainly at the top of the screen.
 *
 * This card is the single most important thing on it: without it, every disabled
 * control below looks like a bug in GreenPods rather than a limit of the phone.
 */
@Composable
private fun GateCard(
    state: ControlsUiState,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = if (state.controlAvailable) "Controls are live" else "Controls unavailable",
        subtitle = state.gateReason,
        icon = if (state.controlAvailable) Icons.Filled.NoiseAware else Icons.Filled.Lock,
        tonal = !state.controlAvailable,
        modifier = modifier,
    ) {
        if (state.probing) {
            CircularProgressIndicator(Modifier.padding(4.dp))
        } else if (state.pod != null) {
            OutlinedButton(onClick = onProbe, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.NoiseAware, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(if (state.controlAvailable) "Re-check the channel" else "Check again")
            }
        }
    }
}

private fun NoiseControlMode.label(): String =
    when (this) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.NOISE_CANCELLATION -> "ANC"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
    }
