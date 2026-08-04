package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading

/**
 * Turns the accessory's monotonic report counter into a wall-clock time.
 *
 * The report's 8-byte "timestamp" is **not a date**. The captured values are around
 * 56 339 seconds — about fifteen hours — where a nanosecond epoch would be near 1.78e18.
 * It is a counter running from some accessory-local origin, plausibly since the buds
 * powered on (R-4a).
 *
 * What makes it worth keeping is that consecutive values are exactly 1.000 s apart, which
 * is more precise than the moment a packet happened to be read off a socket. What makes
 * it dangerous is that the absolute value is meaningless: written into a health store as
 * an instant it would put every reading roughly fifteen hours in the user's past, and
 * nothing downstream would notice.
 *
 * So the counter supplies the *spacing* and the host clock supplies the *origin*, joined
 * once per session. The anchor is never persisted: nothing guarantees the counter survives
 * a disconnect, so a session that restarts re-anchors.
 */
class HeartRateTimestampAnchor {
    private var anchorMillis: Long? = null

    /** True once a session's first report has fixed the origin. */
    val isAnchored: Boolean get() = anchorMillis != null

    /**
     * Resolves one report's counter to epoch milliseconds, establishing the anchor from
     * [hostNowMillis] if this is the session's first report.
     */
    fun resolve(
        reportNanos: Long,
        hostNowMillis: Long,
    ): Long {
        val counterMillis = reportNanos / NANOS_PER_MILLI
        val anchor = anchorMillis ?: (hostNowMillis - counterMillis).also { anchorMillis = it }
        return anchor + counterMillis
    }

    /** Forgets the origin. Called whenever a session ends, and never skipped. */
    fun reset() {
        anchorMillis = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The result of meeting one HID input report on the heart-rate service.
 *
 * Traffic is never simply discarded: a report that cannot become a reading becomes an
 * [Unhandled] carrying its *shape* — report id, length and why — which is what lets
 * Principle IV and FR-023 both hold. Nothing here ever carries a body.
 */
sealed interface HeartRateDecodeResult {
    data class Decoded(
        val reading: HeartRateReading,
    ) : HeartRateDecodeResult

    data class Unhandled(
        val reportId: Int,
        val length: Int,
        val reason: Reason,
    ) : HeartRateDecodeResult {
        enum class Reason {
            /** A report id the heart-rate service declares but this project does not decode. */
            NOT_THE_HEART_RATE_REPORT,

            /** The length disagrees with the layout the accessory itself declared. */
            LENGTH_MISMATCH,

            /** A BPM outside the survivable range. Discarded, counted, never shown (FR-009). */
            IMPLAUSIBLE,

            /** The descriptor declared no confidence field, so nothing can be gated (R-2). */
            NO_CONFIDENCE_FIELD,
        }
    }
}

/**
 * Decodes heart-rate input reports against the layout the accessory published.
 *
 * Every field is resolved **by usage**, never by offset. Asking for usage `0x0400B8`
 * rather than for byte 1 is what makes a model that lays its report out differently work
 * without a code change, and it is the difference between reading a descriptor and
 * decorating a hard-coded struct with one (AD-2).
 *
 * Pure: given the same bytes, the same anchor state and the same host clock, the same
 * answer. Pinned against the eight-report series captured on 2026-08-04.
 */
class HeartRateReportDecoder(
    descriptor: HidReportDescriptor,
) {
    private val layout: HidReportLayout? = descriptor.heartRateReport()

    private val heartRateField =
        layout?.input(HidReportDescriptor.USAGE_PAGE_SENSORS, HidReportDescriptor.USAGE_HEART_RATE)
    private val confidenceField =
        layout?.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_CONFIDENCE)
    private val sequenceField =
        layout?.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_SEQUENCE)
    private val timestampField =
        layout?.input(HidReportDescriptor.USAGE_PAGE_APPLE_VENDOR, HidReportDescriptor.USAGE_TIMESTAMP)

    /**
     * False when the accessory declares a heart rate it publishes no confidence for.
     *
     * The feature then reports itself unsupported *with that reason* rather than showing
     * ungated numbers. An ungated number is the precise failure this specification exists
     * to prevent, and a model that cannot be gated is not a model this feature can serve.
     */
    val isUsable: Boolean get() = layout != null && heartRateField != null && confidenceField != null

    /** The report id the heart-rate stream arrives under, or null when unusable. */
    val reportId: Int? get() = layout?.reportId

    /** How many bytes a whole report occupies, report id included. */
    val reportByteSize: Int? get() = layout?.inputByteSize

    fun decode(
        report: ByteArray,
        anchor: HeartRateTimestampAnchor,
        hostNowMillis: Long,
    ): HeartRateDecodeResult {
        val incomingId = report.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        val expected = layout
        if (expected == null || heartRateField == null || confidenceField == null) {
            return HeartRateDecodeResult.Unhandled(
                incomingId,
                report.size,
                HeartRateDecodeResult.Unhandled.Reason.NO_CONFIDENCE_FIELD,
            )
        }

        if (incomingId != expected.reportId) {
            // Report id 2 — 600 bytes, vendor usage 0xFF0A:0x13 — is declared by the same
            // service and is real traffic this project does not decode. It surfaces by
            // shape so it stays findable rather than becoming noise.
            return HeartRateDecodeResult.Unhandled(
                incomingId,
                report.size,
                HeartRateDecodeResult.Unhandled.Reason.NOT_THE_HEART_RATE_REPORT,
            )
        }

        // Exact, not "at least": a report whose length disagrees with the layout the
        // accessory declared is one whose fields are not where the descriptor says, and
        // reading it anyway would produce a number rather than an error.
        if (report.size != expected.inputByteSize) {
            return HeartRateDecodeResult.Unhandled(
                incomingId,
                report.size,
                HeartRateDecodeResult.Unhandled.Reason.LENGTH_MISMATCH,
            )
        }

        val beatsPerMinute = unsigned(report, heartRateField) ?: return lengthMismatch(incomingId, report)
        val confidence = unsigned(report, confidenceField) ?: return lengthMismatch(incomingId, report)
        val sequence = sequenceField?.let { unsigned(report, it) }
        val measuredAt =
            timestampField
                ?.let { field -> unsignedLong(report, field) }
                ?.let { nanos -> anchor.resolve(nanos, hostNowMillis) }
                // A descriptor with no timestamp field is not a failure — the host clock
                // is a worse origin than the accessory's counter, not an unusable one.
                ?: hostNowMillis

        val reading =
            HeartRateReading.orNull(
                beatsPerMinute = beatsPerMinute,
                confidence = confidence,
                source = HeartRateReading.Source.AAP,
                measuredAtEpochMillis = measuredAt,
                sequence = sequence,
            ) ?: return HeartRateDecodeResult.Unhandled(
                incomingId,
                report.size,
                HeartRateDecodeResult.Unhandled.Reason.IMPLAUSIBLE,
            )

        return HeartRateDecodeResult.Decoded(reading)
    }

    private fun lengthMismatch(
        reportId: Int,
        report: ByteArray,
    ) = HeartRateDecodeResult.Unhandled(reportId, report.size, HeartRateDecodeResult.Unhandled.Reason.LENGTH_MISMATCH)

    /** Little-endian, up to four bytes. Null when the field does not fit the report. */
    private fun unsigned(
        report: ByteArray,
        field: HidReportField,
    ): Int? {
        val size = field.byteSize
        if (!field.isByteAligned || size !in 1..4) return null
        if (field.byteOffset + size > report.size) return null
        var value = 0
        for (index in 0 until size) {
            value = value or ((report[field.byteOffset + index].toInt() and 0xFF) shl (8 * index))
        }
        return value
    }

    private fun unsignedLong(
        report: ByteArray,
        field: HidReportField,
    ): Long? {
        val size = field.byteSize
        if (!field.isByteAligned || size !in 1..8) return null
        if (field.byteOffset + size > report.size) return null
        var value = 0L
        for (index in 0 until size) {
            value = value or ((report[field.byteOffset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}
