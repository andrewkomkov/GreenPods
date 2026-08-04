package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * The channel could not even be constructed, because this Android build refuses
     * reflective access to `android.bluetooth` and no public API builds the secure
     * channel AirPods require. See [HiddenApiAccess].
     */
    data class ReflectionBlocked(
        val detail: String,
    ) : AapAvailability

    /**
     * The socket was created and the connect call was accepted, but no channel ever
     * came up — the first read returns -1 immediately.
     *
     * This is what an unpatched stack does when everything else is right: the buds are
     * paired, connected and playing audio, and the L2CAP channel still never forms.
     * [route] records which socket API got that far, which is the part worth reporting.
     */
    data class ChannelNotEstablished(
        val route: String,
    ) : AapAvailability

    /** Bluetooth is off, or CONNECT permission has not been granted. */
    data object NotPermitted : AapAvailability

    data class Failed(
        val reason: String,
    ) : AapAvailability

    val isAvailable: Boolean get() = this is Available
}

/**
 * Turns whatever a socket read happened to return into whole AAP frames.
 *
 * `read()` returns bytes, not messages. Until this existed the transport emitted one
 * packet per read and hoped they lined up — which they did, right up to the point where
 * they would not: the 2026-08-04 capture saw a 996-byte descriptor frame against a
 * 1024-byte buffer, so one extra service on some future model splits a frame and every
 * decoder downstream silently misparses it. Nothing throws in that world; the numbers
 * are just wrong.
 *
 * Only opcode `0x17` declares its own length — a 16-bit little-endian count at offset 10
 * covering the protobuf body, so a whole frame is `12 + length` bytes. Every other opcode
 * carries no length at all, so for those this does what the transport always did and
 * emits what arrived. Guessing at boundaries for them by hunting for the next header
 * would split a payload that happened to contain `04 00 04 00`, which is a worse failure
 * than the one being fixed.
 *
 * Pure and stateful-by-instance, so the interesting cases — a frame in two halves, two
 * frames in one read, a length that could not possibly be right — are unit-testable
 * without a socket.
 */
internal class AapFrameReassembler(
    /**
     * Beyond this a declared length is not a long frame, it is a misparse. Surfacing the
     * bytes is then better than buffering forever waiting for a frame that will never
     * complete.
     */
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
) {
    private var pending: ByteArray = ByteArray(0)

    /** Feeds [length] bytes from [chunk] in, and returns every whole frame now available. */
    fun offer(
        chunk: ByteArray,
        length: Int = chunk.size,
    ): List<ByteArray> {
        pending = if (pending.isEmpty()) chunk.copyOf(length) else pending + chunk.copyOf(length)

        val frames = mutableListOf<ByteArray>()
        while (true) {
            val frame = takeFrame() ?: break
            frames += frame
        }
        return frames
    }

    /** Drops anything half-received. Called when the channel goes away. */
    fun reset() {
        pending = ByteArray(0)
    }

    /** Whatever is buffered and not yet a whole frame. Only interesting to tests. */
    val bufferedBytes: Int get() = pending.size

    private fun takeFrame(): ByteArray? {
        if (pending.size < OPCODE_END) return null

        // Not our framing at all. Hand it on rather than dropping it — it becomes
        // AapEvent.Unknown, which is where unrecognised traffic belongs (Principle IV).
        if (!pending.copyOfRange(0, 4).contentEquals(AapProtocol.HEADER)) return drain()

        val opcode = (pending[4].toInt() and 0xFF) or ((pending[5].toInt() and 0xFF) shl 8)
        if (opcode != Opcode.HEAD_TRACKING.value) return drain()

        if (pending.size < BODY_OFFSET) return null
        val declared =
            (pending[LENGTH_OFFSET].toInt() and 0xFF) or ((pending[LENGTH_OFFSET + 1].toInt() and 0xFF) shl 8)
        val total = BODY_OFFSET + declared
        if (total > maxFrameBytes) return drain()
        if (pending.size < total) return null

        val frame = pending.copyOfRange(0, total)
        pending = pending.copyOfRange(total, pending.size)
        return frame
    }

    private fun drain(): ByteArray? {
        if (pending.isEmpty()) return null
        val all = pending
        pending = ByteArray(0)
        return all
    }

    private companion object {
        const val OPCODE_END = 6

        /** Header, opcode and the 4-byte `00 00 10 00` prefix. */
        const val LENGTH_OFFSET = 10
        const val BODY_OFFSET = 12

        /**
         * The declared length is 16 bits, so 65 535 is expressible — but the largest frame
         * ever observed was 996 bytes and the largest single report the accessory declares
         * is 601. Eight kilobytes is generous by an order of magnitude and still small
         * enough that a misparse is caught in one read rather than buffered.
         */
        const val MAX_FRAME_BYTES = 8_192
    }
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
    /**
     * Needed only by the newest hidden-constructor signature, which takes the adapter as
     * its first argument. Null simply skips that signature.
     */
    private val adapter: BluetoothAdapter? = null,
) {
    private var socket: BluetoothSocket? = null

    /**
     * Whether the channel is up and writable.
     *
     * [connect] returns a cold flow, so the socket does not exist until something starts
     * collecting it — a write issued straight after subscribing would otherwise find no
     * socket and be dropped. Callers wait on this instead of guessing at a delay.
     */
    private val ready = MutableStateFlow(false)

    /**
     * Suspends until the channel is writable, or until [timeoutMillis] passes.
     *
     * Returns false on timeout rather than throwing: a channel that never comes up is an
     * ordinary outcome on hardware that refuses it.
     */
    suspend fun awaitReady(timeoutMillis: Long = READY_TIMEOUT_MILLIS): Boolean =
        withTimeoutOrNull(timeoutMillis) { ready.first { it } } ?: false

    /**
     * Which socket API actually produced the channel on the last attempt.
     *
     * Reported in failures because it is the single most useful fact when someone
     * sends in a diagnostic: whether this Android version still rejects PSM 0x1001
     * outright, or accepts the call and fails later, tells you which of two completely
     * different problems you are looking at.
     */
    @Volatile
    var lastSocketRoute: String = "not attempted"
        private set

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
                when {
                    message.contains("channel type", ignoreCase = true) ||
                        message.contains("not support", ignoreCase = true) -> {
                        AapAvailability.ChannelModeRefused
                    }

                    // What a stock stack actually produces: the socket opens, the
                    // accessory never completes the channel, and the first read returns
                    // -1. Observed on Pixel 8 / Android 17 with AirPods Pro 3 paired and
                    // connected — see docs/protocol-research.md.
                    message.contains("read failed", ignoreCase = true) ||
                        message.contains("socket might closed", ignoreCase = true) -> {
                        AapAvailability.ChannelNotEstablished(lastSocketRoute)
                    }

                    else -> {
                        AapAvailability.Failed("${message.ifBlank { "L2CAP connect failed" }} [$lastSocketRoute]")
                    }
                }
            } catch (e: ReflectiveOperationException) {
                AapAvailability.ReflectionBlocked(e.message.orEmpty())
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
            ready.value = true

            val reassembler = AapFrameReassembler()
            val framesDelivered =
                java.util.concurrent.atomic
                    .AtomicInteger(0)

            // A channel that opens and then says nothing is dead, and it does not
            // announce itself as dead: the socket stays open, writes are accepted, and
            // reads simply never return anything. Seen twice on hardware — once with a
            // second app holding the channel, once after a blocked write left the
            // accessory's end wedged — and both times it looked exactly like a working
            // channel with a quiet accessory.
            //
            // There is no such thing as a quiet accessory here. The handshake is answered
            // with the whole configuration within a second, every time. So silence past
            // this point is a diagnosis, and failing fast lets the caller reopen instead
            // of leaving the user to reconnect the earbuds by hand.
            val watchdog =
                CoroutineScope(ioDispatcher).launch {
                    delay(SILENT_CHANNEL_TIMEOUT_MILLIS)
                    if (framesDelivered.get() == 0) {
                        Log.w(TAG, "AAP channel opened but delivered nothing; closing it")
                        close(
                            IOException(
                                "The Apple protocol channel opened but the accessory sent nothing. " +
                                    "Another app may be holding the channel, or it needs reconnecting.",
                            ),
                        )
                        runCatching { bluetoothSocket.close() }
                    }
                }

            val reader =
                CoroutineScope(ioDispatcher).launch {
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    var delivered = 0
                    var failure: Throwable? = null
                    try {
                        while (true) {
                            val read = bluetoothSocket.inputStream.read(buffer)
                            if (read <= 0) break
                            reassembler.offer(buffer, read).forEach { frame ->
                                delivered++
                                framesDelivered.incrementAndGet()
                                logFrame("rx", frame)
                                trySend(frame)
                            }
                        }
                    } catch (e: IOException) {
                        Log.d(TAG, "AAP channel closed: ${e.message}")
                    }
                    // A channel that connected, accepted the handshake and then ended
                    // without ever delivering a frame is not an accessory with nothing to
                    // say — on this transport it is very nearly always **another app
                    // already holding PSM 0x1001**. Only one client may, and the loser
                    // gets exactly this: a successful connect, writes that do not throw,
                    // and a silent EOF a few seconds later. Reported as a distinct cause
                    // because the alternative is a channel that looks like it is working
                    // while nothing will ever arrive on it (Principle II).
                    if (delivered == 0) {
                        failure =
                            IOException(
                                "The Apple protocol channel opened but the accessory sent nothing before " +
                                    "closing it. Another app is most likely holding the channel — only one " +
                                    "may at a time.",
                            )
                        Log.w(TAG, "AAP channel delivered no frames; suspecting another client")
                    }
                    close(failure)
                }

            awaitClose {
                ready.value = false
                watchdog.cancel()
                reader.cancel()
                reassembler.reset()
                runCatching { bluetoothSocket.close() }
                socket = null
            }
        }.flowOn(ioDispatcher)

    /** Writes a raw AAP packet. No-op when the channel is not open. */
    suspend fun send(packet: ByteArray): Boolean =
        withContext(ioDispatcher) {
            val stream = socket?.outputStream ?: return@withContext false
            try {
                logFrame("tx", packet)
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
     * The channel that works is **secure** — authenticated and encrypted — and carries
     * Apple's AAP service UUID. `createInsecureL2capChannel` builds neither of those, and
     * an insecure channel is exactly what the accessory drops on the floor: the connect
     * is accepted and the first read returns -1. That symptom was read as "the stack
     * refuses PSM 0x1001 without a Magisk patch" for the whole life of this project, and
     * it was the wrong conclusion — the request was simply the wrong shape.
     *
     * No public API constructs that channel, so the hidden [BluetoothSocket] constructor
     * is used, trying the signatures across Android versions in turn. This is the same
     * route LibrePods takes, and it needs no root.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openSocket(device: BluetoothDevice): BluetoothSocket =
        try {
            secureL2capSocket(device)
        } catch (e: ReflectiveOperationException) {
            // Nothing matched — fall back to the public API so the failure is still
            // reported against a real attempt rather than a missing constructor.
            device.createInsecureL2capChannel(AapProtocol.PSM).also {
                lastSocketRoute = "public createInsecureL2capChannel (reflection unavailable)"
            }
        }

    /**
     * Builds a secure L2CAP socket on [AapProtocol.PSM] through whichever hidden
     * constructor this Android version exposes.
     *
     * The signature has been reshuffled repeatedly across releases, so all known forms
     * are tried in order of how recent they are. The constant part is the arguments:
     * type 3 (L2CAP), auth `true`, encrypt `true`, PSM `0x1001`, and [APPLE_AAP_UUID].
     */
    private fun secureL2capSocket(device: BluetoothDevice): BluetoothSocket {
        // Without this the constructor lookup below fails with NoSuchMethodException even
        // though the constructor exists — see HiddenApiAccess.
        val access = HiddenApiAccess.ensureBluetoothSocketReachable()
        if (!access.isUsable) {
            lastSocketRoute = "blocked by non-SDK restriction"
            throw NoSuchMethodException("android.bluetooth is not reflectable: $access")
        }

        val uuid = ParcelUuid.fromString(APPLE_AAP_UUID)
        val psm = AapProtocol.PSM
        val candidates: List<Pair<String, Array<Any>>> =
            buildList {
                adapter?.let {
                    add("adapter+device (Android 16 QPR3+)" to arrayOf(it, device, L2CAP_TYPE, true, true, psm, uuid))
                }
                add("device,type,auth,encrypt,psm,uuid" to arrayOf(device, L2CAP_TYPE, true, true, psm, uuid))
                add("device,type,fd,auth,encrypt,psm,uuid" to arrayOf(device, L2CAP_TYPE, 1, true, true, psm, uuid))
                add("type,fd,auth,encrypt,device,psm,uuid" to arrayOf(L2CAP_TYPE, 1, true, true, device, psm, uuid))
                add("type,auth,encrypt,device,psm,uuid" to arrayOf(L2CAP_TYPE, true, true, device, psm, uuid))
            }

        var lastFailure: Exception? = null
        for ((name, args) in candidates) {
            try {
                val parameterTypes = args.map { it::class.javaPrimitiveType ?: it::class.java }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*parameterTypes)
                constructor.isAccessible = true
                return (constructor.newInstance(*args) as BluetoothSocket).also {
                    lastSocketRoute = "secure L2CAP via hidden constructor [$name]"
                }
            } catch (e: NoSuchMethodException) {
                lastFailure = e
            } catch (e: InvocationTargetException) {
                throw (e.cause as? IOException ?: IOException("L2CAP socket construction failed", e))
            } catch (e: ReflectiveOperationException) {
                lastFailure = e
            }
        }
        // Printed only when every signature missed, because it is then the one fact that
        // says how a new Android release reshuffled the constructor.
        val available =
            BluetoothSocket::class.java.declaredConstructors.joinToString("; ") { constructor ->
                constructor.parameterTypes.joinToString(", ") { it.simpleName }
            }
        Log.w(TAG, "No BluetoothSocket constructor matched. Available: $available")
        throw NoSuchMethodException(
            "No known BluetoothSocket constructor matched (tried ${candidates.size}): ${lastFailure?.message}",
        )
    }

    /**
     * Logs one frame in hex, when frame logging has been turned on for this tag:
     *
     * ```
     * adb shell setprop log.tag.AapTransport DEBUG
     * ```
     *
     * The wire format is reverse-engineered, so the raw bytes are the only ground truth
     * when a command is accepted and nothing observable happens. It is off by default
     * because these frames carry serial numbers and the accessory's whole configuration.
     */
    private fun logFrame(
        direction: String,
        packet: ByteArray,
    ) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return

        // Heart-rate reports are the one thing that never reaches this log, at any level.
        // FR-023 says no heart rate appears in any diagnostic path, and this log is the
        // most diagnostic path there is — people paste it into bug reports. Its shape is
        // still recorded, so a report arriving is still visible; only the body is not.
        if (isHidInputReport(packet)) {
            Log.d(TAG, "$direction ${packet.size} bytes, HID input report (body withheld)")
            return
        }
        Log.d(TAG, "$direction ${packet.joinToString(" ") { "%02X".format(it) }}")
    }

    /** True for a `0x17` frame carrying protobuf field 7 — a sensor report. */
    private fun isHidInputReport(packet: ByteArray): Boolean {
        if (packet.size <= HID_BODY_OFFSET) return false
        if (!packet.copyOfRange(0, 4).contentEquals(AapProtocol.HEADER)) return false
        val opcode = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        if (opcode != Opcode.HEAD_TRACKING.value) return false
        return HidDescriptorParser.hasInputReport(packet.copyOfRange(HID_BODY_OFFSET, packet.size))
    }

    private companion object {
        const val TAG = "AapTransport"

        /**
         * Four times the largest frame observed (996 bytes, three service descriptors).
         * Reassembly means an undersized buffer is now a performance detail rather than a
         * correctness one, but sitting 28 bytes under the old 1024 was too close to read
         * as deliberate.
         */
        const val READ_BUFFER_BYTES = 4096

        /**
         * How long a newly opened channel may stay silent before it is treated as dead.
         *
         * The accessory answers the handshake with its whole configuration in well under
         * a second. Generous against that, and short enough that a user toggling heart
         * rate on does not sit watching a spinner.
         */
        const val SILENT_CHANNEL_TIMEOUT_MILLIS = 6_000L

        /**
         * How long a write waits for the channel. Generous, because opening it involves a
         * full authenticated L2CAP setup with the accessory, not just a local call.
         */
        const val READY_TIMEOUT_MILLIS = 5_000L

        /** `BluetoothSocket.TYPE_L2CAP`, which is not public API. */
        const val L2CAP_TYPE = 3

        /** Header, opcode and the `00 00 10 00 <length>` prefix a `0x17` frame carries. */
        const val HID_BODY_OFFSET = 12

        /**
         * Apple's AAP service UUID, as advertised in the accessory's SDP record.
         *
         * The socket carries it so the stack requests the right service rather than a
         * bare PSM.
         */
        const val APPLE_AAP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
    }
}
