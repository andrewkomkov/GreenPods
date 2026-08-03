package io.github.andrewkomkov.greenpods.core.model

/** Whether a transport has been established, ruled out, or not yet looked at. */
enum class TransportAvailability {
    /** Live: features needing this transport are usable. */
    AVAILABLE,

    /** Tried and refused. [TransportStatus.reason] says by what. */
    UNAVAILABLE,

    /**
     * Never attempted. Distinct from UNAVAILABLE on purpose — telling the user a
     * feature is unsupported when nothing was tried is a lie the diagnostics screen
     * would be blamed for.
     */
    NOT_PROBED,
}

/**
 * One transport's state plus the sentence a user should be shown for it.
 *
 * The reason is carried as data rather than derived in the UI because the interesting
 * failures — a PSM the public API rejects, a channel mode the buds refuse — are
 * discovered deep in the Bluetooth layer and would otherwise be flattened into a
 * useless "unavailable".
 */
data class TransportStatus(
    val transport: Transport,
    val availability: TransportAvailability,
    val reason: String = "",
) {
    val isAvailable: Boolean get() = availability == TransportAvailability.AVAILABLE

    companion object {
        /** The advertisement transport needs nothing and is therefore always live. */
        val AdvertisementAvailable =
            TransportStatus(
                transport = Transport.BLE_ADVERTISEMENT,
                availability = TransportAvailability.AVAILABLE,
                reason = "Apple's proximity advertisement needs no pairing.",
            )

        fun notProbed(transport: Transport): TransportStatus =
            TransportStatus(transport, TransportAvailability.NOT_PROBED, "Not checked yet.")
    }
}
