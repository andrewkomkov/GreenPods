package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A deliberately silly face that follows your head.
 *
 * Head tracking is otherwise invisible — a stream of numbers with nothing to show
 * for it. This gives it a body: the face leans with roll, looks around with yaw and
 * pitch, and squashes when you move fast. Every value is spring-animated through
 * the expressive motion scheme rather than tweened, so it overshoots and settles
 * the way a physical object would.
 *
 * @param yawDegrees left/right rotation, negative is left
 * @param pitchDegrees up/down rotation, negative is down
 * @param rollDegrees head tilt, negative is toward the left shoulder
 * @param celebrating briefly true after a gesture is recognised
 */
@Composable
fun HeadMotionToy(
    yawDegrees: Float,
    pitchDegrees: Float,
    rollDegrees: Float,
    modifier: Modifier = Modifier,
    celebrating: Boolean = false,
    faceColor: Color = MaterialTheme.colorScheme.primaryContainer,
    featureColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val spring = GreenPodsMotion.slowSpatial<Float>()

    val yaw by animateFloatAsState(yawDegrees.coerceIn(-60f, 60f), spring, label = "yaw")
    val pitch by animateFloatAsState(pitchDegrees.coerceIn(-45f, 45f), spring, label = "pitch")
    val roll by animateFloatAsState(rollDegrees.coerceIn(-45f, 45f), spring, label = "roll")

    // A one-shot squash so a recognised gesture is felt, not just logged. The specs are
    // read here rather than inside the effect: they come from the theme now, and a
    // coroutine body is not a composable context.
    val squash = GreenPodsMotion.fastSpatial<Float>()
    val settle = GreenPodsMotion.slowSpatial<Float>()
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(celebrating) {
        if (celebrating) {
            bounce.animateTo(CELEBRATION_SCALE, squash)
            bounce.animateTo(1f, settle)
        }
    }

    Box(modifier.size(200.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            rotate(roll) {
                drawFace(
                    yaw = yaw,
                    pitch = pitch,
                    scale = bounce.value,
                    faceColor = faceColor,
                    featureColor = featureColor,
                )
            }
        }
    }
}

private fun DrawScope.drawFace(
    yaw: Float,
    pitch: Float,
    scale: Float,
    faceColor: Color,
    featureColor: Color,
) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.minDimension / 2.6f) * scale

    // Turning the head shrinks the visible width — a cheap fake of perspective
    // that reads as "looking away" far better than a flat translation does.
    val widthFactor = cos(Math.toRadians(yaw.toDouble())).toFloat().coerceAtLeast(0.55f)

    drawCircle(color = faceColor, radius = radius, center = centre)

    val gaze =
        Offset(
            x = centre.x + sin(Math.toRadians(yaw.toDouble())).toFloat() * radius * 0.35f,
            y = centre.y + sin(Math.toRadians(pitch.toDouble())).toFloat() * radius * 0.35f,
        )

    val eyeOffset = radius * 0.36f * widthFactor
    val eyeRadius = radius * 0.13f
    // Eyes narrow as the head pitches back, which is what makes it look up rather
    // than merely move up.
    val squint = (1f - abs(pitch) / 90f).coerceIn(0.45f, 1f)

    listOf(-eyeOffset, eyeOffset).forEach { dx ->
        drawOval(
            color = featureColor,
            topLeft = Offset(gaze.x + dx - eyeRadius, gaze.y - eyeRadius * squint - radius * 0.12f),
            size =
                androidx.compose.ui.geometry
                    .Size(eyeRadius * 2f, eyeRadius * 2f * squint),
        )
    }

    val mouthWidth = radius * 0.5f * widthFactor
    val mouthY = gaze.y + radius * 0.34f
    drawLine(
        color = featureColor,
        start = Offset(gaze.x - mouthWidth, mouthY),
        end = Offset(gaze.x + mouthWidth, mouthY),
        strokeWidth = radius * 0.09f,
    )
}

/** How far a recognised gesture squashes the face before it settles back. */
private const val CELEBRATION_SCALE = 1.18f
