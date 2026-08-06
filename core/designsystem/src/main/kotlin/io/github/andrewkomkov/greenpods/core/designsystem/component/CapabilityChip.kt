package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One hardware feature, whether it is reachable, and — when it is not — why. */
data class CapabilityUi(
    val label: String,
    val available: Boolean,
    val reason: String = "",
)

/**
 * Shows a feature and whether it is reachable.
 *
 * Gated features are shown rather than hidden. "AirPods Pro can do noise cancellation
 * but this phone's Bluetooth stack will not carry the command" is information the user
 * needs; a missing button just looks like a bug. Tapping a locked chip reveals the
 * specific reason, which is the difference between an explanation and an excuse.
 */
@Composable
fun CapabilityChip(
    capability: CapabilityUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    AssistChip(
        onClick = onClick,
        modifier =
            modifier.semantics {
                contentDescription =
                    if (capability.available) {
                        "${capability.label}, available"
                    } else {
                        "${capability.label}, locked. ${capability.reason}"
                    }
            },
        label = { Text(capability.label) },
        leadingIcon = {
            Icon(
                imageVector = if (capability.available) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
            )
        },
        colors =
            AssistChipDefaults.assistChipColors(
                labelColor =
                    if (capability.available) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                leadingIconContentColor =
                    if (capability.available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
    )
}

/**
 * Every capability of one accessory.
 *
 * Wrapping rather than scrolling horizontally: the full list is the point, and a row
 * that runs off-screen hides exactly the locked features this is meant to surface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityRow(
    capabilities: List<CapabilityUi>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<CapabilityUi?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            capabilities.forEach { capability ->
                CapabilityChip(
                    capability = capability,
                    onClick = { selected = if (selected == capability) null else capability },
                )
            }
        }

        AnimatedVisibility(visible = selected != null) {
            Text(
                text = selected?.reason.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The same information, sized to how much of it is worth reading.
 *
 * [CapabilityRow] renders every capability as a chip, which is right when several are
 * locked and wrong when none are: five identical green chips take a third of the card to
 * say "everything works" five times, and the eye has to read all five to find that out.
 * Worse, when one *is* locked it sits in a row of near-identical shapes, which is the
 * opposite of what "locked, not hidden" is for — the locked one is the informative one and
 * it should be the one that stands out.
 *
 * So: locked capabilities keep their chips, because each carries a different reason and a
 * user may want to tap it. Everything that works collapses into one line, because "it
 * works" is the same sentence however many features are saying it.
 */
@Composable
fun CapabilitySummary(
    capabilities: List<CapabilityUi>,
    modifier: Modifier = Modifier,
) {
    val locked = capabilities.filterNot(CapabilityUi::available)
    val working = capabilities.filter(CapabilityUi::available)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locked.isNotEmpty()) {
            CapabilityRow(capabilities = locked)
        }

        if (working.isNotEmpty()) {
            val text =
                if (locked.isEmpty()) {
                    "Everything works on this phone"
                } else {
                    "${working.size} other ${if (working.size == 1) "feature works" else "features work"}"
                }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier.semantics {
                        contentDescription = working.joinToString { it.label } + " available"
                    },
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
