package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The parser against frames taken straight off the socket.
 *
 * `HidDescriptorParserTest` runs on a frame transcribed into the contract by hand.
 * This one runs on what the accessory actually sent on 2026-08-04, captured by holding
 * the channel open across an ACL reconnection — and the two disagree in two places. The
 * device is right, so these expectations are written from the capture rather than from
 * what the contract says the capture ought to have been.
 *
 * The disagreements are worth naming, because one of them looked alarming and is not:
 *
 * - The accessory splits its announcement across **two** frames, not one — service
 *   `0x10` alone, then `0x11`, `0x12` and `0x13` together. Anything that assumed a
 *   single descriptor frame carried every service would find three of them missing.
 * - The heart-rate service's name key is **`HeartRateService`**, not
 *   `AccessoryService` as it is for the other three. Its display name therefore reads
 *   as absent, which is cosmetic — detection is by the name key or the entitlement, and
 *   both are present, so `isHeartRate` is unaffected. That distinction is what this file
 *   exists to pin: a discovery failure would be a shipping bug, a missing label is not.
 */
class HidDescriptorLiveCaptureTest {
    private fun body(frame: ByteArray) = frame.copyOfRange(12, frame.size)

    private val descriptorFrames = AapFixtures.liveDescriptorFrames.take(2)

    private val services = descriptorFrames.flatMap { HidDescriptorParser.services(body(it)) }

    @Test
    fun `the accessory splits its announcement across two frames`() {
        // 488 and 996 bytes. The second sat 28 bytes under the old 1024-byte read
        // buffer, which is why reassembly against the declared length is not optional.
        AapFixtures.liveDescriptorFrames.map { it.size } shouldContainExactly listOf(488, 996, 20, 28)

        HidDescriptorParser.services(body(descriptorFrames[0])).map(HidService::id) shouldContainExactly listOf(0x10)
        HidDescriptorParser.services(body(descriptorFrames[1])).map(HidService::id) shouldContainExactly
            listOf(0x11, 0x12, 0x13)
    }

    @Test
    fun `the heart-rate service is still found, by whichever identifier is present`() {
        // The one assertion that decides whether the feature works at all. Its name key
        // is not the one the other services use, so this must not depend on the label.
        val heartRate = services.single(HidService::isHeartRate)

        heartRate.id shouldBe 0x13
    }

    @Test
    fun `every announced service is accounted for`() {
        services.map(HidService::id) shouldContainExactly listOf(0x10, 0x11, 0x12, 0x13)
    }

    /**
     * Rewrites every service id in a descriptor body, and nothing else.
     *
     * Walks `2A <varint len> 08 <id>` properly rather than scanning for the byte pair:
     * the field-5 length here is a two-byte varint, so a fixed-offset patch silently
     * renumbers nothing and the test passes while proving the opposite of what it claims.
     */
    private fun renumber(
        body: ByteArray,
        by: Int,
    ): ByteArray {
        val out = body.copyOf()
        var i = 0
        while (i < out.size) {
            if (out[i] != FIELD_5_TAG) {
                i++
                continue
            }
            var j = i + 1
            while (j < out.size && (out[j].toInt() and 0x80) != 0) j++
            j++
            if (j + 1 < out.size && out[j] == FIELD_1_TAG) out[j + 1] = (out[j + 1] + by).toByte()
            i = j
        }
        return out
    }

    @Test
    fun `renumbering the live frames still finds the sensor`() {
        // FR-002 against real bytes rather than transcribed ones. A parser holding a
        // hard-coded 0x13 passes everything above and fails here.
        val renumbered = descriptorFrames.map { renumber(body(it), by = 0x20) }

        renumbered.flatMap { HidDescriptorParser.services(it) }.map(HidService::id) shouldContainExactly
            listOf(0x30, 0x31, 0x32, 0x33)

        val found = renumbered.flatMap { HidDescriptorParser.services(it) }.filter(HidService::isHeartRate)
        found.size shouldBe 1
        found.single().id shouldBe 0x33
    }

    private companion object {
        /** Field 5, length-delimited — one service descriptor. */
        const val FIELD_5_TAG = 0x2A.toByte()

        /** Field 1, varint — the service id, inside a descriptor entry. */
        const val FIELD_1_TAG = 0x08.toByte()
    }
}
