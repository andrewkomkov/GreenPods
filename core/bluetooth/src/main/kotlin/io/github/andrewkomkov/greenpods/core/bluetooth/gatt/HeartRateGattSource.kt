package io.github.andrewkomkov.greenpods.core.bluetooth.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Heart rate over the standard Bluetooth SIG Heart Rate Profile.
 *
 * Among Apple's accessories only Powerbeats Pro 2 exposes this: Beats is positioned as
 * cross-platform, so it kept the open profile, while AirPods Pro 3 publishes heart rate
 * only over Apple's own protocol — which GreenPods now also reads, by a completely
 * separate path. The two never blend (FR-004).
 *
 * Because this is a plain SIG profile, there is nothing Apple-specific here: the same
 * code reads any chest strap or fitness band.
 *
 * **No confidence field exists on this route**, and per R-10 that is a difference in the
 * profile's contract rather than a gap: a device speaking the SIG profile publishes a
 * measurement it already considers valid, with no settling series to protect the user
 * from. The gate here is therefore plausibility alone, and the UI says which route
 * produced the number rather than hiding the asymmetry.
 *
 * **Unverified on hardware.** No Powerbeats Pro 2 is available to this project, so this
 * path is exercised off-device against captured characteristic values and ships as
 * implemented-and-unverified. SC-003 is not claimed for it.
 */
class HeartRateGattSource(
    private val context: Context,
    /** Injected so the emitted timestamps are testable off-device. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Connects, subscribes to heart-rate measurement notifications, and emits a
     * reading per notification. The GATT connection closes when collection stops.
     */
    @SuppressLint("MissingPermission")
    fun measurements(device: BluetoothDevice): Flow<HeartRateReading> =
        callbackFlow {
            val callback =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> close()
                        }
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int,
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            close(IllegalStateException("Service discovery failed: $status"))
                            return
                        }
                        val characteristic =
                            gatt
                                .getService(HEART_RATE_SERVICE)
                                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
                        if (characteristic == null) {
                            close(UnsupportedOperationException("Device does not expose the Heart Rate Profile"))
                            return
                        }

                        gatt.setCharacteristicNotification(characteristic, true)
                        // The profile requires writing the CCC descriptor as well; without it
                        // the peripheral never actually starts notifying.
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
                        parseMeasurement(value, clock())?.let { trySend(it) }
                    }

                    @Deprecated("Kept for API < 33, which does not deliver the value parameter.")
                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
                        characteristic.value?.let { value ->
                            parseMeasurement(value, clock())?.let { trySend(it) }
                        }
                    }
                }

            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            awaitClose {
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
            }
        }

    private companion object {
        const val TAG = "HeartRateGatt"

        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/**
 * Decodes a Heart Rate Measurement characteristic value.
 *
 * Bit 0 of the flags byte selects the BPM width: clear means one byte, set means
 * two bytes little-endian. Kept internal and pure so it can be unit-tested.
 *
 * Returns null for anything implausible rather than a reading that would have to be
 * filtered downstream — the plausibility range is the whole gate on this route, so it
 * belongs where the bytes are read.
 */
internal fun parseMeasurement(
    value: ByteArray,
    nowMillis: Long,
): HeartRateReading? {
    if (value.isEmpty()) return null
    val flags = value[0].toInt() and 0xFF
    val isUint16 = flags and 0x01 != 0

    val bpm =
        if (isUint16) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            if (value.size < 2) return null
            value[1].toInt() and 0xFF
        }

    return HeartRateReading.orNull(
        beatsPerMinute = bpm,
        // The SIG profile publishes none, and null here means "this route has no
        // confidence to give" rather than "no confidence in this reading" (R-10).
        confidence = null,
        source = HeartRateReading.Source.GATT,
        // The host clock, because the profile carries no timestamp of its own. Nothing
        // to anchor, and nothing that could be mistaken for a counter.
        measuredAtEpochMillis = nowMillis,
    ) ?: run {
        Log.v("HeartRateGatt", "Ignoring implausible BPM $bpm")
        null
    }
}
