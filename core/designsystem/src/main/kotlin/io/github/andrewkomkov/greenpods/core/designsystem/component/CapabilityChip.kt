package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shows a feature and whether it is reachable.
 *
 * Gated features are shown rather than hidden, because "AirPods Pro can do ANC but
 * this phone's Bluetooth stack will not let us" is information the user needs — a
 * missing button just looks like a bug.
 */
@Composable
fun CapabilityChip(
    label: String,
    available: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    AssistChip(
        onClick = onClick,
        enabled = available,
        modifier = modifier,
        label = { Text(label) },
        leadingIcon =
            if (available) {
                null
            } else {
                { Icon(Icons.Filled.Lock, contentDescription = "Not available on this device") }
            },
        colors =
            AssistChipDefaults.assistChipColors(
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}

@Composable
fun CapabilityRow(
    labels: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { (label, available) ->
            CapabilityChip(label = label, available = available)
        }
    }
}
