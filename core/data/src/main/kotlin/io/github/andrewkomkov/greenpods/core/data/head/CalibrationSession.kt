package io.github.andrewkomkov.greenpods.core.data.head

import io.github.andrewkomkov.greenpods.core.bluetooth.head.CalibrationSolver
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeldSegment
import io.github.andrewkomkov.greenpods.core.bluetooth.head.PlateauDetector
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.CalibrationPose
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.PodModel

/**
 * The wizard, as a state machine.
 *
 * Pure: it takes samples and wearer actions in, and produces state out. No coroutines, no
 * Android, no I/O — which is what lets the whole run be driven from `adb` with injected
 * samples, on a phone that cannot open the Apple protocol channel at all, and unit-tested
 * without a person, without earbuds and without a head.
 *
 * **Nothing is persisted before [finish].** Abandoning, navigating away or losing the stream
 * leaves any stored calibration exactly as it was, so a failed re-run can never destroy a
 * good stored result. The session writes once, at the end, or not at all.
 */
class CalibrationSession(
    val model: PodModel,
    private val detector: PlateauDetector = PlateauDetector(),
    private val solver: CalibrationSolver = CalibrationSolver(),
    private val poses: List<CalibrationPose> = CalibrationPose.Sequence,
) {
    /** Where the run is. Exhaustive: there is no state not named here. */
    sealed interface State {
        /** Nothing running. A previously stored calibration may exist and is untouched. */
        data object Idle : State

        /**
         * Showing the instruction for [pose], not yet counting.
         *
         * [refusal] is set when this pose has just been attempted and did not hold. The
         * wizard stays here rather than moving on, because FR-015 asks it to name the pose
         * that failed **and offer to repeat it** — and a wizard that had already advanced
         * could only offer that at the end, by which time the wearer has performed the rest
         * of the run to be told about the first thing that went wrong.
         */
        data class Awaiting(
            val pose: CalibrationPose,
            val index: Int,
            val refusal: String? = null,
        ) : State

        /** Counting down and collecting samples. */
        data class Holding(
            val pose: CalibrationPose,
            val index: Int,
            val remainingMillis: Long,
            val samplesCollected: Int,
        ) : State

        /** Every pose attempted. Verdicts shown; nothing stored yet. */
        data class Reviewing(
            val axes: Map<HeadAxis, AxisCalibration>,
        ) : State

        /** The stream ended mid-run. No partial result is stored as a completed one. */
        data class Stopped(
            val reason: String,
        ) : State
    }

    var state: State = State.Idle
        private set

    private val collected = mutableListOf<PlateauDetector.Timed>()
    private var neutral: HeldSegment? = null
    private val held = mutableListOf<CalibrationSolver.Held>()
    private val skipped = mutableSetOf<HeadAxis>()
    private val notHeld = mutableMapOf<HeadAxis, String>()
    private var holdStartedAtMillis = 0L

    /** Begins a run. Any stored calibration stays untouched until [finish]. */
    fun start() {
        collected.clear()
        held.clear()
        skipped.clear()
        notHeld.clear()
        neutral = null
        state = State.Awaiting(poses.first(), 0)
    }

    /** Begins the hold for the pose currently being shown. */
    fun advance(nowMillis: Long) {
        val awaiting = state as? State.Awaiting ?: return
        collected.clear()
        holdStartedAtMillis = nowMillis
        state =
            State.Holding(
                pose = awaiting.pose,
                index = awaiting.index,
                remainingMillis = awaiting.pose.holdMillis,
                samplesCollected = 0,
            )
    }

    /**
     * Feeds one sample.
     *
     * Samples arriving outside a hold are dropped rather than buffered: the countdown is what
     * bounds *when* the measurement is taken, and a wearer moving between poses has nothing
     * to contribute to either of them.
     */
    fun onSample(
        sample: HeadTrackingSample,
        nowMillis: Long,
    ) {
        val holding = state as? State.Holding ?: return
        collected += PlateauDetector.Timed(sample, nowMillis)

        val elapsed = nowMillis - holdStartedAtMillis
        val remaining = (holding.pose.holdMillis - elapsed).coerceAtLeast(0L)
        state = holding.copy(remainingMillis = remaining, samplesCollected = collected.size)

        if (remaining == 0L) analyse(holding.pose, holding.index)
    }

    /**
     * Analyses what was collected and moves on.
     *
     * The plateau is found *inside* the collected window rather than assumed to be all of it
     * — the wearer is not obliged to obey the countdown exactly, and usually does not.
     */
    private fun analyse(
        pose: CalibrationPose,
        index: Int,
    ) {
        val segment = detector.longestHold(collected.toList())
        val axis = pose.axis

        if (segment == null) {
            val refusal = detector.refusal(collected.toList())
            axis?.let { notHeld[it] = refusal }

            // Stay on the pose that failed rather than walking past it. The wearer is told
            // what moved and by how much, and can repeat it or skip it — which is what
            // FR-015 asks for, and which also stops a failed *neutral* costing three more
            // poses before the run turns out to have had nothing to measure against.
            state = State.Awaiting(pose, index, refusal)
            return
        }

        if (axis == null) neutral = segment else held += CalibrationSolver.Held(axis, segment)
        axis?.let(notHeld::remove)

        moveOn(index)
    }

    /** Skips the current pose. Its axis stores nothing and says why. */
    fun skip() {
        val current = currentPoseAndIndex() ?: return
        current.first.axis?.let { skipped += it }
        moveOn(current.second)
    }

    /**
     * Re-runs the current pose without restarting the whole wizard.
     *
     * The clearing matters now that a failed pose stays current: without it a retry would
     * inherit the previous attempt's refusal and its partial samples, so the second try
     * could be judged on the first one's evidence.
     */
    fun repeat() {
        val current = currentPoseAndIndex() ?: return
        current.first.axis?.let { axis ->
            skipped -= axis
            notHeld.remove(axis)
            held.removeAll { entry -> entry.axis == axis }
        }
        if (current.first.isNeutral) neutral = null
        collected.clear()
        state = State.Awaiting(current.first, current.second)
    }

    private fun currentPoseAndIndex(): Pair<CalibrationPose, Int>? =
        when (val s = state) {
            is State.Awaiting -> s.pose to s.index
            is State.Holding -> s.pose to s.index
            else -> null
        }

    private fun moveOn(index: Int) {
        val next = index + 1
        state =
            if (next < poses.size) {
                State.Awaiting(poses[next], next)
            } else {
                State.Reviewing(solve())
            }
    }

    private fun solve(atEpochMillis: Long = 0L): Map<HeadAxis, AxisCalibration> =
        solver.solve(
            neutral = neutral,
            poses = held.toList(),
            skipped = skipped.toSet(),
            notHeld = notHeld.toMap(),
            atEpochMillis = atEpochMillis,
        )

    /**
     * The stream stopped while the run was going.
     *
     * A partial run is never stored as a completed one — the wearer is told the stream went
     * away, which is a different thing from their pose having failed.
     */
    fun onStreamEnded(reason: String) {
        if (state is State.Idle || state is State.Reviewing) return
        state = State.Stopped(reason)
    }

    /** Ends the run and stores nothing. */
    fun abandon() {
        state = State.Idle
    }

    /**
     * Confirms a suspect result so [finish] may store it.
     *
     * Separate from finishing on purpose. A number the app itself called implausible should
     * take two decisions to keep, not one.
     */
    fun confirmSuspect(axis: HeadAxis) {
        val reviewing = state as? State.Reviewing ?: return
        val current = reviewing.axes[axis] ?: return
        val suspect = current.verdict as? AxisVerdict.Suspect ?: return
        state =
            State.Reviewing(
                reviewing.axes + (axis to current.copy(verdict = suspect.copy(confirmed = true))),
            )
    }

    /** Axes whose suspect result is still waiting on a decision. */
    fun unconfirmedSuspects(): Set<HeadAxis> {
        val reviewing = state as? State.Reviewing ?: return emptySet()
        return reviewing.axes
            .filterValues { it.verdict is AxisVerdict.Suspect && !(it.verdict as AxisVerdict.Suspect).confirmed }
            .keys
    }

    /**
     * The calibration to store, or null if this run has nothing to store.
     *
     * Refuses while an unconfirmed suspect is present. Returns a result even when every axis
     * refused: a run that measured nothing still records *that*, and the difference between
     * "never calibrated" and "calibrated, and the axes turned out to be cross-coupled" is
     * exactly the finding this feature exists to produce.
     */
    fun finish(atEpochMillis: Long): HeadCalibration? {
        val reviewing = state as? State.Reviewing ?: return null
        if (unconfirmedSuspects().isNotEmpty()) return null

        state = State.Idle
        return HeadCalibration(
            model = model,
            axes = reviewing.axes.mapValues { (_, axis) -> axis.copy(measuredAtEpochMillis = atEpochMillis) },
            measuredAtEpochMillis = atEpochMillis,
        )
    }
}
