package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.StemLongPressAction

/**
 * Every packet GreenPods is willing to send, as pure byte arrays.
 *
 * Building is deliberately separated from writing: no test device can open the L2CAP
 * channel, so the only way to have confidence in these commands is to pin their bytes
 * in unit tests. [AapSession] is the part that performs I/O, and it does no encoding.
 *
 * Commands whose wire format is not publicly documented — renaming, EQ — are absent
 * rather than guessed. See `docs/protocol-research.md`.
 */
object AapCommands {
    /** Off / ANC / Transparency / Adaptive. Control command 0x0D. */
    fun listeningMode(
        mode: NoiseControlMode,
    ): ByteArray = AapProtocol.control(ControlCommand.LISTENING_MODE, mode.wireValue)

    /**
     * Adaptive-audio noise strength. Apple's own UI exposes three steps; the protocol
     * takes any value in 0..100, and out-of-range input is clamped rather than rejected
     * because a slider should never be able to produce an invalid packet.
     */
    fun adaptiveNoiseStrength(percent: Int): ByteArray =
        AapProtocol.control(ControlCommand.AUTO_ANC_STRENGTH, percent.coerceIn(0, 100))

    fun conversationalAwareness(enabled: Boolean): ByteArray =
        AapProtocol.control(ControlCommand.CONVERSATION_DETECT_CONFIG, Toggle.of(enabled))

    fun earDetection(enabled: Boolean): ByteArray =
        AapProtocol.control(
            ControlCommand.EAR_DETECTION,
            Toggle.of(enabled),
        )

    /**
     * The modes a stem long-press cycles through, as a bitmask.
     *
     * Returns null for an empty selection: the accessory has to cycle through
     * *something*, and sending a zero mask puts it in a state the user cannot undo
     * from the phone. Callers surface this as a rejected edit, not as a failed write.
     */
    fun listeningModeCycle(modes: Set<NoiseControlMode>): ByteArray? {
        if (modes.isEmpty()) return null
        val mask = modes.fold(0) { acc, mode -> acc or mode.configBit }
        return AapProtocol.control(ControlCommand.LISTENING_MODE_CONFIGS, mask)
    }

    /**
     * What a long press does, per bud. The first data byte is the right bud, the
     * second the left — the order is the accessory's, not ours.
     */
    fun stemLongPress(
        right: StemLongPressAction,
        left: StemLongPressAction = right,
    ): ByteArray = AapProtocol.control(ControlCommand.CLICK_HOLD_MODE, right.wireValue, left.wireValue)

    /**
     * Powers the optical heart-rate sensor on models that have one.
     *
     * Sending this is understood; *reading* the resulting measurement is not — the
     * frame layout has never been publicly decoded, so GreenPods offers the toggle
     * only where it can also show a number, which today means nowhere over AAP.
     */
    fun heartRateSensor(enabled: Boolean): ByteArray = AapProtocol.control(ControlCommand.HRM_STATE, Toggle.of(enabled))

    // Deliberately absent: head-tracking start/stop and the device-info request. Both
    // opcodes are known (0x0017, 0x001D) but their request payloads have never been
    // captured, and a guessed payload is exactly the kind of fiction this project does
    // not ship. Gestures therefore consume a stream the accessory is already sending.
    // See docs/protocol-research.md.
}
