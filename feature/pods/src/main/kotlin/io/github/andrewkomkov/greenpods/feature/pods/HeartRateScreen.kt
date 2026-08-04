package io.github.andrewkomkov.greenpods.feature.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.HeartBeatIcon

/**
 * The measurement, given the whole display.
 *
 * The card on the main screen has to share space with three battery rings, which caps how
 * large the number can be; a reading someone is actually watching — during a walk, at the
 * end of a set — wants to be readable at arm's length. So the card grows into a screen on
 * tap rather than shouting on the list.
 *
 * Nothing is added on the way here. The same state, the same confidence gate, the same
 * eight shapes: this is the card at a different size, not a second opinion about the
 * reading. Below it sits what running the sensor costs, which is the part people are
 * entitled to know while it is running rather than only before they switch it on.
 */
@Composable
fun HeartRateScreen(
    ui: HeartRateUi,
    intervalMillis: Int,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onStart: () -> Unit = {},
) {
    val measuring = ui.kind != HeartRateUi.Kind.OFF

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(ui = ui, intervalMillis = intervalMillis)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(HeartRateCopy.WHILE_MEASURING, style = MaterialTheme.typography.titleMedium)
                Fact(Icons.Filled.Hearing, HeartRateCopy.KEEP_A_BUD_IN)
                Fact(Icons.Filled.BatteryFull, HeartRateCopy.SENSING_COSTS_BATTERY)
                Fact(Icons.Filled.Notifications, HeartRateCopy.ONGOING_NOTIFICATION)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Stopping is one press and needs no confirmation — the sensor is not holding
        // anything that could be lost by turning it off, and a dialog here would imply
        // otherwise.
        Button(
            onClick = if (measuring) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors =
                if (measuring) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
        ) {
            Icon(
                if (measuring) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (measuring) HeartRateCopy.STOP else HeartRateCopy.START,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/**
 * The number, or the state standing in its place.
 *
 * The gate does not soften because there is more room: a settling sensor gets no digits
 * on this screen either, and the reason it has none is spelled out where a number would
 * otherwise be the first thing read.
 */
@Composable
private fun Hero(
    ui: HeartRateUi,
    intervalMillis: Int,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val reading = ui.kind == HeartRateUi.Kind.MEASURING && ui.beatsPerMinute != null

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = ui.spoken
                    liveRegion = LiveRegionMode.Polite
                },
        shape = shape,
        color =
            if (reading) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        contentColor =
            if (reading) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
    ) {
        Box(Modifier.clip(shape), contentAlignment = Alignment.Center) {
            if (reading) Glow(Modifier.align(Alignment.BottomStart), size = 200.dp)

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (reading) {
                    HeartBeatIcon(
                        beatsPerMinute = ui.beatsPerMinute ?: 0,
                        size = 56.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            ui.beatsPerMinute.toString(),
                            style = MaterialTheme.typography.displayLarge,
                        )
                        Text(
                            HeartRateCopy.UNIT,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Text(
                        HeartRateCopy.cadence(intervalMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 300.dp).alpha(BODY_ALPHA),
                    )
                } else {
                    Text(ui.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        ui.body,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 300.dp).alpha(BODY_ALPHA),
                    )
                }
            }
        }
    }
}

@Composable
private fun Fact(
    icon: ImageVector,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val BODY_ALPHA = 0.8f
