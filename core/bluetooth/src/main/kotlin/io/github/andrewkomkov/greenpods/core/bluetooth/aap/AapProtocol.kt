package io.github.andrewkomkov.greenpods.core.bluetooth.aap

/**
 * Apple Accessory Protocol (also written AACP) framing.
 *
 * Every packet is a 4-byte header, a little-endian 16-bit opcode, then a payload:
 *
 * ```
 * 04 00 04 00 | <opcode LE> | <payload…>
 * ```
 *
 * The protocol runs over an L2CAP channel on PSM 0x1001. Nothing here performs
 * I/O — this file is a pure codec so it can be unit-tested off-device, which
 * matters because the byte-level details come from community reverse-engineering
 * rather than a published spec.
 *
 * Sources: the LibrePods protocol notes (docs/AAP Definitions.md, opcodes.md,
 * control_commands.md), captured against AirPods Pro 2 firmware 7A305 and the
 * iOS 19.1 beta Bluetooth stack.
 */
object AapProtocol {
    /** L2CAP Protocol/Service Multiplexer that AAP listens on. */
    const val PSM = 0x1001

    /** Standard 4-byte prefix on every AAP packet. */
    val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    /**
     * Opening packet. The buds ignore every other packet until they receive this,
     * so it must be the first thing written after the channel opens.
     */
    val HANDSHAKE =
        byteArrayOf(
            0x00,
            0x00,
            0x04,
            0x00,
            0x01,
            0x00,
            0x02,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )

    /**
     * Declares host capabilities. Without it the buds keep Conversational
     * Awareness and Adaptive Transparency disabled while audio is playing —
     * Apple gates these on the host being an Apple device.
     */
    val SET_HOST_CAPABILITIES =
        byteArrayOf(
            0x04,
            0x00,
            0x04,
            0x00,
            0x4D,
            0x00,
            0xFF.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )

    /** Subscribes to battery, ear-detection, noise-control and CA notifications. */
    val REQUEST_NOTIFICATIONS =
        byteArrayOf(
            0x04,
            0x00,
            0x04,
            0x00,
            0x0F,
            0x00,
            0xFF.toByte(),
            0xFF.toByte(),
            0xFE.toByte(),
            0xFF.toByte(),
        )

    /** Builds an arbitrary packet: header + little-endian opcode + payload. */
    fun packet(
        opcode: Opcode,
        vararg payload: Byte,
    ): ByteArray =
        HEADER +
            byteArrayOf((opcode.value and 0xFF).toByte(), ((opcode.value shr 8) and 0xFF).toByte()) +
            payload

    /**
     * Builds a control command (opcode 0x09). The body is fixed at 7 bytes:
     * the identifier followed by up to four data bytes, zero-padded.
     *
     * `data2` is used where a setting differs per bud (stem long-press) or has
     * two state variables (hearing aid enrolled/enabled).
     */
    fun control(
        command: ControlCommand,
        data1: Int,
        data2: Int = 0,
    ): ByteArray =
        packet(
            Opcode.CONTROL,
            command.id.toByte(),
            (data1 and 0xFF).toByte(),
            (data2 and 0xFF).toByte(),
            0x00,
            0x00,
        )

    /** Parses the opcode out of a received packet, or null if it is malformed. */
    fun opcodeOf(packet: ByteArray): Opcode? {
        if (packet.size < 6) return null
        if (!packet.copyOfRange(0, 4).contentEquals(HEADER)) return null
        val raw = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        return Opcode.fromValue(raw)
    }

    /** Returns the payload after header + opcode, or an empty array. */
    fun payloadOf(packet: ByteArray): ByteArray =
        if (packet.size <=
            6
        ) {
            ByteArray(0)
        } else {
            packet.copyOfRange(6, packet.size)
        }
}

/**
 * AAP opcodes. `Host` means accessory → phone, `Accessory` means phone → buds.
 */
enum class Opcode(
    val value: Int,
) {
    /** Accessory ← host. Battery report. */
    BATTERY(0x0004),

    /** Accessory → host. Ear-detection change. */
    EAR_DETECTION(0x0006),

    /** Both directions. Get/set a single setting — see [ControlCommand]. */
    CONTROL(0x0009),

    /** Host → accessory. Subscribe to notifications. */
    NOTIFICATION_REGISTER(0x000F),

    /** Both. Head-tracking start/stop and the resulting sensor stream. */
    HEAD_TRACKING(0x0017),

    /** Accessory → host. Stem press event. */
    STEM_PRESS(0x0019),

    /** Accessory → host. Name, model, serial and firmware strings. */
    DEVICE_INFO(0x001D),

    /** Host → accessory. Rename the accessory. */
    RENAME(0x001E),

    /** Accessory → host. Conversational-awareness speech level. */
    CONVERSATIONAL_AWARENESS(0x004B),

    /** Host → accessory. Declare host capabilities. */
    HOST_CAPABILITIES(0x004D),

    /** Both. EQ / headphone-accommodation data. */
    EQ(0x0053),
    ;

    companion object {
        private val byValue = entries.associateBy(Opcode::value)

        fun fromValue(value: Int): Opcode? = byValue[value]
    }
}

/**
 * Identifiers carried inside a [Opcode.CONTROL] packet, extracted from the iOS
 * Bluetooth stack. Not every one is understood; those without a documented value
 * range are still listed so the diagnostics screen can label unknown traffic.
 */
enum class ControlCommand(
    val id: Int,
) {
    MIC_MODE(0x01),
    BUTTON_SEND_MODE(0x05),
    OWNS_CONNECTION(0x06),
    EAR_DETECTION(0x0A),

    /** Listening mode: 1 off, 2 ANC, 3 transparency, 4 adaptive. */
    LISTENING_MODE(0x0D),

    VOICE_TRIGGER_SIRI(0x12),
    SINGLE_CLICK_MODE(0x14),
    DOUBLE_CLICK_MODE(0x15),

    /** Two bytes: first right bud, second left bud. 0x01 noise control, 0x05 Siri. */
    CLICK_HOLD_MODE(0x16),

    DOUBLE_CLICK_INTERVAL(0x17),
    CLICK_HOLD_INTERVAL(0x18),

    /** Bitmask of the modes a long press cycles through. See NoiseControlMode.configBit. */
    LISTENING_MODE_CONFIGS(0x1A),

    ONE_BUD_ANC_MODE(0x1B),
    CROWN_ROTATION_DIRECTION(0x1C),
    AUTO_ANSWER_MODE(0x1E),
    CHIME_VOLUME(0x1F),
    CONNECT_AUTOMATICALLY(0x20),
    VOLUME_SWIPE_INTERVAL(0x23),
    CALL_MANAGEMENT_CONFIG(0x24),
    VOLUME_SWIPE_MODE(0x25),
    ADAPTIVE_VOLUME_CONFIG(0x26),
    SOFTWARE_MUTE_CONFIG(0x27),

    /** Conversational awareness: 0x01 on, 0x02 off. */
    CONVERSATION_DETECT_CONFIG(0x28),

    SSL(0x29),

    /** Two bytes: enrolled, enabled. */
    HEARING_AID(0x2C),

    /** Adaptive-audio noise strength, 0..100. Only effective in adaptive mode. */
    AUTO_ANC_STRENGTH(0x2E),

    HPS_GAIN_SWIPE(0x2F),

    /**
     * A control id named for the heart-rate sensor, and **not** how GreenPods starts it.
     *
     * The name comes from the id table, not from observed behaviour: no capture has ever
     * shown this command being sent or answered. What actually starts the sensor is a
     * report-interval feature report written to the heart-rate HID service discovered
     * over opcode `0x17`, and interval 0 stops it — see `HidTransport` and
     * docs/protocol-research.md.
     *
     * Kept as an identifier so that if an accessory ever *sends* 0x30, diagnostics name
     * it instead of reporting an unknown id (Principle IV). GreenPods never writes it.
     */
    HRM_STATE(0x30),

    IN_CASE_TONE_CONFIG(0x31),
    SIRI_MULTITONE_CONFIG(0x32),
    HEARING_ASSIST_CONFIG(0x33),
    ALLOW_OFF_LISTENING_MODE(0x34),
    SLEEP_DETECTION_CONFIG(0x35),
    ALLOW_AUTO_CONNECT(0x36),

    /** Bitmask: 0x01 single, 0x02 double, 0x04 triple, 0x08 long press. */
    RAW_GESTURES_CONFIG(0x39),

    TEMPORARY_PAIRING_CONFIG(0x3A),
    DYNAMIC_END_OF_CHARGE(0x3B),
    IN_CASE_TONE_VOLUME(0x40),
    DISABLE_BUTTON_INPUT(0x41),
    ;

    companion object {
        private val byId = entries.associateBy(ControlCommand::id)

        fun fromId(id: Int): ControlCommand? = byId[id]
    }
}

/** Common on/off encoding used by most control commands. */
object Toggle {
    const val ENABLED = 0x01
    const val DISABLED = 0x02

    fun of(enabled: Boolean): Int = if (enabled) ENABLED else DISABLED

    fun toBoolean(value: Int): Boolean? =
        when (value) {
            ENABLED -> true
            DISABLED -> false
            else -> null
        }
}
