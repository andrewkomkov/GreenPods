package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Diagonal hatching, drawn behind whatever it is applied to.
 *
 * This is the texture half of the app's "present, but not on this phone" language. It
 * matters that it is a *texture* rather than a colour: a greyed-out card and a quiet card
 * look the same at a glance, and several states in this app are legitimately quiet. Hatch
 * reads as struck through — deliberately unavailable — from across the room, and it
 * survives being looked at by someone who cannot separate the two greys.
 *
 * Clip to the same shape as the container, or the lines will run past its corners.
 */
fun Modifier.hatched(
    color: Color,
    spacing: Dp = 8.dp,
    thickness: Dp = 3.dp,
): Modifier =
    drawBehind {
        val step = spacing.toPx()
        val height = size.height
        // Start a full height to the left so the first lines still cross the top-left
        // corner: each line is drawn up and to the right, so it enters from off-canvas.
        var x = -height
        while (x < size.width + height) {
            drawLine(
                color = color,
                start = Offset(x, height),
                end = Offset(x + height, 0f),
                strokeWidth = thickness.toPx(),
            )
            x += step
        }
    }

/**
 * A dashed outline where something used to be.
 *
 * Used for a value that has been *withdrawn* rather than one that never arrived: an
 * outline the size of the missing number says it was taken back, where an empty space
 * says the app lost it.
 */
fun Modifier.dashedOutline(
    color: Color,
    cornerRadius: Dp = 8.dp,
    thickness: Dp = 1.5.dp,
): Modifier =
    drawBehind {
        val stroke = thickness.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style =
                Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 4f, stroke * 3f)),
                ),
        )
    }

/**
 * A container for something this phone cannot do.
 *
 * Outline instead of fill, hatch instead of grey, and no shadow — three signals that all
 * say the same thing, so none of them has to be seen on its own. Everything inside stays
 * legible: a locked feature is shown, never hidden, because a control that vanished reads
 * as a bug in GreenPods and a control that explains itself reads as a property of the
 * phone.
 */
@Composable
fun LockedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, outline),
    ) {
        Column(Modifier.hatched(outline.copy(alpha = HATCH_ALPHA))) { content() }
    }
}

/**
 * The whole locked statement: the texture, a lock, and a sentence about the phone.
 *
 * [body] is the sentence a person can act on — or be relieved by. It says what their
 * phone will not do; it never names a protocol, a channel or a PSM, because none of those
 * are things they can change, and a screen that mentions them has stopped being a product
 * and started being a log.
 */
@Composable
fun LockedCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Lock,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    LockedSurface(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .padding(16.dp)
                    .semantics { contentDescription = "$title, locked. $body" },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            content()
        }
    }
}

/** Faint enough to read through, strong enough to see without looking for it. */
private const val HATCH_ALPHA = 0.14f
