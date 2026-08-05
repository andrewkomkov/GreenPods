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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private val scope: CoroutineScope,
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) {
    private var registered = false
    private var attempts: Job? = null

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
        attempts?.cancel()
        attempts = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    private fun connectAlreadyConnected() {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val connected =
            runCatching {
                manager.adapter
                    ?.bondedDevices
                    .orEmpty()
                    .filter(resolver::isPodCandidate)
            }.getOrDefault(emptyList())
        connected.firstOrNull()?.let(::onConnected)
    }

    private companion object {
        /**
         * About three seconds of trying, at a cadence that costs nothing.
         *
         * Sized against the window rather than against politeness: the announcement
         * arrives within the first seconds of the link and there is no point still
         * knocking after it has gone.
         */
        const val MAX_ATTEMPTS = 12
        const val RETRY_DELAY_MILLIS = 250L

        /**
         * How long one attempt waits for the channel to carry traffic.
         *
         * Short on purpose: a refused channel must be discovered and retried inside the
         * window, not waited out. The transport's own default is sized for a user who
         * pressed a button, which is the opposite situation.
         */
        const val READY_TIMEOUT_MILLIS = 700L

        /**
         * How long to let the channel settle before asking the accessory to describe
         * itself. Long enough that the request is not simply the handshake's again, short
         * enough to stay inside the connection the announcement belongs to.
         */
        const val SETTLE_BEFORE_REQUEST_MILLIS = 1_500L
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(device: BluetoothDevice) {
        // Only the accessory this app is about. Every Bluetooth device on the phone
        // broadcasts here, and opening an L2CAP channel to a game controller is not
        // harmless — it is exactly what this app did until `isPodCandidate` existed,
        // because `resolve` exact-matches any bonded address and so passed everything.
        if (!resolver.isPodCandidate(device)) return

        // No model to offer: this is a bonded device, not an advertisement, so it resolves
        // by exact address and needs no corroboration.
        val key = identity.stableKey(device.address, model = null)
        attempts?.cancel()
        attempts = scope.launch { openInsistently(key) }
    }

    /**
     * Opens the channel, retrying briefly, because both failure modes are real.
     *
     * `ACTION_ACL_CONNECTED` means the *link* is up, not that the accessory will accept
     * an L2CAP channel yet: connecting on the broadcast itself fails with "ACL connection
     * failed", observed on every reconnection. But waiting a comfortable second instead
     * risks the opposite failure — the accessory announces its HID services once, shortly
     * after the link comes up, and a channel opened after that never learns which service
     * carries heart rate.
     *
     * So this retries fast and gives up early rather than backing off politely: the whole
     * useful window is the first few seconds, and after that there is nothing left to be
     * late for.
     */
    private suspend fun openInsistently(address: String) {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (gateway.openAndAwait(address, READY_TIMEOUT_MILLIS)) {
                // Opening is not enough. A channel opened at the instant the link comes
                // up gets the accessory's whole configuration and *not* a word about its
                // HID services — the announcement follows a request made once the channel
                // has settled, and it happens once per connection or never. Verified on
                // hardware: listening alone yielded no descriptors across several
                // reconnections; one request produced ten 0x17 frames and a discovered
                // heart-rate service immediately.
                delay(SETTLE_BEFORE_REQUEST_MILLIS)
                gateway.requestNotifications()
                diagnostics.record(
                    DiagnosticCategory.TRANSPORT,
                    "Opened the Apple protocol channel on connect",
                    // Worth a line: this is the difference between "heart rate will work
                    // on this connection" and "it cannot, and will look like a hang".
                    "Attempt ${attempt + 1}. The accessory announces its sensor services once per connection.",
                )
                return
            }
            delay(RETRY_DELAY_MILLIS)
        }
        diagnostics.record(
            DiagnosticCategory.TRANSPORT,
            "Could not open the Apple protocol channel on connect",
            "Gave up after $MAX_ATTEMPTS attempts. Heart rate cannot discover its sensor on this connection.",
        )
    }

    private fun onDisconnected(device: BluetoothDevice) {
        if (!resolver.isPodCandidate(device)) return
        attempts?.cancel()
        attempts = null
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
