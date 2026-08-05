package io.github.andrewkomkov.greenpods.core.data.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapTransport
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog

/**
 * The real [AapProbe]: resolves an advertisement to a paired device and asks
 * [AapTransport] to open the channel.
 *
 * Everything here is Android plumbing; the decisions live in [TransportGate], and the
 * advertisement-to-bond correlation lives in [BondedPodResolver].
 */
class AndroidAapProbe(
    context: Context,
    private val diagnostics: DiagnosticsLog,
    private val transport: AapTransport =
        AapTransport(
            adapter = context.getSystemService(BluetoothManager::class.java)?.adapter,
        ),
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) : AapProbe {
    override suspend fun probe(address: String): ProbeOutcome =
        // No model: an accessory worth probing is one this phone is paired to, and those
        // are filed under the bonded address, which matches exactly. See BondedPodResolver.
        when (val resolution = resolver.resolve(address, advertisedModel = null)) {
            is BondedPodResolver.Resolution.Resolved -> {
                diagnostics.record(
                    DiagnosticCategory.TRANSPORT,
                    "Advertisement $address resolved to paired ${resolution.device.address}",
                    "AirPods advertise from a rotating private address; the channel is opened " +
                        "to the paired classic address.",
                )
                ProbeOutcome.Attempted(transport.probe(resolution.device))
            }

            BondedPodResolver.Resolution.NoCandidate -> {
                ProbeOutcome.NoPairedDevice
            }

            // There is a paired accessory, just not this one. From the caller's side that
            // is the same answer — this phone has no bond for the thing being probed — and
            // the reason is recorded rather than folded into a silent negative.
            is BondedPodResolver.Resolution.NotThisAccessory -> {
                diagnostics.record(
                    DiagnosticCategory.TRANSPORT,
                    "Not probing $address: it is not this phone's accessory",
                    "Advertised as ${resolution.advertisedModel}; the paired accessory is " +
                        "${resolution.bondedName}. Different product families, so the " +
                        "advertisement cannot be from the paired one.",
                )
                ProbeOutcome.NoPairedDevice
            }

            is BondedPodResolver.Resolution.Ambiguous -> {
                ProbeOutcome.Ambiguous(resolution.candidates)
            }

            BondedPodResolver.Resolution.Unavailable -> {
                ProbeOutcome.Unavailable
            }
        }
}
