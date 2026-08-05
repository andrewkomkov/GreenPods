package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import kotlin.math.abs

/**
 * The head pose must not move when only the sequence counter does.
 *
 * The protobuf carries that counter as a varint in field 1, so it takes one byte up to
 * 127 and two from 128 — and everything after it, the input report included, shifts by a
 * byte partway through every stream. Reading absolute packet offsets meant the same
 * physical pose decoded from different bytes either side of that moment, roughly five
 * seconds into every session at 25 Hz.
 *
 * Pinned against four consecutive frames off the accessory, captured 2026-08-05.
 */
class HeadTrackingOffsetTest {
    private val frames = AapFixtures.headTrackingVarintBoundary

    private fun decoderKnowingHeadTracking(): AapDecoder =
        AapDecoder().apply {
            restoreServices(
                listOf(
                    HidService(
                        id = 0x10,
                        name = "devmotion",
                        reportDescriptor = ByteArray(0),
                        isHeartRate = false,
                        isHeadTracking = true,
                    ),
                ),
            )
        }

    @Test
    fun `the capture really does straddle the varint boundary`() {
        // If this stops being true the fixture has been replaced and the rest of this
        // file is testing nothing.
        frames shouldHaveSize 4

        // `08 7F` is one byte of payload; `08 80 01` is two. The frame grows with it.
        frames[1][13] shouldBe 0x7F.toByte()
        frames[2][13] shouldBe 0x80.toByte()
        frames[2].size shouldBe frames[1].size + 1
    }

    @Test
    fun `the pose is continuous across the boundary`() {
        val decoder = decoderKnowingHeadTracking()

        val samples =
            frames.map { frame ->
                decoder
                    .decode(frame)
                    .shouldBeInstanceOf<AapEvent.HeadTracking>()
                    .sample
            }

        // Four frames 40 ms apart. Whatever these three values are, they belong to a head,
        // and a head does not jump a quarter of full scale in 40 ms. Before the fix the
        // step across frames 127 -> 128 was exactly that kind of jump, because the two
        // reads straddled different bytes.
        val limit = 8_000
        samples.zipWithNext().forEach { (before, after) ->
            abs(after.orientation1 - before.orientation1) shouldBeLessThan limit
            abs(after.orientation2 - before.orientation2) shouldBeLessThan limit
            abs(after.orientation3 - before.orientation3) shouldBeLessThan limit
        }
    }

    @Test
    fun `the same report decodes the same whatever the sequence counter costs`() {
        // The decisive one. Take the report out of the short-counter frame, put it in a
        // frame whose counter needs two bytes, and the pose must not move: nothing about
        // a head pose depends on how many frames have been sent.
        val report = reportOf(frames[1])

        val short = AapFixtures.inputReportFrame(serviceId = 0x10, report = report, sequence = 7)
        val long = AapFixtures.inputReportFrame(serviceId = 0x10, report = report, sequence = 4_242)
        long.size shouldBe short.size + 1

        fun pose(frame: ByteArray) =
            decoderKnowingHeadTracking()
                .decode(frame)
                .shouldBeInstanceOf<AapEvent.HeadTracking>()
                .sample

        pose(long) shouldBe pose(short)
    }

    /** The field-7 input report inside a captured frame. */
    private fun reportOf(frame: ByteArray): ByteArray {
        val body = frame.copyOfRange(AapProtocol.HID_BODY_OFFSET, frame.size)
        val (serviceId, report) = checkNotNull(HidDescriptorParser.inputReport(body))
        serviceId shouldBe 0x10
        return report
    }
}
