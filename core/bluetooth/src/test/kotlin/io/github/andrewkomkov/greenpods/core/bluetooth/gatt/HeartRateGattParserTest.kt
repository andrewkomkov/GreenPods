package io.github.andrewkomkov.greenpods.core.bluetooth.gatt

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The Heart Rate Measurement characteristic, decoded off-device.
 *
 * **This is the only evidence the GATT route works.** No Powerbeats Pro 2 is available to
 * this project, so unlike the AAP path there is no capture to compare against and no
 * device to try it on — which makes the parser's contract worth pinning tightly rather
 * than loosely. The byte layouts below come from the Bluetooth SIG Heart Rate Service
 * specification (`0x2A37`), not from a capture, and that distinction is recorded in
 * spec.md's Assumptions rather than blurred.
 */
class HeartRateGattParserTest {
    private val now = 1_785_672_000_000L

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `flags bit 0 clear means a one-byte BPM`() {
        val reading = parseMeasurement(bytes(0x00, 0x48), now)

        reading?.beatsPerMinute shouldBe 72
        reading?.source shouldBe HeartRateReading.Source.GATT
    }

    @Test
    fun `flags bit 0 set means a two-byte little-endian BPM`() {
        // 0x00C8 = 200, which only fits the wide form. Getting the endianness backwards
        // would read 0xC800 and be rejected as implausible rather than be visibly wrong,
        // so the wide form needs a value that distinguishes the two.
        val reading = parseMeasurement(bytes(0x01, 0xC8, 0x00), now)

        reading?.beatsPerMinute shouldBe 200
    }

    @Test
    fun `the host clock is the timestamp, because the profile carries none`() {
        // Unlike the AAP route there is no counter to anchor (AD-10) — and nothing here
        // that could be mistaken for one.
        parseMeasurement(bytes(0x00, 0x48), now)?.measuredAtEpochMillis shouldBe now
    }

    @Test
    fun `this route has no confidence to give, which is not the same as no confidence`() {
        // R-10: the SIG profile publishes a measurement it already considers valid. Null
        // means the field does not exist on this route; a zero would mean the sensor
        // distrusted the reading, and the confidence gate would then reject every one.
        parseMeasurement(bytes(0x00, 0x48), now)?.confidence.shouldBeNull()
    }

    @Test
    fun `a truncated value yields nothing rather than a partial reading`() {
        parseMeasurement(ByteArray(0), now).shouldBeNull()
        // Flags say one-byte BPM, but the BPM byte is absent.
        parseMeasurement(bytes(0x00), now).shouldBeNull()
        // Flags say two-byte BPM, and only the low half arrived.
        parseMeasurement(bytes(0x01, 0xC8), now).shouldBeNull()
    }

    @Test
    fun `an implausible value is rejected here rather than filtered downstream`() {
        // Plausibility is the whole gate on this route — there is no confidence byte to
        // fall back on — so it belongs where the bytes are read.
        parseMeasurement(bytes(0x00, 0x00), now).shouldBeNull()
        parseMeasurement(bytes(0x00, 0x18), now).shouldBeNull()
        parseMeasurement(bytes(0x01, 0xFB, 0x00), now).shouldBeNull()

        // And the edges of the range are kept, not rounded away.
        parseMeasurement(bytes(0x00, 0x19), now)?.beatsPerMinute shouldBe 25
        parseMeasurement(bytes(0x00, 0xFA), now)?.beatsPerMinute shouldBe 250
    }

    @Test
    fun `the other flag bits are ignored rather than misread as data`() {
        // Bits 1-2 are sensor-contact status, bit 3 energy-expended, bit 4 RR-intervals.
        // A real strap sets them, and only bit 0 changes where the BPM is — a parser that
        // treated any other bit as a width selector would read the wrong byte.
        val withContactAndRr = parseMeasurement(bytes(0x16, 0x48, 0x01, 0x02), now)

        withContactAndRr?.beatsPerMinute shouldBe 72
    }
}
