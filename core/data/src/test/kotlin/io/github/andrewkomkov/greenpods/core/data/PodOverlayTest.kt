package io.github.andrewkomkov.greenpods.core.data

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.ControlCommand
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.HeartRateSample
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

    @Test
    fun `ear detection from the channel is folded in`() {
        val state = EarDetectionState(WearState.IN_EAR, WearState.IN_CASE)
        val overlay = PodOverlay.Empty.reduce(AapEvent.EarDetection(state))

        overlay.applyTo(pod).earDetection shouldBe state
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
    fun `heart rate is carried without touching anything else`() {
        val overlay = PodOverlay.Empty.copy(heartRate = HeartRateSample(72, HeartRateSample.Source.GATT))

        val applied = overlay.applyTo(pod)

        applied.heartRate?.beatsPerMinute shouldBe 72
        applied.noiseControlMode shouldBe NoiseControlMode.OFF
    }
}
