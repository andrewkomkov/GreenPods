package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidService

/**
 * What an accessory has already said about its own sensor services, kept across sessions.
 *
 * **Why this is not a violation of FR-002.** The rule is that the heart-rate service id
 * must be *discovered* rather than assumed — that a hard-coded constant which happens to
 * match one firmware is a bug waiting for the next one. Nothing here is constant or
 * guessed: every byte was sent by this accessory, about itself, and is stored against its
 * own address. Remembering an answer is not inventing one.
 *
 * **Why it is needed.** The accessory announces its services once per Bluetooth link, in
 * reply to the first request after that link comes up. An app that restarts mid-link has
 * lost the announcement and cannot obtain another one — asking again on the same link is
 * simply not answered. Before this, that meant heart rate could not be switched on until
 * the earbuds were put back in the case and taken out, which is an absurd thing to ask of
 * someone who just wants to see their pulse.
 *
 * A live announcement always outranks a remembered one, so a firmware update that
 * renumbers services corrects this at the first opportunity rather than being shadowed by
 * it.
 */
interface HidServiceMemory {
    /** What [address] last described about itself, or empty if it never has. */
    suspend fun remembered(address: String): List<HidService>

    /** Records what [address] has just described. Replaces anything held for it. */
    suspend fun remember(
        address: String,
        services: List<HidService>,
    )

    /** Forgets everything. For the adb surface and for a user clearing their data. */
    suspend fun forget()

    companion object {
        /**
         * A memory that remembers nothing.
         *
         * The default, so nothing that has no store has to pretend it does — and so the
         * tests exercise the discovery path rather than a cache.
         */
        val None: HidServiceMemory =
            object : HidServiceMemory {
                override suspend fun remembered(address: String): List<HidService> = emptyList()

                override suspend fun remember(
                    address: String,
                    services: List<HidService>,
                ) = Unit

                override suspend fun forget() = Unit
            }
    }
}
