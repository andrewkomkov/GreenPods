package io.github.andrewkomkov.greenpods.core.data.health

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Batching, which is where SC-009 is won or lost.
 *
 * "No gaps and no duplicates after an interruption" is not a property of the writer; it
 * is a property of the identity the writer attaches. Every case here is about that
 * identity surviving something — a boundary, a stop, a resume inside the same minute.
 */
class HeartRateBatcherTest {
    /** 2026-08-04T12:00:00Z, exactly on a minute boundary. */
    private val minuteStart = 1_785_672_000_000L

    private var version = 1_000L

    private fun batcher() = HeartRateBatcher(clock = { version++ })

    private fun reading(
        atMillis: Long,
        beatsPerMinute: Int = 72,
    ) = HeartRateReading(
        beatsPerMinute = beatsPerMinute,
        confidence = 200,
        source = HeartRateReading.Source.AAP,
        measuredAtEpochMillis = atMillis,
    )

    @Test
    fun `windows align to the wall clock, not to when sensing started`() {
        val batcher = batcher()

        // Starting 17 seconds into a minute must still land in that minute's window —
        // alignment is what makes the id deterministic across interruptions.
        batcher.add(reading(minuteStart + 17_000)).shouldBeNull()
        val batch = batcher.flush().shouldNotBeNull()

        batch.windowStartEpochSeconds shouldBe minuteStart / 1_000
        batch.clientRecordId shouldBe "greenpods:hr:${minuteStart / 1_000}"
    }

    @Test
    fun `crossing a minute boundary emits the completed window and starts the next`() {
        val batcher = batcher()

        (0 until 60).forEach { second -> batcher.add(reading(minuteStart + second * 1_000L)).shouldBeNull() }
        val completed = batcher.add(reading(minuteStart + 60_000)).shouldNotBeNull()

        completed.samples.size shouldBe 60
        completed.windowStartEpochSeconds shouldBe minuteStart / 1_000
        batcher.pendingCount shouldBe 1
    }

    @Test
    fun `an interruption inside one window produces the same record id, not a second record`() {
        // FR-019 in one test: stop at second 20, resume at second 40, and the health
        // store sees one window updated rather than two records for the same minute.
        val first = batcher()
        (0 until 20).forEach { second -> first.add(reading(minuteStart + second * 1_000L)) }
        val partial = first.flush().shouldNotBeNull()

        val resumed = batcher()
        (40 until 60).forEach { second -> resumed.add(reading(minuteStart + second * 1_000L)) }
        val second = resumed.flush().shouldNotBeNull()

        second.clientRecordId shouldBe partial.clientRecordId
        second.windowStartEpochSeconds shouldBe partial.windowStartEpochSeconds
    }

    @Test
    fun `the version rises on every flush, including two flushes of the same size`() {
        // The failure this prevents: a session that stopped after 30 seconds and a later
        // one covering the other 30 both hold 30 samples. With the sample count as the
        // version, Health Connect keeps whichever landed first and says nothing.
        val batcher = batcher()
        (0 until 30).forEach { second -> batcher.add(reading(minuteStart + second * 1_000L)) }
        val firstFlush = batcher.flush().shouldNotBeNull()

        (30 until 60).forEach { second -> batcher.add(reading(minuteStart + second * 1_000L)) }
        val secondFlush = batcher.flush().shouldNotBeNull()

        firstFlush.samples.size shouldBe secondFlush.samples.size
        (secondFlush.clientRecordVersion > firstFlush.clientRecordVersion) shouldBe true
    }

    @Test
    fun `the version keeps rising even when two flushes share a millisecond`() {
        val frozen = HeartRateBatcher(clock = { 42L })

        frozen.add(reading(minuteStart))
        val first = frozen.flush().shouldNotBeNull()
        frozen.add(reading(minuteStart + 1_000))
        val second = frozen.flush().shouldNotBeNull()

        (second.clientRecordVersion > first.clientRecordVersion) shouldBe true
    }

    @Test
    fun `a partial window is emitted on stop rather than discarded`() {
        val batcher = batcher()
        (0 until 40).forEach { second -> batcher.add(reading(minuteStart + second * 1_000L)) }

        // A session ending at second 40 must not throw away forty seconds waiting for a
        // boundary that will never arrive.
        val batch = batcher.flush().shouldNotBeNull()

        batch.samples.size shouldBe 40
        batcher.pendingCount shouldBe 0
    }

    @Test
    fun `flushing an empty batcher emits nothing`() {
        batcher().flush().shouldBeNull()
    }

    @Test
    fun `a record spans its first sample to a second past its last`() {
        val batcher = batcher()
        batcher.add(reading(minuteStart + 5_000))
        batcher.add(reading(minuteStart + 25_000))

        val batch = batcher.flush().shouldNotBeNull()

        batch.startEpochMillis shouldBe minuteStart + 5_000
        // A record covering one instant has no duration, and health apps render those
        // inconsistently.
        batch.endEpochMillis shouldBe minuteStart + 26_000
    }

    @Test
    fun `samples keep their own timestamps, not the write time`() {
        val batcher = batcher()
        batcher.add(reading(minuteStart + 1_000, beatsPerMinute = 68))
        batcher.add(reading(minuteStart + 2_000, beatsPerMinute = 71))

        val batch = batcher.flush().shouldNotBeNull()

        batch.samples.map(HeartRateReading::measuredAtEpochMillis) shouldBe
            listOf(minuteStart + 1_000, minuteStart + 2_000)
        batch.samples.map(HeartRateReading::beatsPerMinute) shouldBe listOf(68, 71)
    }

    @Test
    fun `resetting drops the window without emitting it`() {
        val batcher = batcher()
        batcher.add(reading(minuteStart))

        batcher.reset()

        batcher.pendingCount shouldBe 0
        batcher.flush().shouldBeNull()
    }
}
