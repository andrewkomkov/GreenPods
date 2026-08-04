package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Reassembly, which is the defect heart rate walks into before it decodes anything.
 *
 * A socket read is not a message. The failure this prevents is silent: a frame split
 * across two reads still parses as *something*, and the something is wrong. Every case
 * here is therefore a case where the old code produced no error and no correct answer.
 */
class AapFramingTest {
    private fun frame(bodyBytes: Int): ByteArray {
        val body = ByteArray(bodyBytes) { index -> (index and 0xFF).toByte() }
        return byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00, 0x10, 0x00) +
            byteArrayOf((bodyBytes and 0xFF).toByte(), ((bodyBytes shr 8) and 0xFF).toByte()) +
            body
    }

    @Test
    fun `a frame split across two reads is held until it is whole`() {
        val whole = frame(bodyBytes = 200)
        val reassembler = AapFrameReassembler()

        val firstHalf = reassembler.offer(whole.copyOfRange(0, 90))
        firstHalf.shouldBeEmpty()
        reassembler.bufferedBytes shouldBe 90

        val rest = reassembler.offer(whole.copyOfRange(90, whole.size))
        rest.size shouldBe 1
        rest.single() shouldBe whole
        reassembler.bufferedBytes shouldBe 0
    }

    @Test
    fun `a frame split at the length field itself still reassembles`() {
        // The nastiest split: fewer than the 12 bytes needed even to know how long the
        // frame is. A reassembler that read the length eagerly would index out of bounds.
        val whole = frame(bodyBytes = 40)
        val reassembler = AapFrameReassembler()

        reassembler.offer(whole.copyOfRange(0, 11)).shouldBeEmpty()
        reassembler.offer(whole.copyOfRange(11, whole.size)).single() shouldBe whole
    }

    @Test
    fun `two frames arriving in one read yield two frames`() {
        val first = frame(bodyBytes = 16)
        val second = frame(bodyBytes = 8)

        val frames = AapFrameReassembler().offer(first + second)

        frames.size shouldBe 2
        frames[0] shouldBe first
        frames[1] shouldBe second
    }

    @Test
    fun `a trailing partial frame waits while the complete one ahead of it is emitted`() {
        val complete = frame(bodyBytes = 16)
        val partial = frame(bodyBytes = 64).copyOfRange(0, 20)
        val reassembler = AapFrameReassembler()

        val frames = reassembler.offer(complete + partial)

        frames.single() shouldBe complete
        reassembler.bufferedBytes shouldBe partial.size
    }

    @Test
    fun `a declared length beyond anything the accessory could send is surfaced, not buffered`() {
        // 0xFFFF bytes is expressible in the length field and is not a frame this
        // protocol produces. Waiting for the rest would wedge the reader forever, so the
        // bytes are handed on and become AapEvent.Unknown.
        val nonsense =
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00, 0x10, 0x00) +
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()) +
                ByteArray(4) { 0x2A }
        val reassembler = AapFrameReassembler()

        val frames = reassembler.offer(nonsense)

        frames.single() shouldBe nonsense
        reassembler.bufferedBytes shouldBe 0
    }

    @Test
    fun `opcodes without a declared length are emitted as they arrive`() {
        // Battery, control and the rest carry no length field. Splitting them on a
        // guessed boundary would be a new bug, so they behave exactly as before.
        val battery = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01, 0x02, 0x01, 0x64, 0x02, 0x01)

        AapFrameReassembler().offer(battery).single() shouldBe battery
    }

    @Test
    fun `traffic that is not AAP framing at all is passed on rather than dropped`() {
        val junk = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x01, 0x02)

        AapFrameReassembler().offer(junk).single() shouldBe junk
    }

    @Test
    fun `fewer bytes than an opcode is not yet anything`() {
        val reassembler = AapFrameReassembler()

        reassembler.offer(byteArrayOf(0x04, 0x00, 0x04)).shouldBeEmpty()
        reassembler.bufferedBytes shouldBe 3
    }

    @Test
    fun `reset drops a half-received frame so a new channel starts clean`() {
        val reassembler = AapFrameReassembler()
        reassembler.offer(frame(bodyBytes = 100).copyOfRange(0, 30))

        reassembler.reset()

        reassembler.bufferedBytes shouldBe 0
    }

    @Test
    fun `only the bytes actually read are taken from the buffer`() {
        // The reader passes its whole scratch buffer with a length, and the tail of that
        // buffer is stale bytes from the previous read.
        val whole = frame(bodyBytes = 8)
        val scratch = whole + ByteArray(1000) { 0x7F }

        AapFrameReassembler().offer(scratch, length = whole.size).single() shouldBe whole
    }
}
