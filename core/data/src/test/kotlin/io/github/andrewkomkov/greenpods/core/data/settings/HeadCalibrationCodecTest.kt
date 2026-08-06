package io.github.andrewkomkov.greenpods.core.data.settings

import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * What survives a restart, and what must not be invented on the way back.
 *
 * The decoding half matters more than the encoding half here. A preference file is the one
 * input this app cannot validate at the source: it may have been written by a later build,
 * a partly-failed write, or a version that named verdicts this one has never heard of. The
 * rule throughout is that a bad row costs its own axis and nothing else — and that a missing
 * axis comes back as *uncalibrated*, never as a number.
 */
class HeadCalibrationCodecTest {
    private val model = PodModel.AIRPODS_PRO_2

    private fun calibration(vararg axes: Pair<HeadAxis, AxisVerdict>) =
        HeadCalibration(
            model = model,
            axes =
                HeadAxis.entries.associateWith { axis ->
                    AxisCalibration(axis, axes.toMap()[axis] ?: AxisVerdict.Uncalibrated)
                },
        )

    @Test
    fun `a measured calibration round-trips`() {
        val original =
            calibration(
                HeadAxis.YAW to AxisVerdict.Measured(0.0143f, OrientationField.O1, 6_290),
            )

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        restored.forAxis(HeadAxis.YAW).appliedScale shouldBe 0.0143f
        restored.forAxis(HeadAxis.YAW).respondingField shouldBe OrientationField.O1
    }

    @Test
    fun `the stored precision does not round a real scale away to nothing`() {
        // A plausible scale is a number like 0.0143 degrees per unit. The gesture codec's
        // two decimal places would store every one of them as 0.00 — a measurement silently
        // becoming a refusal that still calls itself a measurement.
        val original = calibration(HeadAxis.YAW to AxisVerdict.Measured(0.0143f, OrientationField.O1, 6_290))

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        restored.forAxis(HeadAxis.YAW).appliedScale!!.toDouble() shouldBeGreaterThan 0.0
    }

    @Test
    fun `an axis the stored value never mentions decodes as uncalibrated, not as a default`() {
        // The whole point of departing from GestureBindingCodec. A default scale here would
        // be a fabricated constant re-entering by the back door.
        val restored = HeadCalibrationCodec.decode(model, "YAW:MEASURED:O1:0.0143000:6290:0")

        restored.forAxis(HeadAxis.PITCH).verdict shouldBe AxisVerdict.Uncalibrated
        restored.forAxis(HeadAxis.PITCH).appliedScale.shouldBeNull()
        restored.forAxis(HeadAxis.ROLL).appliedScale.shouldBeNull()
    }

    @Test
    fun `a malformed row costs its own axis and nothing else`() {
        val restored =
            HeadCalibrationCodec.decode(
                model,
                "YAW:MEASURED:O1:0.0143000:6290:0|PITCH:nonsense|ROLL:SKIPPED::0.0000000:0:0",
            )

        restored.forAxis(HeadAxis.YAW).appliedScale shouldBe 0.0143f
        restored.forAxis(HeadAxis.PITCH).verdict shouldBe AxisVerdict.Uncalibrated
        restored.forAxis(HeadAxis.ROLL).verdict shouldBe AxisVerdict.Skipped
    }

    @Test
    fun `a verdict this build has never heard of drops its row rather than the record`() {
        val restored =
            HeadCalibrationCodec.decode(
                model,
                "YAW:MEASURED:O1:0.0143000:6290:0|PITCH:QUATERNION_FITTED:O2:0.0100000:100:0",
            )

        restored.forAxis(HeadAxis.YAW).appliedScale shouldBe 0.0143f
        restored.forAxis(HeadAxis.PITCH).verdict shouldBe AxisVerdict.Uncalibrated
    }

    @Test
    fun `an empty or absent preference is a fully uncalibrated accessory`() {
        listOf(null, "").forEach { raw ->
            val restored = HeadCalibrationCodec.decode(model, raw)
            restored.hasAnyScale shouldBe false
            HeadAxis.entries.forEach { restored.forAxis(it).verdict shouldBe AxisVerdict.Uncalibrated }
        }
    }

    @Test
    fun `an unconfirmed suspect stays unusable across a restart`() {
        // Confirmation is a decision the wearer made about one measurement. Losing it on the
        // way through the preference file would promote a suspect number to a trusted one by
        // restarting the app, which is the cheapest possible way to defeat FR-018.
        val original =
            calibration(
                HeadAxis.YAW to
                    AxisVerdict.Suspect(2.25f, OrientationField.O1, 40, why = "implausible", confirmed = false),
            )

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        restored.forAxis(HeadAxis.YAW).appliedScale.shouldBeNull()
        (restored.forAxis(HeadAxis.YAW).verdict as AxisVerdict.Suspect).confirmed shouldBe false
    }

    @Test
    fun `a confirmed suspect survives as confirmed, and still says it is suspect`() {
        val original =
            calibration(
                HeadAxis.YAW to
                    AxisVerdict.Suspect(2.25f, OrientationField.O1, 40, why = "implausible", confirmed = true),
            )

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        restored.forAxis(HeadAxis.YAW).appliedScale shouldBe 2.25f
        val verdict = restored.forAxis(HeadAxis.YAW).verdict as AxisVerdict.Suspect
        verdict.confirmed shouldBe true
        // The restored reason says it was measured then, rather than replaying a sentence
        // written about a hold that is long over.
        verdict.why shouldContain "when it was measured"
    }

    @Test
    fun `a restored mismatch keeps the field that responded`() {
        val original =
            calibration(
                HeadAxis.PITCH to
                    AxisVerdict.Mismatched(OrientationField.O2, OrientationField.O3, 10_920),
            )

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        val verdict = restored.forAxis(HeadAxis.PITCH).verdict as AxisVerdict.Mismatched
        verdict.respondingField shouldBe OrientationField.O3
        verdict.expectedField shouldBe OrientationField.O2
        restored.forAxis(HeadAxis.PITCH).appliedScale.shouldBeNull()
    }

    @Test
    fun `a cross-coupled verdict survives as a refusal`() {
        // The expected outcome on current hardware. It must come back as itself rather than
        // as "uncalibrated", because those are different findings: one says nobody measured,
        // the other says somebody measured and the model did not fit.
        val original =
            calibration(
                HeadAxis.YAW to
                    AxisVerdict.CrossCoupled(mapOf(OrientationField.O1 to 5_900, OrientationField.O2 to 6_290)),
            )

        val restored = HeadCalibrationCodec.decode(model, HeadCalibrationCodec.encode(original))

        restored.forAxis(HeadAxis.YAW).verdict.shouldBeCrossCoupled()
        restored.forAxis(HeadAxis.YAW).appliedScale.shouldBeNull()
    }

    private fun AxisVerdict.shouldBeCrossCoupled() {
        (this is AxisVerdict.CrossCoupled) shouldBe true
    }
}
