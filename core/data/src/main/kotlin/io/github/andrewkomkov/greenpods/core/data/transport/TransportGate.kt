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
                    TransportAvailability.AVAILABLE to
                        "Channel open. Settings can be read and written."
                }

                AapAvailability.PsmRejected -> {
                    TransportAvailability.UNAVAILABLE to
                        "Android refuses PSM 0x1001, the channel AirPods use for settings. " +
                        "Reaching it needs a patched Bluetooth stack, which means root."
                }

                AapAvailability.ApiUnavailable -> {
                    TransportAvailability.UNAVAILABLE to
                        "L2CAP sockets need Android 10 or newer."
                }

                AapAvailability.ChannelModeRefused -> {
                    TransportAvailability.UNAVAILABLE to
                        "The buds refused the channel mode this phone's Bluetooth stack offers. " +
                        "This is the usual outcome on stock Android."
                }

                is AapAvailability.ChannelNotEstablished -> {
                    TransportAvailability.UNAVAILABLE to
                        "Your AirPods are paired and connected, and this phone accepted the " +
                        "request — but the settings channel never came up. That is what a stock " +
                        "Android Bluetooth stack does with PSM 0x1001; reaching it needs a " +
                        "patched stack, which means root. Battery, ear detection and auto-pause " +
                        "are unaffected. (${availability.route})"
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
}
