package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.StemLongPressAction
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Every command GreenPods can send, pinned byte for byte.
 *
 * No phone available to this project can open the L2CAP channel, so these tests are the
 * *only* verification these packets will ever get before they reach real hardware. They
 * are written against the LibrePods captures rather than against the implementation:
 * if a command changes shape, this file should be the thing that objects.
 */
class AapCommandsTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `listening mode carries the wire value in the first data byte`() {
        hex(AapCommands.listeningMode(NoiseControlMode.OFF)) shouldBe "04 00 04 00 09 00 0D 01 00 00 00"
        hex(AapCommands.listeningMode(NoiseControlMode.NOISE_CANCELLATION)) shouldBe "04 00 04 00 09 00 0D 02 00 00 00"
        hex(AapCommands.listeningMode(NoiseControlMode.TRANSPARENCY)) shouldBe "04 00 04 00 09 00 0D 03 00 00 00"
        hex(AapCommands.listeningMode(NoiseControlMode.ADAPTIVE)) shouldBe "04 00 04 00 09 00 0D 04 00 00 00"
    }

    @Test
    fun `adaptive noise strength is clamped so a slider cannot build an invalid packet`() {
        hex(AapCommands.adaptiveNoiseStrength(0)) shouldBe "04 00 04 00 09 00 2E 00 00 00 00"
        hex(AapCommands.adaptiveNoiseStrength(100)) shouldBe "04 00 04 00 09 00 2E 64 00 00 00"
        hex(AapCommands.adaptiveNoiseStrength(255)) shouldBe "04 00 04 00 09 00 2E 64 00 00 00"
        hex(AapCommands.adaptiveNoiseStrength(-20)) shouldBe "04 00 04 00 09 00 2E 00 00 00 00"
    }

    @Test
    fun `toggles use the protocol's 01 on and 02 off encoding`() {
        hex(AapCommands.conversationalAwareness(true)) shouldBe "04 00 04 00 09 00 28 01 00 00 00"
        hex(AapCommands.conversationalAwareness(false)) shouldBe "04 00 04 00 09 00 28 02 00 00 00"
        hex(AapCommands.earDetection(true)) shouldBe "04 00 04 00 09 00 0A 01 00 00 00"
        hex(AapCommands.heartRateSensor(false)) shouldBe "04 00 04 00 09 00 30 02 00 00 00"
    }

    @Test
    fun `long press cycle is a bitmask of one shl wireValue minus one`() {
        // ANC (0x02) and Transparency (0x03) -> bits 1 and 2 -> 0b0110 = 0x06.
        val packet =
            AapCommands.listeningModeCycle(
                setOf(NoiseControlMode.NOISE_CANCELLATION, NoiseControlMode.TRANSPARENCY),
            )

        hex(packet!!) shouldBe "04 00 04 00 09 00 1A 06 00 00 00"
    }

    @Test
    fun `every mode selected sets all four bits`() {
        val packet = AapCommands.listeningModeCycle(NoiseControlMode.entries.toSet())

        hex(packet!!) shouldBe "04 00 04 00 09 00 1A 0F 00 00 00"
    }

    @Test
    fun `an empty cycle produces no packet at all`() {
        // A zero mask would leave the accessory cycling through nothing, and the phone
        // could not undo it. Refusing to build the packet is the only safe answer.
        AapCommands.listeningModeCycle(emptySet()).shouldBeNull()
    }

    @Test
    fun `stem long press puts the right bud first`() {
        val packet =
            AapCommands.stemLongPress(
                right = StemLongPressAction.NOISE_CONTROL,
                left = StemLongPressAction.VOICE_ASSISTANT,
            )

        hex(packet) shouldBe "04 00 04 00 09 00 16 01 05 00 00"
    }

    @Test
    fun `a single argument applies the same action to both buds`() {
        hex(AapCommands.stemLongPress(StemLongPressAction.NOISE_CONTROL)) shouldBe "04 00 04 00 09 00 16 01 01 00 00"
    }

    @Test
    fun `the 1 Hz heart-rate start frame matches the capture byte for byte`() {
        // The frame that was actually sent to AirPods Pro 3 on 2026-08-04 and produced
        // heart-rate reports one second apart, with no workout running.
        val packet =
            HidTransport.setReportInterval(
                serviceId = 0x13,
                featureReportId = 1,
                intervalMicros = 1_000_000,
            )

        hex(packet) shouldBe
            "04 00 04 00 17 00 00 00 10 00 0F 00 08 78 42 0B 08 13 10 02 1A 05 01 40 42 0F 00"
    }

    @Test
    fun `stopping is the same frame with interval zero`() {
        // Not a separate command, and not merely hiding the number: interval 0 is the
        // whole off switch, and it is what makes FR-014 true in the accessory rather
        // than only on screen.
        hex(HidTransport.stopReportStream(serviceId = 0x13, featureReportId = 1)) shouldBe
            "04 00 04 00 17 00 00 00 10 00 0F 00 08 78 42 0B 08 13 10 02 1A 05 01 00 00 00 00"
    }

    @Test
    fun `the service id and report id are whatever discovery found`() {
        // Nothing here defaults. A default service id is a hard-coded service id in a
        // disguise, and that is how LibrePods' 0x0E came to be silently ignored (FR-002).
        val packet =
            HidTransport.setReportInterval(
                serviceId = 0x2A,
                featureReportId = 7,
                intervalMicros = 2_000_000,
                sequence = 1,
            )

        hex(packet) shouldBe
            "04 00 04 00 17 00 00 00 10 00 0F 00 08 01 42 0B 08 2A 10 02 1A 05 07 80 84 1E 00"
    }

    @Test
    fun `the declared length always matches the body that follows it`() {
        listOf(0x13, 0x80, 0x1234).forEach { serviceId ->
            val packet = HidTransport.setReportInterval(serviceId, featureReportId = 1, intervalMicros = 500_000)
            val declared = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8)

            (packet.size - 12) shouldBe declared
        }
    }

    @Test
    fun `milliseconds become the microseconds the wire wants`() {
        HidTransport.intervalMicros(1_000) shouldBe 1_000_000
        HidTransport.intervalMicros(0) shouldBe HidTransport.INTERVAL_STOPPED_MICROS
    }
}
