package io.github.andrewkomkov.greenpods.core.bluetooth.ble

import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.WearState

/**
 * Decoder for Apple's "proximity pairing" BLE advertisement.
 *
 * This is the transport that works on every unrooted Android device: the buds
 * broadcast it continuously and no connection or pairing is required. It is
 * strictly read-only — writing settings needs AAP over L2CAP.
 *
 * Payload layout (the 27 bytes that follow the 0x004C company id):
 *
 * ```
 *  0      type, always 0x07 for proximity pairing
 *  1      remaining length, always 0x19 (25)
 *  2      status/prefix byte
 *  3..4   model id, big-endian (e.g. 0x0E20 = AirPods Pro)
 *  5      status flags: primary-bud selector, in-ear bits, "one pod in case"
 *  6      battery nibbles for the two buds
 *  7      case battery nibble (low) + charging flags (high)
 *  8      lid open counter
 *  9      device colour
 * 10      unknown/reserved
 * 11..26  encrypted payload (requires the pairing key; not decoded here)
 * ```
 *
 * Battery levels arrive as nibbles: 0..10 meaning 0..100 % in 10 % steps, and
 * 0x0F meaning "unknown". The bud nibbles are swapped depending on which bud is
 * currently primary, which is what [FLAG_PRIMARY_IS_LEFT] disambiguates.
 */
object AppleBeaconDecoder {
    /** Bluetooth SIG company identifier for Apple, Inc. */
    const val APPLE_COMPANY_ID = 0x004C

    /** Manufacturer-data type byte identifying a proximity-pairing message. */
    const val TYPE_PROXIMITY_PAIRING = 0x07

    /** Proximity-pairing payloads are always exactly this long. */
    const val PAIRING_MESSAGE_LENGTH = 27

    private const val NIBBLE_UNKNOWN = 0x0F

    private const val FLAG_PRIMARY_IS_LEFT = 0x20
    private const val FLAG_ONE_POD_IN_CASE = 0x10
    private const val FLAG_BOTH_IN_CASE = 0x04
    private const val FLAG_PRIMARY_IN_EAR = 0x02
    private const val FLAG_SECONDARY_IN_EAR = 0x08

    private const val CHARGING_RIGHT = 0x10
    private const val CHARGING_LEFT = 0x20
    private const val CHARGING_CASE = 0x40

    /**
     * Decodes a proximity-pairing payload, or returns null when [payload] is not
     * one (wrong type byte or wrong length).
     */
    fun decode(payload: ByteArray): AppleBeacon? {
        if (payload.size != PAIRING_MESSAGE_LENGTH) return null
        if (payload[0].toInt() and 0xFF != TYPE_PROXIMITY_PAIRING) return null

        val modelId = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
        val model = PodModel.fromModelId(modelId)

        val status = payload[5].toInt() and 0xFF
        val batteryByte = payload[6].toInt() and 0xFF
        val caseByte = payload[7].toInt() and 0xFF

        val primaryIsLeft = status and FLAG_PRIMARY_IS_LEFT != 0

        // The high nibble always describes the primary bud, the low nibble the
        // secondary one; which physical side that is depends on the flag above.
        val primaryNibble = batteryByte shr 4
        val secondaryNibble = batteryByte and 0x0F
        val leftNibble = if (primaryIsLeft) primaryNibble else secondaryNibble
        val rightNibble = if (primaryIsLeft) secondaryNibble else primaryNibble

        val chargingFlags = caseByte shr 4
        val caseNibble = caseByte and 0x0F

        val battery =
            BatteryState(
                left = component(leftNibble, chargingFlags and (CHARGING_LEFT shr 4) != 0),
                right = component(rightNibble, chargingFlags and (CHARGING_RIGHT shr 4) != 0),
                case = component(caseNibble, chargingFlags and (CHARGING_CASE shr 4) != 0),
            )

        return AppleBeacon(
            model = model,
            rawModelId = modelId,
            battery = battery,
            earDetection = decodeWear(status),
            lidOpenCounter = payload[8].toInt() and 0xFF,
            colorCode = payload[9].toInt() and 0xFF,
            encryptedPayload = payload.copyOfRange(11, PAIRING_MESSAGE_LENGTH),
        )
    }

    private fun component(
        nibble: Int,
        charging: Boolean,
    ): BatteryComponent =
        when {
            nibble == NIBBLE_UNKNOWN -> {
                BatteryComponent.Unknown
            }

            else -> {
                BatteryComponent(
                    levelPercent = (nibble.coerceAtMost(10)) * 10,
                    status = if (charging) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING,
                )
            }
        }

    private fun decodeWear(status: Int): EarDetectionState {
        val bothInCase = status and FLAG_BOTH_IN_CASE != 0
        val oneInCase = status and FLAG_ONE_POD_IN_CASE != 0

        val primary =
            when {
                bothInCase -> WearState.IN_CASE
                status and FLAG_PRIMARY_IN_EAR != 0 -> WearState.IN_EAR
                oneInCase -> WearState.IN_CASE
                else -> WearState.OUT_OF_EAR
            }
        val secondary =
            when {
                bothInCase -> WearState.IN_CASE
                status and FLAG_SECONDARY_IN_EAR != 0 -> WearState.IN_EAR
                else -> WearState.OUT_OF_EAR
            }

        return EarDetectionState(primary = primary, secondary = secondary)
    }
}

/** Decoded contents of one Apple proximity-pairing advertisement. */
data class AppleBeacon(
    val model: PodModel,
    val rawModelId: Int,
    val battery: BatteryState,
    val earDetection: EarDetectionState,
    /**
     * Increments every time the case lid opens. Used to detect a fresh "case opened"
     * event so the pop-up is not re-shown for every repeated advertisement.
     */
    val lidOpenCounter: Int,
    val colorCode: Int,
    val encryptedPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppleBeacon) return false
        return model == other.model &&
            rawModelId == other.rawModelId &&
            battery == other.battery &&
            earDetection == other.earDetection &&
            lidOpenCounter == other.lidOpenCounter &&
            colorCode == other.colorCode &&
            encryptedPayload.contentEquals(other.encryptedPayload)
    }

    override fun hashCode(): Int {
        var result = model.hashCode()
        result = 31 * result + rawModelId
        result = 31 * result + battery.hashCode()
        result = 31 * result + earDetection.hashCode()
        result = 31 * result + lidOpenCounter
        result = 31 * result + colorCode
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }
}
