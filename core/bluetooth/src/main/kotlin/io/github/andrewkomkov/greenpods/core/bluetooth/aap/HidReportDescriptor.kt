package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import kotlin.math.pow

/**
 * One declared field inside a report, with its position derived rather than written down.
 *
 * [byteOffset] is absolute within the report as it arrives on the wire — the report id
 * byte included — because that is the array the decoder will be handed.
 */
data class HidReportField(
    val usagePage: Int,
    val usage: Int,
    val bitSize: Int,
    val count: Int,
    /** Bit position within the report's data, after the report id. */
    val bitOffset: Int,
    /** Byte position within the whole report, report id included. */
    val byteOffset: Int,
    /**
     * The range the raw value is declared to span. Defaults describe a field that
     * declared nothing, which HID treats as an unconstrained unsigned value.
     */
    val logicalMinimum: Int = 0,
    val logicalMaximum: Int = 0,
    /**
     * The range the raw value *means*, in the field's unit.
     *
     * Both zero is HID's "same as logical" — the spec's own default, not a sentinel this
     * code invented — and [toPhysical] then returns the raw value unchanged.
     */
    val physicalMinimum: Int = 0,
    val physicalMaximum: Int = 0,
    /** Power of ten applied to the physical value. HID stores it as a signed nibble. */
    val unitExponent: Int = 0,
) {
    val byteSize: Int get() = (bitSize * count) / 8

    /** True when this field sits on byte boundaries and can be read as whole bytes. */
    val isByteAligned: Boolean get() = bitOffset % 8 == 0 && bitSize % 8 == 0

    /**
     * True when the declared range goes below zero, so the raw bytes are two's complement.
     *
     * Worth reading off the descriptor rather than assuming either way: an orientation
     * read as unsigned is not slightly wrong, it is wrong by a whole turn on half the
     * inputs.
     */
    val isSigned: Boolean get() = logicalMinimum < 0

    /**
     * Whether this field says enough to convert a raw reading into its unit.
     *
     * False means the accessory declared no usable mapping, and a caller must say so
     * rather than fall back to a constant — a made-up scale is indistinguishable from a
     * measured one once it is past this point.
     */
    val hasPhysicalScale: Boolean
        get() = logicalMaximum != logicalMinimum && physicalMaximum != physicalMinimum

    /**
     * Maps a raw reading onto the unit the descriptor declares.
     *
     * This is HID's own formula, and using it is the difference between reporting degrees
     * because the accessory said so and reporting degrees because somebody divided by
     * `Short.MAX_VALUE` and it looked about right.
     */
    fun toPhysical(raw: Int): Double {
        if (logicalMaximum == logicalMinimum) return raw.toDouble()
        val scaled =
            if (physicalMaximum == physicalMinimum) {
                // HID's default: the physical range is the logical range.
                raw.toDouble()
            } else {
                physicalMinimum +
                    (raw - logicalMinimum).toDouble() *
                    (physicalMaximum - physicalMinimum) /
                    (logicalMaximum - logicalMinimum)
            }
        return scaled * TEN.pow(unitExponent)
    }

    fun matches(
        page: Int,
        usage: Int,
    ): Boolean = usagePage == page && this.usage == usage

    private companion object {
        const val TEN = 10.0
    }
}

/** Every field one report id declares, in declaration order. */
data class HidReportLayout(
    val reportId: Int,
    val inputFields: List<HidReportField> = emptyList(),
    val featureFields: List<HidReportField> = emptyList(),
) {
    /** How many bytes a complete input report of this id occupies, report id included. */
    val inputByteSize: Int
        get() = 1 + inputFields.sumOf { it.byteSize }

    /** Looks a field up **by usage**, which is the whole point of parsing the descriptor. */
    fun input(
        usagePage: Int,
        usage: Int,
    ): HidReportField? = inputFields.firstOrNull { it.matches(usagePage, usage) }
}

/**
 * The accessory's own description of the reports it sends, walked into a usable form.
 *
 * The alternative — a hard-coded 18-byte struct — works today on one model and is
 * indistinguishable from working on the day it stops being right (R-2). Reading the
 * descriptor means a model that lays its report out differently needs no code change,
 * and a model that does not declare a confidence field is *detectably* not one this
 * feature can honour, rather than one it quietly mis-reads.
 *
 * Pure. Pinned against the 126 bytes captured from AirPods Pro 3 on 2026-08-04.
 */
data class HidReportDescriptor(
    val reports: List<HidReportLayout>,
) {
    fun report(reportId: Int): HidReportLayout? = reports.firstOrNull { it.reportId == reportId }

    /**
     * The report id whose **feature** report carries the 32-bit interval, which is the
     * field that starts and stops the stream.
     *
     * Identified by shape — a single 32-bit feature field — rather than by its usage,
     * because the usage (`0x0020:0x030E`) is the one part of the descriptor whose meaning
     * was read off behaviour rather than declared anywhere.
     */
    val intervalFeatureReportId: Int?
        get() =
            reports
                .firstOrNull { report ->
                    report.featureFields.any { it.bitSize == INTERVAL_BITS && it.count == 1 }
                }?.reportId

    /**
     * The heart-rate input report, but **only when it can be gated**.
     *
     * R-2's rule, enforced here rather than left to a caller: a layout with a heart-rate
     * field and no confidence field is not a usable heart-rate layout. The feature reports
     * itself unsupported with that reason instead of showing numbers it cannot vouch for.
     * An ungated number is the precise failure this whole specification exists to prevent.
     */
    fun heartRateReport(): HidReportLayout? =
        reports.firstOrNull { report ->
            report.input(USAGE_PAGE_SENSORS, USAGE_HEART_RATE) != null &&
                report.input(USAGE_PAGE_APPLE_VENDOR, USAGE_CONFIDENCE) != null
        }

    companion object {
        /** HID usage page "Sensors". */
        const val USAGE_PAGE_SENSORS = 0x0020

        /** Apple vendor page carrying confidence, sequence and the timestamp. */
        const val USAGE_PAGE_APPLE_VENDOR = 0xFF15

        const val USAGE_HEART_RATE = 0x04B8
        const val USAGE_CONFIDENCE = 0x0120
        const val USAGE_SEQUENCE = 0x0121
        const val USAGE_TIMESTAMP = 0x0004

        private const val INTERVAL_BITS = 32

        private const val TYPE_MAIN = 0
        private const val TYPE_GLOBAL = 1
        private const val TYPE_LOCAL = 2

        private const val MAIN_INPUT = 8
        private const val MAIN_FEATURE = 11
        private const val MAIN_COLLECTION = 10
        private const val MAIN_END_COLLECTION = 12

        private const val GLOBAL_USAGE_PAGE = 0
        private const val GLOBAL_LOGICAL_MINIMUM = 1
        private const val GLOBAL_LOGICAL_MAXIMUM = 2
        private const val GLOBAL_PHYSICAL_MINIMUM = 3
        private const val GLOBAL_PHYSICAL_MAXIMUM = 4
        private const val GLOBAL_UNIT_EXPONENT = 5
        private const val GLOBAL_REPORT_SIZE = 7
        private const val GLOBAL_REPORT_ID = 8
        private const val GLOBAL_REPORT_COUNT = 9

        private const val LOCAL_USAGE = 0
        private const val LOCAL_USAGE_MINIMUM = 1

        private const val LONG_ITEM_PREFIX = 0xFE

        /**
         * Walks HID short items into reports.
         *
         * Returns null only for input that is not a descriptor at all. A descriptor that
         * declares things this walker does not model — outputs, units, padding — parses
         * fine; those items simply do not contribute fields.
         */
        fun parse(descriptor: ByteArray): HidReportDescriptor? {
            if (descriptor.isEmpty()) return null

            var usagePage = 0
            var reportSize = 0
            var reportCount = 0
            var reportId = 0
            var logicalMinimum = 0
            var logicalMaximum = 0
            var physicalMinimum = 0
            var physicalMaximum = 0
            var unitExponent = 0
            val localUsages = mutableListOf<Int>()
            var usageMinimum: Int? = null

            // Input and feature bit positions accumulate independently per report id: they
            // are two different byte streams that happen to share an id.
            val inputBits = mutableMapOf<Int, Int>()
            val featureBits = mutableMapOf<Int, Int>()
            val inputs = mutableMapOf<Int, MutableList<HidReportField>>()
            val features = mutableMapOf<Int, MutableList<HidReportField>>()
            val order = mutableListOf<Int>()

            var offset = 0
            while (offset < descriptor.size) {
                val prefix = descriptor[offset].toInt() and 0xFF
                offset++

                if (prefix == LONG_ITEM_PREFIX) {
                    // Never observed on this accessory. Skipping it correctly is still the
                    // difference between ignoring one item and misreading the rest.
                    if (offset + 2 > descriptor.size) return null
                    val dataSize = descriptor[offset].toInt() and 0xFF
                    offset += 2 + dataSize
                    continue
                }

                val sizeCode = prefix and 0x03
                val size = if (sizeCode == 3) 4 else sizeCode
                val type = (prefix shr 2) and 0x03
                val tag = (prefix shr 4) and 0x0F
                if (offset + size > descriptor.size) return null

                val value = unsigned(descriptor, offset, size)
                offset += size

                when (type) {
                    TYPE_GLOBAL -> {
                        when (tag) {
                            GLOBAL_USAGE_PAGE -> usagePage = value

                            GLOBAL_REPORT_SIZE -> reportSize = value

                            GLOBAL_REPORT_ID -> reportId = value

                            GLOBAL_REPORT_COUNT -> reportCount = value

                            // Ranges are signed: HID writes a logical minimum of -32767 as
                            // `16 01 80`, and reading that unsigned yields 32769, which
                            // then says the field is unsigned and inverts half its range.
                            GLOBAL_LOGICAL_MINIMUM -> logicalMinimum = signed(descriptor, offset - size, size)

                            GLOBAL_LOGICAL_MAXIMUM -> logicalMaximum = signed(descriptor, offset - size, size)

                            GLOBAL_PHYSICAL_MINIMUM -> physicalMinimum = signed(descriptor, offset - size, size)

                            GLOBAL_PHYSICAL_MAXIMUM -> physicalMaximum = signed(descriptor, offset - size, size)

                            // A signed nibble, not a byte: 0x0F means -1, not 15.
                            GLOBAL_UNIT_EXPONENT -> unitExponent = signedNibble(value)

                            else -> Unit
                        }
                    }

                    TYPE_LOCAL -> {
                        when (tag) {
                            // A four-byte usage carries its page in the high half and
                            // overrides the global page for that usage alone.
                            LOCAL_USAGE -> localUsages += value

                            LOCAL_USAGE_MINIMUM -> usageMinimum = value

                            else -> Unit
                        }
                    }

                    TYPE_MAIN -> {
                        when (tag) {
                            MAIN_INPUT, MAIN_FEATURE -> {
                                val fullUsage = localUsages.firstOrNull() ?: usageMinimum ?: 0
                                val page = if (fullUsage > 0xFFFF) fullUsage ushr 16 else usagePage
                                val field =
                                    HidReportField(
                                        usagePage = page,
                                        usage = fullUsage and 0xFFFF,
                                        bitSize = reportSize,
                                        count = reportCount,
                                        bitOffset = 0,
                                        byteOffset = 0,
                                        logicalMinimum = logicalMinimum,
                                        logicalMaximum = logicalMaximum,
                                        physicalMinimum = physicalMinimum,
                                        physicalMaximum = physicalMaximum,
                                        unitExponent = unitExponent,
                                    )
                                val bits = if (tag == MAIN_INPUT) inputBits else featureBits
                                val sink = if (tag == MAIN_INPUT) inputs else features
                                val startBit = bits.getOrDefault(reportId, 0)
                                sink
                                    .getOrPut(reportId) { mutableListOf() }
                                    .add(
                                        field.copy(
                                            bitOffset = startBit,
                                            // +1 for the report id byte the accessory
                                            // prefixes every report with.
                                            byteOffset = 1 + startBit / 8,
                                        ),
                                    )
                                bits[reportId] = startBit + reportSize * reportCount
                                if (reportId !in order) order += reportId
                            }

                            MAIN_COLLECTION, MAIN_END_COLLECTION -> {
                                Unit
                            }

                            else -> {
                                Unit
                            }
                        }
                        // Local items describe the main item that follows them and nothing
                        // beyond it. Forgetting to clear them is the classic HID bug.
                        localUsages.clear()
                        usageMinimum = null
                    }

                    else -> {
                        localUsages.clear()
                        usageMinimum = null
                    }
                }
            }

            if (order.isEmpty()) return null
            return HidReportDescriptor(
                order.map { id ->
                    HidReportLayout(
                        reportId = id,
                        inputFields = inputs[id].orEmpty(),
                        featureFields = features[id].orEmpty(),
                    )
                },
            )
        }

        /**
         * Finds where a report descriptor starting at [from] ends, or null if one does
         * not start there.
         *
         * This exists so the descriptor can be lifted out of Apple's property dictionary
         * **without knowing how that dictionary encodes a value**. The capture recorded
         * the shape of its keys and nothing about its value types, and inventing one would
         * be exactly the fiction Principle V forbids. A HID descriptor does not need the
         * help: it is entirely self-describing, and it ends when its collections balance.
         */
        fun extentOf(
            data: ByteArray,
            from: Int,
        ): Int? {
            // A report descriptor opens with a Usage Page. Insisting on that is not
            // decoration: without it the search happily starts one byte early, on the
            // dictionary's own type byte, and swallows `05 20` as that byte's data — so
            // the usage page is never set, every usage lands on page 0, and the walk
            // still balances. It parses, it is wrong, and nothing says so.
            if (!isUsagePageItem(data, from)) return null

            var offset = from
            var depth = 0
            var opened = false

            while (offset < data.size) {
                val prefix = data[offset].toInt() and 0xFF
                offset++
                if (prefix == LONG_ITEM_PREFIX) {
                    if (offset + 2 > data.size) return null
                    offset += 2 + (data[offset].toInt() and 0xFF)
                    continue
                }

                val sizeCode = prefix and 0x03
                val size = if (sizeCode == 3) 4 else sizeCode
                val type = (prefix shr 2) and 0x03
                val tag = (prefix shr 4) and 0x0F
                if (type == 3) return null
                if (offset + size > data.size) return null
                offset += size

                if (type == TYPE_MAIN) {
                    when (tag) {
                        MAIN_COLLECTION -> {
                            depth++
                            opened = true
                        }

                        MAIN_END_COLLECTION -> {
                            depth--
                            if (depth < 0) return null
                            if (depth == 0 && opened) return offset
                        }

                        else -> {
                            if (!opened) return null
                        }
                    }
                }
            }
            return null
        }

        private fun isUsagePageItem(
            data: ByteArray,
            at: Int,
        ): Boolean {
            if (at >= data.size) return false
            val prefix = data[at].toInt() and 0xFF
            val sizeCode = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            return type == TYPE_GLOBAL && tag == GLOBAL_USAGE_PAGE && sizeCode in 1..2
        }

        /**
         * A global item's data as a signed value, sign-extended from its own width.
         *
         * HID stores Logical and Physical bounds as signed, and the width is whatever the
         * item prefix chose — so -1 is `FF` in a one-byte item and `FF FF` in a two-byte
         * one, and neither is 255 or 65535.
         *
         * A zero-length item carries no data and means zero.
         */
        private fun signed(
            data: ByteArray,
            from: Int,
            size: Int,
        ): Int {
            if (size == 0) return 0
            val raw = unsigned(data, from, size)
            if (size >= 4) return raw
            val signBit = 1 shl (size * 8 - 1)
            return if (raw and signBit != 0) raw - (1 shl (size * 8)) else raw
        }

        /** HID's 4-bit signed exponent: 0..7 are themselves, 8..15 are -8..-1. */
        private fun signedNibble(value: Int): Int {
            val nibble = value and 0x0F
            return if (nibble >= 8) nibble - 16 else nibble
        }

        private fun unsigned(
            data: ByteArray,
            from: Int,
            size: Int,
        ): Int {
            var value = 0
            for (index in 0 until size) {
                value = value or ((data[from + index].toInt() and 0xFF) shl (8 * index))
            }
            return value
        }
    }
}
