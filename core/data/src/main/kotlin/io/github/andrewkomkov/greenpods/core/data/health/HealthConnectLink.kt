package io.github.andrewkomkov.greenpods.core.data.health

import androidx.activity.result.contract.ActivityResultContract
import io.github.andrewkomkov.greenpods.core.data.heartrate.TrustedReadingSink
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the phone has a health store, and what to say when it does not.
 *
 * Three states rather than a Boolean, because FR-020 wants a *reason*: "Health Connect
 * needs an update" is an action the user can take, and "this device does not have it" is
 * not. Collapsing them would turn a fixable situation into a dead end.
 */
sealed interface HealthStoreAvailability {
    val sentence: String

    data object Available : HealthStoreAvailability {
        override val sentence: String get() = "Health Connect is available on this phone."
    }

    data object NeedsProviderUpdate : HealthStoreAvailability {
        override val sentence: String
            get() = "Health Connect needs an update before GreenPods can write to it."
    }

    data object NotOnThisDevice : HealthStoreAvailability {
        override val sentence: String
            get() = "This phone does not have Health Connect. Heart rate still works on screen."
    }

    val isAvailable: Boolean get() = this is Available
}

/** How many of GreenPods' own records exist in a window, and how many samples they hold. */
data class OwnRecordCount(
    val records: Int,
    val samples: Int,
)

/** The accessory a batch is attributed to (FR-021). */
data class HealthDevice(
    val manufacturer: String,
    val model: String,
)

/**
 * The narrow slice of Health Connect this feature uses.
 *
 * An interface for two reasons. The obvious one is that the link's rules — what happens
 * when permission is absent, what each availability state says — are decidable without a
 * provider installed, and this is what makes them testable. The less obvious one is that
 * it keeps every Health Connect type on one side of a boundary, so nothing above
 * `core/data` ever sees one.
 */
interface HealthStoreClient {
    fun availability(): HealthStoreAvailability

    suspend fun hasWritePermission(): Boolean

    suspend fun hasReadPermission(): Boolean

    suspend fun insert(
        batch: HeartRateBatch,
        device: HealthDevice,
    )

    /** Counts **our own** records only — see the class docs on why that is not pedantry. */
    suspend fun countOwnRecords(
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ): OwnRecordCount

    suspend fun deleteOwnRecords()

    /**
     * The permission request, typed on plain strings.
     *
     * `ActivityResultContract<Set<String>, Set<String>>` is what makes AD-11 work: the
     * settings screen can launch this without linking Health Connect, because neither
     * type parameter is a Health Connect type.
     */
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>>

    /** The permission strings to ask for. Opaque above this layer. */
    fun requiredPermissions(): Set<String>
}

/**
 * Writes trusted heart-rate readings into the phone's health store, and nothing else.
 *
 * The rules it enforces, in the order they matter:
 *
 * 1. **Only what came out of [HeartRateState.Measuring] is written.** Enforced by type —
 *    the sink takes a reading that could only have come from there, and nothing else can
 *    produce one. FR-007 stops being a rule three components must remember (AD-3).
 * 2. **Permission is checked before every flush, and never re-requested.** Losing it
 *    stops writing immediately and silently; the reading on screen keeps working
 *    (FR-018).
 * 3. **The integration is separately switchable from the display.** Seeing a number and
 *    putting it in someone's health history are two decisions (FR-017).
 *
 * Failures are swallowed rather than propagated. A provider that is updating, or a write
 * that races a revocation, must not take the reading off the screen — the health store is
 * an output of this feature, not a dependency of it.
 */
class HealthConnectLink(
    private val client: HealthStoreClient?,
    private val settings: Flow<GreenPodsSettings> = flowOf(GreenPodsSettings.Default),
    private val batcherFor: () -> HeartRateBatcher = { HeartRateBatcher() },
) : TrustedReadingSink {
    private val batchers = mutableMapOf<String, HeartRateBatcher>()
    private val devices = mutableMapOf<String, HealthDevice>()

    fun availability(): HealthStoreAvailability =
        client?.availability() ?: HealthStoreAvailability.NotOnThisDevice

    /**
     * Read on demand and never cached across a session.
     *
     * Health Connect is a moving provider, and the user grants and revokes these
     * permissions from *outside* the app entirely — a cached answer is a stale one by
     * design.
     */
    suspend fun hasWritePermission(): Boolean = client?.hasWritePermission() ?: false

    suspend fun hasReadPermission(): Boolean = client?.hasReadPermission() ?: false

    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>>? =
        client?.permissionRequestContract()

    fun requiredPermissions(): Set<String> = client?.requiredPermissions().orEmpty()

    override suspend fun onTrusted(
        pod: PodState,
        reading: HeartRateReading,
    ) {
        val batcher = batchers.getOrPut(pod.address) { batcherFor() }
        devices[pod.address] = HealthDevice(manufacturer = APPLE, model = pod.model.displayName)

        val completed = batcher.add(reading) ?: return
        write(pod.address, completed)
    }

    override suspend fun onSensingStopped(address: String) {
        val batcher = batchers[address] ?: return
        val partial = batcher.flush() ?: return
        write(address, partial)
    }

    /** Counts records GreenPods itself wrote in the last [minutes]. FR-029, SC-009. */
    suspend fun countOwnRecords(
        minutes: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): OwnRecordCount? {
        val store = client ?: return null
        if (!store.availability().isAvailable || !store.hasReadPermission()) return null
        return runCatching {
            store.countOwnRecords(nowEpochMillis - minutes * MILLIS_PER_MINUTE, nowEpochMillis)
        }.getOrNull()
    }

    /**
     * Deletes what GreenPods wrote, and only that.
     *
     * The UI says plainly beside this that data already in the health store is managed
     * there, because deleting our own records is not the same as clearing a user's
     * history and must not be presented as though it were (FR-025).
     */
    suspend fun deleteOwnRecords(): Boolean {
        val store = client ?: return false
        if (!store.availability().isAvailable || !store.hasWritePermission()) return false
        return runCatching { store.deleteOwnRecords() }.isSuccess
    }

    private suspend fun write(
        address: String,
        batch: HeartRateBatch,
    ) {
        val store = client ?: return
        if (!settings.first().heartRateHealthConnectEnabled) return
        if (!store.availability().isAvailable) return
        // Checked here, per flush, rather than once at start: revocation happens from
        // outside the app and must stop the very next write (FR-018).
        if (!store.hasWritePermission()) return

        val device = devices[address] ?: HealthDevice(APPLE, "AirPods")
        runCatching { store.insert(batch, device) }
    }

    private companion object {
        const val APPLE = "Apple"
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
