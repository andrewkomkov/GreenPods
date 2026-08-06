@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.andrewkomkov.greenpods.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.designsystem.component.HeadMotionToy
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedCard
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsSpacing
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import kotlinx.coroutines.delay

/**
 * Learning what counts as a nod.
 *
 * A head gesture is the only control in this app with no feedback of its own: you move,
 * and either something happens or nothing does. When nothing does there is no way to tell
 * whether you moved too little, in the wrong axis, or at the wrong speed — so people
 * conclude the feature is broken and switch it off. This screen is the missing half of
 * that loop.
 *
 * Three things, in the order they are needed. A face that moves with your head, so the
 * connection between you and the earbuds is visible before anything is asked of you. Two
 * meters, one per axis, filling towards the threshold the detector actually uses — that
 * is what turns "not enough" into something you can see while it is still happening. And
 * a confirmation when a gesture lands, because the moment you learn from is the one where
 * you find out *that* worked.
 *
 * The sensor runs only while this screen is on screen. Leaving it stops the stream in the
 * earbuds, not merely on the phone.
 */
@Composable
fun HeadGestureScreen(
    state: HeadGestureUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // Content runs under the floating bar; this is what keeps the
                // last control reachable above it.
                .padding(bottom = GreenPodsSpacing.FloatingBarSpace),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isLocked) {
            LockedCard(
                title = "Head gestures can't run here",
                body = state.refusal.sentence(),
                icon = Icons.Filled.Face,
            )
            return@Column
        }

        Mirror(state)

        Meter(
            icon = Icons.Filled.SwapVert,
            title = "Nod",
            instruction = "Chin down and back up, in about half a second.",
            progress = state.nodProgress,
            armed = state.lastGesture == HeadGesture.NOD,
        )

        Meter(
            icon = Icons.Filled.SwapHoriz,
            title = "Shake",
            instruction = "Turn left, right, and left again — twice past the mark.",
            progress = state.shakeProgress,
            armed = state.lastGesture == HeadGesture.SHAKE,
        )

        Text(
            "The bar fills as you move. It has to reach the end for the gesture to " +
                "count, and the movement has to come back — a head that stops at the far " +
                "side is looking at something, not nodding.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The face, following the head it is reading.
 *
 * This is the part that has to work before anything else does: a person who cannot tell
 * whether the earbuds are talking to the phone at all has no way to interpret a gesture
 * that did not fire. It leans, turns and looks with the live pose, and squashes when a
 * gesture is recognised.
 */
@Composable
private fun Mirror(
    state: HeadGestureUiState,
    modifier: Modifier = Modifier,
) {
    // Held briefly rather than derived, so the celebration is a moment rather than a
    // single frame nobody sees.
    var celebrating by remember { mutableStateOf(false) }
    LaunchedEffect(state.lastGestureAtMillis) {
        if (state.lastGestureAtMillis != 0L) {
            celebrating = true
            delay(CELEBRATION_MILLIS)
            celebrating = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            // Colours given rather than defaulted. The toy's default face is
            // `primaryContainer`, which is exactly the colour of the card it sits on —
            // the head vanished and left a pair of eyes floating in the void.
            HeadMotionToy(
                yawDegrees = state.pose.yawDegrees,
                pitchDegrees = state.pose.pitchDegrees,
                rollDegrees = state.pose.rollDegrees,
                celebrating = celebrating,
                faceColor = MaterialTheme.colorScheme.primary,
                featureColor = MaterialTheme.colorScheme.onPrimary,
            )

            if (!state.streaming) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator()
                    Text("Waiting for the earbuds…", style = MaterialTheme.typography.titleMedium)
                }
            }

            // The confirmation sits over the face rather than beside it: at the moment a
            // gesture lands, the face is exactly where the eyes already are.
            AnimatedVisibility(
                visible = celebrating,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = fadeIn(GreenPodsMotion.effects()) + scaleIn(GreenPodsMotion.fastSpatial()),
                exit = fadeOut(GreenPodsMotion.effects()) + scaleOut(GreenPodsMotion.effects()),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .semantics {
                                    contentDescription = "${state.lastGesture.label()} recognised"
                                    liveRegion = LiveRegionMode.Polite
                                },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(20.dp))
                        Text(
                            "${state.lastGesture.label()} — that one counted",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One axis, as a bar that fills towards the threshold.
 *
 * Wavy on purpose, and not merely for the look: Material 3 Expressive uses the wave to
 * mean live and continuous, which is exactly what this is — it is not loading towards a
 * completion, it is showing a value that moves with the reader's own head. The wave
 * amplitude falls away as the bar empties, so a still head reads as a flat line.
 */
@Composable
private fun Meter(
    icon: ImageVector,
    title: String,
    instruction: String,
    progress: Float,
    armed: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown by animateFloatAsState(progress, GreenPodsMotion.fastSpatial(), label = "meter-$title")
    val container by animateColorAsState(
        targetValue =
            if (armed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        animationSpec = GreenPodsMotion.effects(),
        label = "meter-container-$title",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LinearWavyProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth().height(16.dp),
            )
        }
    }
}

private fun HeadGesture?.label(): String =
    when (this) {
        HeadGesture.NOD -> "Nod"
        HeadGesture.SHAKE -> "Shake"
        HeadGesture.TILT_LEFT -> "Tilt left"
        HeadGesture.TILT_RIGHT -> "Tilt right"
        HeadGesture.LOOK_UP -> "Look up"
        HeadGesture.LOOK_DOWN -> "Look down"
        null -> "Gesture"
    }

/** Each refusal is a different situation, and only one of them is worth re-checking. */
private fun HeadTrackingController.Refusal?.sentence(): String =
    when (this) {
        HeadTrackingController.Refusal.NO_ACCESSORY -> {
            "No earbuds in range. Open your case nearby and come back."
        }

        HeadTrackingController.Refusal.UNSUPPORTED -> {
            "These earbuds do not track head movement."
        }

        else -> {
            "This phone can't read head movement from your earbuds. Nothing is wrong " +
                "with them — the same limit turns off noise control here."
        }
    }

/** Long enough to be seen and read, short enough not to sit over the next attempt. */
private const val CELEBRATION_MILLIS = 1_400L
