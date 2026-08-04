package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The 126 bytes the accessory published about its own heart-rate report, walked.
 *
 * The offsets asserted here are the ones in `contracts/aap-hid.md`, and the point is that
 * this file **derives** them rather than declaring them: a hard-coded struct produces the
 * same numbers today and cannot tell you when it has stopped being right (R-2).
 */
class HidReportDescriptorTest {
    private val descriptor =
        checkNotNull(HidReportDescriptor.parse(AapFixtures.heartRateReportDescriptor)) {
            "the captured descriptor must parse; if it does not, the walker is wrong"
        }

    @Test
    fun `the captured descriptor declares two reports`() {
        descriptor.reports.map(HidReportLayout::reportId) shouldBe listOf(1, 2)
    }

    @Test
    fun `report one lays its fields out at the offsets the contract records`() {
        val report = checkNotNull(descriptor.report(1))

        fun offsetOf(
            page: Int,
            usage: Int,
        ) = checkNotNull(report.input(page, usage)) { "missing usage %04X:%04X".format(page, usage) }

        val heartRate = offsetOf(HidReportDescriptor.USAGE_PAGE_SENSORS, HidReportDescriptor.USAGE_HEART_RATE)
        heartRate.byteOffset shouldBe 1
        heartRate.byteSize shouldBe 1

        val confidence =
            offsetOf(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_CONFIDENCE)
        confidence.byteOffset shouldBe 2
        confidence.byteSize shouldBe 1

        val sequence = offsetOf(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_SEQUENCE)
        sequence.byteOffset shouldBe 3
        sequence.byteSize shouldBe 2

        val timestamp = offsetOf(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_TIMESTAMP)
        timestamp.byteOffset shouldBe 6
        timestamp.byteSize shouldBe 8
    }

    @Test
    fun `report one is the eighteen bytes the capture shows`() {
        checkNotNull(descriptor.report(1)).inputByteSize shouldBe 18
    }

    @Test
    fun `report two is the 600-byte vendor report, declared and not decoded`() {
        val report = checkNotNull(descriptor.report(2))

        report.inputByteSize shouldBe 601
        report.inputFields.single().count shouldBe 600
        // MaxReportSize in the property blob is 601 — report 2 plus its id byte — which
        // is the accessory confirming this is real traffic rather than a descriptor
        // artefact.
        report.inputFields.single().usagePage shouldBe 0xFF0A
    }

    @Test
    fun `the status enum takes its usage from the usage-minimum item`() {
        // 1A 04 01 2A 05 01 81 00 — logical 1..2, usages 0x0104..0x0105. Earlier notes
        // called this the confidence field; it is the status field, and the confidence
        // byte is the vendor usage before it. Getting these two the wrong way round
        // would gate on a constant.
        val status = checkNotNull(descriptor.report(1)).inputFields[3]

        status.byteOffset shouldBe 5
        status.usage shouldBe 0x0104
    }

    @Test
    fun `the feature report carrying the interval is found by its shape`() {
        descriptor.intervalFeatureReportId shouldBe 1
        checkNotNull(descriptor.report(1)).featureFields.single().bitSize shouldBe 32
    }

    @Test
    fun `the captured descriptor is a usable heart-rate layout`() {
        descriptor.heartRateReport().shouldNotBeNull().reportId shouldBe 1
    }

    @Test
    fun `a descriptor with no confidence field yields no usable layout`() {
        // R-2's rule, and the reason it is not merely defensive: without a confidence
        // field there is nothing for FR-006 to gate on, and a feature that shows ungated
        // numbers is the exact failure this specification exists to prevent. Better to
        // report the model unsupported than to show a heart rate nobody can vouch for.
        val withoutConfidence = removeConfidenceField(AapFixtures.heartRateReportDescriptor)

        val parsed = checkNotNull(HidReportDescriptor.parse(withoutConfidence))
        parsed
            .report(1)
            ?.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_CONFIDENCE)
            .shouldBeNull()
        parsed.heartRateReport().shouldBeNull()
    }

    @Test
    fun `removing the confidence field shifts every later offset, which is the point`() {
        val parsed =
            checkNotNull(HidReportDescriptor.parse(removeConfidenceField(AapFixtures.heartRateReportDescriptor)))
        val report = checkNotNull(parsed.report(1))

        // The sequence field moves from offset 3 to offset 2 — derived offsets follow the
        // descriptor, which is the whole reason they are derived.
        checkNotNull(
            report.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_SEQUENCE),
        ).byteOffset shouldBe 2
    }

    @Test
    fun `nonsense is rejected rather than half-parsed`() {
        HidReportDescriptor.parse(ByteArray(0)).shouldBeNull()
        // A trailing item that claims more data than the descriptor holds.
        HidReportDescriptor.parse(byteArrayOf(0x05, 0x20, 0x27, 0x01)).shouldBeNull()
    }

    @Test
    fun `a descriptor is located by where its collections balance, not by a length`() {
        val padded = AapFixtures.heartRateReportDescriptor + ByteArray(32) { 0x5A }

        HidReportDescriptor.extentOf(padded, 0) shouldBe AapFixtures.heartRateReportDescriptor.size
    }

    /** Drops the `06 15 FF 0A 20 01 95 01 75 08 81 02` group — the confidence input. */
    private fun removeConfidenceField(descriptor: ByteArray): ByteArray {
        val group = AapFixtures.hex("06 15 FF 0A 20 01 95 01 75 08 81 02")
        val at =
            (0..descriptor.size - group.size).first { start ->
                group.indices.all { descriptor[start + it] == group[it] }
            }
        return descriptor.copyOfRange(0, at) + descriptor.copyOfRange(at + group.size, descriptor.size)
    }
}
