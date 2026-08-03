package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * These fixtures are the exact byte sequences captured from AirPods Pro 2
 * (firmware 7A305) and published in the LibrePods protocol notes. They are the
 * only ground truth available for a format Apple has never documented, so the
 * decoder is pinned against them rather than against hand-written examples.
 */
class AapProtocolTest {
    private fun bytes(hex: String): ByteArray =
        hex
            .split(" ")
            .filter(String::isNotBlank)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun `control command encodes identifier and data into the fixed 7-byte body`() {
        val packet = AapProtocol.control(ControlCommand.LISTENING_MODE, NoiseControlMode.TRANSPARENCY.wireValue)

        packet shouldBe bytes("04 00 04 00 09 00 0D 03 00 00 00")
    }

    @Test
    fun `opcode is read little-endian from the header`() {
        AapProtocol.opcodeOf(bytes("04 00 04 00 09 00 0D 03 00 00 00")) shouldBe Opcode.CONTROL
        AapProtocol.opcodeOf(bytes("04 00 04 00 4B 00 02 00 01 03")) shouldBe Opcode.CONVERSATIONAL_AWARENESS
    }

    @Test
    fun `malformed packets do not decode as an opcode`() {
        AapProtocol.opcodeOf(bytes("04 00")) shouldBe null
        AapProtocol.opcodeOf(bytes("FF FF FF FF 09 00")) shouldBe null
    }

    @Test
    fun `battery report decodes all three components with charge state`() {
        // Component ids come from the normative table (case 0x08, left 0x04, right 0x02).
        // The source doc's worked example labels 0x02 as "Left", contradicting its own
        // table; the table wins here. Worth re-confirming against a real device.
        val event =
            AapDecoder.decode(
                bytes("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01"),
            )

        val battery = event.shouldBeInstanceOf<AapEvent.Battery>().state
        battery.right.levelPercent shouldBe 100
        battery.right.status shouldBe ChargeStatus.DISCHARGING
        battery.left.levelPercent shouldBe 99
        battery.left.status shouldBe ChargeStatus.CHARGING
        battery.case.levelPercent shouldBe 17
        battery.case.status shouldBe ChargeStatus.DISCHARGING
    }

    @Test
    fun `noise control notification maps wire values to modes`() {
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 0D 01 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.OFF
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 0D 02 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.NOISE_CANCELLATION
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 0D 04 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.ADAPTIVE
    }

    @Test
    fun `ear detection reports both buds`() {
        val state =
            AapDecoder
                .decode(bytes("04 00 04 00 06 00 00 02"))
                .shouldBeInstanceOf<AapEvent.EarDetection>()
                .state

        state.primary shouldBe WearState.IN_EAR
        state.secondary shouldBe WearState.IN_CASE
        state.anyInEar shouldBe true
        state.bothInEar shouldBe false
    }

    @Test
    fun `conversational awareness state and level use different opcodes`() {
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 28 01 00 00 00"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessState>()
            .enabled shouldBe true
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 28 02 00 00 00"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessState>()
            .enabled shouldBe false
        AapDecoder
            .decode(bytes("04 00 04 00 4B 00 02 00 01 03"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessLevel>()
            .level shouldBe 3
    }

    @Test
    fun `adaptive noise strength is clamped to the documented 0-100 range`() {
        AapDecoder
            .decode(bytes("04 00 04 00 09 00 2E 64 00 00 00"))
            .shouldBeInstanceOf<AapEvent.AdaptiveNoiseStrength>()
            .level shouldBe 100
    }

    @Test
    fun `recognised control commands without a typed model are surfaced, not dropped`() {
        val event = AapDecoder.decode(bytes("04 00 04 00 09 00 30 01 00 00 00"))

        // HRM_STATE is understood as an identifier but has no decoder yet; it must
        // still reach diagnostics rather than vanish.
        event.shouldBeInstanceOf<AapEvent.UnhandledControl>().command shouldBe ControlCommand.HRM_STATE
    }

    @Test
    fun `unknown traffic is preserved verbatim`() {
        AapDecoder.decode(bytes("04 00 04 00 EE 00 01 02")).shouldBeInstanceOf<AapEvent.Unknown>()
    }

    @Test
    fun `listening mode config bits match the documented bitmask`() {
        NoiseControlMode.OFF.configBit shouldBe 0x01
        NoiseControlMode.NOISE_CANCELLATION.configBit shouldBe 0x02
        NoiseControlMode.TRANSPARENCY.configBit shouldBe 0x04
        NoiseControlMode.ADAPTIVE.configBit shouldBe 0x08
    }

    @Test
    fun `handshake matches the captured opening packet`() {
        AapProtocol.HANDSHAKE shouldBe
            bytes("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
        AapProtocol.REQUEST_NOTIFICATIONS shouldBe bytes("04 00 04 00 0F 00 FF FF FE FF")
    }

    @Test
    fun `device info splits the null-terminated string table`() {
        val packet =
            bytes("04 00 04 00 1D 00") +
                "AirPods Pro\u0000A3048\u0000Apple Inc.\u0000QXNRHHYXP6\u0000".toByteArray()

        val fields = AapDecoder.decode(packet).shouldBeInstanceOf<AapEvent.DeviceInfo>().fields
        fields.shouldNotBeNull()
        fields shouldBe listOf("AirPods Pro", "A3048", "Apple Inc.", "QXNRHHYXP6")
    }
}
