package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.bluetooth.BluetoothDevice
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.StemLongPressAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

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
    /**
     * One decoder per session, because the `0x17` channel is stateful: which service a
     * HID report belongs to is only knowable from descriptors sent earlier on the same
     * channel, and the heart-rate timestamp anchor must not outlive it.
     */
    private val decoder: AapDecoder = AapDecoder(),
) {
    /** Opens the channel and emits every decoded message until collection stops. */
    fun events(device: BluetoothDevice): Flow<AapEvent> =
        transport
            .connect(device)
            .onStart {
                decoder.resetSession()
                // `resetSession` clears the decoder, so nothing is known about this
                // accessory's services again and the frame log must withhold every report
                // body until the accessory says otherwise.
                transport.withholdAllReportBodies()
            }.map(decoder::decode)
            .onEach { event -> if (event is AapEvent.HidServices) narrowWithheldServices() }
            .onCompletion { decoder.resetSession() }

    /**
     * Tells the transport which services carry measurements the frame log may not print.
     *
     * Only the heart-rate service does: FR-023 is about a health measurement, not about
     * sensor traffic in general. Head-tracking reports carry orientation and are the only
     * ground truth for where an orientation sits inside a report, so withholding them
     * bought no privacy and cost the ability to derive their layout.
     *
     * Called only where the services are actually known — an empty result then means the
     * accessory has no heart-rate service, not that it has not been asked yet.
     */
    private fun narrowWithheldServices() {
        val sensitive =
            decoder.knownServices
                .filter(HidService::isHeartRate)
                .map(HidService::id)
                .toSet()
        transport.restrictLoggingOf(sensitive)
    }

    /**
     * Seeds this session with what the accessory said about itself on an earlier link.
     *
     * See [AapDecoder.restoreServices]. A live announcement always wins.
     */
    fun restoreServices(remembered: List<HidService>) {
        decoder.restoreServices(remembered)
        // Remembered services identify the heart-rate service as well as live ones do, so
        // they are enough to narrow what the log withholds. Only narrow if the decoder
        // actually took them: `restoreServices` declines when it already has live ones.
        if (decoder.knownServices.isNotEmpty()) narrowWithheldServices()
    }

    /** The services the accessory has described, live or remembered. */
    val describedServices: List<HidService> get() = decoder.knownServices

    /** The heart-rate service id the accessory announced, or null before it has. */
    val heartRateServiceId: Int? get() = decoder.discoveredHeartRateServiceId

    /** True once a heart-rate service with a gateable confidence field has been described. */
    val canMeasureHeartRate: Boolean get() = decoder.canMeasureHeartRate

    /**
     * Suspends until the channel is writable.
     *
     * [events] is a cold flow: the channel exists only once something collects it, so a
     * command issued immediately after subscribing would be written to a socket that
     * does not exist yet. Callers wait on this first.
     */
    suspend fun awaitReady(): Boolean = transport.awaitReady()

    /**
     * Waits a caller-chosen time for the channel to carry traffic.
     *
     * The default is sized for "a user pressed a button and can wait". A caller racing
     * the accessory's one-shot service announcement needs a much shorter answer so it can
     * try again while the window is still open.
     */
    suspend fun awaitReady(timeoutMillis: Long): Boolean = transport.awaitReady(timeoutMillis)

    /**
     * Asks the accessory, again, to tell the host what it has.
     *
     * The same request goes out with the handshake, and on a channel opened the instant
     * the Bluetooth link comes up that is demonstrably too early: the accessory answers
     * with its whole configuration and says nothing about its HID services. Asking a
     * second time, once the channel has settled, is what makes it announce them — and
     * that announcement is the only way the heart-rate service id is ever learned.
     *
     * A request, not a change: it writes no setting and moves nothing in the accessory.
     */
    suspend fun requestNotifications(): Boolean = transport.send(AapProtocol.REQUEST_NOTIFICATIONS)

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

    /**
     * Asks the heart-rate service to report every [intervalMicros] microseconds.
     *
     * Returns false, without writing, when the accessory has not yet described a feature
     * report to write the interval into. That is not a defensive check — it is FR-002:
     * there is no constant to fall back on here, and inventing one is the bug this
     * project already watched LibrePods ship with head tracking's `0x0E`.
     *
     * Waits for the channel first. [events] is a cold flow, so the socket does not exist
     * until something collects it, and a command sent before then is silently dropped.
     */
    suspend fun startHeartRate(
        serviceId: Int,
        intervalMicros: Int,
    ): Boolean {
        val featureReportId = decoder.heartRateFeatureReportId ?: return false
        if (!awaitReady()) return false
        return transport.send(HidTransport.setReportInterval(serviceId, featureReportId, intervalMicros))
    }

    /**
     * Stops the sensor in the accessory — interval zero.
     *
     * This is the difference between FR-014 being satisfied and being faked. A UI that
     * stops showing a number while the optical sensor keeps drawing the buds' battery
     * looks identical and is not the same thing.
     */
    suspend fun stopHeartRate(serviceId: Int): Boolean {
        val featureReportId = decoder.heartRateFeatureReportId ?: return false
        if (!awaitReady()) return false
        return transport.send(HidTransport.stopReportStream(serviceId, featureReportId))
    }

    /**
     * Asks the head-tracking service to report every [intervalMicros] microseconds.
     *
     * Mechanically identical to the heart-rate stream — opcode 0x17 is a HID transport
     * carrying several services, and starting any of them is a report-interval feature
     * report — but it is written out rather than shared, because the two are allowed to
     * diverge and a single "start whatever" would hide it when they do.
     */
    suspend fun startHeadTracking(
        serviceId: Int,
        intervalMicros: Int,
    ): Boolean {
        val featureReportId = decoder.headTrackingFeatureReportId ?: return false
        if (!awaitReady()) return false
        return transport.send(HidTransport.setReportInterval(serviceId, featureReportId, intervalMicros))
    }

    /** Stops it — interval zero, so the buds stop sensing rather than the app stop reading. */
    suspend fun stopHeadTracking(serviceId: Int): Boolean {
        val featureReportId = decoder.headTrackingFeatureReportId ?: return false
        if (!awaitReady()) return false
        return transport.send(HidTransport.stopReportStream(serviceId, featureReportId))
    }
}
