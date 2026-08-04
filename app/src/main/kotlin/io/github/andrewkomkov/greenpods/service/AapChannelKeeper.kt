package io.github.andrewkomkov.greenpods.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.andrewkomkov.greenpods.core.data.control.AapControlGateway
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.data.transport.PodIdentity

/**
 * Holds the Apple protocol channel open across the accessory's reconnections.
 *
 * **Why this has to exist.** The accessory announces its HID services — the descriptors
 * that say which service id carries heart rate — exactly once, moments after the
 * Bluetooth link comes up, unprompted and never again. A client that opens the L2CAP
 * channel later has already missed them, and no request is known that asks for them
 * again. Opening the channel lazily on the first write, which is what the gateway did on
 * its own, is therefore always too late for heart rate: the write that would trigger it
 * is the start command, and the start command needs the service id the announcement
 * carries. The result was a session that sat in `STARTING` for ever, on hardware that
 * was working perfectly.
 *
 * So the trigger is the ACL connection itself, not anything the app wants to send. When
 * the earbuds connect, the channel opens immediately and simply listens.
 *
 * `ACTION_ACL_CONNECTED` rather than the advertisement stream, because the advertisement
 * is both late and lossy — it arrives when the accessory next chooses to advertise, which
 * can be seconds after the link is up, and by then the announcement is gone.
 */
class AapChannelKeeper(
    private val context: Context,
    private val gateway: AapControlGateway,
    private val identity: PodIdentity,
    private val diagnostics: DiagnosticsLog,
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) {
    private var registered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val device = intent?.bluetoothDevice() ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(device)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(device)
                }
            }
        }

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The buds may already be connected when this starts — on a service restart, or
        // when the user enables heart rate with them in their ears. That link's
        // announcement is long gone, so this open will not discover anything by itself;
        // it is here so the channel is live and ready, and so everything that does not
        // depend on discovery keeps working until the next reconnection.
        connectAlreadyConnected()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    private fun connectAlreadyConnected() {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected =
            runCatching {
                manager.adapter?.bondedDevices.orEmpty().filter { device ->
                    resolver.resolve(device.address) is BondedPodResolver.Resolution.Resolved
                }
            }.getOrDefault(emptyList())
        connected.firstOrNull()?.let(::onConnected)
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(device: BluetoothDevice) {
        // Only the accessory this app is about. Every Bluetooth device on the phone
        // broadcasts here, and opening an L2CAP channel to a keyboard is not harmless.
        if (resolver.resolve(device.address) !is BondedPodResolver.Resolution.Resolved) return

        val key = identity.stableKey(device.address)
        val opened = gateway.connect(key)
        diagnostics.record(
            DiagnosticCategory.TRANSPORT,
            if (opened) "Opening the Apple protocol channel on connect" else "Could not open the channel on connect",
            // The reason this is worth a line: it is the difference between "heart rate
            // will work on this connection" and "it cannot, and will look like a hang".
            "The accessory announces its sensor services once per connection.",
        )
    }

    private fun onDisconnected(device: BluetoothDevice) {
        if (resolver.resolve(device.address) !is BondedPodResolver.Resolution.Resolved) return
        gateway.disconnect()
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
