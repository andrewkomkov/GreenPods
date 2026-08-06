package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import kotlin.math.abs

/**
 * What a run of held poses is allowed to conclude.
 *
 * Read the cross-coupling test first. On the hardware this project has, that is the expected
 * outcome of a real run — not an edge case — and a solver that quietly produced three scales
 * instead would be fitting numbers to a model already known not to fit.
 */
class CalibrationSolverTest {
    private val solver = CalibrationSolver()
    private val detector = PlateauDetector()

    private fun hold(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ): HeldSegment =
        detector.longestHold(HeadCalibrationFixtures.steady(o1 = o1, o2 = o2, o3 = o3, count = 60))!!

    private infix fun Float.shouldBeAbout(expected: Float) {
        (abs(this - expected) < 0.0005f) shouldBe true
    }

    @Test
    fun `a clean separated run measures each axis on its own field`() {
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o2 = 3_150)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o3 = 3_150)),
                    ),
            )

        val yaw = result.getValue(HeadAxis.YAW).verdict as AxisVerdict.Measured
        yaw.field shouldBe OrientationField.O1
        // 90° over 6290 units.
        yaw.degreesPerUnit shouldBeAbout (90f / 6_290f)

        (result.getValue(HeadAxis.PITCH).verdict is AxisVerdict.Measured) shouldBe true
        (result.getValue(HeadAxis.ROLL).verdict is AxisVerdict.Measured) shouldBe true
    }

    @Test
    fun `the scale is a difference against neutral, not an absolute`() {
        // The accessory's zero is wherever the head was when the stream started, so an
        // absolute median describes the session and not the neck. With neutral at 1000 and
        // the pose at 7290, the measurement is the 6290 between them.
        val result =
            solver.solve(
                neutral = hold(o1 = 1_000),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 7_290)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o1 = 1_000, o2 = 3_150)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o1 = 1_000, o3 = 3_150)),
                    ),
            )

        val yaw = result.getValue(HeadAxis.YAW).verdict as AxisVerdict.Measured
        yaw.deltaUnits shouldBe 6_290
        yaw.degreesPerUnit shouldBeAbout (90f / 6_290f)
    }

    @Test
    fun `without a neutral hold nothing can be derived for any axis`() {
        val result =
            solver.solve(
                neutral = null,
                poses = listOf(CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290))),
            )

        HeadAxis.entries.forEach { axis ->
            result.getValue(axis).appliedScale.shouldBeNull()
        }
        (result.getValue(HeadAxis.YAW).verdict as AxisVerdict.NotHeld).reason shouldContain "neutral"
    }

    @Test
    fun `a pose that moves the wrong field is a mismatch, and stores no scale`() {
        // The outcome the motivating capture could not settle, and the one that must be
        // loudest: a scale derived on a wrong axis assignment is worse than no calibration,
        // because it looks calibrated.
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o3 = 10_920)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o3 = 3_150)),
                    ),
            )

        val pitch = result.getValue(HeadAxis.PITCH).verdict as AxisVerdict.Mismatched
        pitch.expectedField shouldBe OrientationField.O2
        pitch.respondingField shouldBe OrientationField.O3
        result.getValue(HeadAxis.PITCH).appliedScale.shouldBeNull()
    }

    @Test
    fun `two fields responding comparably is inconclusive, not the larger of the two`() {
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        // 6290 against 5900 — nowhere near a factor of two apart.
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290, o2 = 5_900)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o2 = 3_150)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o3 = 3_150)),
                    ),
            )

        val yaw = result.getValue(HeadAxis.YAW).verdict as AxisVerdict.Inconclusive
        yaw.contenders shouldBe setOf(OrientationField.O1, OrientationField.O2)
        result.getValue(HeadAxis.YAW).appliedScale.shouldBeNull()
    }

    @Test
    fun `a run where no pose separates the fields is cross-coupled, on every axis`() {
        // The expected result on current hardware. Each pose moves several fields together,
        // so the per-axis model does not fit — and the solver says so rather than fitting a
        // number to it. Every axis reports it, because it is a finding about the run.
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290, o2 = 5_900, o3 = 4_100)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o1 = 3_100, o2 = 3_150, o3 = 2_900)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o1 = 2_800, o2 = 3_000, o3 = 3_150)),
                    ),
            )

        HeadAxis.entries.forEach { axis ->
            val verdict = result.getValue(axis).verdict
            (verdict is AxisVerdict.CrossCoupled) shouldBe true
            result.getValue(axis).appliedScale.shouldBeNull()
        }

        // And it carries the evidence: every field's response, not just the winner there
        // wasn't. This is the measurement the project actually lacks.
        val yaw = result.getValue(HeadAxis.YAW).verdict as AxisVerdict.CrossCoupled
        yaw.responses.keys shouldBe OrientationField.entries.toSet()
    }

    @Test
    fun `one ambiguous pose among clean ones is that pose's problem, not the model's`() {
        // The distinction cross-coupling rests on. If a single pose were enough to condemn
        // the model, one wobbly hold would discard two good measurements.
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290, o2 = 5_900)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o2 = 3_150)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o3 = 3_150)),
                    ),
            )

        (result.getValue(HeadAxis.YAW).verdict is AxisVerdict.Inconclusive) shouldBe true
        (result.getValue(HeadAxis.PITCH).verdict is AxisVerdict.Measured) shouldBe true
        (result.getValue(HeadAxis.ROLL).verdict is AxisVerdict.Measured) shouldBe true
    }

    @Test
    fun `the suspect bounds are reachable at all`() {
        // Guarding the guard. The first version of the plausible range could never fire: the
        // minimum-delta gate rejects anything under 900 units before a scale is computed, so
        // every derivable scale sat inside the bounds and the suspect path was dead code
        // dressed as a safety check. If someone widens the range again, this fails.
        val smallestDelta = CalibrationSolver.DEFAULT_MINIMUM_DELTA_UNITS
        val largestDerivable = HeadAxis.YAW.referenceDegrees / smallestDelta

        (largestDerivable > CalibrationSolver.DEFAULT_PLAUSIBLE_RANGE.endInclusive) shouldBe true
    }

    @Test
    fun `an implausible scale is suspect, and shows its arithmetic`() {
        // 90° from a delta barely above the noise floor implies a scale under which the
        // sensor's whole range would cover several complete turns. Reported with the
        // numbers, because "this looks wrong" without them is an opinion.
        val result =
            solver.solve(
                neutral = hold(),
                poses =
                    listOf(
                        CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 950)),
                        CalibrationSolver.Held(HeadAxis.PITCH, hold(o2 = 3_150)),
                        CalibrationSolver.Held(HeadAxis.ROLL, hold(o3 = 3_150)),
                    ),
            )

        val yaw = result.getValue(HeadAxis.YAW).verdict as AxisVerdict.Suspect
        yaw.why shouldContain "outside the plausible"
        yaw.confirmed shouldBe false
        result.getValue(HeadAxis.YAW).appliedScale.shouldBeNull()
    }

    @Test
    fun `a skipped axis and an unheld axis keep their siblings' results`() {
        val result =
            solver.solve(
                neutral = hold(),
                poses = listOf(CalibrationSolver.Held(HeadAxis.YAW, hold(o1 = 6_290))),
                skipped = setOf(HeadAxis.PITCH),
                notHeld = mapOf(HeadAxis.ROLL to "never settled: O3 moved 3140 units"),
            )

        (result.getValue(HeadAxis.YAW).verdict is AxisVerdict.Measured) shouldBe true
        result.getValue(HeadAxis.PITCH).verdict shouldBe AxisVerdict.Skipped
        (result.getValue(HeadAxis.ROLL).verdict as AxisVerdict.NotHeld).reason shouldContain "3140"
    }

    @Test
    fun `the response carries every field, and names no winner when there is none`() {
        val response = solver.respond(hold(), hold(o1 = 6_290, o2 = 5_900))

        response.deltas.keys shouldBe OrientationField.entries.toSet()
        response.dominant.shouldBeNull()
        response.largest shouldBe OrientationField.O1
    }

    @Test
    fun `a clear winner is named`() {
        val response = solver.respond(hold(), hold(o1 = 6_290, o2 = 1_000))

        response.dominant.shouldNotBeNull()
        response.dominant shouldBe OrientationField.O1
    }
}
