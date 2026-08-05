package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.doubles.plusOrMinus
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
    fun `the declared ranges are read off the capture, not assumed`() {
        val report = checkNotNull(descriptor.report(1))

        // `14 … 26 FF 00` — logical 0..255, so the heart-rate byte is unsigned.
        val heartRate =
            checkNotNull(report.input(HidReportDescriptor.USAGE_PAGE_SENSORS, HidReportDescriptor.USAGE_HEART_RATE))
        heartRate.logicalMinimum shouldBe 0
        heartRate.logicalMaximum shouldBe 255
        heartRate.isSigned shouldBe false

        // `26 FF 7F` — the sequence counter runs to 32767 rather than to 65535.
        val sequence =
            checkNotNull(report.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_SEQUENCE))
        sequence.logicalMaximum shouldBe 32767

        // `15 01 25 02` — the status enum, and the one field here with a non-zero floor.
        val status = report.inputFields[3]
        status.logicalMinimum shouldBe 1
        status.logicalMaximum shouldBe 2
    }

    @Test
    fun `a field that declares no physical range reports its raw value`() {
        // HID's own default, and the reason it matters: this is what stops a missing
        // declaration from silently becoming a scale of zero.
        val heartRate =
            checkNotNull(
                checkNotNull(descriptor.report(1))
                    .input(HidReportDescriptor.USAGE_PAGE_SENSORS, HidReportDescriptor.USAGE_HEART_RATE),
            )

        heartRate.hasPhysicalScale shouldBe false
        heartRate.toPhysical(72) shouldBe 72.0
    }

    @Test
    fun `a signed logical minimum is sign-extended from its own width`() {
        // `16 01 80` is -32767, not 32769. Read unsigned, the field stops looking signed
        // and every negative reading comes out a whole turn away — which is exactly the
        // failure mode an orientation field would have.
        val signedField =
            checkNotNull(
                HidReportDescriptor.parse(
                    AapFixtures.hex(
                        "05 20 09 16 A1 01 85 01 0A 01 03 16 01 80 26 FF 7F " +
                            "75 10 95 01 81 02 C0",
                    ),
                ),
            )
        val field = checkNotNull(signedField.report(1)).inputFields.single()

        field.logicalMinimum shouldBe -32767
        field.logicalMaximum shouldBe 32767
        field.isSigned shouldBe true
    }

    @Test
    fun `a declared physical range converts a raw reading into its unit`() {
        // The same field, now declaring physical -180..180 with exponent 0: the mapping
        // stops being a guess and becomes arithmetic on what the accessory said.
        val parsed =
            checkNotNull(
                HidReportDescriptor.parse(
                    AapFixtures.hex(
                        "05 20 09 16 A1 01 85 01 0A 01 03 16 01 80 26 FF 7F " +
                            "36 4C FF 46 B4 00 55 00 75 10 95 01 81 02 C0",
                    ),
                ),
            )
        val field = checkNotNull(parsed.report(1)).inputFields.single()

        field.physicalMinimum shouldBe -180
        field.physicalMaximum shouldBe 180
        field.unitExponent shouldBe 0
        field.hasPhysicalScale shouldBe true

        field.toPhysical(0) shouldBe 0.0
        field.toPhysical(32767) shouldBe 180.0
        field.toPhysical(-32767) shouldBe (-180.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `a negative unit exponent is a signed nibble, not a byte`() {
        // `55 0F` is 10^-1, not 10^15. Getting this wrong is not a rounding error; it is
        // fourteen orders of magnitude.
        val parsed =
            checkNotNull(
                HidReportDescriptor.parse(
                    AapFixtures.hex(
                        "05 20 09 16 A1 01 85 01 0A 01 03 15 00 26 E8 03 " +
                            "35 00 46 E8 03 55 0F 75 10 95 01 81 02 C0",
                    ),
                ),
            )
        val field = checkNotNull(parsed.report(1)).inputFields.single()

        field.unitExponent shouldBe -1
        // 1000 raw maps onto 1000 physical, then 10^-1 — a tenth of a unit per count.
        field.toPhysical(1000) shouldBe (100.0 plusOrMinus TOLERANCE)
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

    private companion object {
        /** Floating-point slack: the mapping is a ratio, so exact equality is luck. */
        const val TOLERANCE = 1e-6
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
