package io.github.andrewkomkov.greenpods.core.data.diagnostics

import io.kotest.matchers.shouldBe
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

    @Test
    fun `a timestamp and detail are carried through`() {
        val log = DiagnosticsLog(clock = { 1_234L })

        log.record(DiagnosticCategory.UPDATE, "checked", detail = "no newer release")

        val event = log.events.value.single()
        event.atEpochMillis shouldBe 1_234L
        event.category shouldBe DiagnosticCategory.UPDATE
        event.detail shouldBe "no newer release"
    }
}
