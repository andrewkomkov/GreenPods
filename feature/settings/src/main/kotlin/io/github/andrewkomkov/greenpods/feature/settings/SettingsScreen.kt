package io.github.andrewkomkov.greenpods.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding

/**
 * Settings, head-gesture bindings, and the diagnostics that explain why a feature
 * is unavailable on this particular phone.
 */
@Composable
fun SettingsScreen(
    bindings: List<HeadGestureBinding>,
    transportSummary: String,
    updateSummary: String,
    onBindingChanged: (HeadGestureBinding) -> Unit,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Head gestures", style = MaterialTheme.typography.titleMedium)
        Text(
            "Bind an action to a head movement. Needs head tracking, which requires " +
                "the AAP transport.",
            style = MaterialTheme.typography.bodySmall,
        )
        bindings.forEach { binding ->
            GestureBindingRow(binding = binding, onChanged = onBindingChanged)
        }

        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Text(transportSummary, modifier = Modifier.padding(16.dp))
        }

        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(updateSummary)
                Button(onClick = onCheckForUpdates) { Text("Check for updates") }
            }
        }
    }
}

@Composable
private fun GestureBindingRow(
    binding: HeadGestureBinding,
    onChanged: (HeadGestureBinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(binding.gesture.name, modifier = Modifier.weight(0.35f))

        Box(Modifier.weight(0.45f)) {
            TextButton(onClick = { expanded = true }) { Text(binding.action.name) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.name) },
                        onClick = {
                            expanded = false
                            onChanged(binding.copy(action = action))
                        },
                    )
                }
            }
        }

        Switch(
            checked = binding.enabled,
            onCheckedChange = { onChanged(binding.copy(enabled = it)) },
        )
    }
}
