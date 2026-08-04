package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeaconDecoder

/**
 * Builds a valid proximity-pairing payload from human-sized inputs.
 *
 * The encoder the decoder never had. It exists so an operator can say "left bud out,
 * 30 percent" over adb instead of hand-assembling nibbles, and — usefully — because
 * round-tripping through [AppleBeaconDecoder] is an independent check that the decoder
 * reads the layout the way these notes describe it.
 *
 * Pure, so it is unit-tested against the decoder on the JVM.
 */
object SyntheticBeacon {
    /** Wear flags, as laid out in the status byte. */
    private const val FLAG_PRIMARY_IS_LEFT = 0x20
    private const val FLAG_ONE_POD_IN_CASE = 0x10
    private const val FLAG_BOTH_IN_CASE = 0x04
    private const val FLAG_PRIMARY_IN_EAR = 0x02
    private const val FLAG_SECONDARY_IN_EAR = 0x08

    private const val CHARGING_RIGHT = 0x1
    private const val CHARGING_LEFT = 0x2
    private const val CHARGING_CASE = 0x4

    private const val NIBBLE_UNKNOWN = 0x0F

    /**
     * @param wear one of `in_ear`, `out`, `in_case`, `one_out` — the transitions that
     *   drive auto-pause.
     * @param casePercent null renders the "unknown" sentinel rather than zero.
     */
    fun build(
        modelId: Int,
        leftPercent: Int,
        rightPercent: Int,
        casePercent: Int?,
        wear: String,
        charging: Boolean,
        lidCounter: Int = 1,
    ): ByteArray {
        val payload = ByteArray(AppleBeaconDecoder.PAIRING_MESSAGE_LENGTH)
        payload[0] = AppleBeaconDecoder.TYPE_PROXIMITY_PAIRING.toByte()
        payload[1] = 0x19
        payload[3] = ((modelId shr 8) and 0xFF).toByte()
        payload[4] = (modelId and 0xFF).toByte()

        // Always declare the left bud primary, so the nibble order is unambiguous.
        var status = FLAG_PRIMARY_IS_LEFT
        status =
            status or
            when (wear.lowercase()) {
                "in_ear" -> FLAG_PRIMARY_IN_EAR or FLAG_SECONDARY_IN_EAR
                "one_out" -> FLAG_PRIMARY_IN_EAR
                "in_case" -> FLAG_BOTH_IN_CASE or FLAG_ONE_POD_IN_CASE
                else -> 0
            }
        payload[5] = status.toByte()

        payload[6] = ((nibble(leftPercent) shl 4) or nibble(rightPercent)).toByte()

        var chargingFlags = 0
        if (charging) chargingFlags = CHARGING_LEFT or CHARGING_RIGHT or CHARGING_CASE
        payload[7] = ((chargingFlags shl 4) or nibble(casePercent ?: -1)).toByte()

        payload[8] = lidCounter.toByte()
        return payload
    }

    /** Levels travel as 0..10 in steps of ten; anything unknown is the 0x0F sentinel. */
    private fun nibble(percent: Int): Int = if (percent < 0) NIBBLE_UNKNOWN else (percent / 10).coerceIn(0, 10)
}
