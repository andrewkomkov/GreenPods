package io.github.andrewkomkov.greenpods.core.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.andrewkomkov.greenpods.core.model.AppleAccessoryFamily
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
 * So a candidate must now be corroborated by [AppleAccessoryFamily]: the advertised model
 * and the bonded device's name have to belong to the same product family. That does not
 * identify anything — two people with the same AirPods Pro remain indistinguishable, which
 * is exactly what a private address is for — but it rules out the case that actually
 * happens, at no cost and with no new permission.
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

        val plausible =
            candidates.filter { AppleAccessoryFamily.couldBeTheSame(it.name, advertisedModel.displayName) }

        return when (plausible.size) {
            0 -> {
                Resolution.NotThisAccessory(
                    bondedName =
                        candidates
                            .first()
                            .name
                            .orEmpty()
                            .ifBlank { candidates.first().address },
                    advertisedModel = advertisedModel.displayName,
                )
            }

            1 -> {
                Resolution.Resolved(plausible.single())
            }

            else -> {
                Resolution.Ambiguous(plausible.map { it.name.orEmpty().ifBlank { it.address } })
            }
        }
    }

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
     * Name matching is unlovely, but it is what is available: the Bluetooth class says
     * only "audio", and the manufacturer metadata needs a privileged permission. Apple
     * accessories keep their model name unless deliberately renamed, and a rename that
     * drops every known prefix simply falls back to the honest "no candidate" answer.
     */
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.looksLikeAppleAudio(): Boolean {
        val isAudio = bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        if (!isAudio) return false
        val name = runCatching { name }.getOrNull().orEmpty()
        return APPLE_NAME_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }
    }

    private companion object {
        /** Default names Apple and Beats ship; a renamed accessory is not guessed at. */
        val APPLE_NAME_PREFIXES = listOf("AirPods", "Beats", "Powerbeats")
    }
}
