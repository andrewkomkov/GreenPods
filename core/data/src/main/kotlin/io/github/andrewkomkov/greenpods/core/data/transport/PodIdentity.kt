package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.model.PodModel

/**
 * What an accessory is called, for as long as it is the same accessory.
 *
 * AirPods advertise from a resolvable private address that rotates — in practice every
 * time they reconnect, and periodically while connected. Everything this app accumulates
 * is keyed by address: the overlay carrying noise-control mode and heart rate, the
 * transport gate's record of an open channel, and the heart-rate controller's session.
 * Keyed by the *advertised* address, all of it is silently orphaned by a rotation.
 *
 * That is not theoretical. Observed on 2026-08-04: after taking the buds out of the case
 * the pod advertised `5D:ED:95:CC:44:42` while the live channel and its events were
 * recorded under `73:C3:9E:7B:98:F5`. Heart rate showed `LOCKED` with `reports=0` while
 * sixteen input reports a second were arriving at the transport — the state was correct
 * for the key it was stored under, and that key no longer described anything.
 *
 * The fix is to give the accessory one name and use it everywhere. The bonded classic
 * address is the natural choice: it is what the channel is opened to, it does not rotate,
 * and it is what "this is the same earbuds as a minute ago" actually means.
 */
fun interface PodIdentity {
    /**
     * The stable key for an advertised address.
     *
     * Must be cheap: this is called for **every** advertisement, several times a second
     * per accessory. Implementations that consult the Bluetooth stack are expected to
     * memoise — see [BondedPodIdentity].
     *
     * [model] is what the advertisement announced, and it is not optional decoration: it
     * is the only per-advertisement signal that can tell a stranger's AirPods from the
     * ones this phone is paired to. Null means unknown, and unknown resolves to nothing.
     */
    fun stableKey(
        advertisedAddress: String,
        model: PodModel?,
    ): String

    companion object {
        /**
         * The advertised address, unchanged.
         *
         * The honest default: with no way to resolve a bond there is nothing better to
         * use, and pretending otherwise would invent an identity rather than find one.
         * Also what the tests use, so they are not asserting against a Bluetooth stack.
         */
        val Advertised = PodIdentity { address, _ -> address }
    }
}

/**
 * Resolves an advertisement to the bonded accessory it belongs to, and remembers.
 *
 * Memoised because [PodIdentity.stableKey] runs per advertisement while
 * [BondedPodResolver.resolve] enumerates bonded devices and reads their names — cheap
 * once, wasteful thousands of times an hour.
 *
 * The cache only ever grows by one entry per rotation and is dropped with the process,
 * which is the right lifetime: a bond that changes while the app runs is a re-pair, and
 * [forget] exists for that.
 */
class BondedPodIdentity(
    private val resolver: BondedPodResolver,
) : PodIdentity {
    /**
     * Keyed by address **and** model, because the answer depends on both.
     *
     * Caching on the address alone would let the first advertisement seen from a rotating
     * address fix the answer for every later one — including a rotation that has since
     * been reused by a different accessory.
     */
    private val resolved = mutableMapOf<Pair<String, PodModel?>, String>()

    override fun stableKey(
        advertisedAddress: String,
        model: PodModel?,
    ): String =
        synchronized(resolved) {
            resolved.getOrPut(advertisedAddress to model) {
                // Unresolvable means unresolvable — an accessory that is not paired, one of
                // several that a private address cannot choose between, or one whose model
                // says it is somebody else's. Falling back to the advertised address keeps
                // such a device visible with the features the advertisement alone can
                // support, which is Principle II, and keeps it out of this phone's bond,
                // which is what stops it overwriting the owner's accessory.
                (resolver.resolve(advertisedAddress, model) as? BondedPodResolver.Resolution.Resolved)
                    ?.device
                    ?.address
                    ?: advertisedAddress
            }
        }

    /** Forgets what was resolved, for when the set of bonded devices changes. */
    fun forget() = synchronized(resolved) { resolved.clear() }
}
