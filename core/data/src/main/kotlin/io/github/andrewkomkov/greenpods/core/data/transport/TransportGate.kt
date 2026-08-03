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
 * Attempts the AAP channel for one accessory address.
 *
 * An interface rather than a direct call into [io.github.andrewkomkov.greenpods.core
 * .bluetooth.aap.AapTransport] so the gate — where all the branching lives — can be
 * tested for every outcome on a JVM, including the ones no real phone can produce.
 */
fun interface AapProbe {
    /** Returns the outcome, or null when the address cannot be resolved to a device. */
    suspend fun probe(address: String): AapAvailability?
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

            val status = aapProbe.probe(address)?.let(::describe) ?: unresolvable()

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

    private fun unresolvable(): TransportStatus =
        TransportStatus(
            transport = Transport.AAP_L2CAP,
            availability = TransportAvailability.UNAVAILABLE,
            reason =
                "These AirPods are not paired with this phone, so there is no device to " +
                    "open a channel to. Pair them in Bluetooth settings and check again.",
        )

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
