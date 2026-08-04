package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * One HID service the accessory announced about itself.
 *
 * [id] is the value that goes into a start or stop frame, and it comes from the protobuf
 * rather than from a constant. That is FR-002 in one field: LibrePods hard-codes `0x0E`
 * for head tracking and AirPods Pro 3 ignores it silently, which is the same class of bug
 * one model later.
 */
data class HidService(
    val id: Int,
    val name: String?,
    val reportDescriptor: ByteArray,
    val isHeartRate: Boolean,
    /**
     * The head-tracking service, named `devmotion` by this accessory.
     *
     * Recorded for the same reason as [isHeartRate]: `0x17` is a shared channel, and
     * until this existed every frame on it long enough to look like a head pose was
     * decoded as one — including the service descriptors themselves.
     */
    val isHeadTracking: Boolean = false,
) {
    /** The walked form of [reportDescriptor], or null if it did not parse. */
    val layout: HidReportDescriptor? by lazy { HidReportDescriptor.parse(reportDescriptor) }

    override fun equals(other: Any?): Boolean =
        other is HidService &&
            id == other.id &&
            name == other.name &&
            isHeartRate == other.isHeartRate &&
            reportDescriptor.contentEquals(other.reportDescriptor)

    override fun hashCode(): Int =
        (31 * (31 * id + (name?.hashCode() ?: 0)) + isHeartRate.hashCode()) * 31 +
            reportDescriptor.contentHashCode()
}

/**
 * Reads the service descriptions the accessory sends unprompted under opcode `0x17`.
 *
 * The heart-rate service is found by **name** — the `HeartRateService` key, or failing
 * that the `com.apple.hid.heartrate-access` entitlement — and the id is read from beside
 * it. Both of those are model-independent; the id is not. A unit test feeds this the
 * captured frame with the ids renumbered and expects the sensor to still be found, which
 * is what stops "discovered" from decaying into "read from a constant that happens to
 * match" (AD-1).
 *
 * What this deliberately does **not** do is parse Apple's property dictionary. The capture
 * recorded the shape of its keys — `<u32 length LE> <type> <ASCII key>` — and nothing at
 * all about how it encodes a value, so a value parser would be invention. Instead the
 * keys are located by scanning for their ASCII bytes, and the report descriptor is lifted
 * out by walking it as HID, which is self-describing. A wrong guess about the dictionary
 * therefore cannot make this wrong on a device.
 */
object HidDescriptorParser {
    private const val FIELD_SEQUENCE = 1
    private const val FIELD_DESCRIPTOR = 5
    private const val FIELD_INPUT_REPORT = 7
    private const val FIELD_REQUEST = 8

    /** The accessory's acknowledgement of a start, carrying the service id it acted on. */
    private const val FIELD_STARTED = 9
    private const val FIELD_READY = 12

    private const val INNER_SERVICE_ID = 1
    private const val INNER_PROPERTIES = 2
    private const val INNER_REPORT_BYTES = 3

    private const val KEY_ACCESSORY_SERVICE = "AccessoryService"
    private const val KEY_REPORT_DESCRIPTOR = "ReportDescriptor"
    private const val NAME_HEART_RATE = "HeartRateService"
    private const val ENTITLEMENT_HEART_RATE = "com.apple.hid.heartrate-access"

    /** Apple's own name for the head-tracking service, observed at id 0x10. */
    private const val NAME_HEAD_TRACKING = "devmotion"

    /**
     * How far past a key the value's own bytes may start. The dictionary's per-value
     * header is a few bytes at most; searching further would start finding the *next*
     * entry's contents.
     */
    private const val VALUE_SEARCH_WINDOW = 24

    /** Every service descriptor carried in a `0x17` protobuf body. Empty if there are none. */
    fun services(body: ByteArray): List<HidService> =
        Protobuf
            .allBytesOf(Protobuf.fields(body), FIELD_DESCRIPTOR)
            .mapNotNull(::service)

    /** The field-12 readiness list: the accessory saying those services are up. */
    fun readyServiceIds(body: ByteArray): List<Int> =
        Protobuf
            .allBytesOf(Protobuf.fields(body), FIELD_READY)
            .mapNotNull { entry -> Protobuf.varintOf(Protobuf.fields(entry), INNER_SERVICE_ID)?.toInt() }

    /**
     * The service the accessory says it has just started. Null when field 9 is absent.
     *
     * Captured on 2026-08-04 as `4A 02 08 13` immediately after a start request: the
     * accessory confirming *which* service it acted on. Worth naming rather than leaving
     * as unknown traffic — but it confirms a **command**, not a measurement, and nothing
     * may treat it as evidence the sensor is running. On this transport an accepted write
     * is the characteristic false positive, and only an arriving report says the sensor
     * is on (Principle I).
     */
    fun startedServiceId(body: ByteArray): Int? {
        val entry = Protobuf.bytesOf(Protobuf.fields(body), FIELD_STARTED) ?: return null
        return Protobuf.varintOf(Protobuf.fields(entry), INNER_SERVICE_ID)?.toInt()
    }

    /** An input report: which service sent it, and its bytes. Null when field 7 is absent. */
    fun inputReport(body: ByteArray): Pair<Int, ByteArray>? {
        val entry = Protobuf.bytesOf(Protobuf.fields(body), FIELD_INPUT_REPORT) ?: return null
        val inner = Protobuf.fields(entry)
        val serviceId = Protobuf.varintOf(inner, INNER_SERVICE_ID)?.toInt() ?: return null
        val report = Protobuf.bytesOf(inner, INNER_REPORT_BYTES) ?: return null
        return serviceId to report
    }

    /** Which top-level fields a `0x17` body carries, so dispatch is on content, not size. */
    fun bodyFields(body: ByteArray): Set<Int> = Protobuf.fields(body).map { it.number }.toSet()

    fun hasDescriptors(body: ByteArray): Boolean = FIELD_DESCRIPTOR in bodyFields(body)

    fun hasReadyList(body: ByteArray): Boolean = FIELD_READY in bodyFields(body)

    fun hasInputReport(body: ByteArray): Boolean = FIELD_INPUT_REPORT in bodyFields(body)

    fun isRequest(body: ByteArray): Boolean = FIELD_REQUEST in bodyFields(body)

    fun sequenceOf(body: ByteArray): Long? = Protobuf.varintOf(Protobuf.fields(body), FIELD_SEQUENCE)

    private fun service(entry: ByteArray): HidService? {
        val inner = Protobuf.fields(entry)
        val id = Protobuf.varintOf(inner, INNER_SERVICE_ID)?.toInt() ?: return null
        val properties = Protobuf.bytesOf(inner, INNER_PROPERTIES) ?: ByteArray(0)

        return HidService(
            id = id,
            name = stringAfter(properties, KEY_ACCESSORY_SERVICE),
            reportDescriptor = reportDescriptor(properties) ?: ByteArray(0),
            // Either identifier is enough. The entitlement is the fallback because a model
            // that renames the service is far likelier than one that changes the
            // entitlement string, which other software depends on.
            isHeartRate =
                contains(properties, NAME_HEART_RATE) || contains(properties, ENTITLEMENT_HEART_RATE),
            isHeadTracking = contains(properties, NAME_HEAD_TRACKING),
        )
    }

    /**
     * The first printable run after [key], which is that key's string value.
     *
     * Not a dictionary parse — a scan. It is right for the shape the capture recorded and
     * merely returns null for a shape it does not recognise, which costs a display name
     * and never a service id.
     */
    private fun stringAfter(
        blob: ByteArray,
        key: String,
    ): String? {
        val keyEnd = indexOf(blob, key.toByteArray(Charsets.US_ASCII))?.plus(key.length) ?: return null
        var offset = keyEnd
        val limit = minOf(blob.size, keyEnd + VALUE_SEARCH_WINDOW)
        while (offset < limit && !isPrintable(blob[offset])) offset++
        if (offset >= limit) return null

        val start = offset
        while (offset < blob.size && isPrintable(blob[offset])) offset++
        return String(blob, start, offset - start, Charsets.US_ASCII).takeIf { it.length > 1 }
    }

    /**
     * Lifts the HID report descriptor out of the property blob.
     *
     * The start offset is searched rather than computed, because the dictionary's value
     * header is not documented. The *end* is not searched at all — a HID descriptor
     * terminates when its collections balance, so [HidReportDescriptor.extentOf] finds it
     * exactly, and an offset that is not a descriptor simply fails to walk.
     */
    private fun reportDescriptor(blob: ByteArray): ByteArray? {
        val keyEnd =
            indexOf(blob, KEY_REPORT_DESCRIPTOR.toByteArray(Charsets.US_ASCII))
                ?.plus(KEY_REPORT_DESCRIPTOR.length) ?: return null

        for (start in keyEnd until minOf(blob.size, keyEnd + VALUE_SEARCH_WINDOW)) {
            val end = HidReportDescriptor.extentOf(blob, start) ?: continue
            val candidate = blob.copyOfRange(start, end)
            if (HidReportDescriptor.parse(candidate)?.reports?.isNotEmpty() == true) return candidate
        }
        return null
    }

    private fun contains(
        blob: ByteArray,
        text: String,
    ): Boolean = indexOf(blob, text.toByteArray(Charsets.US_ASCII)) != null

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int? {
        if (needle.isEmpty() || needle.size > haystack.size) return null
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) continue@outer
            }
            return start
        }
        return null
    }

    private fun isPrintable(byte: Byte): Boolean = (byte.toInt() and 0xFF) in 0x20..0x7E
}
