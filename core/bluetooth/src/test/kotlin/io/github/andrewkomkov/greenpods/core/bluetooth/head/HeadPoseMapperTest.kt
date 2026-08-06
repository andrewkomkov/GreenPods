package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidReportField
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.abs

/**
 * What a stored calibration is allowed to change, and what it must not.
 *
 * The mapper is where a measurement becomes an angle somebody reads, so it is the last place
 * a refusal can still leak a number. Two rules carry the weight: an axis whose verdict does
 * not permit a scale falls back to the labelled approximation rather than borrowing its
 * neighbour's, and calibrating one axis leaves the other two exactly where they were.
 */
class HeadPoseMapperTest {
    private val zero: Short = 0

    private fun sample(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ) = HeadTrackingSample(o1.toShort(), o2.toShort(), o3.toShort(), zero, zero)

    private fun calibrated(vararg axes: Pair<HeadAxis, AxisVerdict>) =
        HeadCalibration(
            model = PodModel.AIRPODS_PRO_2,
            axes =
                HeadAxis.entries.associateWith { axis ->
                    AxisCalibration(axis, axes.toMap()[axis] ?: AxisVerdict.Uncalibrated)
                },
        )

    private infix fun Float.shouldBeAbout(expected: Float) {
        (abs(this - expected) < 0.01f) shouldBe true
    }

    @Test
    fun `the uncalibrated mapper reproduces the constant this project has always used`() {
        // Pinned so that "calibration changed the angles" is a claim a test can distinguish
        // from "somebody changed the fallback".
        HeadPoseMapper.Uncalibrated
            .toPose(sample(o1 = Short.MAX_VALUE.toInt()))
            .yawDegrees shouldBeAbout 180f
    }

    @Test
    fun `a measured axis uses its own scale`() {
        val mapper =
            HeadPoseMapper(
                calibrated(HeadAxis.YAW to AxisVerdict.Measured(0.01431f, OrientationField.O1, 6_290)),
            )

        // 6290 units at 0.01431 deg/unit is the 90 degrees the pose asked for.
        mapper.toPose(sample(o1 = 6_290)).yawDegrees shouldBeAbout 90f
        mapper.isCalibrated(HeadAxis.YAW) shouldBe true
    }

    @Test
    fun `one axis's scale is never applied to another`() {
        val mapper =
            HeadPoseMapper(
                calibrated(HeadAxis.YAW to AxisVerdict.Measured(0.01431f, OrientationField.O1, 6_290)),
            )

        // Pitch was not measured, so it must still read at the fallback — the same raw value
        // through the uncalibrated constant, not through yaw's number.
        val pose = mapper.toPose(sample(o2 = 6_290))
        pose.pitchDegrees shouldBeAbout 6_290 * HeadPoseMapper.UNCALIBRATED_SCALE
        mapper.isCalibrated(HeadAxis.PITCH) shouldBe false
    }

    @Test
    fun `every refusal falls back rather than producing a number`() {
        // The set of verdicts that carry no usable scale, each checked to leave the angle at
        // the approximation. If any of them ever starts scaling, this is where it shows.
        val refusals =
            listOf(
                AxisVerdict.Uncalibrated,
                AxisVerdict.Skipped,
                AxisVerdict.NotHeld("never settled"),
                AxisVerdict.Inconclusive(setOf(OrientationField.O1, OrientationField.O2)),
                AxisVerdict.CrossCoupled(mapOf(OrientationField.O1 to 5_900, OrientationField.O2 to 6_290)),
            )

        refusals.forEach { verdict ->
            val mapper = HeadPoseMapper(calibrated(HeadAxis.YAW to verdict))
            mapper.toPose(sample(o1 = 6_290)).yawDegrees shouldBeAbout 6_290 * HeadPoseMapper.UNCALIBRATED_SCALE
            mapper.isCalibrated(HeadAxis.YAW) shouldBe false
        }
    }

    @Test
    fun `an unconfirmed suspect scale is not applied, and a confirmed one is`() {
        val suspect =
            AxisVerdict.Suspect(
                degreesPerUnit = 2.25f,
                field = OrientationField.O1,
                deltaUnits = 40,
                why = "90 degrees from 40 units implies a full turn from a rounding error",
            )

        HeadPoseMapper(calibrated(HeadAxis.YAW to suspect))
            .toPose(sample(o1 = 40))
            .yawDegrees shouldBeAbout 40 * HeadPoseMapper.UNCALIBRATED_SCALE

        HeadPoseMapper(calibrated(HeadAxis.YAW to suspect.copy(confirmed = true)))
            .toPose(sample(o1 = 40))
            .yawDegrees shouldBeAbout 90f
    }

    @Test
    fun `a declared physical range outranks a stored calibration`() {
        // The accessory describing its own units beats a scale inferred from somebody
        // holding a pose at roughly the angle they were asked for. Nothing declares one
        // today, which is exactly why the ordering is cheap to fix now and awkward later.
        val declared =
            HidReportField(
                usagePage = 0x20,
                usage = 0x0301,
                bitSize = 16,
                count = 1,
                bitOffset = 0,
                byteOffset = 1,
                logicalMinimum = -32_767,
                logicalMaximum = 32_767,
                physicalMinimum = -180,
                physicalMaximum = 180,
            )

        val scales = HeadPoseMapper.declaredScalesFrom(mapOf(HeadAxis.YAW to declared))
        val mapper =
            HeadPoseMapper(
                calibration = calibrated(HeadAxis.YAW to AxisVerdict.Measured(2.0f, OrientationField.O1, 45)),
                declaredScales = scales,
            )

        // 2.0 deg/unit was stored; the descriptor says roughly 0.0055, and the descriptor wins.
        mapper.toPose(sample(o1 = 1_000)).yawDegrees shouldBeAbout 1_000 * (180f / 32_767f)
        mapper.isCalibrated(HeadAxis.YAW) shouldBe true
    }

    @Test
    fun `a descriptor that declares nothing usable yields no scale at all`() {
        // The devmotion case: an opaque blob with no range. Reading a scale out of it would
        // be inventing one, so the map comes back empty and the calibration keeps its turn.
        val silent =
            HidReportField(
                usagePage = 0xFF0C,
                usage = 0x0D,
                bitSize = 16,
                count = 1,
                bitOffset = 0,
                byteOffset = 1,
            )

        HeadPoseMapper.declaredScalesFrom(mapOf(HeadAxis.YAW to silent)) shouldBe emptyMap()
    }

    @Test
    fun `an unsigned declaration is not acted on`() {
        // An orientation read as unsigned is wrong by a whole turn on half its inputs, so a
        // declaration that omits the sign is not one this can act on.
        val unsigned =
            HidReportField(
                usagePage = 0x20,
                usage = 0x0301,
                bitSize = 16,
                count = 1,
                bitOffset = 0,
                byteOffset = 1,
                logicalMinimum = 0,
                logicalMaximum = 65_535,
                physicalMinimum = 0,
                physicalMaximum = 360,
            )

        HeadPoseMapper.declaredScalesFrom(mapOf(HeadAxis.YAW to unsigned)) shouldBe emptyMap()
    }

    @Test
    fun `a measured axis reads the field that actually responded`() {
        // The point of keeping the responding field on the verdict. If a run found that yaw
        // moves O3, the mapper must read O3 for yaw — otherwise the calibration would be a
        // correct scale applied to the wrong bytes, which is worse than no calibration
        // because it looks calibrated.
        val mapper =
            HeadPoseMapper(
                calibrated(HeadAxis.YAW to AxisVerdict.Measured(0.01431f, OrientationField.O3, 6_290)),
            )

        mapper.toPose(sample(o3 = 6_290)).yawDegrees shouldBeAbout 90f
        mapper.toPose(sample(o1 = 6_290)).yawDegrees shouldBeAbout 0f
    }
}
