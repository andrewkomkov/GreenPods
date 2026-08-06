package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion

/**
 * One battery level as a ring, with the component's name under it.
 *
 * A ring rather than a bar because the three components — left, right, case — are
 * peers, and three rings side by side read as one object at a glance, which is how
 * people actually check their AirPods.
 *
 * A null [levelPercent] draws an empty track and shows an em dash: the accessory said
 * "unknown", which is not the same as zero and must never look like it.
 */
@Composable
fun BatteryRing(
    label: String,
    levelPercent: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier,
    caption: String = "",
    size: androidx.compose.ui.unit.Dp = 88.dp,
) {
    val target = (levelPercent ?: 0) / 100f
    val progress by animateFloatAsState(target, GreenPodsMotion.defaultSpatial(), label = "battery-$label")

    // Contrast against the card, not against the window. `surfaceVariant` sits almost on
    // top of a filled card's own colour, so the ring vanished and an unknown level read as
    // a bare dash floating in space — the case, which is unknown most of the time, looked
    // like a rendering fault rather than like a gauge with nothing in it.
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val indicator =
        when {
            levelPercent == null -> track
            charging -> MaterialTheme.colorScheme.tertiary
            levelPercent <= LOW_BATTERY -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }

    Column(
        modifier =
            modifier.semantics {
                contentDescription =
                    buildString {
                        append(label)
                        append(", ")
                        append(levelPercent?.let { "$it percent" } ?: "level unknown")
                        if (charging) append(", charging")
                    }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * STROKE_FRACTION
                drawArc(
                    color = track,
                    startAngle = START_ANGLE,
                    sweepAngle = FULL_SWEEP,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (progress > 0f) {
                    drawArc(
                        color = indicator,
                        startAngle = START_ANGLE,
                        sweepAngle = FULL_SWEEP * progress,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }

            if (charging) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = indicator,
                    modifier = Modifier.size(size / 3),
                )
            } else {
                Text(
                    text = levelPercent?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (levelPercent == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
            }
        }

        Text(label, style = MaterialTheme.typography.labelMedium)
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the ring starts and how far it sweeps: a gap at the bottom, like a gauge. */
private const val START_ANGLE = 135f
private const val FULL_SWEEP = 270f
private const val STROKE_FRACTION = 0.11f

/** Visible on a filled card without competing with the level itself. */
private const val TRACK_ALPHA = 0.14f
private const val LOW_BATTERY = 20
