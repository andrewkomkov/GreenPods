package io.github.andrewkomkov.greenpods.core.model

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
