package io.github.andrewkomkov.greenpods.core.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapProtocol
import io.github.andrewkomkov.greenpods.core.model.PodModel

/**
 * Finds the *paired* device that an advertisement belongs to.
 *
 * This exists because the two identities never match. AirPods advertise from a
 * resolvable private address that rotates, while the bond — and therefore anything
 * connectable — lives on the classic Bluetooth address. Looking up the advertised
 * address among bonded devices finds nothing, on every phone, always. Resolving the
 * address correctly is what makes "these AirPods are not paired" a true statement
 * instead of a permanent excuse.
 *
 * Correlating the two without privileged access is not possible in general: the whole
 * point of a private address is that it cannot be linked to an identity without the
 * pairing key. What *is* possible is to notice that the user has exactly one paired
 * Apple audio accessory, and to check that the advertisement could plausibly have come
 * from it. When it could not, or when several bonds could match, that is reported rather
 * than guessed at.
 *
 * **"Exactly one paired accessory" is not on its own enough**, and believing it was cost
 * this app a user-visible bug. The scan matches Apple's company id, so it sees *every*
 * AirPods in radio range, not only the paired ones — and this resolver answered
 * "that's yours" about all of them. Observed 2026-08-05: a stranger's AirPods 3 at −83 dBm
 * took the bonded key, the owner's AirPods Pro 3 at −62 were pushed onto their rotating
 * advertised address and appeared as a second accessory, and the open channel was recorded
 * against the stranger's entry. Same model as yours and it would have been worse — one
 * entry, silently carrying someone else's battery and wear.
 *
 * So a candidate must now be corroborated: the advertised model and the bonded device's
 * name have to belong to the same product family. That does not identify anything — two
 * people with the same AirPods Pro remain indistinguishable, which is exactly what a
 * private address is for — but it rules out the case that actually happens, at no cost and
 * with no new permission.
 *
 * Corroboration reads a name, and names are the user's to change, so [BondedPodMatch]
 * separates "not yours" from "cannot tell" and this class carries the second as
 * [Resolution.Uncorroborated]. A renamed accessory is recognised as Apple at all through
 * its SDP record rather than its name — see [looksLikeAppleAudio].
 */
class BondedPodResolver(
    private val context: Context,
) {
    sealed interface Resolution {
        data class Resolved(
            val device: BluetoothDevice,
        ) : Resolution

        /** No paired Apple or Beats audio device at all. */
        data object NoCandidate : Resolution

        /** Several, and a private address cannot say which. */
        data class Ambiguous(
            val candidates: List<String>,
        ) : Resolution

        /**
         * A paired accessory exists, but this advertisement cannot be from it — the
         * product families disagree. Someone else's AirPods, in other words.
         */
        data class NotThisAccessory(
            val bondedName: String,
            val advertisedModel: String,
        ) : Resolution

        /**
         * A paired accessory exists whose name says nothing about what it is, so this
         * advertisement can be neither confirmed nor excluded.
         *
         * Renaming is the ordinary cause, and it is not exotic — the Bluetooth settings
         * screen invites it. Distinguished from [NotThisAccessory] because the two demand
         * opposite handling: that one is grounds to discard the advertisement, this one is
         * grounds to keep it and claim nothing.
         */
        data class Uncorroborated(
            val bondedName: String,
        ) : Resolution

        /** Bluetooth is off, or the Connect permission is missing. */
        data object Unavailable : Resolution
    }

    /**
     * @param advertisedModel the model this advertisement announced, when the caller has
     *   it. Null means the caller holds an address rather than an advertisement — in which
     *   case the only resolution offered is the exact address match, because there is
     *   nothing to corroborate the single-candidate guess with and guessing is what broke.
     */
    @SuppressLint("MissingPermission")
    fun resolve(
        advertisedAddress: String,
        advertisedModel: PodModel?,
    ): Resolution {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Resolution.Unavailable
        if (!adapter.isEnabled) return Resolution.Unavailable

        val bonded =
            try {
                adapter.bondedDevices.orEmpty()
            } catch (e: SecurityException) {
                return Resolution.Unavailable
            }

        // A caller that already holds a classic address gets an exact match. This is the
        // only branch that is a fact rather than an inference.
        bonded.firstOrNull { it.address == advertisedAddress }?.let { return Resolution.Resolved(it) }

        val candidates = bonded.filter { it.looksLikeAppleAudio() }
        if (candidates.isEmpty()) return Resolution.NoCandidate
        if (advertisedModel == null) return Resolution.NoCandidate

        val names = candidates.map { runCatching { it.name }.getOrNull() }
        return when (val verdict = BondedPodMatch.of(names, advertisedModel)) {
            is BondedPodMatch.Verdict.Resolved -> {
                Resolution.Resolved(candidates[verdict.index])
            }

            BondedPodMatch.Verdict.NotThisAccessory -> {
                Resolution.NotThisAccessory(
                    bondedName = candidates.first().label(),
                    advertisedModel = advertisedModel.displayName,
                )
            }

            BondedPodMatch.Verdict.Uncorroborated -> {
                Resolution.Uncorroborated(bondedName = candidates.first().label())
            }

            is BondedPodMatch.Verdict.Ambiguous -> {
                Resolution.Ambiguous(verdict.indices.map { candidates[it].label() })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.label(): String = runCatching { name }.getOrNull().orEmpty().ifBlank { address }

    /**
     * Whether this bonded device is plausibly the accessory GreenPods is about.
     *
     * Separate from [resolve] on purpose, and not a convenience. [resolve] answers "which
     * paired device does this advertisement belong to", and it deliberately short-circuits
     * on an exact address match — so asking it whether an arbitrary bonded device is
     * "ours" gets `Resolved(itself)` for **every** paired device on the phone, keyboards
     * and game controllers included. Used as a filter it filters nothing, which is how
     * this app came to open an L2CAP channel to a DualSense controller and spend three
     * seconds retrying it.
     */
    @SuppressLint("MissingPermission")
    fun isPodCandidate(device: BluetoothDevice): Boolean = device.looksLikeAppleAudio()

    /**
     * A paired device that plausibly *is* the advertising accessory.
     *
     * Two independent signals, because the obvious one does not survive ordinary use. The
     * name carries the model when the accessory still has the one Apple shipped, and the
     * Bluetooth settings screen invites renaming it — after which nothing in the name says
     * Apple at all. Recognising only by name meant a renamed accessory was not a candidate,
     * and once foreign advertisements began being discarded that made the owner's own
     * earbuds vanish from the app.
     *
     * The SDP record does survive renaming. An accessory that speaks Apple's protocol
     * publishes [AapProtocol.SERVICE_UUID] whatever its owner has called it, and no
     * non-Apple headphones publish it — which is exactly the discrimination that
     * "any bonded audio device" would have thrown away, on a phone that is typically
     * paired to a speaker, a car and two other headsets.
     */
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.looksLikeAppleAudio(): Boolean {
        val isAudio = bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        if (!isAudio) return false
        if (publishesAppleService()) return true
        val name = runCatching { name }.getOrNull().orEmpty()
        return APPLE_NAME_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }
    }

    /** Whether the bond's SDP record carries Apple's AAP service. Survives renaming. */
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.publishesAppleService(): Boolean =
        runCatching { uuids }
            .getOrNull()
            .orEmpty()
            .any { it?.uuid?.toString().equals(AapProtocol.SERVICE_UUID, ignoreCase = true) }

    private companion object {
        /**
         * Default names Apple and Beats ship.
         *
         * Kept alongside the service-UUID check rather than replaced by it: the SDP record
         * is only populated once the phone has completed discovery with the accessory, and
         * a freshly restored bond may not have it yet.
         */
        val APPLE_NAME_PREFIXES = listOf("AirPods", "Beats", "Powerbeats")
    }
}
