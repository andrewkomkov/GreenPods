package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * The captured series, decoded.
 *
 * This is the fixture the whole feature is judged against: eight consecutive reports in
 * which the first four are the optical sensor settling and are **wrong** — 169 BPM from
 * someone sitting still — while confidence reads 20 for exactly those four. A decoder
 * that produces the right numbers here and a gate that keeps the first four off the
 * screen are two different jobs, and this file only does the first.
 */
class HeartRateReportDecoderTest {
    private val descriptor =
        checkNotNull(HidReportDescriptor.parse(AapFixtures.heartRateReportDescriptor))
    private val decoder = HeartRateReportDecoder(descriptor)

    private val hostNow = 1_770_000_000_000L

    @Test
    fun `the captured series decodes to the documented bpm and confidence pairs`() {
        val anchor = HeartRateTimestampAnchor()

        val decoded =
            AapFixtures.heartRateSeries.map { entry ->
                decoder
                    .decode(entry.report, anchor, hostNow)
                    .shouldBeInstanceOf<HeartRateDecodeResult.Decoded>()
                    .reading
            }

        decoded.map(HeartRateReading::beatsPerMinute) shouldContainExactly
            listOf(169, 147, 128, 96, 94, 94, 91, 81)
        decoded.map(HeartRateReading::confidence) shouldContainExactly
            listOf(20, 20, 20, 20, 156, 205, 233, 237)
        decoded.map(HeartRateReading::sequence) shouldContainExactly listOf(0, 1, 2, 3, 4, 7, 12, 23)
        decoded.forEach { reading -> reading.source shouldBe HeartRateReading.Source.AAP }
    }

    @Test
    fun `an anchored session maps the counter to wall-clock times one second apart`() {
        val anchor = HeartRateTimestampAnchor()

        val times =
            AapFixtures.heartRateSeries.map { entry ->
                decoder
                    .decode(entry.report, anchor, hostNow)
                    .shouldBeInstanceOf<HeartRateDecodeResult.Decoded>()
                    .reading.measuredAtEpochMillis
            }

        // The first report defines the origin, so it lands on the host clock exactly.
        times.first() shouldBe hostNow
        // The accessory's own spacing is kept: 1.000 s between consecutive reports, and
        // the later gaps follow the sequence counter rather than socket jitter.
        times.zipWithNext { a, b -> b - a } shouldContainExactly
            listOf(1_000L, 1_000L, 1_000L, 1_000L, 3_000L, 5_000L, 11_000L)
    }

    @Test
    fun `the raw counter never appears as an instant`() {
        // 56 339 327 885 000 ns is about 15 hours, not a date. Written straight into a
        // health store it would put every reading fifteen hours in the user's past, and
        // nothing downstream would notice (R-4a). This is the test that notices.
        val first = AapFixtures.heartRateSeries.first()
        val anchor = HeartRateTimestampAnchor()

        val reading =
            decoder
                .decode(first.report, anchor, hostNow)
                .shouldBeInstanceOf<HeartRateDecodeResult.Decoded>()
                .reading

        (reading.measuredAtEpochMillis == first.timestampNanos / 1_000_000) shouldBe false
        (reading.measuredAtEpochMillis > hostNow - 1_000) shouldBe true
    }

    @Test
    fun `a session that restarts re-anchors instead of carrying the old origin`() {
        val first = AapFixtures.heartRateSeries.first()
        val anchor = HeartRateTimestampAnchor()
        decoder.decode(first.report, anchor, hostNow)

        anchor.reset()
        val later = hostNow + 3_600_000L
        val reading =
            decoder
                .decode(first.report, anchor, later)
                .shouldBeInstanceOf<HeartRateDecodeResult.Decoded>()
                .reading

        reading.measuredAtEpochMillis shouldBe later
    }

    @Test
    fun `report id two is unhandled by shape, never by content`() {
        val reportTwo = byteArrayOf(0x02) + ByteArray(600) { 0x11 }

        val result =
            decoder
                .decode(reportTwo, HeartRateTimestampAnchor(), hostNow)
                .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()

        result.reportId shouldBe 2
        result.length shouldBe 601
        result.reason shouldBe HeartRateDecodeResult.Unhandled.Reason.NOT_THE_HEART_RATE_REPORT
    }

    @Test
    fun `a report shorter than the descriptor declares is rejected`() {
        val truncated =
            AapFixtures.heartRateSeries
                .first()
                .report
                .copyOfRange(0, 12)

        decoder
            .decode(truncated, HeartRateTimestampAnchor(), hostNow)
            .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()
            .reason shouldBe HeartRateDecodeResult.Unhandled.Reason.LENGTH_MISMATCH
    }

    @Test
    fun `a report longer than the descriptor declares is rejected too`() {
        // Not "at least 18 bytes": a longer report means the fields are not where the
        // descriptor says they are, and reading it anyway yields a number, not an error.
        val padded = AapFixtures.heartRateSeries.first().report + ByteArray(4)

        decoder
            .decode(padded, HeartRateTimestampAnchor(), hostNow)
            .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()
            .reason shouldBe HeartRateDecodeResult.Unhandled.Reason.LENGTH_MISMATCH
    }

    @Test
    fun `an empty report is unhandled rather than a crash`() {
        decoder
            .decode(ByteArray(0), HeartRateTimestampAnchor(), hostNow)
            .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()
    }

    @Test
    fun `an implausible bpm is discarded and surfaced, not shown`() {
        listOf(0, 24, 251, 255).forEach { bpm ->
            val report =
                AapFixtures.heartRateSeries
                    .first()
                    .report
                    .copyOf()
            report[1] = bpm.toByte()

            decoder
                .decode(report, HeartRateTimestampAnchor(), hostNow)
                .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()
                .reason shouldBe HeartRateDecodeResult.Unhandled.Reason.IMPLAUSIBLE
        }
    }

    @Test
    fun `the plausible boundaries themselves decode`() {
        listOf(25, 250).forEach { bpm ->
            val report =
                AapFixtures.heartRateSeries
                    .first()
                    .report
                    .copyOf()
            report[1] = bpm.toByte()

            decoder
                .decode(report, HeartRateTimestampAnchor(), hostNow)
                .shouldBeInstanceOf<HeartRateDecodeResult.Decoded>()
                .reading.beatsPerMinute shouldBe bpm
        }
    }

    @Test
    fun `a descriptor with no confidence field decodes nothing at all`() {
        val group = AapFixtures.hex("06 15 FF 0A 20 01 95 01 75 08 81 02")
        val raw = AapFixtures.heartRateReportDescriptor
        val at = (0..raw.size - group.size).first { start -> group.indices.all { raw[start + it] == group[it] } }
        val stripped = raw.copyOfRange(0, at) + raw.copyOfRange(at + group.size, raw.size)

        val blind = HeartRateReportDecoder(checkNotNull(HidReportDescriptor.parse(stripped)))

        blind.isUsable shouldBe false
        blind
            .decode(AapFixtures.heartRateSeries.first().report, HeartRateTimestampAnchor(), hostNow)
            .shouldBeInstanceOf<HeartRateDecodeResult.Unhandled>()
            .reason shouldBe HeartRateDecodeResult.Unhandled.Reason.NO_CONFIDENCE_FIELD
    }

    @Test
    fun `the decoder reports the layout it is working from`() {
        decoder.isUsable shouldBe true
        decoder.reportId shouldBe 1
        decoder.reportByteSize shouldBe 18
    }
}
