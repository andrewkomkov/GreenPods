package io.github.andrewkomkov.greenpods.core.data.head

import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.CalibrationPose
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * The rules the wizard keeps, driven without a person, without earbuds and without a head.
 *
 * The one that matters most is not about measuring at all: **nothing leaves this session
 * except through `finish`**, so a re-run that goes badly cannot damage a calibration that
 * went well (FR-007, SC-007). The rest are the refusals — a skipped pose, a pose the wearer
 * moved through, a neutral that was never held, a scale the app itself calls implausible —
 * each of which has to produce a verdict and no number.
 *
 * **Every sample sequence in this file is synthetic**, and that is stated rather than
 * implied: these are perfectly still holds and perfectly linear drifts, which no neck
 * performs. `HeadCalibrationFixtures` builds the same shapes, but it lives in
 * `core/bluetooth`'s test source set, which this module cannot see — hence the small local
 * equivalents below.
 */
class CalibrationSessionTest {
    /** The session carries no clock of its own; this is the one the test advances. */
    private var nowMillis = 10_000L

    // Deltas chosen so the derived scale lands inside the solver's plausible range: 90° over
    // 6 000 units and 45° over 3 000 both come to 0.015°/unit.
    private val yawUnits = 6_000
    private val pitchUnits = 3_000
    private val rollUnits = 3_000

    private val measuredAt = 1_700_000_000_000L

    private fun session(): CalibrationSession = CalibrationSession(model = PodModel.AIRPODS_PRO_3)

    /**
     * The provisional numbers are settings, and a session built from them uses them (T018).
     *
     * Both directions are asserted because only the pair proves the wiring: a tolerance that
     * accepts a wobble the default rejects, and one that rejects a wobble the default accepts.
     * Checking one alone would pass against a session that ignored the setting entirely.
     */
    @Test
    fun `the plateau tolerance comes from settings`() {
        val wobble = 1_500

        val strict =
            CalibrationSession.from(
                PodModel.AIRPODS_PRO_3,
                GreenPodsSettings.Default.copy(calibrationToleranceUnits = 900),
            )
        strict.start()
        strict.holdWobbling(by = wobble)
        strict
            .state
            .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
            .refusal
            .shouldNotBeNull()

        val loose =
            CalibrationSession.from(
                PodModel.AIRPODS_PRO_3,
                GreenPodsSettings.Default.copy(calibrationToleranceUnits = 4_000),
            )
        loose.start()
        loose.holdWobbling(by = wobble)
        loose.state
            .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
            .refusal
            .shouldBeNull()
    }

    /**
     * The hold duration comes from settings, and reaches the poses as well as the detector.
     *
     * The countdown the wearer is shown and the window the plateau is looked for in have to be
     * the same number — a longer hold asked for and judged against a shorter one would pass
     * for the wrong reason.
     */
    @Test
    fun `the hold duration comes from settings`() {
        val session =
            CalibrationSession.from(
                PodModel.AIRPODS_PRO_3,
                GreenPodsSettings.Default.copy(calibrationHoldMillis = 5_000L),
            )
        session.start()

        // The countdown is the plateau plus the settle margin, and the gap between them is
        // load-bearing: with the two equal, the plateau had to span the whole window and a
        // pose held perfectly still was refused on hardware for being seven milliseconds
        // short.
        session.awaitingPose().holdMillis shouldBe 5_000L + CalibrationSession.SETTLE_MARGIN_MILLIS

        // Two seconds of perfectly still samples used to be a complete hold. Against a
        // five-second setting it is not one, and the run must still be waiting. Fed at a fixed
        // length on purpose — the other helpers derive theirs from the pose, which is exactly
        // what this test must not do if it is to prove the setting reached the pose.
        val start = nowMillis
        session.advance(nowMillis)
        for (index in 0..(2_000L / SAMPLE_INTERVAL_MILLIS).toInt()) {
            nowMillis = start + index * SAMPLE_INTERVAL_MILLIS
            session.onSample(sample(), nowMillis)
        }
        session.state.shouldBeInstanceOf<CalibrationSession.State.Holding>()
    }

    @Test
    fun `a clean run measures a scale for every axis`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        session.state.shouldBeInstanceOf<CalibrationSession.State.Reviewing>()
        session.unconfirmedSuspects() shouldBe emptySet()

        val stored = session.finish(measuredAt).shouldNotBeNull()
        stored.model shouldBe PodModel.AIRPODS_PRO_3
        stored.measuredAtEpochMillis shouldBe measuredAt
        stored.hasAnyScale shouldBe true

        val yaw = stored.measured(HeadAxis.YAW)
        yaw.field shouldBe OrientationField.O1
        yaw.deltaUnits shouldBe yawUnits
        yaw.degreesPerUnit shouldBe HeadAxis.YAW.referenceDegrees / yawUnits

        val pitch = stored.measured(HeadAxis.PITCH)
        pitch.field shouldBe OrientationField.O2
        pitch.deltaUnits shouldBe pitchUnits
        pitch.degreesPerUnit shouldBe HeadAxis.PITCH.referenceDegrees / pitchUnits

        val roll = stored.measured(HeadAxis.ROLL)
        roll.field shouldBe OrientationField.O3
        roll.deltaUnits shouldBe rollUnits
        roll.degreesPerUnit shouldBe HeadAxis.ROLL.referenceDegrees / rollUnits

        // The timestamp travels onto every axis, not only onto the set: an axis read on its
        // own has to be able to say how old it is.
        HeadAxis.entries.forEach { stored.forAxis(it).measuredAtEpochMillis shouldBe measuredAt }
        session.state shouldBe CalibrationSession.State.Idle
    }

    @Test
    fun `the run walks neutral first, then one pose per axis`() {
        val session = session()
        session.start()
        session.awaitingPose() shouldBe CalibrationPose(axis = null)

        session.holdStill()
        session.awaitingPose().axis shouldBe HeadAxis.YAW
        session.holdStill(o1 = yawUnits)
        session.awaitingPose().axis shouldBe HeadAxis.PITCH
        session.holdStill(o2 = pitchUnits)
        session.awaitingPose().axis shouldBe HeadAxis.ROLL
    }

    @Test
    fun `an abandoned run stores nothing`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = yawUnits)

        session.abandon()

        session.state shouldBe CalibrationSession.State.Idle
        session.finish(measuredAt).shouldBeNull()
    }

    /**
     * The session's central promise. A wearer who re-runs the wizard, moves halfway through
     * and walks away must be no worse off than before they started — so the session hands a
     * result out exactly once, at the end, and there is no other door.
     */
    @Test
    fun `a failed re-run cannot destroy a good stored result`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)
        val stored = session.finish(measuredAt).shouldNotBeNull()
        val asStored = stored.copy(axes = stored.axes.toMap())

        session.start()
        session.holdStill()
        session.holdDrifting()
        session.abandon()

        session.state shouldBe CalibrationSession.State.Idle
        session.finish(measuredAt + 1) shouldBe null
        stored shouldBe asStored
    }

    /**
     * Enforced against the class rather than against one path through it: the promise above
     * is only worth anything if no second method can also produce a calibration.
     */
    @Test
    fun `finish is the only method that yields a calibration`() {
        val producers =
            CalibrationSession::class.java.methods
                .filter { it.returnType == HeadCalibration::class.java }
                .map { it.name }
                .distinct()
                .sorted()

        producers shouldContainExactly listOf("finish")
    }

    @Test
    fun `the stream ending mid-run stops the run and stores nothing`() {
        val session = session()
        session.start()
        session.holdStill()
        session.beginHold()

        session.onStreamEnded("the accessory went away")

        val stopped = session.state.shouldBeInstanceOf<CalibrationSession.State.Stopped>()
        stopped.reason shouldBe "the accessory went away"
        session.finish(measuredAt).shouldBeNull()
    }

    /**
     * The stream stopping is only news while a pose is being measured. Reported after the
     * poses are done it would throw away a complete run the wearer already performed, and
     * reported before one starts it would announce a failure that has not happened.
     */
    @Test
    fun `the stream ending does not disturb an idle or reviewing session`() {
        val idle = session()
        idle.onStreamEnded("the accessory went away")
        idle.state shouldBe CalibrationSession.State.Idle

        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)
        val reviewing = session.state.shouldBeInstanceOf<CalibrationSession.State.Reviewing>()

        session.onStreamEnded("the accessory went away")

        session.state shouldBe reviewing
        session.finish(measuredAt).shouldNotBeNull().hasAnyScale shouldBe true
    }

    /**
     * The other half of "evidence beats absence", and the half that stops it becoming
     * "everything is not held": a pose declined without being attempted has produced no
     * measurement, so there is nothing for a refusal to outrank and it stays
     * [AxisVerdict.Skipped].
     */
    @Test
    fun `a pose that was never attempted stays skipped, and leaves the others measured`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = yawUnits)
        session.skip()
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        stored.forAxis(HeadAxis.PITCH).verdict shouldBe AxisVerdict.Skipped
        stored.forAxis(HeadAxis.PITCH).appliedScale.shouldBeNull()
        stored.measured(HeadAxis.YAW).field shouldBe OrientationField.O1
        stored.measured(HeadAxis.ROLL).field shouldBe OrientationField.O3
    }

    /**
     * A refusal has to carry the measurement that produced it, and it has to arrive while
     * the wearer can still act on it. "You moved" is an opinion; "O1 moved 10 000 units,
     * past the 900 allowed" is something a wearer can act on and a reader can check — and
     * said on the pose that failed rather than at the end, it is an offer to try again
     * instead of a post-mortem (FR-015).
     */
    @Test
    fun `a pose the wearer moved through stays on that pose, and says what moved`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdDrifting()

        val stuck = session.state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
        stuck.pose.axis shouldBe HeadAxis.YAW
        stuck.index shouldBe 1
        val refusal = stuck.refusal.shouldNotBeNull()
        refusal shouldContain "never settled"
        refusal shouldContain "O1"
        refusal shouldContain "10000"

        session.skip()
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        // Skipping past a pose the app has already refused does not turn it back into a
        // pose the wearer declined to perform. The attempt happened and its measurement
        // survives the skip — evidence beats absence.
        val yaw = stored.forAxis(HeadAxis.YAW).verdict.shouldBeInstanceOf<AxisVerdict.NotHeld>()
        yaw.reason shouldContain "O1"
        yaw.reason shouldContain "10000"
        stored.forAxis(HeadAxis.YAW).appliedScale.shouldBeNull()
        stored.measured(HeadAxis.PITCH).field shouldBe OrientationField.O2
        stored.measured(HeadAxis.ROLL).field shouldBe OrientationField.O3
    }

    /**
     * The countdown bounds *when* samples are taken; the plateau inside them is what gets
     * measured. A wearer who arrives at the pose with a fifth of a second left has held it
     * perfectly still and still not held it for long enough, and the refusal must say which
     * of the two it was — otherwise the retry it offers is a retry of the wrong thing.
     */
    @Test
    fun `a pose settled into too late is refused for its duration, not its steadiness`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdArrivingLate()

        val stuck = session.state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
        stuck.pose.axis shouldBe HeadAxis.YAW
        stuck.index shouldBe 1
        val refusal = stuck.refusal.shouldNotBeNull()
        refusal shouldContain "held for only"
        refusal shouldNotContain "never settled"

        session.skip()
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        val yaw = stored.forAxis(HeadAxis.YAW).verdict.shouldBeInstanceOf<AxisVerdict.NotHeld>()
        yaw.reason shouldContain "held for only"
        yaw.reason shouldNotContain "never settled"
        stored.forAxis(HeadAxis.YAW).appliedScale.shouldBeNull()
        stored.measured(HeadAxis.PITCH).field shouldBe OrientationField.O2
    }

    /**
     * An axis is described by its most recent attempt, never by an older one. Otherwise a
     * wearer who retries a pose and fails it differently the second time is handed the first
     * failure's advice, which is advice about something they have already stopped doing.
     */
    @Test
    fun `a second failed attempt supersedes the first refusal`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdDrifting()
        session.repeat()
        session.holdArrivingLate()
        session.skip()
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        val yaw = stored.forAxis(HeadAxis.YAW).verdict.shouldBeInstanceOf<AxisVerdict.NotHeld>()
        yaw.reason shouldContain "held for only"
        yaw.reason shouldNotContain "never settled"
    }

    /**
     * Every scale is a difference against the neutral hold, so a run without one has nothing
     * to measure from — however well the three poses that follow are performed. The wizard
     * therefore stops on the neutral instead of collecting three poses it already knows it
     * cannot use, and if the wearer skips past it anyway, every axis says the same thing:
     * one cause, one sentence.
     */
    @Test
    fun `a neutral that was never held stops the run, and then fails every axis`() {
        val session = session()
        session.start()
        session.holdDrifting()

        val stuck = session.state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
        stuck.pose.isNeutral shouldBe true
        stuck.index shouldBe 0
        stuck.refusal.shouldNotBeNull() shouldContain "never settled"

        session.skip()
        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        stored.hasAnyScale shouldBe false
        HeadAxis.entries.forEach { axis ->
            val verdict = stored.forAxis(axis).verdict.shouldBeInstanceOf<AxisVerdict.NotHeld>()
            verdict.reason shouldContain "neutral"
            stored.forAxis(axis).appliedScale.shouldBeNull()
        }
    }

    /**
     * A retry is a fresh attempt, not an amendment to the last one. The previous attempt's
     * refusal and its samples are the evidence for a pose the wearer has since re-performed,
     * and judging the second try on the first one's evidence would make repeating pointless.
     */
    @Test
    fun `a repeat after a failure is judged on the retry's evidence alone`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdDrifting()
        session.state
            .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
            .refusal
            .shouldNotBeNull()

        session.repeat()

        val retry = session.state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
        retry.pose.axis shouldBe HeadAxis.YAW
        retry.index shouldBe 1
        retry.refusal.shouldBeNull()

        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        val yaw = stored.measured(HeadAxis.YAW)
        yaw.deltaUnits shouldBe yawUnits
        yaw.field shouldBe OrientationField.O1
        yaw.degreesPerUnit shouldBe HeadAxis.YAW.referenceDegrees / yawUnits
    }

    /**
     * Repeating is a retry of one pose, not a restart of the wizard: the neutral hold the
     * wearer already gave is what everything else is measured against, and making them
     * perform it again to fix a wobbly nod would be a tax on the app's own strictness.
     */
    @Test
    fun `repeat re-runs the current pose without restarting the run`() {
        val session = session()
        session.start()
        session.holdStill()
        session.beginHold()
        session.onSample(sample(o1 = 20_000), nowMillis)

        session.repeat()

        val awaiting = session.state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
        awaiting.pose.axis shouldBe HeadAxis.YAW
        awaiting.index shouldBe 1

        session.holdStill(o1 = yawUnits)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        // The discarded attempt contributed nothing: the delta is the retry's, exactly.
        stored.measured(HeadAxis.YAW).deltaUnits shouldBe yawUnits
    }

    /**
     * A number the app itself called implausible takes two decisions to keep, not one.
     * Finishing is a decision about the run; confirming is a decision about that number, and
     * collapsing them into one button would make the warning a formality.
     */
    @Test
    fun `an unconfirmed suspect blocks finishing, and stays suspect once confirmed`() {
        val session = session()
        session.start()
        session.holdStill()
        // 90° over 1 000 units implies 0.09°/unit — past the plausible ceiling, but far
        // enough above the noise floor to be a response rather than a refusal.
        session.holdStill(o1 = 1_000)
        session.holdStill(o2 = pitchUnits)
        session.holdStill(o3 = rollUnits)

        session.unconfirmedSuspects() shouldBe setOf(HeadAxis.YAW)
        session.finish(measuredAt).shouldBeNull()
        session.state.shouldBeInstanceOf<CalibrationSession.State.Reviewing>()

        session.confirmSuspect(HeadAxis.YAW)
        session.unconfirmedSuspects() shouldBe emptySet()

        val stored = session.finish(measuredAt).shouldNotBeNull()
        val yaw = stored.forAxis(HeadAxis.YAW).verdict.shouldBeInstanceOf<AxisVerdict.Suspect>()
        yaw.confirmed shouldBe true
        yaw.why shouldContain "outside the plausible"
        stored.forAxis(HeadAxis.YAW).appliedScale shouldBe HeadAxis.YAW.referenceDegrees / 1_000
    }

    /**
     * The finding this feature exists to produce, and the reason a run with no scale in it
     * is still worth storing: "we measured, and the per-axis model did not fit" is a
     * different thing from "never calibrated", and only the first one can be reported.
     */
    @Test
    fun `a run in which no pose separates the fields stores the coupling and no scale`() {
        val session = session()
        session.start()
        session.holdStill()
        session.holdStill(o1 = 3_000, o2 = 3_000)
        session.holdStill(o2 = 3_000, o3 = 3_000)
        session.holdStill(o1 = 3_000, o3 = 3_000)

        val stored = session.finish(measuredAt).shouldNotBeNull()
        stored.hasAnyScale shouldBe false
        HeadAxis.entries.forEach { axis ->
            stored.forAxis(axis).verdict.shouldBeInstanceOf<AxisVerdict.CrossCoupled>()
            stored.forAxis(axis).appliedScale.shouldBeNull()
        }

        val yaw = stored.forAxis(HeadAxis.YAW).verdict as AxisVerdict.CrossCoupled
        yaw.responses shouldBe
            mapOf(
                OrientationField.O1 to 3_000,
                OrientationField.O2 to 3_000,
                OrientationField.O3 to 0,
            )
    }

    // --- driving the session -------------------------------------------------------------
    //
    // Synthetic sample runs. A hold is fifty-one samples at the stream's 25 Hz, the last of
    // which lands exactly on the end of the countdown and so is the one that triggers the
    // analysis.

    private fun sample(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ): HeadTrackingSample =
        HeadTrackingSample(
            orientation1 = o1.toShort(),
            orientation2 = o2.toShort(),
            orientation3 = o3.toShort(),
            horizontalAcceleration = 0,
            verticalAcceleration = 0,
        )

    /** Starts the countdown for the pose being shown, leaving the session collecting. */
    private fun CalibrationSession.beginHold() {
        advance(nowMillis)
    }

    /**
     * How many samples cover the countdown of the pose now being shown.
     *
     * Derived rather than fixed, because the countdown is the required plateau *plus* a settle
     * margin now. A hard-coded fifty stopped a second short of it and the hold never completed
     * — which is the same shape as the hardware defect that put the margin there.
     */
    private fun CalibrationSession.samplesForHold(): Int {
        val hold =
            (state as? CalibrationSession.State.Awaiting)?.pose?.holdMillis
                ?: CalibrationPose.DEFAULT_HOLD_MILLIS
        return (hold / SAMPLE_INTERVAL_MILLIS).toInt()
    }

    /** A pose held perfectly still for the whole countdown — a thing only a test can do. */
    private fun CalibrationSession.holdStill(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ) {
        val start = nowMillis
        val samples = samplesForHold()
        beginHold()
        for (index in 0..samples) {
            nowMillis = start + index * SAMPLE_INTERVAL_MILLIS
            onSample(sample(o1, o2, o3), nowMillis)
        }
        nowMillis += GAP_BETWEEN_POSES_MILLIS
    }

    /**
     * A hold that alternates by [by] units, so whether it settles depends only on the tolerance.
     *
     * Synthetic, like everything else here: a real head wanders, it does not square-wave.
     */
    private fun CalibrationSession.holdWobbling(by: Int) {
        val start = nowMillis
        val samples = samplesForHold()
        beginHold()
        for (index in 0..samples) {
            nowMillis = start + index * SAMPLE_INTERVAL_MILLIS
            onSample(sample(o1 = if (index % 2 == 0) 0 else by), nowMillis)
        }
        nowMillis += GAP_BETWEEN_POSES_MILLIS
    }

    /** A pose that never settles: every sample further from the last, so no plateau exists. */
    private fun CalibrationSession.holdDrifting() {
        val start = nowMillis
        val samples = samplesForHold()
        beginHold()
        for (index in 0..samples) {
            nowMillis = start + index * SAMPLE_INTERVAL_MILLIS
            onSample(sample(o1 = index * DRIFT_STEP_UNITS), nowMillis)
        }
        nowMillis += GAP_BETWEEN_POSES_MILLIS
    }

    /** Perfectly still, but only for the last fifth of a second of the countdown. */
    private fun CalibrationSession.holdArrivingLate() {
        val start = nowMillis
        beginHold()
        for (index in 0..LATE_SAMPLES) {
            nowMillis = start + LATE_ARRIVAL_MILLIS + index * LATE_INTERVAL_MILLIS
            onSample(sample(o1 = 6_000), nowMillis)
        }
        nowMillis += GAP_BETWEEN_POSES_MILLIS
    }

    private fun CalibrationSession.awaitingPose(): CalibrationPose =
        state.shouldBeInstanceOf<CalibrationSession.State.Awaiting>().pose

    private fun HeadCalibration.measured(axis: HeadAxis): AxisVerdict.Measured =
        forAxis(axis).verdict.shouldBeInstanceOf<AxisVerdict.Measured>()

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 40L
        const val GAP_BETWEEN_POSES_MILLIS = 1_000L
        const val DRIFT_STEP_UNITS = 200

        const val LATE_ARRIVAL_MILLIS = 1_800L
        const val LATE_INTERVAL_MILLIS = 20L
        const val LATE_SAMPLES = 10
    }
}
