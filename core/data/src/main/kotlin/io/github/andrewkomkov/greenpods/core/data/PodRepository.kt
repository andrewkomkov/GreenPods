package io.github.andrewkomkov.greenpods.core.data

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodIdentity
import io.github.andrewkomkov.greenpods.core.data.transport.PodIdentity
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

/**
 * A decoded AAP message and which accessory it came from.
 *
 * The repository folds these into state; the heart-rate session needs to *see* them,
 * because service discovery and a report arriving are both events rather than states.
 */
data class AddressedAapEvent(
    val address: String,
    val event: AapEvent,
)

/**
 * Single source of truth for what GreenPods knows about nearby accessories.
 *
 * Three streams meet here and the merge rules are the whole job:
 *
 * - **Advertisements** arrive per device, out of order and lossily. State is
 *   accumulated rather than replaced, and an accessory that goes quiet ages out over
 *   [STALE_AFTER_MILLIS] instead of vanishing on one missed packet — the radio drops
 *   packets constantly, and a list that flickers is worse than one that lags.
 * - **Connected-transport knowledge** (AAP, GATT) lives in a [PodOverlay] so the next
 *   advertisement cannot wipe a noise-control mode or a heart rate that no
 *   advertisement carries.
 * - **The transport gate** decorates every result, so `usableFeatures` is correct by
 *   construction rather than by each screen remembering to check.
 *
 * The clock is injected: staleness is the one behaviour here that is impossible to
 * test against a wall clock.
 */
class PodRepository(
    private val source: PodSightingSource,
    private val gate: TransportGate,
    private val diagnostics: DiagnosticsLog,
    /**
     * Where the shared scan lives.
     *
     * [pods] is shared rather than cold because the Bluetooth stack allows one scan
     * registration per app: the screen, the ear-detection controller and the monitoring
     * service all want the same stream, and three cold collections become three
     * `startScan` calls, of which two fail with
     * `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`.
     */
    private val scope: CoroutineScope,
    private val settings: Flow<GreenPodsSettings> = flowOf(GreenPodsSettings.Default),
    /**
     * What an accessory is called across address rotations.
     *
     * Defaults to the advertised address so nothing that has no way to resolve a bond
     * has to pretend it does. The app supplies [BondedPodIdentity], which is what keeps
     * an open channel, its overlay and its heart-rate session attached to the same
     * earbuds after they come out of the case.
     */
    private val identity: PodIdentity = PodIdentity.Advertised,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Drives re-evaluation of staleness. Without it an accessory that stops
     * advertising would stay on screen until some *other* accessory happened to be
     * seen — ageing has to be driven by time passing, not by traffic arriving.
     */
    private val ageTicker: Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(AGE_TICK_MILLIS)
            }
        },
) {
    private val overlays = MutableStateFlow<Map<String, PodOverlay>>(emptyMap())

    /**
     * Which undecodable HID report shapes have already been written down.
     *
     * Cleared when a channel closes, because the next channel may genuinely differ — a
     * shape that was undecodable only because descriptors were missed is worth recording
     * again once there is a new session to blame it on.
     */
    private val seenHidShapes = mutableSetOf<String>()
    private val _scanFailure = MutableStateFlow<String?>(null)

    /**
     * Bumped when the user asks for the scan to be started again.
     *
     * A radio that refused a scan usually keeps refusing until something changes, and the
     * only thing the app can change is to ask once more. Without this the "try again"
     * the empty state offers would be a button that does nothing — worse than not
     * offering one, because it teaches that the app's buttons are decorative.
     */
    private val retries = MutableStateFlow(0)

    /**
     * Sightings fed in by hand rather than heard on the air.
     *
     * The states worth testing are physical — a bud leaving an ear, a case closing, a
     * battery crossing a threshold — and none of them can be produced on demand from a
     * laptop. Merging an injectable stream into the same pipeline the radio feeds means
     * the whole chain is exercised, not a mock of it. Only the debug build exposes a way
     * to write to it.
     */
    private val injected = MutableSharedFlow<PodSighting>(extraBufferCapacity = INJECT_BUFFER)

    private val _aapEvents = MutableSharedFlow<AddressedAapEvent>(extraBufferCapacity = AAP_EVENT_BUFFER)

    /** Set when the scanner cannot run — Bluetooth off, permission missing, radio busy. */
    val scanFailure: StateFlow<String?> = _scanFailure.asStateFlow()

    /**
     * Every decoded AAP message, as it arrives.
     *
     * Overlay state answers "what is true now"; this answers "what just happened", and
     * the heart-rate session needs the second. Buffered rather than blocking: a slow
     * subscriber must never stall the socket reader.
     */
    val aapEvents: Flow<AddressedAapEvent> = _aapEvents

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sighted: Flow<Map<String, PodState>> =
        combine(settings.map { it.scanMode }.distinctUntilChanged(), retries, ::Pair)
            .flatMapLatest { (scanMode, _) ->
                merge(source.sightings(scanMode), injected)
                    .onStart { _scanFailure.value = null }
                    .catch { error ->
                        // Scanning stops when Bluetooth is switched off or the permission
                        // is revoked. That is a state to explain, not an exception to
                        // propagate into the UI as a crash.
                        _scanFailure.value = error.message ?: "Scanning is unavailable"
                        diagnostics.record(DiagnosticCategory.SCAN, "Scan stopped", error.message.orEmpty())
                    }.onEach(::noteUnknownModel)
                    .runningFold(emptyMap(), ::accumulate)
            }

    /**
     * Every currently-known accessory, closest first.
     *
     * The gate's status map is one of the inputs, not just something read on the way
     * past: a probe result has to re-emit the list, or a feature that just became
     * available would stay locked on screen until the next advertisement happened to
     * arrive — which, for an accessory sitting in its case, could be never.
     */
    val pods: Flow<List<PodState>> =
        combine(sighted, overlays, ageTicker, gate.aapStatuses) { known, overlay, _, _ ->
            val now = clock()
            known.values
                .asSequence()
                .filter { pod -> pod.model != PodModel.UNKNOWN }
                .filter { pod -> now - pod.lastSeenEpochMillis < STALE_AFTER_MILLIS }
                .map { pod -> (overlay[pod.address] ?: PodOverlay.Empty).applyTo(pod) }
                .map(gate::decorate)
                .sortedByDescending { pod -> pod.rssi ?: Int.MIN_VALUE }
                .toList()
        }.distinctUntilChanged()
            .shareIn(
                scope = scope,
                // Keep the radio going briefly across a rotation or a tab switch; drop it
                // when nothing is watching, so a backgrounded app stops scanning.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = SHARE_TIMEOUT_MILLIS),
                replay = 1,
            )

    /**
     * The accessory the user most likely means: the closest one still advertising.
     * RSSI is noisy, but over the advertisement transport it is the only signal there is.
     */
    val primaryPod: Flow<PodState?> = pods.map { it.firstOrNull() }.distinctUntilChanged()

    /**
     * Asks the gate whether the Apple protocol channel can be opened for an accessory.
     *
     * Exposed here so screens have one dependency rather than two, and so the
     * probe-once rule is enforced in a single place.
     */
    suspend fun probeAap(
        address: String,
        force: Boolean = false,
    ): TransportStatus = gate.probeAap(address, force)

    /**
     * Reports that an Apple protocol channel is live for an accessory.
     *
     * A running session is direct evidence, where a probe is only a prediction — so this
     * unlocks the features the channel carries even if an earlier probe had failed.
     */
    fun onAapChannelOpen(address: String) = gate.recordChannelOpen(address)

    /** Reports that the channel dropped, so the next attempt is made afresh. */
    fun onAapChannelClosed(
        address: String,
        reason: String,
    ) {
        seenHidShapes.clear()
        gate.recordChannelClosed(address, reason)
    }

    /** Restarts the scan the radio refused. Safe to call when nothing is wrong. */
    fun retryScan() {
        retries.value += 1
    }

    /**
     * Feeds a sighting into the pipeline as though the radio had heard it.
     *
     * Used by the debug build's adb surface to drive states that cannot be produced on
     * demand. It goes through exactly the same accumulate-age-decorate path as a real
     * advertisement, which is the point: a test that bypasses the pipeline proves
     * nothing about the pipeline.
     */
    fun injectSighting(sighting: PodSighting): Boolean = injected.tryEmit(sighting)

    /** Folds a decoded AAP message into the overlay for one accessory. */
    fun onAapEvent(
        address: String,
        event: AapEvent,
    ) {
        when (event) {
            is AapEvent.Unknown -> {
                diagnostics.record(
                    DiagnosticCategory.UNKNOWN_TRAFFIC,
                    "Undecoded AAP packet from $address",
                    DiagnosticsLog.hex(event.raw),
                )
            }

            is AapEvent.UnhandledControl -> {
                diagnostics.record(
                    DiagnosticCategory.UNKNOWN_TRAFFIC,
                    "Control ${event.command.name} has no decoder yet",
                    DiagnosticsLog.hex(event.payload),
                )
            }

            // By shape, never by body. This is the one place FR-023 ("no heart rate in
            // any diagnostic") and Principle IV ("unknown traffic is never dropped")
            // genuinely collide, and recording *that* a report arrived and what it was —
            // rather than what it said — is what lets both hold (R-9).
            is AapEvent.UnhandledHidReport -> {
                // Once per distinct shape, not once per report. These arrive at the
                // sensor's cadence — 152 identical lines in one session evicted the whole
                // rest of the log, including the channel history needed to work out why
                // they were undecodable in the first place. Principle IV asks that nothing
                // be dropped silently, and the shape is what carries the information; the
                // hundred and fifty-first copy of it carries none.
                val shape = "%02X/%d/%s".format(event.serviceId, event.reportId, event.reason)
                if (seenHidShapes.add(shape)) {
                    diagnostics.record(
                        DiagnosticCategory.UNKNOWN_TRAFFIC,
                        "HID report on service 0x%02X has no decoder".format(event.serviceId),
                        "report id ${event.reportId}, ${event.length} bytes, ${event.reason}. " +
                            "Repeats are not logged again.",
                    )
                }
            }

            is AapEvent.HidServices -> {
                diagnostics.record(
                    DiagnosticCategory.TRANSPORT,
                    "Accessory described ${event.services.size} HID services",
                    event.services.joinToString { service ->
                        "0x%02X %s".format(service.id, service.name ?: "unnamed")
                    },
                )
            }

            // AapEvent.HeartRateReport deliberately falls through to nothing. A reading
            // reaches the controller and the screen; it reaches no log, at any level.
            else -> {
                Unit
            }
        }
        overlays.update { current ->
            val existing = current[address] ?: PodOverlay.Empty
            current + (address to existing.reduce(event))
        }
        _aapEvents.tryEmit(AddressedAapEvent(address, event))
    }

    /**
     * Publishes what the heart-rate session currently is, and the counts behind it.
     *
     * A state, never a sample: the number lives inside [HeartRateState.Measuring] or
     * nowhere, so a screen, a health-store writer and a diagnostic cannot each decide
     * for themselves whether a reading is worth showing (AD-3).
     */
    fun onHeartRateState(
        address: String,
        state: HeartRateState,
        sensing: HeartRateSensing,
    ) {
        overlays.update { current ->
            val existing = current[address] ?: PodOverlay.Empty
            current + (address to existing.copy(heartRate = state, heartRateSensing = sensing))
        }
    }

    /**
     * Drops everything the connected transports contributed for one accessory. Called
     * when a session ends, so a stale noise-control mode is not shown as current.
     */
    fun clearOverlay(address: String) {
        overlays.update { it - address }
    }

    /**
     * The key to file a sighting under: the accessory's stable identity, unless taking it
     * would merge two different accessories.
     *
     * [PodIdentity] answers "which bond does this advertisement belong to", and with a
     * single paired Apple accessory the honest answer for *any* Apple advertisement is
     * that one bond. That is right for the case it was built for — the same earbuds
     * advertising from a rotated address — and wrong when a second Apple accessory is in
     * the room, which on a real phone is most of the time. Collapsing them produced a pod
     * whose model changed with whichever beacon landed last, and a heart-rate feature that
     * reported itself unsupported on AirPods Pro 3.
     *
     * So the model decides. Same model, same accessory: merge, and the rotation is
     * invisible. Different model: keep them apart, and accept that rotation is only
     * absorbed for one accessory — the honest limit of what a private address allows.
     */
    private fun stableKeyFor(
        known: Map<String, PodState>,
        fresh: PodState,
    ): String {
        val key = identity.stableKey(fresh.address, fresh.model)
        if (key == fresh.address) return key
        val existing = known[key] ?: return key
        return if (existing.model == fresh.model) key else fresh.address
    }

    /**
     * Folds one sighting in, under the accessory's stable key rather than the address it
     * happened to advertise from.
     *
     * The substitution happens here and nowhere else, deliberately: every consumer
     * downstream — the overlay, the transport gate, the heart-rate session — keys off
     * `PodState.address`, so resolving once at the point of accumulation makes all of them
     * agree without any of them knowing that rotation exists. Doing it later would leave
     * each consumer to remember, and the one that forgot would fail silently.
     */
    private fun accumulate(
        known: Map<String, PodState>,
        sighting: PodSighting,
    ): Map<String, PodState> {
        val fresh = sighting.toPodState().let { it.copy(address = stableKeyFor(known, it)) }
        val existing = known[fresh.address]
        val updated =
            existing?.copy(
                model = fresh.model,
                battery = fresh.battery,
                earDetection = fresh.earDetection,
                rssi = fresh.rssi,
                lastSeenEpochMillis = fresh.lastSeenEpochMillis,
            ) ?: fresh
        return known + (fresh.address to updated)
    }

    /**
     * An Apple accessory whose model id is not in the registry is not shown, but it is
     * recorded: those ids are how the registry grows.
     */
    private fun noteUnknownModel(sighting: PodSighting) {
        if (sighting.beacon.model != PodModel.UNKNOWN) return
        diagnostics.record(
            DiagnosticCategory.UNKNOWN_DEVICE,
            "Unrecognised Apple model id 0x%04X".format(sighting.beacon.rawModelId),
            "Address ${sighting.address}, RSSI ${sighting.rssi} dBm",
        )
    }

    private companion object {
        /** Roughly ten missed advertisement intervals. */
        const val STALE_AFTER_MILLIS = 30_000L

        /** How often staleness is re-evaluated when nothing is being seen. */
        const val AGE_TICK_MILLIS = 5_000L

        /** How long the shared scan outlives its last subscriber. */
        const val SHARE_TIMEOUT_MILLIS = 5_000L

        /** Room for a short burst of injected sightings without blocking the caller. */
        const val INJECT_BUFFER = 16

        /**
         * A second of heart-rate reports plus whatever else the channel says.
         * Buffered so a slow subscriber drops events rather than stalling the reader.
         */
        const val AAP_EVENT_BUFFER = 64
    }
}
