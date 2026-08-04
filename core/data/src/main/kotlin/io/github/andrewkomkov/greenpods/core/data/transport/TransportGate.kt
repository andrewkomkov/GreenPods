package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapAvailability
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What came of trying to reach an accessory over the Apple protocol.
 *
 * "Could not even try" is kept distinct from "tried and was refused" because the two
 * need different things from the user — pair the buds, versus accept that this phone's
 * Bluetooth stack will not carry the channel.
 */
sealed interface ProbeOutcome {
    /** A channel attempt was actually made. */
    data class Attempted(
        val availability: AapAvailability,
    ) : ProbeOutcome

    /** No paired accessory to open a channel to. */
    data object NoPairedDevice : ProbeOutcome

    /** Several paired Apple accessories; a private address cannot say which advertised. */
    data class Ambiguous(
        val candidates: List<String>,
    ) : ProbeOutcome

    /** Bluetooth is off, or the Connect permission is missing. */
    data object Unavailable : ProbeOutcome
}

/**
 * Attempts the AAP channel for one accessory address.
 *
 * An interface rather than a direct call into `AapTransport`, so the gate — where all
 * the branching lives — can be tested for every outcome on a JVM, including the ones no
 * real phone can produce.
 */
fun interface AapProbe {
    suspend fun probe(address: String): ProbeOutcome
}

/**
 * The single place that knows which transports are live for an accessory, and why.
 *
 * Every capability the UI offers is derived from here. Screens never probe a transport
 * themselves — if they could, "is this feature available" would become a question each
 * screen answers slightly differently, which is exactly how a gated feature turns into
 * a bug report.
 *
 * Two rules are structural rather than advisory:
 *
 * - **Probe once.** A stock Android stack refuses the AAP channel every time, so
 *   retrying costs battery and buys nothing. A probe re-runs only when the caller
 *   explicitly asks (`force = true`), which is what the diagnostics re-check does.
 * - **Failure is data.** A refused channel produces a status with a sentence in it, not
 *   an exception.
 */
class TransportGate(
    private val diagnostics: DiagnosticsLog,
    private val aapProbe: AapProbe,
) {
    private val _aapStatuses = MutableStateFlow<Map<String, TransportStatus>>(emptyMap())

    /** AAP status per accessory address. An absent entry means "not probed". */
    val aapStatuses: StateFlow<Map<String, TransportStatus>> = _aapStatuses.asStateFlow()

    private val probeLock = Mutex()

    /**
     * Fills in [PodState.activeTransports] and [PodState.transportStatuses] for one
     * accessory. This is the only supported way to populate those fields.
     */
    fun decorate(pod: PodState): PodState {
        val statuses =
            listOf(
                TransportStatus.AdvertisementAvailable,
                gattStatus(pod),
                _aapStatuses.value[pod.address] ?: TransportStatus.notProbed(Transport.AAP_L2CAP),
            )
        return pod.copy(
            activeTransports = statuses.filter(TransportStatus::isAvailable).map(TransportStatus::transport).toSet(),
            transportStatuses = statuses,
        )
    }

    /**
     * Attempts the AAP channel and records the outcome. Safe to call repeatedly: the
     * cached status is returned unless [force] is set.
     */
    suspend fun probeAap(
        address: String,
        force: Boolean = false,
    ): TransportStatus =
        probeLock.withLock {
            val cached = _aapStatuses.value[address]
            if (cached != null && !force) return@withLock cached

            val status = describe(aapProbe.probe(address))

            diagnostics.record(
                category = DiagnosticCategory.TRANSPORT,
                message = "AAP probe for $address: ${status.availability.name.lowercase()}",
                detail = status.reason,
            )
            _aapStatuses.update { it + (address to status) }
            status
        }

    /**
     * Records that a channel is genuinely open, which outranks any probe.
     *
     * A probe opens a channel and closes it again; a session *is* the channel. If the two
     * ever disagree — a probe that failed while a session is carrying traffic — the
     * session is the one telling the truth, and a stale "unavailable" would otherwise
     * keep every feature locked while the accessory is actively being controlled.
     */
    fun recordChannelOpen(address: String) {
        _aapStatuses.update {
            it + (address to TransportStatus(Transport.AAP_L2CAP, TransportAvailability.AVAILABLE, CHANNEL_OPEN_REASON))
        }
    }

    /**
     * Records that the channel dropped.
     *
     * Deliberately *not* an "unavailable" verdict: a channel that opened once can open
     * again, and remembering a disconnect as a refusal would lock features that work.
     * Clearing the entry restores "not probed", so the next attempt actually happens.
     */
    fun recordChannelClosed(
        address: String,
        reason: String,
    ) {
        diagnostics.record(
            category = DiagnosticCategory.TRANSPORT,
            message = "AAP channel closed for $address",
            detail = reason,
        )
        _aapStatuses.update { it - address }
    }

    /** Drops cached probe results — e.g. after Bluetooth is switched back on. */
    fun invalidate() {
        _aapStatuses.value = emptyMap()
    }

    /**
     * GATT is a standard profile, so its availability is a property of the *model*
     * rather than of this phone: either the accessory exposes service 0x180D or it does
     * not. Powerbeats Pro 2 is currently the only Apple-family model that does.
     */
    private fun gattStatus(pod: PodState): TransportStatus =
        if (PodFeature.HEART_RATE_GATT in pod.model.features) {
            TransportStatus(
                transport = Transport.GATT,
                availability = TransportAvailability.AVAILABLE,
                reason = "This model exposes the standard Bluetooth heart-rate profile.",
            )
        } else {
            TransportStatus(
                transport = Transport.GATT,
                availability = TransportAvailability.UNAVAILABLE,
                reason = "${pod.model.displayName} does not expose the standard heart-rate profile.",
            )
        }

    private fun describe(outcome: ProbeOutcome): TransportStatus =
        when (outcome) {
            is ProbeOutcome.Attempted -> {
                describe(outcome.availability)
            }

            ProbeOutcome.NoPairedDevice -> {
                unavailable(
                    "No paired AirPods to open a channel to. Settings live behind a normal " +
                        "Bluetooth pairing, so pair them first — battery and ear detection " +
                        "work either way.",
                )
            }

            is ProbeOutcome.Ambiguous -> {
                unavailable(
                    "More than one paired Apple accessory (${outcome.candidates.joinToString(", ")}), " +
                        "and the advertisement uses a rotating private address, so GreenPods " +
                        "cannot tell which of them it came from.",
                )
            }

            ProbeOutcome.Unavailable -> {
                unavailable("Bluetooth is off, or the Connect permission has not been granted.")
            }
        }

    private fun unavailable(reason: String): TransportStatus =
        TransportStatus(Transport.AAP_L2CAP, TransportAvailability.UNAVAILABLE, reason)

    private fun describe(availability: AapAvailability): TransportStatus {
        val (state, reason) =
            when (availability) {
                AapAvailability.Available -> {
                    TransportAvailability.AVAILABLE to CHANNEL_OPEN_REASON
                }

                AapAvailability.PsmRejected -> {
                    TransportAvailability.UNAVAILABLE to
                        "This Android version refuses PSM 0x1001, the channel AirPods use for " +
                        "settings. Battery, ear detection and auto-pause are unaffected."
                }

                AapAvailability.ApiUnavailable -> {
                    TransportAvailability.UNAVAILABLE to
                        "L2CAP sockets need Android 10 or newer."
                }

                AapAvailability.ChannelModeRefused -> {
                    TransportAvailability.UNAVAILABLE to
                        "The buds refused the channel mode this phone's Bluetooth stack offers."
                }

                is AapAvailability.ReflectionBlocked -> {
                    TransportAvailability.UNAVAILABLE to
                        "This Android build will not let GreenPods build the secure channel " +
                        "AirPods require: reflective access to android.bluetooth is refused, and " +
                        "no public API creates that channel. Battery, ear detection and " +
                        "auto-pause are unaffected. (${availability.detail})"
                }

                is AapAvailability.ChannelNotEstablished -> {
                    TransportAvailability.UNAVAILABLE to
                        "Your AirPods are paired and connected, and this phone accepted the " +
                        "request — but the settings channel never came up. Battery, ear " +
                        "detection and auto-pause are unaffected. (${availability.route})"
                }

                AapAvailability.NotPermitted -> {
                    TransportAvailability.UNAVAILABLE to
                        "Bluetooth is off, or the Connect permission has not been granted."
                }

                is AapAvailability.Failed -> {
                    TransportAvailability.UNAVAILABLE to
                        "The L2CAP connection failed: ${availability.reason}"
                }
            }
        return TransportStatus(Transport.AAP_L2CAP, state, reason)
    }

    private companion object {
        const val CHANNEL_OPEN_REASON = "Channel open. Settings can be read and written."
    }
}
