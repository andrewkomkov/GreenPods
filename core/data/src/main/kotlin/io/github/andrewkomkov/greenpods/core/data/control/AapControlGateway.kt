package io.github.andrewkomkov.greenpods.core.data.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapSession
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapTransport
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * The real control path: an [AapSession] over an L2CAP channel to a bonded accessory.
 *
 * On most phones this never connects, and that is designed for rather than worked
 * around. [connect] reports success as a Boolean, decoded events are folded straight
 * into the repository, and a dropped channel clears the overlay so a stale
 * noise-control mode is never shown as current.
 */
class AapControlGateway(
    context: Context,
    private val repository: PodRepository,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    // The adapter is not optional in practice: the only BluetoothSocket constructor that
    // exists on current Android takes it as its first argument, so a transport built
    // without one can open no channel at all.
    private val session: AapSession =
        AapSession(
            AapTransport(adapter = context.getSystemService(BluetoothManager::class.java)?.adapter),
        ),
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) : PodControlGateway {
    private var connectedAddress: String? = null
    private var readerJob: Job? = null

    /** True when a channel was opened and the event stream is running. */
    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (connectedAddress == address && readerJob?.isActive == true) return true
        disconnect()

        // The address here comes from an advertisement, which uses a rotating private
        // address — the channel has to be opened to the paired classic address instead.
        val resolution = resolver.resolve(address)
        val device = (resolution as? BondedPodResolver.Resolution.Resolved)?.device ?: return false

        connectedAddress = address
        readerJob =
            scope.launch {
                session
                    .events(device)
                    .catch { error ->
                        diagnostics.record(
                            DiagnosticCategory.TRANSPORT,
                            "Apple protocol channel closed",
                            error.message.orEmpty(),
                        )
                    }.onCompletion { cause ->
                        repository.clearOverlay(address)
                        repository.onAapChannelClosed(address, cause?.message ?: "channel ended")
                    }.collect { event -> repository.onAapEvent(address, event) }
            }

        // Announce the channel only once it is actually carrying traffic. Announcing on
        // subscription would unlock every gated feature a moment before the accessory
        // could answer, which reads as a feature that does nothing.
        scope.launch {
            if (session.awaitReady()) repository.onAapChannelOpen(address)
        }
        return true
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        connectedAddress?.let(repository::clearOverlay)
        connectedAddress = null
    }

    override suspend fun setNoiseControlMode(
        address: String,
        mode: NoiseControlMode,
    ): Boolean = withChannel(address) { session.setNoiseControlMode(mode) }

    override suspend fun setAdaptiveNoiseStrength(
        address: String,
        percent: Int,
    ): Boolean = withChannel(address) { session.setAdaptiveNoiseStrength(percent) }

    override suspend fun setConversationalAwareness(
        address: String,
        enabled: Boolean,
    ): Boolean = withChannel(address) { session.setConversationalAwareness(enabled) }

    override suspend fun setListeningModeCycle(
        address: String,
        modes: Set<NoiseControlMode>,
    ): Boolean = withChannel(address) { session.setListeningModeCycle(modes) }

    /**
     * Sends a packet the caller assembled itself, over the ordinary session.
     *
     * For protocol work: the frames that are not decoded yet can only be characterised by
     * sending them and reading the reply, and doing that over the real session is what
     * makes the result mean anything.
     */
    suspend fun sendRaw(
        address: String,
        packet: ByteArray,
    ): Boolean = withChannel(address) { session.send(packet) }

    /**
     * Asks the accessory's heart-rate service to report at [intervalMicros].
     *
     * [serviceId] came from the accessory's own descriptors — the session refuses to
     * write without a feature report id discovered the same way, so there is no path
     * here that reaches the wire with a guessed id (FR-002).
     */
    override suspend fun startHeartRate(
        address: String,
        serviceId: Int,
        intervalMicros: Int,
    ): Boolean = withChannel(address) { session.startHeartRate(serviceId, intervalMicros) }

    /** Interval zero: the sensor stops in the earbuds, not merely on screen (FR-014). */
    override suspend fun stopHeartRate(
        address: String,
        serviceId: Int,
    ): Boolean = withChannel(address) { session.stopHeartRate(serviceId) }

    private suspend fun withChannel(
        address: String,
        write: suspend () -> Boolean,
    ): Boolean {
        if (!connect(address)) return false
        // connect() only starts the collection; the channel comes up a moment later.
        // Writing before then silently drops the command.
        if (!session.awaitReady()) {
            diagnostics.record(
                DiagnosticCategory.TRANSPORT,
                "Apple protocol write skipped",
                "The channel did not become writable in time.",
            )
            return false
        }
        return write()
    }
}
