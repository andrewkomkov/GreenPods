package io.github.andrewkomkov.greenpods.core.data.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

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
 * Apple audio accessory, in which case there is nothing to confuse it with. When there
 * is more than one, that ambiguity is reported rather than guessed at.
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

        /** Bluetooth is off, or the Connect permission is missing. */
        data object Unavailable : Resolution
    }

    @SuppressLint("MissingPermission")
    fun resolve(advertisedAddress: String): Resolution {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return Resolution.Unavailable
        if (!adapter.isEnabled) return Resolution.Unavailable

        val bonded =
            try {
                adapter.bondedDevices.orEmpty()
            } catch (e: SecurityException) {
                return Resolution.Unavailable
            }

        // A caller that already holds a classic address gets an exact match.
        bonded.firstOrNull { it.address == advertisedAddress }?.let { return Resolution.Resolved(it) }

        val candidates = bonded.filter { it.looksLikeAppleAudio() }
        return when (candidates.size) {
            0 -> Resolution.NoCandidate
            1 -> Resolution.Resolved(candidates.single())
            else -> Resolution.Ambiguous(candidates.map { it.name.orEmpty().ifBlank { it.address } })
        }
    }

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
