package io.github.andrewkomkov.greenpods.core.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.core.model.Transport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One decoded advertisement together with its radio metadata. */
data class PodSighting(
    val address: String,
    val beacon: AppleBeacon,
    val rssi: Int,
    val timestampMillis: Long,
) {
    fun toPodState(): PodState =
        PodState(
            address = address,
            model = beacon.model,
            battery = beacon.battery,
            earDetection = beacon.earDetection,
            rssi = rssi,
            activeTransports = setOf(Transport.BLE_ADVERTISEMENT),
            lastSeenEpochMillis = timestampMillis,
        )
}

/**
 * Where accessory sightings come from.
 *
 * The repository depends on this rather than on [PodScanner] so the whole
 * accumulate-age-rank pipeline can be driven from captured advertisements in a unit
 * test, with no Bluetooth adapter in sight.
 */
fun interface PodSightingSource {
    fun sightings(scanMode: ScanMode): Flow<PodSighting>
}

/**
 * Passive BLE scanner for Apple proximity-pairing advertisements.
 *
 * This is GreenPods' baseline transport: it needs no pairing, no connection and no
 * root, and it is the only thing guaranteed to work on every device. Callers must
 * hold `BLUETOOTH_SCAN` (API 31+) or `ACCESS_FINE_LOCATION` (API 30 and below).
 */
class PodScanner(
    private val context: Context,
) : PodSightingSource {
    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(BluetoothManager::class.java)

    /**
     * Emits a sighting for every matching advertisement.
     *
     * Scanning is filtered in the Bluetooth stack rather than in the app so the
     * radio can stay in a low-power mode: [ScanFilter] matches Apple's company id
     * and the proximity-pairing type byte, and everything else is discarded before
     * it ever reaches the process.
     */
    @SuppressLint("MissingPermission")
    override fun sightings(scanMode: ScanMode): Flow<PodSighting> =
        callbackFlow {
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                close(IllegalStateException("Bluetooth LE scanner unavailable"))
                return@callbackFlow
            }

            val filters =
                listOf(
                    ScanFilter
                        .Builder()
                        .setManufacturerData(
                            AppleBeaconDecoder.APPLE_COMPANY_ID,
                            byteArrayOf(AppleBeaconDecoder.TYPE_PROXIMITY_PAIRING.toByte()),
                            byteArrayOf(0xFF.toByte()),
                        ).build(),
                )

            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(scanMode.toAndroidScanMode())
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .build()

            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val payload =
                            result.scanRecord
                                ?.getManufacturerSpecificData(AppleBeaconDecoder.APPLE_COMPANY_ID)
                                ?: return
                        // Unknown model ids are forwarded rather than dropped: the
                        // repository logs them so the registry can grow, and decides
                        // what to show. Filtering here would lose that information.
                        val beacon = AppleBeaconDecoder.decode(payload) ?: return

                        trySend(
                            PodSighting(
                                address = result.device.address,
                                beacon = beacon,
                                rssi = result.rssi,
                                timestampMillis = System.currentTimeMillis(),
                            ),
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "BLE scan failed with code $errorCode")
                        close(IllegalStateException("BLE scan failed: $errorCode"))
                    }
                }

            scanner.startScan(filters, settings, callback)
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }

    private companion object {
        const val TAG = "PodScanner"

        @Suppress("unused")
        val HEART_RATE_SERVICE: ParcelUuid = ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    }
}

/**
 * Maps the app's scan preference onto the platform constant.
 *
 * Kept as an extension rather than a field on [ScanMode] so `core/model` stays free of
 * Android types.
 */
internal fun ScanMode.toAndroidScanMode(): Int =
    when (this) {
        ScanMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
        ScanMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
        ScanMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
    }
