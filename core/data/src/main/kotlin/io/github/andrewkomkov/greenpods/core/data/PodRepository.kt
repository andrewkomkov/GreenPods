package io.github.andrewkomkov.greenpods.core.data

import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodScanner
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/**
 * Single source of truth for what GreenPods knows about nearby accessories.
 *
 * Advertisements arrive per-device and out of order, so state is accumulated
 * rather than replaced: each sighting updates one entry and leaves the rest alone.
 * Entries that stop advertising age out via [STALE_AFTER_MILLIS] instead of being
 * removed on the first missed packet — BLE advertisements are lossy, and dropping a
 * device because one packet went missing makes the UI flicker.
 */
class PodRepository(
    context: Context,
    private val scanner: PodScanner = PodScanner(context),
) {
    /** Every currently-known accessory, most recently seen first. */
    fun observePods(): Flow<List<PodState>> =
        scanner
            .sightings()
            .catch { /* Scanning stops when Bluetooth is off; surface an empty list, not a crash. */ }
            .runningFold(emptyMap<String, PodState>()) { known, sighting ->
                val existing = known[sighting.address]
                val updated =
                    sighting.toPodState().let { fresh ->
                        // Preserve anything only AAP or GATT can tell us; the advertisement
                        // does not carry noise-control mode or heart rate.
                        existing?.copy(
                            model = fresh.model,
                            battery = fresh.battery,
                            earDetection = fresh.earDetection,
                            rssi = fresh.rssi,
                            lastSeenEpochMillis = fresh.lastSeenEpochMillis,
                        ) ?: fresh
                    }
                known + (sighting.address to updated)
            }.map { known ->
                val now = System.currentTimeMillis()
                known.values
                    .filter { now - it.lastSeenEpochMillis < STALE_AFTER_MILLIS }
                    .sortedByDescending { it.lastSeenEpochMillis }
            }

    /**
     * The accessory the user is most likely to mean: the closest one still
     * advertising. RSSI is noisy, but for "which of my two AirPods is in my ears"
     * it is the only signal available over the advertisement transport.
     */
    fun observePrimaryPod(): Flow<PodState?> =
        observePods().map { pods ->
            pods.maxByOrNull { it.rssi ?: Int.MIN_VALUE }
        }

    /**
     * Transports currently usable. Only [Transport.BLE_ADVERTISEMENT] is
     * guaranteed; the rest are probed. See `docs/protocol-research.md`.
     */
    fun availableTransports(): Set<Transport> = setOf(Transport.BLE_ADVERTISEMENT)

    private companion object {
        /** Roughly ten missed advertisement intervals. */
        const val STALE_AFTER_MILLIS = 30_000L
    }
}
