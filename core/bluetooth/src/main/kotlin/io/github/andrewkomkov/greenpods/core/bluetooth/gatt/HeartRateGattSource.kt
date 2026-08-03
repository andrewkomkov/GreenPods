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
import io.github.andrewkomkov.greenpods.core.model.HeartRateSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Heart rate over the standard Bluetooth SIG Heart Rate Profile.
 *
 * This is the *only* heart-rate path that works on an unrooted Android device, and
 * among Apple's accessories only Powerbeats Pro 2 exposes it — AirPods Pro 3
 * deliberately keeps its HR data inside Apple's ecosystem. See
 * `docs/protocol-research.md`.
 *
 * Because it is a plain SIG profile, there is nothing Apple-specific here: the same
 * code reads any chest strap or fitness band.
 */
class HeartRateGattSource(
    private val context: Context,
) {
    /**
     * Connects, subscribes to heart-rate measurement notifications, and emits a
     * sample per notification. The GATT connection closes when collection stops.
     */
    @SuppressLint("MissingPermission")
    fun measurements(device: BluetoothDevice): Flow<HeartRateSample> =
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
                        parseMeasurement(value)?.let { trySend(it) }
                    }

                    @Deprecated("Kept for API < 33, which does not deliver the value parameter.")
                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
                        characteristic.value?.let { value -> parseMeasurement(value)?.let { trySend(it) } }
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
 */
internal fun parseMeasurement(value: ByteArray): HeartRateSample? {
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

    if (bpm <= 0) {
        Log.v("HeartRateGatt", "Ignoring non-positive BPM $bpm")
        return null
    }
    return HeartRateSample(beatsPerMinute = bpm, source = HeartRateSample.Source.GATT)
}
