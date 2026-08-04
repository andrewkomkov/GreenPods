package io.github.andrewkomkov.greenpods.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NoiseAware
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.SectionCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.SegmentedChoice
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

    // Locked controls stay on screen, legible, in their real order — just visibly out of
    // reach. Hiding them would hide the answer to the question this screen exists to
    // answer, which is what this phone can and cannot do with these earbuds.
    val reachable = Modifier.alpha(if (enabled) 1f else UNREACHABLE_ALPHA)

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
                modifier = reachable,
            ) {
                SegmentedChoice(
                    options = NoiseControlMode.entries,
                    selected = { it == state.mode },
                    label = NoiseControlMode::label,
                    onSelect = onModeSelected,
                    enabled = enabled,
                )
            }
        }

        if (state.supportsAdaptiveAudio) {
            SectionCard(
                title = "Adaptive audio noise",
                subtitle = "How much noise is filtered while Adaptive is on.",
                icon = Icons.Filled.GraphicEq,
                modifier = reachable,
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
                modifier = reachable,
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
                modifier = reachable,
            ) {
                // Several may be on at once — a long press cycles through whatever is
                // checked — so this is the same connected group used as a multi-select.
                SegmentedChoice(
                    options = NoiseControlMode.entries,
                    selected = { it in state.longPressCycle },
                    label = NoiseControlMode::label,
                    onSelect = onCycleModeToggled,
                    enabled = enabled,
                )
            }
        }

        if (state.pod != null && !state.supportsNoiseControl) {
            SectionCard(title = "Nothing to control here") {
                Text("${state.pod.model.displayName} has no adjustable listening modes.")
            }
        }

        // Last, because it is only worth reading once the locked controls above have been
        // seen — and because it is the only thing on this screen that actually recovers
        // them today.
        if (!enabled && state.pod != null && state.supportsNoiseControl) {
            SectionCard(
                title = ControlsUiState.WORKAROUND_TITLE,
                icon = Icons.Filled.Lightbulb,
                tonal = true,
            ) {
                Text(ControlsUiState.WORKAROUND_BODY, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The gate, stated plainly at the top of the screen.
 *
 * This card is the single most important thing on it: without it, every disabled control
 * below looks like a bug in GreenPods rather than a limit of the phone. Locked, it wears
 * the same outline-and-hatch the locked chips and the locked heart-rate card wear, so
 * "this phone will not do that" is one thing the user learns once instead of four
 * unrelated greys they each have to work out.
 */
@Composable
private fun GateCard(
    state: ControlsUiState,
    onProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.controlAvailable) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text("Controls are live", style = MaterialTheme.typography.titleMedium)
                    Text(state.gateReason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }

    LockedCard(
        title = "This phone can't change these",
        body = state.gateReason,
        modifier = modifier,
    ) {
        if (state.probing) {
            CircularProgressIndicator(Modifier.padding(4.dp))
        } else if (state.pod != null) {
            OutlinedButton(onClick = onProbe, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }
    }
}

internal fun NoiseControlMode.label(): String =
    when (this) {
        NoiseControlMode.OFF -> "Off"
        NoiseControlMode.NOISE_CANCELLATION -> "ANC"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE -> "Adaptive"
    }

/**
 * Visible, readable, and plainly not something this phone will accept a press on.
 *
 * Deliberately gentle. Every control inside is *also* drawn in its own disabled colours,
 * and the two multiply: at the 0.55 the mock suggested, the section titles came out grey
 * on grey and the segment labels were barely legible. Locked has to stay readable — the
 * whole argument for showing these controls instead of hiding them is that a user can see
 * what their phone is missing.
 */
private const val UNREACHABLE_ALPHA = 0.8f
