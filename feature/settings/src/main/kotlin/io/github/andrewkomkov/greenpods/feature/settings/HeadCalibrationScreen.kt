@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.andrewkomkov.greenpods.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.data.head.CalibrationSession
import io.github.andrewkomkov.greenpods.core.designsystem.component.LockedCard
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsMotion
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsSpacing
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.CalibrationPose
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.OrientationField

/**
 * Measuring what a degree is worth on these earbuds.
 *
 * The app reports head angles by multiplying a raw number by a constant nobody measured. This
 * screen asks a person to hold four named poses and watches what the raw numbers do, so the
 * constant can be replaced by something derived from a real head — or, far more likely on the
 * hardware this project has, so the app can say plainly that the three axes do not separate
 * and that a per-axis constant was never the right model.
 *
 * The screen owns no decisions. Every judgement is made in `CalibrationSession` and its
 * solver, which are pure and drivable from `adb`; this renders what they decided and passes
 * the wearer's actions back. That split is what lets the whole feature be exercised with no
 * earbuds and no head.
 *
 * Two things it must say out loud, and does. Its reference angles are **nominal** — nobody
 * can see what a neck actually did, which is the accuracy floor of the entire feature. And a
 * run that ends in three refusals has not failed: it has produced the measurement this
 * feature exists to produce.
 */
@Composable
fun HeadCalibrationScreen(
    state: HeadCalibrationUiState,
    onStart: () -> Unit,
    onBeginHold: () -> Unit,
    onSkip: () -> Unit,
    onRepeat: () -> Unit,
    onConfirmSuspect: (HeadAxis) -> Unit,
    onSave: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                // Content runs under the floating bar; this is what keeps the last control
                // reachable above it.
                .padding(bottom = GreenPodsSpacing.FloatingBarSpace),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isLocked) {
            LockedCard(
                title = "Calibration can't run here",
                body = state.refusal.sentence(),
                icon = Icons.Filled.Straighten,
            )
            ApproximateNote()
            return@Column
        }

        when (val step = state.step) {
            CalibrationSession.State.Idle -> {
                if (state.abandonedOnSwap) Swapped()
                Intro(state, onStart)
                state.stored?.takeIf { it.hasAnythingToSay }?.let { stored ->
                    Results(
                        heading = "What is stored now",
                        calibration = stored,
                        unconfirmed = emptySet(),
                        onConfirmSuspect = {},
                    )
                }
            }

            is CalibrationSession.State.Awaiting -> {
                // The refusal comes first when there is one: it is about the attempt just
                // made, and reading the instruction again before being told the last try did
                // not count would leave a person repeating a pose they thought had worked.
                step.refusal?.let { Refused(step.pose, it, onRepeat, onSkip) }
                Pose(step.pose, step.index, onBeginHold, onSkip, onAbandon)
            }

            is CalibrationSession.State.Holding -> {
                Holding(step, onRepeat, onSkip)
            }

            is CalibrationSession.State.Reviewing -> {
                Review(
                    axes = step.axes,
                    unconfirmed = state.unconfirmedSuspects,
                    onConfirmSuspect = onConfirmSuspect,
                    onSave = onSave,
                    onStart = onStart,
                    onAbandon = onAbandon,
                )
            }

            is CalibrationSession.State.Stopped -> {
                Stopped(step.reason, onStart)
            }
        }

        ApproximateNote()
    }
}

/** What the run is for, and what it will ask of you, before it asks. */
@Composable
private fun Intro(
    state: HeadCalibrationUiState,
    onStart: () -> Unit,
) {
    StepCard(title = "Measure your head", icon = Icons.Filled.Straighten) {
        Text(
            "GreenPods turns the earbuds' raw orientation numbers into degrees using one " +
                "shared constant that was never measured on anything. Four poses, about two " +
                "seconds each, are enough to check that constant against your own head — and " +
                "to say so plainly when it cannot be checked at all.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CalibrationPose.Sequence.forEachIndexed { index, pose ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(pose.title(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        pose.instruction(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "Nothing is stored until the last screen, and nothing already stored is touched " +
                "before then. Leaving stops the sensor in your earbuds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onStart, enabled = state.canStart) {
            Text(if (state.streaming) "Start" else "Start — waiting for the earbuds")
        }
    }
}

/**
 * The pose that was just attempted and did not hold (FR-015).
 *
 * Named, with the measurement that refused it, at the moment it happened rather than at the
 * end of the run. The sentence comes from the detector, so it says which reading moved and by
 * how much instead of "that did not work" — a person can act on the first and not on the
 * second.
 *
 * It matters most on the neutral pose. Every scale is a difference against that hold, so a
 * wizard that walked past a failed neutral would collect three more poses and only then admit
 * it had nothing to measure them against.
 */
@Composable
private fun Refused(
    pose: CalibrationPose,
    refusal: String,
    onRepeat: () -> Unit,
    onSkip: () -> Unit,
) {
    StepCard(title = "That one did not hold", icon = Icons.Filled.Warning) {
        Text(
            "${pose.title()} — $refusal.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (pose.isNeutral) {
                "Nothing has been stored, and the run is still on this pose. Every other pose " +
                    "is measured as a difference from this one, so there is nothing to skip it " +
                    "in favour of — it is worth another go."
            } else {
                "Nothing has been stored, and the run is still on this pose. Try it again, or " +
                    "skip it and leave this axis uncalibrated — the others are unaffected."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRepeat) { Text("Try this pose again") }
            if (pose.axis != null) {
                TextButton(onClick = onSkip) { Text("Skip this pose") }
            }
        }
        Text(
            "Trying again discards this attempt completely, so the next one is judged on its " +
                "own readings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The accessory changed while the run was going (T058).
 *
 * A calibration is stored per model, so measurements made on one pair and finished on another
 * would be written under the second pair's name — and would then be indistinguishable from a
 * real measurement of it. Abandoning is the only honest outcome, and saying so is what stops
 * it reading as the app having lost the run.
 */
@Composable
private fun Swapped() {
    StepCard(title = "The earbuds changed", icon = Icons.Filled.Block) {
        Text(
            "A different accessory became the connected one while the run was going, so the " +
                "run was abandoned. Measurements belong to the pair that produced them: " +
                "storing these under the new pair's name would look exactly like having " +
                "measured it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Nothing was stored, and nothing already stored for either accessory was touched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One pose, before its countdown starts. Read it, get into position, then begin. */
@Composable
private fun Pose(
    pose: CalibrationPose,
    index: Int,
    onBeginHold: () -> Unit,
    onSkip: () -> Unit,
    onAbandon: () -> Unit,
) {
    StepCard(title = pose.title(), icon = Icons.Filled.Straighten) {
        Text(
            "Pose ${index + 1} of ${CalibrationPose.Sequence.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(pose.instruction(), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Get into position first, then start the countdown and hold still for " +
                "${pose.holdMillis / MILLIS_PER_SECOND} seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onBeginHold) { Text("Start the countdown") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pose.axis != null) {
                TextButton(onClick = onSkip) { Text("Skip this pose") }
            }
            TextButton(onClick = onAbandon) { Text("Stop") }
        }
    }
}

/**
 * The countdown, which is the measurement happening.
 *
 * It advances on arriving samples rather than on a timer, so the bar is describing the data
 * that is actually being collected. A bar that kept moving while nothing arrived would be the
 * one thing this screen must not do: claim a measurement it does not have.
 */
@Composable
private fun Holding(
    step: CalibrationSession.State.Holding,
    onRepeat: () -> Unit,
    onSkip: () -> Unit,
) {
    val elapsed = (step.pose.holdMillis - step.remainingMillis).toFloat() / step.pose.holdMillis
    val progress by animateFloatAsState(
        targetValue = elapsed.coerceIn(0f, 1f),
        animationSpec = GreenPodsMotion.fastSpatial(),
        label = "hold-countdown",
    )

    StepCard(title = step.pose.title(), icon = Icons.Filled.Straighten) {
        Text(step.pose.instruction(), style = MaterialTheme.typography.bodyMedium)

        Text(
            "Hold — %.1f seconds left".format(step.remainingMillis / MILLIS_PER_SECOND.toFloat()),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(16.dp),
        )

        Text(
            "${step.samplesCollected} readings so far. If you moved, run the pose again — a " +
                "hold that never settled is reported as unheld rather than averaged into a number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRepeat) { Text("Start this pose again") }
            if (step.pose.axis != null) {
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}

/**
 * The final screen (SC-006).
 *
 * Everything a person needs to understand the run is here: which axes were measured, which
 * were not, and why each one landed where it did — the responding fields included, because
 * that is the evidence and not a diagnostic detail. Nobody should have to open a log to find
 * out what their own poses produced.
 */
@Composable
private fun Review(
    axes: Map<HeadAxis, AxisCalibration>,
    unconfirmed: Set<HeadAxis>,
    onConfirmSuspect: (HeadAxis) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onAbandon: () -> Unit,
) {
    val measured = HeadAxis.entries.count { axes[it]?.appliedScale != null }
    val coupled = axes.values.any { it.verdict is AxisVerdict.CrossCoupled }

    StepCard(
        title = "What the run found",
        // Hub rather than the ruler when the fields turned out to be entangled: the icon is
        // the first thing read, and a measuring rule over a run that derived no number is the
        // wrong promise.
        icon = if (coupled) Icons.Filled.Hub else Icons.Filled.Straighten,
    ) {
        // The headline leads with the finding, not with the count.
        //
        // On the hardware this project has, every run is expected to end cross-coupled, so
        // "0 of 3 axes measured" would be the first sentence of every successful run — and it
        // reads as a failure. The count is honest and stays, one line down, for whoever wants
        // it. What it must not be is the framing.
        Text(
            when {
                coupled -> "The run measured how these earbuds respond."
                measured == HeadAxis.entries.size -> "Every axis produced a scale."
                else -> "$measured of ${HeadAxis.entries.size} axes measured."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                coupled -> {
                    "It found the three orientation numbers move together, so none of them is " +
                        "an axis on its own. That is the measurement, and it is worth having: " +
                        "it says a per-axis scale is the wrong model for this accessory, which " +
                        "is something the app could only learn by asking you to move. " +
                        "$measured of ${HeadAxis.entries.size} axes produced a scale."
                }

                measured == HeadAxis.entries.size -> {
                    "Saving replaces the shared constant with these for this model."
                }

                else -> {
                    "An axis without a scale keeps the app's shared approximation. Nothing is " +
                        "invented in its place, and each one says below why it produced none."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (coupled) {
        StepCard(title = "The axes did not separate", icon = Icons.Filled.Hub) {
            Text(
                "Every pose moved the same orientation numbers, so no single number belongs " +
                    "to turning, nodding or tilting. That is a result about these earbuds, " +
                    "not a mistake you made: a per-axis scale is the wrong model for them, " +
                    "and no scale is stored.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The three numbers are called O1, O2 and O3 here rather than yaw, pitch and " +
                    "roll, because which is which is precisely what this run was measuring.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    HeadAxis.entries.forEach { axis ->
        AxisResult(
            calibration = axes[axis] ?: AxisCalibration(axis),
            needsDecision = axis in unconfirmed,
            onConfirmSuspect = { onConfirmSuspect(axis) },
        )
    }

    if (unconfirmed.isNotEmpty()) {
        StepCard(title = "One number is waiting on you", icon = Icons.Filled.Warning) {
            Text(
                "A scale the app itself called implausible is not stored on the strength of a " +
                    "single tap. Keep it above, with its arithmetic in front of you, or run " +
                    "that pose again.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = unconfirmed.isEmpty()) { Text("Save this run") }
        OutlinedButton(onClick = onStart) { Text("Run it again") }
        TextButton(onClick = onAbandon) { Text("Discard") }
    }
}

/** A stored calibration, shown outside a run. */
@Composable
private fun Results(
    heading: String,
    calibration: HeadCalibration,
    unconfirmed: Set<HeadAxis>,
    onConfirmSuspect: (HeadAxis) -> Unit,
) {
    Text(heading, style = MaterialTheme.typography.titleMedium)
    HeadAxis.entries.forEach { axis ->
        AxisResult(
            calibration = calibration.forAxis(axis),
            needsDecision = axis in unconfirmed,
            onConfirmSuspect = { onConfirmSuspect(axis) },
        )
    }
}

/** One axis's outcome, stated so that reading only this tells you what happened to it. */
@Composable
private fun AxisResult(
    calibration: AxisCalibration,
    needsDecision: Boolean,
    onConfirmSuspect: () -> Unit,
) {
    val verdict = calibration.verdict

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color =
            if (calibration.appliedScale != null) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(verdict.icon(), contentDescription = null, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${calibration.axis.label()} — ${verdict.headline()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        verdict.detail(calibration.axis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (needsDecision) {
                Button(onClick = onConfirmSuspect) { Text("Keep it anyway") }
            }
        }
    }
}

/** The stream went away mid-run. Different from a pose having failed, and said differently. */
@Composable
private fun Stopped(
    reason: String,
    onStart: () -> Unit,
) {
    StepCard(title = "The run stopped", icon = Icons.Filled.Block) {
        Text(
            "$reason. Nothing was stored — a part-finished run is not kept as a finished one, " +
                "so whatever was already calibrated is exactly as it was.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onStart) { Text("Start again") }
    }
}

/**
 * The accuracy floor, said rather than implied (FR-019).
 *
 * Present on every state of the screen, including the locked one. A person deciding whether
 * to trust an angle needs to know where the number came from at the moment they are reading
 * it, not only on the screen where it was derived.
 */
@Composable
private fun ApproximateNote() {
    Text(
        "The reference angles are approximate. \"Chin toward your shoulder\" is treated as " +
            "about 90° and \"ear toward your shoulder\" as about 45°, and the app cannot see " +
            "what your neck actually did — so a result is only as accurate as the poses were " +
            "performed. This gets the angles from obviously wrong to roughly right; it is not " +
            "a measurement instrument.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The screen's one container shape, so every step reads as part of the same run. */
@Composable
private fun StepCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

private fun HeadAxis.label(): String =
    when (this) {
        HeadAxis.YAW -> "Turning (yaw)"
        HeadAxis.PITCH -> "Nodding (pitch)"
        HeadAxis.ROLL -> "Tilting (roll)"
    }

private fun CalibrationPose.title(): String =
    when (axis) {
        null -> "Look straight ahead"
        HeadAxis.YAW -> "Turn your head"
        HeadAxis.PITCH -> "Lower your chin"
        HeadAxis.ROLL -> "Tilt your head"
    }

private fun CalibrationPose.instruction(): String =
    when (axis) {
        null -> {
            "Sit facing forward and stay still. This is the reference every other pose is " +
                "measured against, so it is the one worth getting right."
        }

        HeadAxis.YAW -> {
            "Turn to look over one shoulder — about 90°, as far as is comfortable — and hold."
        }

        HeadAxis.PITCH -> {
            "Bring your chin down toward your chest, about 45°, and hold."
        }

        HeadAxis.ROLL -> {
            "Tip one ear toward that shoulder, about 45°, without turning to face it."
        }
    }

private fun AxisVerdict.headline(): String =
    when (this) {
        is AxisVerdict.Measured -> "measured"
        is AxisVerdict.Suspect -> if (confirmed) "kept, and still suspect" else "looks wrong"
        is AxisVerdict.Mismatched -> "the wrong reading moved"
        is AxisVerdict.Inconclusive -> "nothing stood out"
        is AxisVerdict.CrossCoupled -> "not a separate axis"
        is AxisVerdict.NotHeld -> "not held"
        AxisVerdict.Skipped -> "skipped"
        AxisVerdict.Uncalibrated -> "never measured"
    }

/**
 * Why an axis landed where it did, in the numbers it landed on.
 *
 * Every branch names what is stored for that axis, including the ones that store nothing.
 * "Not measured" and "measured and then discarded" are different facts about a person's
 * earbuds, and a screen that showed only the absence would leave them the same.
 */
private fun AxisVerdict.detail(axis: HeadAxis): String =
    when (this) {
        is AxisVerdict.Measured -> {
            "$deltaUnits units of ${field.label()} for about ${axis.referenceDegrees.toInt()}°, " +
                "so ${"%.4f".format(degreesPerUnit)}° per unit. This is what the app will use."
        }

        is AxisVerdict.Suspect -> {
            if (confirmed) {
                "$why. You chose to keep it, so the app will use it — and it will keep saying " +
                    "it is suspect."
            } else {
                "$why. Nothing is stored for this axis unless you keep it deliberately."
            }
        }

        is AxisVerdict.Mismatched -> {
            "The app reads ${expectedField.label()} for this movement, but " +
                "${respondingField.label()} is what moved — $deltaUnits units of it. No scale " +
                "is stored: a number derived on top of a wrong assignment would look calibrated."
        }

        is AxisVerdict.Inconclusive -> {
            "${contenders.joinToString { it.label() }} moved by comparable amounts, so the run " +
                "cannot say which one this movement lives in. Nothing is stored; picking the " +
                "larger would turn a coin toss into a constant."
        }

        is AxisVerdict.CrossCoupled -> {
            "This pose moved " +
                responses.entries.joinToString { "${it.key.label()} by ${it.value}" } +
                " — and the other poses moved the same ones. Nothing is stored."
        }

        is AxisVerdict.NotHeld -> {
            "The pose was $reason. Nothing is stored for this axis; running the wizard again " +
                "and holding it steadier is the fix."
        }

        AxisVerdict.Skipped -> {
            "You skipped this pose. Nothing is stored, and the app keeps its shared " +
                "approximation for this movement."
        }

        AxisVerdict.Uncalibrated -> {
            "This axis has never been measured. The app is using its shared approximation for it."
        }
    }

private fun AxisVerdict.icon(): ImageVector =
    when (this) {
        is AxisVerdict.Measured -> Icons.Filled.CheckCircle
        is AxisVerdict.Suspect -> Icons.Filled.Warning
        is AxisVerdict.Mismatched -> Icons.Filled.SwapHoriz
        is AxisVerdict.Inconclusive -> Icons.AutoMirrored.Filled.HelpOutline
        is AxisVerdict.CrossCoupled -> Icons.Filled.Hub
        is AxisVerdict.NotHeld -> Icons.Filled.Block
        AxisVerdict.Skipped -> Icons.Filled.Block
        AxisVerdict.Uncalibrated -> Icons.AutoMirrored.Filled.HelpOutline
    }

/** Named for the wire, not for a movement — which of them is which is the open question. */
private fun OrientationField.label(): String =
    when (this) {
        OrientationField.O1 -> "O1"
        OrientationField.O2 -> "O2"
        OrientationField.O3 -> "O3"
    }

/** True when there is anything to show beyond "nothing has ever been measured". */
private val HeadCalibration.hasAnythingToSay: Boolean
    get() = HeadAxis.entries.any { forAxis(it).verdict != AxisVerdict.Uncalibrated }

private const val MILLIS_PER_SECOND = 1_000
