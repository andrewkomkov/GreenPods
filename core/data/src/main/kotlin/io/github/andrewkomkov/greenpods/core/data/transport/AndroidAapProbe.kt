package io.github.andrewkomkov.greenpods.core.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapAvailability
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapTransport
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog

/**
 * The real [AapProbe]: resolves an address to a Bluetooth device and asks
 * [AapTransport] to open the channel.
 *
 * Everything here is Android plumbing; the decisions live in [TransportGate].
 */
class AndroidAapProbe(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
    private val transport: AapTransport = AapTransport(),
) : AapProbe {
    override suspend fun probe(address: String): AapAvailability? {
        val device = remoteDevice(address) ?: return null
        return transport.probe(device)
    }

    /**
     * Resolves an address to a device, preferring a *bonded* one.
     *
     * AirPods advertise from a resolvable private address that is not their classic
     * Bluetooth address, so the address a scan produces usually cannot be connected to
     * at all. When the buds have been paired normally the bonded entry is the one worth
     * trying; when they have not, there is nothing to try, and saying so is more useful
     * than a generic failure.
     */
    @SuppressLint("MissingPermission")
    private fun remoteDevice(address: String): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return try {
            adapter.bondedDevices?.firstOrNull { it.address == address }
        } catch (e: SecurityException) {
            diagnostics.record(
                DiagnosticCategory.TRANSPORT,
                "Bluetooth Connect permission missing",
                e.message.orEmpty(),
            )
            null
        }
    }
}
