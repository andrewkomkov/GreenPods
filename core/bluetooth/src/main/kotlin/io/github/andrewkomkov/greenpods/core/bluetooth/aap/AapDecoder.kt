package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.WearState

/** A decoded message from the accessory. */
sealed interface AapEvent {
    data class Battery(
        val state: BatteryState,
    ) : AapEvent

    data class EarDetection(
        val state: EarDetectionState,
    ) : AapEvent

    data class NoiseControl(
        val mode: NoiseControlMode,
    ) : AapEvent

    data class ConversationalAwarenessState(
        val enabled: Boolean,
    ) : AapEvent

    data class ConversationalAwarenessLevel(
        val level: Int,
    ) : AapEvent

    data class AdaptiveNoiseStrength(
        val level: Int,
    ) : AapEvent

    data class HeadTracking(
        val sample: HeadTrackingSample,
    ) : AapEvent

    data class DeviceInfo(
        val fields: List<String>,
    ) : AapEvent

    /** A control packet we recognise the id of but have no typed model for yet. */
    data class UnhandledControl(
        val command: ControlCommand,
        val payload: ByteArray,
    ) : AapEvent {
        override fun equals(other: Any?): Boolean =
            other is UnhandledControl && command == other.command && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * command.hashCode() + payload.contentHashCode()
    }

    /** Traffic we cannot interpret at all. Surfaced in diagnostics, never dropped silently. */
    data class Unknown(
        val raw: ByteArray,
    ) : AapEvent {
        override fun equals(other: Any?): Boolean = other is Unknown && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }
}

/**
 * Turns raw AAP packets into [AapEvent]s.
 *
 * Pure and stateless so the whole protocol surface is unit-testable without a
 * device — which is the only practical way to validate a reverse-engineered format.
 */
object AapDecoder {
    fun decode(packet: ByteArray): AapEvent {
        val opcode = AapProtocol.opcodeOf(packet) ?: return AapEvent.Unknown(packet)
        val payload = AapProtocol.payloadOf(packet)

        return when (opcode) {
            Opcode.BATTERY -> decodeBattery(payload) ?: AapEvent.Unknown(packet)
            Opcode.EAR_DETECTION -> decodeEarDetection(payload) ?: AapEvent.Unknown(packet)
            Opcode.CONTROL -> decodeControl(payload) ?: AapEvent.Unknown(packet)
            Opcode.CONVERSATIONAL_AWARENESS -> decodeAwarenessLevel(payload) ?: AapEvent.Unknown(packet)
            Opcode.HEAD_TRACKING -> decodeHeadTracking(packet) ?: AapEvent.Unknown(packet)
            Opcode.DEVICE_INFO -> AapEvent.DeviceInfo(decodeNullTerminatedStrings(payload))
            else -> AapEvent.Unknown(packet)
        }
    }

    /**
     * Battery payload: a count, then that many 5-byte records of
     * `[component] 01 [level] [status] 01`.
     */
    private fun decodeBattery(payload: ByteArray): AapEvent.Battery? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        if (payload.size < 1 + count * RECORD_BYTES) return null

        var state = BatteryState()
        for (index in 0 until count) {
            val offset = 1 + index * RECORD_BYTES
            val component = payload[offset].toInt() and 0xFF
            val level = payload[offset + 2].toInt() and 0xFF
            val status = chargeStatus(payload[offset + 3].toInt() and 0xFF)

            val battery =
                BatteryComponent(
                    levelPercent = level.takeIf { it in 0..100 },
                    status = status,
                )
            state =
                when (component) {
                    COMPONENT_LEFT -> state.copy(left = battery)
                    COMPONENT_RIGHT -> state.copy(right = battery)
                    COMPONENT_CASE -> state.copy(case = battery)
                    else -> state
                }
        }
        return AapEvent.Battery(state)
    }

    private fun decodeEarDetection(payload: ByteArray): AapEvent.EarDetection? {
        if (payload.size < 2) return null
        return AapEvent.EarDetection(
            EarDetectionState(
                primary = wearState(payload[0].toInt() and 0xFF),
                secondary = wearState(payload[1].toInt() and 0xFF),
            ),
        )
    }

    /** Control packets are `[identifier] [data1] [data2] 00 00`. */
    private fun decodeControl(payload: ByteArray): AapEvent? {
        if (payload.size < 2) return null
        val command = ControlCommand.fromId(payload[0].toInt() and 0xFF) ?: return null
        val data1 = payload[1].toInt() and 0xFF

        return when (command) {
            ControlCommand.LISTENING_MODE -> {
                NoiseControlMode.fromWire(data1)?.let(AapEvent::NoiseControl)
            }

            ControlCommand.CONVERSATION_DETECT_CONFIG -> {
                Toggle.toBoolean(data1)?.let(AapEvent::ConversationalAwarenessState)
            }

            ControlCommand.AUTO_ANC_STRENGTH -> {
                AapEvent.AdaptiveNoiseStrength(data1.coerceIn(0, 100))
            }

            else -> {
                AapEvent.UnhandledControl(command, payload.copyOfRange(1, payload.size))
            }
        }
    }

    /** Awareness level packet: `02 00 01 [level]`, level 1..2 speaking, 8..9 normal. */
    private fun decodeAwarenessLevel(payload: ByteArray): AapEvent? {
        if (payload.size < 4) return null
        return AapEvent.ConversationalAwarenessLevel(payload[3].toInt() and 0xFF)
    }

    /**
     * Head-tracking sample. Offsets are absolute within the packet, per the
     * captured layout, so this reads from [packet] rather than the payload slice.
     */
    private fun decodeHeadTracking(packet: ByteArray): AapEvent? {
        if (packet.size < HEAD_TRACKING_MIN_BYTES) return null

        fun le16(offset: Int): Short =
            (
                ((packet[offset + 1].toInt() and 0xFF) shl 8) or
                    (packet[offset].toInt() and 0xFF)
            ).toShort()

        return AapEvent.HeadTracking(
            HeadTrackingSample(
                orientation1 = le16(43),
                orientation2 = le16(45),
                orientation3 = le16(47),
                horizontalAcceleration = le16(51),
                verticalAcceleration = le16(53),
            ),
        )
    }

    /**
     * Device-info payload is a run of null-terminated UTF-8 strings — name, model,
     * manufacturer, serial and firmware — followed by an encrypted tail. Keeping
     * only printable segments drops that tail.
     */
    private fun decodeNullTerminatedStrings(payload: ByteArray): List<String> =
        payload
            .decodeToString()
            .split('\u0000')
            .map(String::trim)
            .filter { field -> field.isNotEmpty() && field.all { it.code in 0x20..0x7E } }

    private fun chargeStatus(value: Int): ChargeStatus =
        when (value) {
            0x01 -> ChargeStatus.CHARGING
            0x02 -> ChargeStatus.DISCHARGING
            0x04 -> ChargeStatus.DISCONNECTED
            else -> ChargeStatus.UNKNOWN
        }

    private fun wearState(value: Int): WearState =
        when (value) {
            0x00 -> WearState.IN_EAR
            0x01 -> WearState.OUT_OF_EAR
            0x02 -> WearState.IN_CASE
            else -> WearState.UNKNOWN
        }

    private const val RECORD_BYTES = 5
    private const val COMPONENT_RIGHT = 0x02
    private const val COMPONENT_LEFT = 0x04
    private const val COMPONENT_CASE = 0x08
    private const val HEAD_TRACKING_MIN_BYTES = 55
}
