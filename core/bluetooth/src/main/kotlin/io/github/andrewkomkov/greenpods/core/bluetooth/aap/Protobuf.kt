package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * Just enough protobuf to read what the accessory sends under opcode `0x17`.
 *
 * There is no `.proto` file for this — the field numbers were read off a capture — so a
 * generated parser would be a generated parser for a schema nobody has. What is actually
 * needed is the wire format's self-describing half: walk `<tag varint> <payload>` pairs,
 * hand back the fields by number, and skip anything unrecognised without losing the rest
 * of the message.
 *
 * Pure, and deliberately tolerant: a field this project has never seen must not stop the
 * fields it has from being read.
 */
internal object Protobuf {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    /** One `<field number, wire type, value>` triple, with the value left unread. */
    data class Field(
        val number: Int,
        val wireType: Int,
        /** Set for varint fields. */
        val varint: Long = 0L,
        /** Set for length-delimited fields. */
        val bytes: ByteArray = EMPTY,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Field &&
                number == other.number &&
                wireType == other.wireType &&
                varint == other.varint &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (31 * (31 * number + wireType) + varint.hashCode()) * 31 + bytes.contentHashCode()
    }

    /**
     * Walks a message into its fields, in wire order.
     *
     * Stops at the first byte that cannot be a valid tag rather than throwing: a frame
     * truncated by a transport bug should yield the fields that did arrive, so the
     * failure is visible as missing data rather than as an exception in a flow.
     */
    fun fields(
        message: ByteArray,
        from: Int = 0,
        until: Int = message.size,
    ): List<Field> {
        val out = mutableListOf<Field>()
        var offset = from
        while (offset < until) {
            val (tag, afterTag) = varint(message, offset, until) ?: break
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            if (number == 0) break

            offset =
                when (wireType) {
                    WIRE_VARINT -> {
                        val (value, next) = varint(message, afterTag, until) ?: return out
                        out += Field(number, wireType, varint = value)
                        next
                    }

                    WIRE_LENGTH_DELIMITED -> {
                        val (length, next) = varint(message, afterTag, until) ?: return out
                        val end = next + length.toInt()
                        if (length < 0 || end > until || end < next) return out
                        out += Field(number, wireType, bytes = message.copyOfRange(next, end))
                        end
                    }

                    WIRE_FIXED64 -> {
                        if (afterTag + 8 > until) return out
                        out += Field(number, wireType, varint = fixed64(message, afterTag))
                        afterTag + 8
                    }

                    WIRE_FIXED32 -> {
                        if (afterTag + 4 > until) return out
                        out += Field(number, wireType, varint = fixed32(message, afterTag))
                        afterTag + 4
                    }

                    // Groups (3, 4) are removed from proto3 and have never been observed
                    // here. Continuing past one would mean guessing where it ends.
                    else -> {
                        return out
                    }
                }
        }
        return out
    }

    fun varintOf(
        fields: List<Field>,
        number: Int,
    ): Long? = fields.firstOrNull { it.number == number && it.wireType == WIRE_VARINT }?.varint

    fun bytesOf(
        fields: List<Field>,
        number: Int,
    ): ByteArray? = fields.firstOrNull { it.number == number && it.wireType == WIRE_LENGTH_DELIMITED }?.bytes

    fun allBytesOf(
        fields: List<Field>,
        number: Int,
    ): List<ByteArray> =
        fields.filter { it.number == number && it.wireType == WIRE_LENGTH_DELIMITED }.map(Field::bytes)

    /** Encodes a tag and a varint value. */
    fun varintField(
        number: Int,
        value: Long,
    ): ByteArray = encodeVarint(((number.toLong() shl 3) or WIRE_VARINT.toLong())) + encodeVarint(value)

    /** Encodes a tag, a length and a payload. */
    fun bytesField(
        number: Int,
        payload: ByteArray,
    ): ByteArray =
        encodeVarint((number.toLong() shl 3) or WIRE_LENGTH_DELIMITED.toLong()) +
            encodeVarint(payload.size.toLong()) +
            payload

    fun encodeVarint(value: Long): ByteArray {
        var remaining = value
        val out = mutableListOf<Byte>()
        while (true) {
            val chunk = (remaining and 0x7FL).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out += chunk.toByte()
                return out.toByteArray()
            }
            out += (chunk or 0x80).toByte()
        }
    }

    /** Returns the value and the offset just past it, or null if the varint is truncated. */
    private fun varint(
        message: ByteArray,
        from: Int,
        until: Int,
    ): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var offset = from
        while (offset < until && shift <= MAX_VARINT_SHIFT) {
            val byte = message[offset].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            offset++
            if (byte and 0x80 == 0) return result to offset
            shift += 7
        }
        return null
    }

    private fun fixed64(
        message: ByteArray,
        from: Int,
    ): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (message[from + index].toLong() and 0xFF)
        }
        return value
    }

    private fun fixed32(
        message: ByteArray,
        from: Int,
    ): Long {
        var value = 0L
        for (index in 3 downTo 0) {
            value = (value shl 8) or (message[from + index].toLong() and 0xFF)
        }
        return value
    }

    private val EMPTY = ByteArray(0)

    /** Ten groups of seven bits covers a 64-bit varint and nothing longer is valid. */
    private const val MAX_VARINT_SHIFT = 63
}
