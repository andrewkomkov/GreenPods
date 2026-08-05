package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * What may and may not be printed to the frame log.
 *
 * FR-023 is a promise about a health measurement, and this is the path where breaking it
 * would be least visible and most damaging: people paste this log into bug reports. The
 * tests below pin both halves of the rule — that a heart rate never appears, and that the
 * rule is not so wide it hides the traffic the protocol work depends on.
 */
class FrameLogPolicyTest {
    /** `04 00 04 00 | 17 00 | 00 00 10 00 | len | 3A len 08 <service> 1A len <report>` */
    private fun inputReportFrame(
        serviceId: Int,
        report: ByteArray = ByteArray(4) { 0x11 },
    ): ByteArray {
        val inner =
            byteArrayOf(0x08, serviceId.toByte(), 0x1A, report.size.toByte()) + report
        val body = byteArrayOf(0x3A, inner.size.toByte()) + inner
        return AapProtocol.HEADER +
            byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x10, 0x00) +
            byteArrayOf((body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte()) +
            body
    }

    @Test
    fun `a heart-rate report body is never printed`() {
        val frame = inputReportFrame(serviceId = 0x13)

        FrameLogPolicy.mayPrintBody(frame, sensitiveServiceIds = setOf(0x13)) shouldBe false
    }

    @Test
    fun `a head-tracking report body is printed once the services are known`() {
        // Orientation is not a health measurement, and these bytes are the only ground
        // truth for where an orientation sits inside a report. Withholding them bought no
        // privacy and cost the ability to derive the layout at all.
        val frame = inputReportFrame(serviceId = 0x10)

        FrameLogPolicy.mayPrintBody(frame, sensitiveServiceIds = setOf(0x13)) shouldBe true
    }

    @Test
    fun `before the accessory has described itself, every report body is withheld`() {
        // The window between a channel opening and the announcement arriving is exactly
        // when a report cannot be attributed — so it is treated as the sensitive one.
        FrameLogPolicy.mayPrintBody(inputReportFrame(0x13), sensitiveServiceIds = null) shouldBe false
        FrameLogPolicy.mayPrintBody(inputReportFrame(0x10), sensitiveServiceIds = null) shouldBe false
    }

    @Test
    fun `an accessory with no heart-rate service withholds nothing`() {
        // The empty set is a statement, not an absence: the accessory described itself
        // and has no such sensor. Collapsing it with null would withhold for ever.
        FrameLogPolicy.mayPrintBody(inputReportFrame(0x10), sensitiveServiceIds = emptySet()) shouldBe true
    }

    @Test
    fun `frames that are not sensor reports are printed`() {
        // The descriptors, the acknowledgements and every other opcode. These are the
        // frames the protocol notes are written from.
        val control = AapProtocol.HEADER + AapFixtures.hex("09 00 0D 01 00 00 00")
        FrameLogPolicy.mayPrintBody(control, sensitiveServiceIds = null) shouldBe true

        val descriptors = AapProtocol.HEADER + AapFixtures.hex("17 00 00 00 10 00 04 00 2A 02 08 10")
        FrameLogPolicy.mayPrintBody(descriptors, sensitiveServiceIds = null) shouldBe true
    }

    @Test
    fun `a report that cannot be attributed to a service is withheld`() {
        // Field 7 is present but its contents do not parse into a service id. Nothing can
        // say this is not a heart rate, so it is treated as one.
        val malformed =
            AapProtocol.HEADER +
                byteArrayOf(0x17, 0x00, 0x00, 0x00, 0x10, 0x00) +
                byteArrayOf(0x04, 0x00) +
                byteArrayOf(0x3A, 0x02, 0x7F, 0x7F)

        FrameLogPolicy.mayPrintBody(malformed, sensitiveServiceIds = setOf(0x13)) shouldBe false
    }
}
