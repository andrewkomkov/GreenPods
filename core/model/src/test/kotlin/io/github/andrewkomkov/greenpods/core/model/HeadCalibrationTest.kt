package io.github.andrewkomkov.greenpods.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The rule that stops a calibration inventing a number it did not measure.
 *
 * Almost every verdict this feature can reach is a refusal, and the whole design rests on
 * refusals being unable to produce a scale by accident. That is enforced twice — the failing
 * variants have no scale field at all, and [AxisCalibration.appliedScale] is the single
 * reader — so these tests are about the second half, which is the half a future edit could
 * quietly break.
 */
class HeadCalibrationTest {
    private fun calibration(verdict: AxisVerdict) = AxisCalibration(HeadAxis.YAW, verdict)

    @Test
    fun `a measured axis is the only verdict that yields a scale outright`() {
        calibration(AxisVerdict.Measured(0.0143f, OrientationField.O1, 6_290)).appliedScale shouldBe 0.0143f
    }

    @Test
    fun `every refusal yields no scale`() {
        // Listed one by one rather than looped: a new refusal variant added later will not
        // be covered by a loop over the ones that already exist, and this is precisely the
        // rule that must not acquire a silent exception.
        calibration(AxisVerdict.Uncalibrated).appliedScale.shouldBeNull()
        calibration(AxisVerdict.Skipped).appliedScale.shouldBeNull()
        calibration(AxisVerdict.NotHeld("never settled")).appliedScale.shouldBeNull()
        calibration(AxisVerdict.Inconclusive(setOf(OrientationField.O1, OrientationField.O2)))
            .appliedScale
            .shouldBeNull()
        calibration(
            AxisVerdict.Mismatched(
                expectedField = OrientationField.O2,
                respondingField = OrientationField.O3,
                deltaUnits = 10_920,
            ),
        ).appliedScale.shouldBeNull()
        calibration(
            AxisVerdict.CrossCoupled(
                mapOf(OrientationField.O1 to 5_900, OrientationField.O2 to 6_290),
            ),
        ).appliedScale.shouldBeNull()
    }

    @Test
    fun `a suspect scale is withheld until it is confirmed`() {
        val suspect =
            AxisVerdict.Suspect(
                degreesPerUnit = 2.25f,
                field = OrientationField.O1,
                deltaUnits = 40,
                why = "90° from 40 units implies a full turn from a rounding error",
            )

        // The number exists on the verdict either way — the refusal has to be able to show
        // its arithmetic — but it does not become usable until somebody says so.
        calibration(suspect).appliedScale.shouldBeNull()
        calibration(suspect.copy(confirmed = true)).appliedScale shouldBe 2.25f
    }

    @Test
    fun `a mismatch still reports which field responded`() {
        // The point of the mismatch verdict is the evidence, not the refusal. An axis that
        // refused *and forgot what moved* would throw away the only measurement this
        // feature exists to take.
        calibration(
            AxisVerdict.Mismatched(
                expectedField = OrientationField.O2,
                respondingField = OrientationField.O3,
                deltaUnits = 10_920,
            ),
        ).respondingField shouldBe OrientationField.O3
    }

    @Test
    fun `the current field assignment is declared in exactly one place`() {
        OrientationField.currentlyMappedTo(HeadAxis.YAW) shouldBe OrientationField.O1
        OrientationField.currentlyMappedTo(HeadAxis.PITCH) shouldBe OrientationField.O2
        OrientationField.currentlyMappedTo(HeadAxis.ROLL) shouldBe OrientationField.O3
    }

    @Test
    fun `a fresh calibration is uncalibrated on every axis, not absent`() {
        val fresh = HeadCalibration.uncalibrated(PodModel.AIRPODS_PRO_2)

        // Present-and-uncalibrated rather than missing, so "never measured" and "the record
        // forgot this axis" stay different facts all the way out to the state dump.
        HeadAxis.entries.forEach { axis ->
            fresh.forAxis(axis).verdict shouldBe AxisVerdict.Uncalibrated
            fresh.forAxis(axis).appliedScale.shouldBeNull()
        }
        fresh.hasAnyScale shouldBe false
    }

    @Test
    fun `the pose sequence starts neutral and covers every axis once`() {
        val sequence = CalibrationPose.Sequence

        // Neutral first is not presentation order: every scale is a difference against the
        // neutral hold, so a run that reached it last could produce nothing at all.
        sequence.first().isNeutral shouldBe true
        sequence.drop(1).mapNotNull { it.axis } shouldBe HeadAxis.entries.toList()
        sequence.first().referenceDegrees shouldBe 0f
    }
}
