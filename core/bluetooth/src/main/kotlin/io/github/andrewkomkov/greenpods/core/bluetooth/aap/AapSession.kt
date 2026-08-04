package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.bluetooth.BluetoothDevice
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.StemLongPressAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A live AAP conversation with one accessory: raw bytes in, [AapEvent]s out, commands
 * back the other way.
 *
 * The session performs no encoding and no interpretation — [AapCommands] builds the
 * packets and [AapDecoder] reads them — so the only thing here that cannot be unit
 * tested is the socket itself, which is exactly the amount of untestable code this
 * layer should have.
 *
 * Every command returns a Boolean rather than throwing. On most phones the channel is
 * never open (see `docs/protocol-research.md`), so a write that goes nowhere is the
 * expected case, not an exception worth propagating.
 */
class AapSession(
    private val transport: AapTransport = AapTransport(),
) {
    /** Opens the channel and emits every decoded message until collection stops. */
    fun events(device: BluetoothDevice): Flow<AapEvent> = transport.connect(device).map(AapDecoder::decode)

    /**
     * Suspends until the channel is writable.
     *
     * [events] is a cold flow: the channel exists only once something collects it, so a
     * command issued immediately after subscribing would be written to a socket that
     * does not exist yet. Callers wait on this first.
     */
    suspend fun awaitReady(): Boolean = transport.awaitReady()

    /**
     * Writes a packet built elsewhere.
     *
     * The wire format is reverse-engineered and incomplete, so establishing what a new
     * frame does means sending it and watching what comes back. This is the only way to
     * do that, and it is why it exists in production code rather than in the debug build:
     * the session that carries an experiment has to be the same session that carries
     * everything else, or the experiment proves nothing about the real path.
     */
    suspend fun send(packet: ByteArray): Boolean = transport.send(packet)

    suspend fun setNoiseControlMode(mode: NoiseControlMode): Boolean = transport.send(AapCommands.listeningMode(mode))

    suspend fun setAdaptiveNoiseStrength(percent: Int): Boolean =
        transport.send(
            AapCommands.adaptiveNoiseStrength(percent),
        )

    suspend fun setConversationalAwareness(enabled: Boolean): Boolean =
        transport.send(
            AapCommands.conversationalAwareness(enabled),
        )

    suspend fun setEarDetection(enabled: Boolean): Boolean = transport.send(AapCommands.earDetection(enabled))

    suspend fun setStemLongPress(
        right: StemLongPressAction,
        left: StemLongPressAction = right,
    ): Boolean = transport.send(AapCommands.stemLongPress(right, left))

    /**
     * Sets which modes a long press cycles through. Returns false for an empty
     * selection without writing anything — the accessory must cycle through at least
     * one mode, and a zero mask would leave it in a state the phone cannot undo.
     */
    suspend fun setListeningModeCycle(modes: Set<NoiseControlMode>): Boolean {
        val packet = AapCommands.listeningModeCycle(modes) ?: return false
        return transport.send(packet)
    }
}
