package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * Decides whether a frame's bytes may be printed to the diagnostic log.
 *
 * Pure, and separate from the transport, because this is the one rule in the frame log
 * that has a specification behind it rather than a preference: FR-023 says no heart rate
 * reaches any diagnostic path, and the frame log is the most diagnostic path there is —
 * people paste it into bug reports.
 *
 * The rule is narrower than "sensor reports are secret". A head-tracking report carries an
 * orientation, which is not a health measurement, and it is the only ground truth for
 * where an orientation sits inside a report. Withholding it bought no privacy and cost the
 * ability to derive the layout at all — which is how those offsets came to be guessed.
 */
object FrameLogPolicy {
    /**
     * Whether the body of [packet] may be printed.
     *
     * @param sensitiveServiceIds services whose measurements may not be printed, or null
     *   when the accessory has not yet said which service is which. Null withholds every
     *   report body: an unidentified sensor report might be the one FR-023 is about, and
     *   the window between a channel opening and the accessory describing itself is
     *   exactly when that is unknowable.
     */
    fun mayPrintBody(
        packet: ByteArray,
        sensitiveServiceIds: Set<Int>?,
    ): Boolean {
        if (packet.size <= AapProtocol.HID_BODY_OFFSET) return true
        if (!packet.copyOfRange(0, 4).contentEquals(AapProtocol.HEADER)) return true
        val opcode = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        if (opcode != Opcode.HEAD_TRACKING.value) return true

        val body = packet.copyOfRange(AapProtocol.HID_BODY_OFFSET, packet.size)
        if (!HidDescriptorParser.hasInputReport(body)) return true

        val known = sensitiveServiceIds ?: return false
        // A report that will not parse cannot be attributed to a service, and an
        // unattributable measurement is treated as the sensitive one.
        val serviceId = HidDescriptorParser.inputReport(body)?.first ?: return false
        return serviceId !in known
    }
}
