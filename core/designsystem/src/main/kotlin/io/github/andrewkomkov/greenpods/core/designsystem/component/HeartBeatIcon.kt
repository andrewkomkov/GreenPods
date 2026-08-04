package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A heart that beats at the rate it is displaying.
 *
 * The point of this is that the motion is *information*. An icon pulsing on a designer's
 * loop says only "this is a heart-rate thing"; one pulsing at the measured rate says what
 * the rate is, before the number has been read — and a glance is enough to tell 55 from
 * 150. It is the one animation in this app whose timing is data rather than taste.
 *
 * The shape of the beat is borrowed from a cardiac cycle rather than a sine wave: a fast
 * contraction, a smaller second rise, then a long rest. A symmetric pulse at 60 bpm reads
 * as breathing; this reads as a heartbeat.
 *
 * [beatsPerMinute] is clamped to the same plausible range the decoders enforce, so a
 * value that should never have reached the UI cannot produce a strobe.
 */
@Composable
fun HeartBeatIcon(
    beatsPerMinute: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val periodMillis = beatPeriodMillis(beatsPerMinute)

    val transition = rememberInfiniteTransition(label = "heartbeat")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    keyframes {
                        durationMillis = periodMillis
                        // Systole: the fast squeeze that carries the beat.
                        1f at 0 using LinearEasing
                        SYSTOLE_SCALE at (periodMillis * 0.12f).roundToInt() using LinearEasing
                        1f at (periodMillis * 0.26f).roundToInt() using LinearEasing
                        // The smaller second rise, then rest for the remainder — the rest
                        // is what makes a slow rate read as slow instead of merely gentle.
                        DIASTOLE_SCALE at (periodMillis * 0.34f).roundToInt() using LinearEasing
                        1f at (periodMillis * 0.46f).roundToInt() using LinearEasing
                        1f at periodMillis
                    },
                repeatMode = RepeatMode.Restart,
            ),
        label = "beat",
    )

    Icon(
        imageVector = Icons.Filled.Favorite,
        // Null: the card as a whole carries the spoken description, and an icon that
        // announced itself separately would interrupt the state with an ornament (T080).
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size).scale(scale),
    )
}

/**
 * How long one beat lasts, in milliseconds, for a given rate.
 *
 * Extracted so the arithmetic that decides the animation's timing can be tested without
 * a screen — the whole claim of this component is that its timing is the data, and a
 * claim like that is worth pinning.
 */
internal fun beatPeriodMillis(beatsPerMinute: Int): Int =
    (MILLIS_PER_MINUTE / beatsPerMinute.coerceIn(MIN_PLAUSIBLE_BPM, MAX_PLAUSIBLE_BPM).toFloat()).roundToInt()

/** Matches `HeartRateReading`'s construction-time range; a stray value must not strobe. */
private const val MIN_PLAUSIBLE_BPM = 25
private const val MAX_PLAUSIBLE_BPM = 250
private const val MILLIS_PER_MINUTE = 60_000f
private const val SYSTOLE_SCALE = 1.18f
private const val DIASTOLE_SCALE = 1.07f
