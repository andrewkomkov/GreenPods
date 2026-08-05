package io.github.andrewkomkov.greenpods.core.data.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapSession
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapTransport
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidService
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.data.transport.HidServiceMemory
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * What this accessory has already said about its own sensor services.
     *
     * Seeded into the session on connect and updated whenever a live announcement
     * arrives. Without it, an app that restarts while the earbuds stay connected can
     * never learn the heart-rate service id again on that link — the accessory answers
     * the "describe yourself" request once per connection and not again. See
     * [HidServiceMemory] for why remembering an answer is still discovery.
     */
    private val serviceMemory: HidServiceMemory = HidServiceMemory.None,
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

        // Everything this accessory has ever said about itself, keyed by service id.
        //
        // Seeded from memory and updated as frames arrive, because the announcement is
        // neither atomic nor repeated: AirPods Pro 3 describe `devmotion6` in one frame
        // and their other three services in another, and a link may re-announce only
        // some of them. Persisting each frame as the complete set — or even each link's
        // accumulation as the complete set — drops whatever that link did not happen to
        // mention. That is what silently cost head tracking its service id while heart
        // rate kept working, purely because heart rate's service was in the frame that
        // arrived last.
        //
        // A live frame still outranks memory, but per service rather than wholesale.
        val announced = linkedMapOf<Int, HidService>()

        readerJob =
            scope.launch {
                runCatching { serviceMemory.remembered(address) }
                    .getOrDefault(emptyList())
                    .forEach { announced[it.id] = it }

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
                    }.collect { event ->
                        // A live announcement replaces whatever was remembered — the
                        // accessory's current word about itself always wins — but it is
                        // *this link's* whole word, accumulated, not the last frame of it.
                        if (event is AapEvent.HidServices) {
                            event.services.forEach { announced[it.id] = it }
                            runCatching { serviceMemory.remember(address, announced.values.toList()) }
                        }
                        repository.onAapEvent(address, event)
                    }
            }

        // Announce the channel only once it is actually carrying traffic. Announcing on
        // subscription would unlock every gated feature a moment before the accessory
        // could answer, which reads as a feature that does nothing.
        scope.launch {
            if (!session.awaitReady()) return@launch
            // Seed before announcing, so anything that reacts to the channel opening
            // already has the service ids this accessory gave us last time.
            runCatching { session.restoreServices(serviceMemory.remembered(address)) }
            repository.onAapChannelOpen(address)
        }
        return true
    }

    /**
     * Opens the channel and waits to find out whether it actually came up.
     *
     * [connect] answers "the attempt started", which is all a fire-and-forget caller
     * needs and useless to one that must retry: a channel refused because the link is
     * still settling fails *after* connect has already returned true. This waits for the
     * transport to report itself writable, so a caller racing the accessory's one-shot
     * service announcement can tell a real failure from a slow start.
     */
    suspend fun openAndAwait(
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        if (!connect(address)) return false
        if (session.awaitReady(timeoutMillis)) return true

        // Only now tear it down. Disconnecting *before* each attempt looks tidier and is
        // actively destructive: [connect] returns the existing channel when one is
        // already live, so a retry that starts by disconnecting kills a channel that had
        // just come up — and with it the decoder session holding the accessory's
        // one-per-connection service announcement. Observed exactly that: descriptors
        // arrived, the next attempt reset the session, and every heart-rate report after
        // it was undecodable.
        disconnect()
        return false
    }

    /**
     * Re-asks the accessory to describe itself, on an already-open channel.
     *
     * See [AapSession.requestNotifications]: the handshake's copy of this request goes
     * out too early to be answered with the HID service announcement, and that
     * announcement happens once per connection or not at all.
     */
    suspend fun requestNotifications(): Boolean = session.requestNotifications()

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

    /**
     * Asks the accessory to announce its sensor services, on whatever channel is open.
     *
     * Opens one first if there is none: the request is only meaningful on a live channel,
     * and a heart-rate session that has just been switched on has every reason to want
     * one. Returns false when no channel can be had, which is an ordinary outcome.
     */
    override suspend fun describeServices(address: String): Boolean =
        withChannel(address) { session.requestNotifications() }

    /** Interval zero: the sensor stops in the earbuds, not merely on screen (FR-014). */
    override suspend fun stopHeartRate(
        address: String,
        serviceId: Int,
    ): Boolean = withChannel(address) { session.stopHeartRate(serviceId) }

    override suspend fun startHeadTracking(
        address: String,
        serviceId: Int,
        intervalMicros: Int,
    ): Boolean = withChannel(address) { session.startHeadTracking(serviceId, intervalMicros) }

    /** The same interval-zero stop: head tracking runs in the buds, and costs them. */
    override suspend fun stopHeadTracking(
        address: String,
        serviceId: Int,
    ): Boolean = withChannel(address) { session.stopHeadTracking(serviceId) }

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

        // Bounded, because a write to this socket can block for ever.
        //
        // `BluetoothSocket` has no write timeout: if the accessory stops reading — which
        // is exactly what a half-dead channel looks like — `OutputStream.write` never
        // returns. The heart-rate controller runs its whole state machine on one
        // collector, so a command that never returns takes the feature with it: observed
        // on hardware as a stop frame that was logged, a state that was never published,
        // and heart rate that could not be switched back on without restarting the app.
        //
        // The timeout does not unblock the socket thread — nothing can — but it unblocks
        // the *caller*, which is the part that matters. Same rule as the health store:
        // this transport is something the session talks to, never something it waits on.
        return withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { write() } ?: run {
            diagnostics.record(
                DiagnosticCategory.TRANSPORT,
                "Apple protocol write did not complete",
                "The channel accepted the bytes but never finished writing them. " +
                    "Treating it as failed so the session keeps running.",
            )
            // The channel is not trustworthy after this; drop it so the next attempt
            // starts clean rather than queueing behind a stuck write.
            disconnect()
            false
        }
    }

    private companion object {
        /**
         * Generous for a handful of bytes over an open channel, and short enough that a
         * user waiting for a toggle does not conclude the app has hung.
         */
        const val WRITE_TIMEOUT_MILLIS = 2_000L
    }
}
