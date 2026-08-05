package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * Reads the byte sequences captured from hardware on 2026-08-04.
 *
 * The fixtures live as text rather than as Kotlin literals so their provenance can be
 * written next to them and read without opening a test. Every file's header says exactly
 * which bytes came off an accessory and which were reconstructed, because a fixture that
 * overstates where it came from is worse than no fixture at all (Principle III).
 */
internal object AapFixtures {
    /** The `0x17` frame in which the accessory describes its four HID services. */
    val descriptorFrame: ByteArray by lazy { section("aap/hid-descriptors.txt", "descriptors") }

    /** The field-12 frame in which it says those services are up. */
    val readyFrame: ByteArray by lazy { section("aap/hid-descriptors.txt", "ready") }

    /**
     * The 2026-08-04 **live** capture: every `0x17` frame, in arrival order.
     *
     * Distinct from [descriptorFrame], which was transcribed into the contract by hand.
     * This one came off the socket verbatim, and the two disagree — which is the whole
     * reason it is here. When a hand-copied fixture and a device disagree, the device is
     * right.
     */
    val liveDescriptorFrames: List<ByteArray> by lazy {
        val frames = mutableListOf<StringBuilder>()
        text("aap/hid-descriptors-live.txt").lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("# frame") -> frames += StringBuilder()
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> frames.lastOrNull()?.append(' ')?.append(line)
            }
        }
        frames.map { hex(it.toString()) }
    }

    /** The heart-rate service's own 126-byte HID report descriptor, verbatim. */
    val heartRateReportDescriptor: ByteArray by lazy { hexBody("aap/hr-report-descriptor.txt") }

    /** One row of the captured series: the decoded columns and the bytes they came from. */
    data class SeriesEntry(
        val beatsPerMinute: Int,
        val confidence: Int,
        val sequence: Int,
        val timestampNanos: Long,
        val report: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** The eight consecutive reports, settling readings first. */
    val heartRateSeries: List<SeriesEntry> by lazy {
        text("aap/hr-report-series.txt")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && '|' in it }
            .map { line ->
                val (columns, bytes) = line.split('|', limit = 2)
                val parts = columns.trim().split(Regex("\\s+"))
                SeriesEntry(
                    beatsPerMinute = parts[0].toInt(),
                    confidence = parts[1].toInt(),
                    sequence = parts[2].toInt(),
                    timestampNanos = parts[3].toLong(),
                    report = hex(bytes),
                )
            }.toList()
    }

    /** The descriptor body, past the AAP header, opcode and length prefix. */
    fun descriptorBody(frame: ByteArray = descriptorFrame): ByteArray = frame.copyOfRange(12, frame.size)

    /**
     * Wraps a HID input report in the `0x17` framing the accessory sends it in:
     * field 1 sequence, then field 7 carrying the service id and the report bytes.
     */
    fun inputReportFrame(
        serviceId: Int,
        report: ByteArray,
        sequence: Int = 42,
    ): ByteArray {
        val entry = Protobuf.varintField(1, serviceId.toLong()) + Protobuf.bytesField(3, report)
        val body = Protobuf.varintField(1, sequence.toLong()) + Protobuf.bytesField(7, entry)
        return HidTransport.frame(body)
    }

    /**
     * Four consecutive head-tracking frames spanning the sequence-counter varint boundary.
     *
     * One frame per line, in capture order: sequence 126, 127, 128, 129. The body grows a
     * byte at 128 and the input report moves with it, which is the whole point of keeping
     * them.
     */
    val headTrackingVarintBoundary: List<ByteArray> by lazy {
        text("aap/head-tracking-varint-boundary.txt")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::hex)
            .toList()
    }

    fun hex(text: String): ByteArray =
        text
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** Every hex byte in a fixture, with the provenance header stripped out. */
    private fun hexBody(path: String): ByteArray =
        hex(
            text(path)
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString(" "),
        )

    private fun text(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture $path — it is a capture, not something to regenerate"
        }.bufferedReader().use { it.readText() }

    private fun section(
        path: String,
        name: String,
    ): ByteArray {
        var inSection = false
        val body = StringBuilder()
        text(path).lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#") || line.isEmpty() -> Unit
                line.startsWith("[") -> inSection = line == "[$name]"
                inSection -> body.append(line).append(' ')
            }
        }
        check(body.isNotEmpty()) { "Fixture $path has no [$name] section" }
        return hex(body.toString())
    }
}
