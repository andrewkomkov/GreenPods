package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Why the AAP transport is or is not usable on this device.
 *
 * This is surfaced verbatim in the diagnostics screen, because "ANC control is
 * missing" is otherwise indistinguishable from a bug.
 */
sealed interface AapAvailability {
    data object Available : AapAvailability

    /** `createInsecureL2capChannel` exists but rejects PSM 0x1001. */
    data object PsmRejected : AapAvailability

    /** The API itself is missing — device predates API 29. */
    data object ApiUnavailable : AapAvailability

    /** The socket opened but the buds refused the negotiated channel mode. */
    data object ChannelModeRefused : AapAvailability

    /** Bluetooth is off, or CONNECT permission has not been granted. */
    data object NotPermitted : AapAvailability

    data class Failed(
        val reason: String,
    ) : AapAvailability

    val isAvailable: Boolean get() = this is Available
}

/**
 * Apple Accessory Protocol client.
 *
 * Opens an L2CAP channel on PSM [AapProtocol.PSM], performs the handshake, and
 * exposes decoded packets as a flow.
 *
 * **This will fail on most stock Android devices** — see `docs/protocol-research.md`.
 * Callers must treat [probe] as authoritative and never assume the channel opens.
 * Failure is a normal, expected outcome, not an error state to report as a crash.
 */
class AapTransport(
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private var socket: BluetoothSocket? = null

    /**
     * Attempts to open a channel and immediately closes it, reporting why it did
     * or did not work. Cheap enough to run when a device is first seen.
     */
    suspend fun probe(device: BluetoothDevice): AapAvailability =
        withContext(ioDispatcher) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext AapAvailability.ApiUnavailable
            try {
                openSocket(device).use { it.connect() }
                AapAvailability.Available
            } catch (e: SecurityException) {
                AapAvailability.NotPermitted
            } catch (e: IllegalArgumentException) {
                // Thrown by the public API's PSM range validation (0x0001..0x00FF).
                AapAvailability.PsmRejected
            } catch (e: IOException) {
                val message = e.message.orEmpty()
                if (message.contains("channel type", ignoreCase = true) ||
                    message.contains("not support", ignoreCase = true)
                ) {
                    AapAvailability.ChannelModeRefused
                } else {
                    AapAvailability.Failed(message.ifBlank { "L2CAP connect failed" })
                }
            } catch (e: ReflectiveOperationException) {
                AapAvailability.Failed("Hidden L2CAP API unavailable: ${e.message}")
            }
        }

    /**
     * Connects, handshakes, subscribes to notifications, and emits every packet the
     * accessory sends. The channel is closed when collection stops.
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Flow<ByteArray> =
        callbackFlow {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                close(UnsupportedOperationException("L2CAP sockets require API 29"))
                return@callbackFlow
            }
            val bluetoothSocket = openSocket(device)
            socket = bluetoothSocket
            bluetoothSocket.connect()

            val output = bluetoothSocket.outputStream
            output.write(AapProtocol.HANDSHAKE)
            output.write(AapProtocol.SET_HOST_CAPABILITIES)
            output.write(AapProtocol.REQUEST_NOTIFICATIONS)
            output.flush()

            val reader =
                CoroutineScope(ioDispatcher).launch {
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    try {
                        while (true) {
                            val read = bluetoothSocket.inputStream.read(buffer)
                            if (read <= 0) break
                            trySend(buffer.copyOf(read))
                        }
                    } catch (e: IOException) {
                        Log.d(TAG, "AAP channel closed: ${e.message}")
                    }
                    close()
                }

            awaitClose {
                reader.cancel()
                runCatching { bluetoothSocket.close() }
                socket = null
            }
        }.flowOn(ioDispatcher)

    /** Writes a raw AAP packet. No-op when the channel is not open. */
    suspend fun send(packet: ByteArray): Boolean =
        withContext(ioDispatcher) {
            val stream = socket?.outputStream ?: return@withContext false
            try {
                stream.write(packet)
                stream.flush()
                true
            } catch (e: IOException) {
                Log.w(TAG, "AAP write failed", e)
                false
            }
        }

    /**
     * Opens the L2CAP socket.
     *
     * The public API is tried first. It rejects PSM 0x1001 outright, so the hidden
     * `createInsecureL2capSocket` is used as a fallback — that is the same route
     * LibrePods takes, and it is what the Magisk stack patch makes actually work.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openSocket(device: BluetoothDevice): BluetoothSocket =
        try {
            device.createInsecureL2capChannel(AapProtocol.PSM)
        } catch (e: IllegalArgumentException) {
            hiddenL2capSocket(device)
        }

    private fun hiddenL2capSocket(device: BluetoothDevice): BluetoothSocket =
        try {
            val method =
                BluetoothDevice::class.java.getMethod(
                    "createInsecureL2capSocket",
                    Int::class.javaPrimitiveType,
                )
            method.invoke(device, AapProtocol.PSM) as BluetoothSocket
        } catch (e: InvocationTargetException) {
            throw (e.cause as? IOException ?: IOException("createInsecureL2capSocket failed", e))
        }

    private companion object {
        const val TAG = "AapTransport"
        const val READ_BUFFER_BYTES = 1024
    }
}
