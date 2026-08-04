package io.github.andrewkomkov.greenpods.core.model

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import org.junit.Test
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * The transport gate, at the level where it is defined.
 *
 * `usableFeatures` is the one derivation the whole UI depends on, and getting it wrong
 * in either direction is bad in a different way: too permissive offers a button that
 * silently does nothing, too strict hides a feature that works.
 */
class PodStateTest {
    private fun pod(
        model: PodModel = PodModel.AIRPODS_PRO_2,
        transports: Set<Transport> = setOf(Transport.BLE_ADVERTISEMENT),
    ) = PodState(address = "AA:BB:CC:DD:EE:FF", model = model, activeTransports = transports)

    @Test
    fun `with only the advertisement live, ear detection is the sole usable feature`() {
        val state = pod()

        state.usableFeatures shouldBe setOf(PodFeature.EAR_DETECTION)
        state.gatedFeatures shouldContain PodFeature.NOISE_CONTROL
        state.gatedFeatures shouldContain PodFeature.HEAD_TRACKING
    }

    @Test
    fun `opening the Apple protocol channel unlocks everything the model has`() {
        val state = pod(transports = setOf(Transport.BLE_ADVERTISEMENT, Transport.AAP_L2CAP))

        state.usableFeatures shouldContain PodFeature.NOISE_CONTROL
        state.usableFeatures shouldContain PodFeature.CONVERSATIONAL_AWARENESS
        state.gatedFeatures shouldBe emptySet()
    }

    @Test
    fun `a feature the hardware lacks is never offered, however many transports are live`() {
        val state =
            pod(
                model = PodModel.AIRPODS_2,
                transports = Transport.entries.toSet(),
            )

        state.usableFeatures shouldBe setOf(PodFeature.EAR_DETECTION)
        state.usableFeatures shouldNotContain PodFeature.NOISE_CONTROL
        state.gatedFeatures shouldBe emptySet()
    }

    @Test
    fun `GATT heart rate needs the GATT transport and nothing else`() {
        val powerbeats = pod(model = PodModel.POWERBEATS_PRO_2, transports = setOf(Transport.GATT))

        powerbeats.usableFeatures shouldContain PodFeature.HEART_RATE_GATT
        powerbeats.usableFeatures shouldNotContain PodFeature.HEART_RATE_AAP
    }

    @Test
    fun `a locked feature always carries a reason`() {
        val state =
            pod().copy(
                transportStatuses =
                    listOf(
                        TransportStatus.AdvertisementAvailable,
                        TransportStatus(
                            Transport.AAP_L2CAP,
                            TransportAvailability.UNAVAILABLE,
                            "This phone refuses PSM 0x1001.",
                        ),
                    ),
            )

        state.reasonFor(PodFeature.NOISE_CONTROL) shouldBe "This phone refuses PSM 0x1001."
    }

    @Test
    fun `an unrecorded transport reports as not probed rather than unavailable`() {
        val state = pod().copy(transportStatuses = listOf(TransportStatus.AdvertisementAvailable))

        state.statusOf(Transport.AAP_L2CAP).availability shouldBe TransportAvailability.NOT_PROBED
        state.reasonFor(PodFeature.HEAD_TRACKING) shouldContainText "Not checked"
    }

    @Test
    fun `a model with no sensor is unsupported for a model reason, not a transport one`() {
        // SC-008's two cases, seen from the gate that produces them. The distinction is
        // not cosmetic: "these earbuds don't have it" is permanent and "this phone can't
        // reach it" may be fixed by re-probing, so a user shown the wrong one either
        // gives up on a working feature or waits for one that will never arrive.
        val noSensor = pod(model = PodModel.AIRPODS_PRO_2, transports = Transport.entries.toSet())
        val unreachable = pod(model = PodModel.AIRPODS_PRO_3, transports = setOf(Transport.BLE_ADVERTISEMENT))

        val unsupported = noSensor.heartRate
        unsupported.shouldBeTypeOf<HeartRateState.Unsupported>()
        unsupported.reason shouldContainText "earbuds"
        // No transport is named, because none is at fault.
        noSensor.heartRateFeature shouldBe null

        val locked = unreachable.heartRate
        locked.shouldBeTypeOf<HeartRateState.Locked>()
        locked.transport shouldBe Transport.AAP_L2CAP
        locked.reason shouldContainText "Not checked"
        // The two never collapse into one sentence.
        (locked.reason == unsupported.reason) shouldBe false
    }

    @Test
    fun `a sensor the transport cannot reach stays locked rather than becoming unsupported`() {
        // The model has the hardware, so the state must keep saying so even with every
        // transport down — otherwise a dropped channel reads as earbuds without a sensor.
        val powerbeats = pod(model = PodModel.POWERBEATS_PRO_2, transports = emptySet())

        // With nothing reachable the preferred route is named, not the last one tried:
        // the lock has to point at the transport worth re-probing.
        powerbeats.heartRateFeature shouldBe PodFeature.HEART_RATE_AAP
        powerbeats.heartRate.shouldBeTypeOf<HeartRateState.Locked>().transport shouldBe Transport.AAP_L2CAP
    }

    @Test
    fun `a live route is preferred over a merely present one, without either falling back`() {
        // Powerbeats Pro 2 is the one model with both routes. FR-004 forbids blending
        // them, and choosing the reachable one is not the same as falling back: whichever
        // is chosen, the reading it produces records its own source.
        val gattOnly = pod(model = PodModel.POWERBEATS_PRO_2, transports = setOf(Transport.GATT))

        gattOnly.heartRateFeature shouldBe PodFeature.HEART_RATE_GATT
        gattOnly.heartRate shouldBe gattOnly.heartRateSession
    }

    @Test
    fun `an unknown battery level is absent rather than zero`() {
        BatteryComponent.Unknown.levelPercent shouldBe null
        BatteryState().lowestBudPercent shouldBe null
    }

    @Test
    fun `the lowest bud level ignores the case and unknown components`() {
        val battery =
            BatteryState(
                left = BatteryComponent(80, ChargeStatus.DISCHARGING),
                right = BatteryComponent(35, ChargeStatus.DISCHARGING),
                case = BatteryComponent(5, ChargeStatus.DISCHARGING),
            )

        battery.lowestBudPercent shouldBe 35
    }

    @Test
    fun `noise control modes map to their long-press bitmask positions`() {
        NoiseControlMode.OFF.configBit shouldBe 0x01
        NoiseControlMode.NOISE_CANCELLATION.configBit shouldBe 0x02
        NoiseControlMode.TRANSPARENCY.configBit shouldBe 0x04
        NoiseControlMode.ADAPTIVE.configBit shouldBe 0x08
    }

    @Test
    fun `every feature has a label and an explanation fit to show a user`() {
        PodFeature.entries.forEach { feature ->
            feature.displayName.isNotBlank() shouldBe true
            feature.displayName shouldBe feature.displayName.trim()
            (feature.displayName != feature.name) shouldBe true
            feature.explanation.isNotBlank() shouldBe true
        }
    }
}
