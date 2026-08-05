package io.github.andrewkomkov.greenpods.core.data.heartrate

import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.gatt.HeartRateGattSource
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Joins the standard-profile source to the controller, and does nothing else.
 *
 * The one piece of work here is the address. An advertisement carries a rotating private
 * address, and a GATT connection needs the bonded classic one — looking up the advertised
 * address directly finds nothing, on every phone, always.
 *
 * `HeartRateGattSource` has existed, tested, with no caller at all. This is the caller
 * (R-10). **Unverified on hardware**: no Powerbeats Pro 2 is available to this project,
 * so the route ships as implemented-and-unverified and SC-003 is not claimed for it.
 */
class AndroidGattHeartRateSource(
    context: Context,
    private val source: HeartRateGattSource = HeartRateGattSource(context),
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) : GattHeartRateReadings {
    override fun readings(address: String): Flow<HeartRateReading> {
        // Exact match or nothing — see the note in AapControlGateway.
        val resolution = resolver.resolve(address, advertisedModel = null)
        val device = (resolution as? BondedPodResolver.Resolution.Resolved)?.device ?: return emptyFlow()
        return source.measurements(device)
    }
}
