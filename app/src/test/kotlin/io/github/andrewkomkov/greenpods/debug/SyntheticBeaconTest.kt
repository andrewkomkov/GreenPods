package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeaconDecoder
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The synthetic beacon round-tripped through the real decoder.
 *
 * Two things at once: it proves the adb injection path produces payloads the app will
 * actually accept, and — because the encoder was written from the layout notes rather
 * than from the decoder — it independently checks that the decoder reads that layout
 * the way `docs/protocol-research.md` describes it.
 */
class SyntheticBeaconTest {
    private fun decode(
        modelId: Int = PodModel.AIRPODS_PRO_2.modelId,
        left: Int = 70,
        right: Int = 50,
        case: Int? = 30,
        wear: String = "in_ear",
        charging: Boolean = false,
    ) = AppleBeaconDecoder.decode(
        SyntheticBeacon.build(
            modelId = modelId,
            leftPercent = left,
            rightPercent = right,
            casePercent = case,
            wear = wear,
            charging = charging,
        ),
    )

    @Test
    fun `a built payload decodes back to the values it was built from`() {
        val beacon = decode().shouldNotBeNull()

        beacon.model shouldBe PodModel.AIRPODS_PRO_2
        beacon.battery.left.levelPercent shouldBe 70
        beacon.battery.right.levelPercent shouldBe 50
        beacon.battery.case.levelPercent shouldBe 30
    }

    @Test
    fun `every model in the registry round-trips`() {
        PodModel.entries
            .filter { it != PodModel.UNKNOWN }
            .forEach { model ->
                decode(modelId = model.modelId).shouldNotBeNull().model shouldBe model
            }
    }

    @Test
    fun `wear states map to the transitions auto-pause cares about`() {
        decode(wear = "in_ear").shouldNotBeNull().earDetection.bothInEar shouldBe true

        val oneOut = decode(wear = "one_out").shouldNotBeNull().earDetection
        oneOut.anyInEar shouldBe true
        oneOut.bothInEar shouldBe false

        val out = decode(wear = "out").shouldNotBeNull().earDetection
        out.anyInEar shouldBe false
        out.primary shouldBe WearState.OUT_OF_EAR

        decode(wear = "in_case").shouldNotBeNull().earDetection.primary shouldBe WearState.IN_CASE
    }

    @Test
    fun `an absent case level is the unknown sentinel, not zero`() {
        decode(case = null)
            .shouldNotBeNull()
            .battery.case.levelPercent shouldBe null
    }

    @Test
    fun `charging is carried in the high nibble of the case byte`() {
        val beacon = decode(charging = true).shouldNotBeNull()

        beacon.battery.left.status shouldBe ChargeStatus.CHARGING
        beacon.battery.right.status shouldBe ChargeStatus.CHARGING
        beacon.battery.case.status shouldBe ChargeStatus.CHARGING
    }

    @Test
    fun `levels are quantised the way the wire format quantises them`() {
        // The advertisement carries steps of ten; 77 % cannot be expressed and must not
        // pretend to be.
        decode(left = 77)
            .shouldNotBeNull()
            .battery.left.levelPercent shouldBe 70
        decode(left = 100)
            .shouldNotBeNull()
            .battery.left.levelPercent shouldBe 100
        decode(left = 0)
            .shouldNotBeNull()
            .battery.left.levelPercent shouldBe 0
    }
}
