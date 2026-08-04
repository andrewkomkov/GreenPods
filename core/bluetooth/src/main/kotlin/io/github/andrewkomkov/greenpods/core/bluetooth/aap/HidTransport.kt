package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * Framing for HID-over-AAP, opcode `0x17`.
 *
 * `0x17` is not "head tracking". It is a transport carrying several sensor services —
 * head tracking is one, the heart-rate sensor is another — and a frame's meaning is in
 * which protobuf field it holds, never in how long it happens to be.
 *
 * Starting a service means writing its **report interval** as a feature report; interval
 * zero stops it. That is the whole on/off mechanism, and it is the same one head tracking
 * uses. Building the bytes is kept separate from writing them so they can be pinned in a
 * unit test, which is the only way to have confidence in a format nobody published.
 *
 * **A start that is accepted is not a start.** The sensor is running when input reports
 * arrive, and not before (Principle I).
 */
object HidTransport {
    /** Top-level protobuf field numbers in a `0x17` body. */
    const val FIELD_SEQUENCE = 1
    const val FIELD_DESCRIPTORS = 5
    const val FIELD_INPUT_REPORT = 7
    const val FIELD_REQUEST = 8
    const val FIELD_READY = 12

    /** Inside field 8: set a feature report on the named service. */
    const val OPERATION_SET_FEATURE_REPORT = 2

    /** Interval zero. Not "no interval" — the off switch. */
    const val INTERVAL_STOPPED_MICROS = 0

    /**
     * The sequence byte from the 2026-08-04 capture.
     *
     * Arbitrary, and echoed back incremented. Kept as the default because a frame that
     * differs from the captured one in exactly nothing is the easiest kind to debug.
     */
    const val DEFAULT_SEQUENCE = 0x78

    /** Wraps a protobuf body in the AAP header, the `0x17` opcode and its declared length. */
    fun frame(body: ByteArray): ByteArray =
        AapProtocol.HEADER +
            byteArrayOf(0x17, 0x00) +
            byteArrayOf(0x00, 0x00, 0x10, 0x00) +
            byteArrayOf((body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte()) +
            body

    /**
     * Asks [serviceId] to report every [intervalMicros] microseconds.
     *
     * [serviceId] and [featureReportId] both come from the accessory's own descriptors.
     * Neither has a default, on purpose: a default here is a hard-coded id wearing a
     * disguise, and FR-002 exists because that is exactly how LibrePods' `0x0E` came to
     * be silently ignored by this model.
     */
    fun setReportInterval(
        serviceId: Int,
        featureReportId: Int,
        intervalMicros: Int,
        sequence: Int = DEFAULT_SEQUENCE,
    ): ByteArray {
        val featureReport =
            byteArrayOf(featureReportId.toByte()) +
                byteArrayOf(
                    (intervalMicros and 0xFF).toByte(),
                    ((intervalMicros shr 8) and 0xFF).toByte(),
                    ((intervalMicros shr 16) and 0xFF).toByte(),
                    ((intervalMicros ushr 24) and 0xFF).toByte(),
                )

        val request =
            Protobuf.varintField(1, serviceId.toLong()) +
                Protobuf.varintField(2, OPERATION_SET_FEATURE_REPORT.toLong()) +
                Protobuf.bytesField(3, featureReport)

        return frame(
            Protobuf.varintField(FIELD_SEQUENCE, sequence.toLong()) +
                Protobuf.bytesField(FIELD_REQUEST, request),
        )
    }

    /** Stops the stream. Interval zero, which the accessory treats as "stop", not "as fast as possible". */
    fun stopReportStream(
        serviceId: Int,
        featureReportId: Int,
        sequence: Int = DEFAULT_SEQUENCE,
    ): ByteArray = setReportInterval(serviceId, featureReportId, INTERVAL_STOPPED_MICROS, sequence)

    /** Milliseconds to the microseconds the wire wants. */
    fun intervalMicros(intervalMillis: Int): Int = intervalMillis * MICROS_PER_MILLI

    private const val MICROS_PER_MILLI = 1_000
}
