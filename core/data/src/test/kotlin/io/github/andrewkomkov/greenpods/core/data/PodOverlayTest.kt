package io.github.andrewkomkov.greenpods.core.data

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.ControlCommand
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The overlay's one rule: null means "nothing learned", never "cleared".
 */
class PodOverlayTest {
    private val pod =
        PodState(
            address = "AA:BB:CC:DD:EE:FF",
            model = PodModel.AIRPODS_PRO_2,
            battery = BatteryState(left = BatteryComponent(80, ChargeStatus.DISCHARGING)),
            noiseControlMode = NoiseControlMode.OFF,
        )

    @Test
    fun `an empty overlay changes nothing`() {
        PodOverlay.Empty.applyTo(pod) shouldBe pod
    }

    @Test
    fun `settings events are folded in`() {
        val overlay =
            PodOverlay.Empty
                .reduce(AapEvent.NoiseControl(NoiseControlMode.ADAPTIVE))
                .reduce(AapEvent.AdaptiveNoiseStrength(70))
                .reduce(AapEvent.ConversationalAwarenessState(enabled = true))

        val applied = overlay.applyTo(pod)

        applied.noiseControlMode shouldBe NoiseControlMode.ADAPTIVE
        applied.adaptiveNoiseStrength shouldBe 70
        applied.conversationalAwarenessEnabled shouldBe true
    }

    @Test
    fun `the accessory's own name wins over the model name`() {
        val overlay = PodOverlay.Empty.reduce(AapEvent.DeviceInfo(listOf("Andrew's AirPods", "AirPods Pro")))

        overlay.applyTo(pod).name shouldBe "Andrew's AirPods"
    }

    @Test
    fun `an empty device-info payload leaves the name alone`() {
        val overlay = PodOverlay.Empty.reduce(AapEvent.DeviceInfo(emptyList()))

        overlay.applyTo(pod).name shouldBe pod.name
    }

    @Test
    fun `an Apple protocol battery report beats the advertisement's ten percent steps`() {
        val precise = BatteryState(left = BatteryComponent(83, ChargeStatus.DISCHARGING))
        val overlay = PodOverlay.Empty.reduce(AapEvent.Battery(precise))

        overlay
            .applyTo(pod)
            .battery.left.levelPercent shouldBe 83
    }

    /**
     * The regression this fix exists for.
     *
     * The channel says a bud's wear changed but not which bud; the advertisement says the
     * side, because it carries the primary flag. While the channel's version was folded in
     * here, it overwrote the advertisement's — and taking out the left bud made the app
     * report the right one as out.
     */
    @Test
    fun `a wear change from the channel never overwrites the side the advertisement resolved`() {
        val fromTheAir =
            pod.copy(earDetection = EarDetectionState(left = WearState.OUT_OF_EAR, right = WearState.IN_EAR))

        val overlay =
            PodOverlay.Empty
                .reduce(AapEvent.EarDetection(first = WearState.IN_EAR, second = WearState.OUT_OF_EAR))
                // The same physical state, the other way round — the order moves on its
                // own, which is the second reason this must not reach per-side state.
                .reduce(AapEvent.EarDetection(first = WearState.OUT_OF_EAR, second = WearState.IN_EAR))

        overlay.applyTo(fromTheAir).earDetection shouldBe fromTheAir.earDetection
    }

    @Test
    fun `momentary and undecoded events never become state`() {
        val overlay =
            PodOverlay.Empty
                .reduce(AapEvent.ConversationalAwarenessLevel(2))
                .reduce(AapEvent.HeadTracking(HeadTrackingSample(1, 2, 3, 4, 5)))
                .reduce(AapEvent.UnhandledControl(ControlCommand.CHIME_VOLUME, byteArrayOf(1)))
                .reduce(AapEvent.Unknown(byteArrayOf(0xF, 0xF)))

        overlay shouldBe PodOverlay.Empty
        overlay.isEmpty shouldBe true
    }

    @Test
    fun `heart rate is carried as a state without touching anything else`() {
        val reading =
            HeartRateReading(
                beatsPerMinute = 72,
                confidence = null,
                source = HeartRateReading.Source.GATT,
                measuredAtEpochMillis = 1L,
            )
        val overlay =
            PodOverlay.Empty.copy(
                heartRate = HeartRateState.Measuring(reading),
                heartRateSensing = HeartRateSensing(enabled = true, trustedCount = 1),
            )

        val applied = overlay.applyTo(pod)

        // The overlay carries what the session published. Whether it may be *shown* is
        // PodState.heartRate's job, and this pod's transports do not carry heart rate at
        // all — which is why the session state is asserted rather than the gated one.
        applied.heartRateSession.trustedReading?.beatsPerMinute shouldBe 72
        applied.heartRateSensing.trustedCount shouldBe 1
        applied.noiseControlMode shouldBe NoiseControlMode.OFF
    }
}
