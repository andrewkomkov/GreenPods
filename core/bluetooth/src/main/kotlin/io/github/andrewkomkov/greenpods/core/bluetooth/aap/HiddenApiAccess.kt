package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Lifts Android's non-SDK interface restriction for `android.bluetooth`.
 *
 * ### Why this exists
 *
 * The AAP channel is a **secure** L2CAP channel on PSM `0x1001` carrying Apple's service
 * UUID. No public API builds one: `createInsecureL2capChannel` builds an unauthenticated,
 * unencrypted channel, which real AirPods accept and then never bring up. The only
 * constructor that builds the right channel is hidden:
 *
 * ```
 * BluetoothSocket(BluetoothAdapter, BluetoothDevice, int type, boolean auth,
 *                 boolean encrypt, int psm, ParcelUuid)
 * ```
 *
 * It is on the **blocklist**, not the greylist, so reflection is denied outright:
 *
 * ```
 * hiddenapi: Accessing hidden method Landroid/bluetooth/BluetoothSocket;-><init>(…)V
 * (runtime_flags=0, domain=platform, api=blocked) … TargetSdkVersion=37
 * using reflection: denied
 * ```
 *
 * That denial — not the Bluetooth stack, and not a missing Magisk module — is what kept
 * every write-capable AirPods feature out of reach on unrooted Android. Exempting the
 * prefix makes the constructor resolvable, and the channel then opens with no root.
 *
 * ### What this is not
 *
 * This does not grant a permission, elevate a process, or patch the system. It clears an
 * app-local flag that governs whether *this* process may reflect on a platform class,
 * which is a documented runtime facility. Every Bluetooth permission is still enforced
 * normally.
 *
 * The exemption is deliberately narrow: two class prefixes, not the blanket `""` that
 * would open the entire framework.
 */
object HiddenApiAccess {
    /**
     * Exactly the prefixes the L2CAP route needs. `BluetoothDevice` is included because
     * the socket constructor takes one and the reflective resolution touches it too.
     */
    private val EXEMPTED_PREFIXES =
        arrayOf(
            "Landroid/bluetooth/BluetoothSocket;",
            "Landroid/bluetooth/BluetoothDevice;",
        )

    @Volatile
    private var outcome: Outcome? = null

    /** What the last (and only) attempt to lift the restriction achieved. */
    sealed interface Outcome {
        /** The blocked constructors are resolvable. The AAP transport can be attempted. */
        data object Granted : Outcome

        /** Nothing to lift — this Android version does not enforce non-SDK restrictions. */
        data object NotEnforced : Outcome

        /**
         * The exemption call was refused or had no effect, so the AAP transport cannot
         * be reached on this device however healthy the Bluetooth connection is.
         */
        data class Denied(
            val reason: String,
        ) : Outcome

        val isUsable: Boolean get() = this is Granted || this is NotEnforced
    }

    /**
     * Applies the exemption once per process and reports whether the blocked constructor
     * is now reachable.
     *
     * Verified rather than assumed: `addHiddenApiExemptions` returning `true` only means
     * the call was made, so the result is confirmed by resolving a constructor that is
     * blocked without it.
     */
    fun ensureBluetoothSocketReachable(): Outcome =
        outcome ?: synchronized(this) {
            outcome ?: apply().also {
                outcome = it
                Log.i(TAG, "non-SDK access for android.bluetooth: $it")
            }
        }

    private fun apply(): Outcome {
        // Restrictions arrived in P. Below that everything is reachable already.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.NotEnforced

        val requested =
            try {
                HiddenApiBypass.addHiddenApiExemptions(*EXEMPTED_PREFIXES)
            } catch (e: Throwable) {
                // The bypass reaches into VM internals that move between releases; a new
                // Android breaking it is a normal outcome to report, not a crash.
                return Outcome.Denied("exemption call failed: ${e.message}")
            }

        return when {
            isConstructorReachable() -> Outcome.Granted
            !requested -> Outcome.Denied("the runtime refused the exemption")
            else -> Outcome.Denied("the exemption was accepted but the constructor is still blocked")
        }
    }

    /**
     * True when reflection can see [BluetoothSocket]'s constructors at all.
     *
     * Under enforcement this list comes back empty rather than throwing, which is why it
     * is a usable probe: emptiness is the signature of a filtered result, since the class
     * always has constructors.
     */
    private fun isConstructorReachable(): Boolean =
        try {
            BluetoothSocket::class.java.declaredConstructors.isNotEmpty()
        } catch (e: Throwable) {
            false
        }

    private const val TAG = "HiddenApiAccess"
}
