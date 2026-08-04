package io.github.andrewkomkov.greenpods.core.data.diagnostics

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun `events are newest first, which is the order anyone reads them in`() {
        val log = DiagnosticsLog(clock = { 0L })

        log.record(DiagnosticCategory.SCAN, "first")
        log.record(DiagnosticCategory.SCAN, "second")

        log.events.value.map { it.message } shouldBe listOf("second", "first")
    }

    @Test
    fun `the log is bounded, so a chatty accessory cannot exhaust memory`() {
        val log = DiagnosticsLog(capacity = 3, clock = { 0L })

        repeat(10) { log.record(DiagnosticCategory.UNKNOWN_TRAFFIC, "packet $it") }

        log.events.value.map { it.message } shouldBe listOf("packet 9", "packet 8", "packet 7")
    }

    @Test
    fun `clearing empties the log`() {
        val log = DiagnosticsLog(clock = { 0L })
        log.record(DiagnosticCategory.TRANSPORT, "probe")

        log.clear()

        log.events.value shouldBe emptyList()
    }

    @Test
    fun `bytes are rendered the way every protocol note in this project writes them`() {
        DiagnosticsLog.hex(byteArrayOf(0x04, 0x00, 0x09, 0xFF.toByte())) shouldBe "04 00 09 FF"
        DiagnosticsLog.hex(ByteArray(0)) shouldBe ""
    }

    /**
     * FR-023, at the only place it can actually be broken.
     *
     * The log is the one component in this feature whose whole purpose is to write down
     * traffic nobody understood, so it is where a heart rate would leak if it leaked at
     * all. The assertion is deliberately blunt — every rendered character of every event
     * is searched for the number — because a test that only checked the fields it
     * expected to be populated would pass while the value sat in a hex detail string.
     */
    @Test
    fun `a heart-rate report body never reaches the log, in any field`() =
        runTest {
            val log = DiagnosticsLog(clock = { 0L })
            val repository =
                PodRepository(
                    source = PodSightingSource { _: ScanMode -> emptyFlow() },
                    gate = TransportGate(log, AapProbe { ProbeOutcome.NoPairedDevice }),
                    diagnostics = log,
                    scope = backgroundScope,
                    clock = { 0L },
                    ageTicker = flow { emit(Unit) },
                )
            val reading =
                HeartRateReading(
                    beatsPerMinute = 137,
                    confidence = 211,
                    source = HeartRateReading.Source.AAP,
                    measuredAtEpochMillis = 1_785_672_000_000L,
                    sequence = 9,
                )

            repository.onAapEvent(ADDRESS, AapEvent.HeartRateReport(serviceId = 0x0F, reading = reading))
            // The discarded case too: an implausible reading is still a reading, and
            // counting it must not mean writing it down (FR-009).
            repository.onAapEvent(
                ADDRESS,
                AapEvent.UnhandledHidReport(
                    serviceId = 0x0F,
                    reportId = 1,
                    length = 22,
                    reason = "IMPLAUSIBLE",
                ),
            )

            val rendered =
                log.events.value.joinToString(" ") { event ->
                    "${event.category} ${event.message} ${event.detail}"
                }

            listOf("137", "211", "9").forEach { value ->
                (value in rendered.split(" ", ",")) shouldBe false
            }
            // Not silence, though: the unhandled report is still surfaced by shape, which
            // is what keeps Principle IV from being traded away for FR-023 (R-9).
            rendered shouldContain "report id 1"
            rendered shouldContain "22 bytes"
            rendered shouldContain "IMPLAUSIBLE"
        }

    @Test
    fun `a timestamp and detail are carried through`() {
        val log = DiagnosticsLog(clock = { 1_234L })

        log.record(DiagnosticCategory.UPDATE, "checked", detail = "no newer release")

        val event = log.events.value.single()
        event.atEpochMillis shouldBe 1_234L
        event.category shouldBe DiagnosticCategory.UPDATE
        event.detail shouldBe "no newer release"
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
