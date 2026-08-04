package io.github.andrewkomkov.greenpods.feature.pods

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.component.HeartBeatIcon
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedCard
import io.github.andrewkomkov.greenpods.core.designsystem.component.dashedOutline
import io.github.andrewkomkov.greenpods.core.designsystem.component.hatched
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion

/**
 * The heart-rate card, in eight shapes.
 *
 * Every state gets a **different shape**, not a different string in the same shape. FR-008
 * is that a settling sensor must never be mistaken for a result, and two states that
 * differ only in wording are two states a glance cannot tell apart — people read the
 * layout long before they read the caption. So:
 *
 * - a trusted reading is a number, and the largest thing on the card;
 * - settling is a hatched block *exactly where the number will be*, which says a number
 *   is coming without ever showing a digit;
 * - uncertain is a dashed outline of the same block, which says the number was taken
 *   back rather than lost;
 * - starting is a spinner with a reason attached;
 * - and the four quiet states each carry their own icon and their own sentence.
 *
 * The card is present in every one of them, including locked and unsupported. A missing
 * card reads as a bug; a card that explains itself reads as the hardware (Principle II).
 */
@Composable
fun HeartRateCard(
    ui: HeartRateUi,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
    onTurnOn: () -> Unit = {},
) {
    val shape = MaterialTheme.shapes.large

    // Only a state with something more to show is worth a tap. Opening a full screen to
    // read the same sentence again is a dead end dressed up as a destination.
    val opens = ui.kind == HeartRateUi.Kind.MEASURING || ui.kind.showsProgress
    val described =
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                // TalkBack must hear a state, never a bare number: "81 beats per minute"
                // read out of context is exactly the reading-as-fact this feature spends
                // its whole design avoiding.
                contentDescription = ui.spoken
                // Announced as it changes, so a blind user learns that settling finished
                // without having to go looking. Polite, not assertive: this is never
                // urgent, and it must not interrupt what is being read.
                liveRegion = LiveRegionMode.Polite
            }.let { if (opens) it.clip(shape).clickable(onClick = onOpen) else it }

    when (ui.kind) {
        HeartRateUi.Kind.MEASURING -> MeasuringCard(ui, described, shape)
        HeartRateUi.Kind.SETTLING -> SettlingCard(ui, described, shape)
        HeartRateUi.Kind.STARTING -> StartingCard(ui, described, shape)
        HeartRateUi.Kind.UNCERTAIN -> UncertainCard(ui, described, shape)
        HeartRateUi.Kind.OFF -> OffCard(ui, described, shape, onTurnOn)
        HeartRateUi.Kind.UNAVAILABLE -> QuietCard(ui, described, shape, Icons.Filled.HearingDisabled)
        HeartRateUi.Kind.UNSUPPORTED -> QuietCard(ui, described, shape, Icons.Filled.SensorsOff)
        HeartRateUi.Kind.LOCKED -> LockedCard(title = ui.title, body = ui.body, modifier = described)
    }
}

/**
 * The one state that carries a number, drawn as the loudest thing on the pod card.
 *
 * The heart beats at the rate it is showing — the app's only animation whose timing is
 * data rather than taste — so 55 is distinguishable from 150 before the digits have been
 * read at all.
 */
@Composable
private fun MeasuringCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.clip(shape)) {
            Glow(Modifier.align(Alignment.TopEnd))

            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeartBeatIcon(
                    beatsPerMinute = ui.beatsPerMinute ?: 0,
                    size = 40.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Withdrawn, not blanked. `AnimatedVisibility` shrinking the number
                    // away is the difference between "the reading is no longer
                    // trustworthy" and "the app lost your reading" — FR-006 asks for the
                    // first, and a value that simply disappears communicates the second.
                    AnimatedVisibility(
                        visible = ui.beatsPerMinute != null,
                        enter =
                            fadeIn(GreenPodsMotion.effects()) +
                                expandVertically(GreenPodsMotion.defaultSpatial()),
                        exit =
                            fadeOut(GreenPodsMotion.effects()) +
                                shrinkVertically(GreenPodsMotion.defaultSpatial()),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // Kept across recompositions so the number does not blink out
                            // during the exit animation it is the subject of.
                            val shown = remember(ui.beatsPerMinute) { ui.beatsPerMinute }
                            Text(
                                shown?.toString().orEmpty(),
                                style = MaterialTheme.typography.displaySmall,
                            )
                            Text(
                                HeartRateCopy.UNIT,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Text(ui.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(BODY_ALPHA))
                }
            }
        }
    }
}

/**
 * Reports are arriving and none of them can be trusted yet.
 *
 * The hatched block is the whole idea: it is the size and position of the number that is
 * coming, so the card's shape already says "a value goes here" while containing no digit
 * that could be misread as one. FR-008's failure mode is a settling sensor that looks
 * like a measurement, and it fails precisely when the two states share a silhouette.
 */
@Composable
private fun SettlingCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
) {
    ActiveCard(modifier = modifier, shape = shape) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                strokeWidth = 3.dp,
                color = LocalContentColor.current,
                trackColor = LocalContentColor.current.copy(alpha = TRACK_ALPHA),
            )
            // Drifting rather than beating: there is no rate to beat at yet, and a pulse
            // on a designer's loop here would be the one animation in the app that lies.
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(20.dp).alpha(drift()),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(PLACEHOLDER_WIDTH)
                        .height(PLACEHOLDER_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .hatched(LocalContentColor.current.copy(alpha = PLACEHOLDER_ALPHA), thickness = 2.dp),
                )
                Text(ui.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(ui.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(BODY_ALPHA))
        }
    }
}

/** A wait with a reason attached, which is the difference between this and a bare spinner. */
@Composable
private fun StartingCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
) {
    ActiveCard(modifier = modifier, shape = shape) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            strokeWidth = 3.dp,
            color = LocalContentColor.current,
            trackColor = LocalContentColor.current.copy(alpha = TRACK_ALPHA),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ui.title, style = MaterialTheme.typography.titleMedium)
            Text(ui.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(BODY_ALPHA))
        }
    }
}

/**
 * Confidence fell away mid-measurement, and the number went with it.
 *
 * The dashed outline is the same block the number occupied, empty and visibly so: the
 * value was taken back rather than lost. A card that simply went blank would be read as
 * the app breaking, which is the interpretation the whole confidence gate exists to
 * avoid earning.
 */
@Composable
private fun UncertainCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
) {
    ActiveCard(modifier = modifier, shape = shape) {
        val ink = LocalContentColor.current

        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(32.dp).alpha(WITHDRAWN_ALPHA),
            )
            // Struck through: the sensor is still running, but this reading is not one
            // GreenPods will stand behind.
            Canvas(Modifier.size(40.dp)) {
                drawLine(
                    color = ink.copy(alpha = STRIKE_ALPHA),
                    start = Offset(size.width * 0.2f, size.height * 0.8f),
                    end = Offset(size.width * 0.8f, size.height * 0.2f),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(PLACEHOLDER_WIDTH)
                        .height(PLACEHOLDER_HEIGHT)
                        .dashedOutline(ink.copy(alpha = WITHDRAWN_ALPHA)),
                )
                Text(ui.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(ui.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(BODY_ALPHA))
        }
    }
}

/**
 * Off reads as a choice, and carries the switch that undoes it.
 *
 * The cost sits next to the button rather than behind a trip to Settings: FR-012 wants
 * the earbuds' battery mentioned *before* the switch can be moved, and that is only true
 * of the screen where the switch actually is.
 */
@Composable
private fun OffCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
    onTurnOn: () -> Unit,
) {
    QuietCard(ui = ui, modifier = modifier, shape = shape, icon = Icons.Filled.MonitorHeart) {
        OutlinedButton(onClick = onTurnOn) { Text(HeartRateCopy.TURN_ON) }
    }
}

/** A state that is only explaining itself: an icon, a fact, and nothing to press. */
@Composable
private fun QuietCard(
    ui: HeartRateUi,
    modifier: Modifier,
    shape: Shape,
    icon: ImageVector,
    trailing: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(ui.body, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }
}

/** The container the three in-progress states share: present, and quieter than a result. */
@Composable
private fun ActiveCard(
    modifier: Modifier,
    shape: Shape,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A soft disc bleeding off the corner, so the one card with a number has some depth. */
@Composable
internal fun Glow(
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size).offset(x = size / 5, y = -size / 4)) {
        drawCircle(color = tint.copy(alpha = GLOW_ALPHA), radius = this.size.minDimension / 2f)
    }
}

/** Slow breathing opacity for a sensor that has not settled. */
@Composable
private fun drift(): Float {
    val transition = rememberInfiniteTransition(label = "settling")
    val alpha by transition.animateFloat(
        initialValue = DRIFT_LOW,
        targetValue = DRIFT_HIGH,
        animationSpec = infiniteRepeatable(tween(DRIFT_MILLIS), RepeatMode.Reverse),
        label = "drift",
    )
    return alpha
}

/** Where the number goes, so its absence has the same footprint as its presence. */
private val PLACEHOLDER_WIDTH = 86.dp
private val PLACEHOLDER_HEIGHT = 30.dp

private const val BODY_ALPHA = 0.85f
private const val PLACEHOLDER_ALPHA = 0.3f
private const val WITHDRAWN_ALPHA = 0.45f
private const val STRIKE_ALPHA = 0.55f
private const val TRACK_ALPHA = 0.22f
private const val GLOW_ALPHA = 0.09f
private const val DRIFT_LOW = 0.35f
private const val DRIFT_HIGH = 0.9f
private const val DRIFT_MILLIS = 800
