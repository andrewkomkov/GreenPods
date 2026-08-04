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
    // The decoder is per-session now: opcode 0x17 carries several HID services at once
    // and which service a report belongs to is only knowable from descriptors sent
    // earlier on the same channel.
    private val decoder = AapDecoder()

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
            decoder.decode(
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
        decoder
            .decode(bytes("04 00 04 00 09 00 0D 01 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.OFF
        decoder
            .decode(bytes("04 00 04 00 09 00 0D 02 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.NOISE_CANCELLATION
        decoder
            .decode(bytes("04 00 04 00 09 00 0D 04 00 00 00"))
            .shouldBeInstanceOf<AapEvent.NoiseControl>()
            .mode shouldBe NoiseControlMode.ADAPTIVE
    }

    @Test
    fun `ear detection reports both buds`() {
        val state =
            decoder
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
        decoder
            .decode(bytes("04 00 04 00 09 00 28 01 00 00 00"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessState>()
            .enabled shouldBe true
        decoder
            .decode(bytes("04 00 04 00 09 00 28 02 00 00 00"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessState>()
            .enabled shouldBe false
        decoder
            .decode(bytes("04 00 04 00 4B 00 02 00 01 03"))
            .shouldBeInstanceOf<AapEvent.ConversationalAwarenessLevel>()
            .level shouldBe 3
    }

    @Test
    fun `adaptive noise strength is clamped to the documented 0-100 range`() {
        decoder
            .decode(bytes("04 00 04 00 09 00 2E 64 00 00 00"))
            .shouldBeInstanceOf<AapEvent.AdaptiveNoiseStrength>()
            .level shouldBe 100
    }

    @Test
    fun `recognised control commands without a typed model are surfaced, not dropped`() {
        val event = decoder.decode(bytes("04 00 04 00 09 00 30 01 00 00 00"))

        // HRM_STATE is understood as an identifier but has no decoder yet; it must
        // still reach diagnostics rather than vanish.
        event.shouldBeInstanceOf<AapEvent.UnhandledControl>().command shouldBe ControlCommand.HRM_STATE
    }

    @Test
    fun `unknown traffic is preserved verbatim`() {
        decoder.decode(bytes("04 00 04 00 EE 00 01 02")).shouldBeInstanceOf<AapEvent.Unknown>()
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
    fun `a descriptor frame is no longer decoded as a head-tracking sample`() {
        // The defect this replaces: every 0x17 packet of 55 bytes or more went to the
        // head-tracking decoder, which reads fixed offsets 43..53. The captured
        // descriptor frame is 961 bytes, so it was decoded as a head pose made of
        // garbage — and nothing noticed, because head gestures are off by default (R-3).
        val event = decoder.decode(AapFixtures.descriptorFrame)

        val services = event.shouldBeInstanceOf<AapEvent.HidServices>().services
        services.map(HidService::id) shouldBe listOf(0x10, 0x11, 0x12, 0x13)
        decoder.discoveredHeartRateServiceId shouldBe 0x13
        decoder.canMeasureHeartRate shouldBe true
    }

    @Test
    fun `a start acknowledgement names the service, and is not a measurement`() {
        // Captured verbatim on 2026-08-04, arriving straight after a start request:
        // field 9 carrying the service the accessory acted on. Before this it fell
        // through to AapEvent.Unknown.
        val frame = AapFixtures.hex("04 00 04 00 17 00 00 00 10 00 08 00 08 0F 10 03 4A 02 08 13")

        val event = decoder.decode(frame)

        event.shouldBeInstanceOf<AapEvent.HidServiceStarted>().serviceId shouldBe 0x13
        // And crucially it does not make the sensor "on": an accepted command is this
        // transport's characteristic false positive, and no state may be derived from it
        // (Principle I). Only an arriving report says a sensor is running.
        decoder.decode(frame).shouldBeInstanceOf<AapEvent.HidServiceStarted>()
    }

    @Test
    fun `the readiness list decodes as readiness, not as a pose`() {
        decoder
            .decode(AapFixtures.readyFrame)
            .shouldBeInstanceOf<AapEvent.HidServicesReady>()
            .serviceIds shouldBe listOf(0x10, 0x11, 0x12, 0x13)
    }

    @Test
    fun `a heart-rate report is routed by its service id once descriptors have arrived`() {
        decoder.decode(AapFixtures.descriptorFrame)
        val report = AapFixtures.heartRateSeries.last().report

        val event =
            decoder
                .decode(AapFixtures.inputReportFrame(serviceId = 0x13, report = report))
                .shouldBeInstanceOf<AapEvent.HeartRateReport>()

        event.serviceId shouldBe 0x13
        event.reading.beatsPerMinute shouldBe 81
        event.reading.confidence shouldBe 237
    }

    @Test
    fun `a report on a service with no decoder is surfaced by shape and never by body`() {
        decoder.decode(AapFixtures.descriptorFrame)

        val event =
            decoder
                .decode(AapFixtures.inputReportFrame(serviceId = 0x11, report = byteArrayOf(0x01, 0x63, 0x44)))
                .shouldBeInstanceOf<AapEvent.UnhandledHidReport>()

        event.serviceId shouldBe 0x11
        event.reportId shouldBe 1
        event.length shouldBe 3
    }

    @Test
    fun `head tracking still decodes once its service is known`() {
        decoder.decode(AapFixtures.descriptorFrame)
        // A frame on the devmotion service, long enough for the pose offsets the
        // head-tracking decoder still reads absolutely.
        val pose = ByteArray(40) { (it + 1).toByte() }

        decoder
            .decode(AapFixtures.inputReportFrame(serviceId = 0x10, report = pose))
            .shouldBeInstanceOf<AapEvent.HeadTracking>()
    }

    @Test
    fun `a session reset forgets the services it discovered`() {
        decoder.decode(AapFixtures.descriptorFrame)
        decoder.resetSession()

        decoder.discoveredHeartRateServiceId shouldBe null
        decoder.canMeasureHeartRate shouldBe false
    }

    @Test
    fun `device info splits the null-terminated string table`() {
        val packet =
            bytes("04 00 04 00 1D 00") +
                "AirPods Pro\u0000A3048\u0000Apple Inc.\u0000QXNRHHYXP6\u0000".toByteArray()

        val fields = decoder.decode(packet).shouldBeInstanceOf<AapEvent.DeviceInfo>().fields
        fields.shouldNotBeNull()
        fields shouldBe listOf("AirPods Pro", "A3048", "Apple Inc.", "QXNRHHYXP6")
    }
}
