package io.github.andrewkomkov.greenpods.feature.pods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.environment.Environment
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why the list is empty, which is the only interesting thing about an empty list. */
enum class PodsEmptyReason {
    /** Scanning, nothing seen yet. The normal case. */
    SEARCHING,

    NO_PERMISSION,
    BLUETOOTH_OFF,
    SCAN_FAILED,
}

data class PodsUiState(
    val pods: List<PodState> = emptyList(),
    val emptyReason: PodsEmptyReason = PodsEmptyReason.SEARCHING,
    val scanFailure: String? = null,
    /**
     * The heart-rate card's presentation, per accessory address.
     *
     * Decided here rather than in the composable so it can be asserted in a JVM test.
     * The two things worth asserting — that settling shows no number, and that uncertain
     * *withdraws* the last one rather than keeping it on screen — are exactly the ones
     * that are painful to check through Compose and trivial to check here.
     */
    val heartRates: Map<String, HeartRateUi> = emptyMap(),
    /**
     * How often the earbuds are asked for a reading.
     *
     * Here because the full-screen view says it out loud: the number visibly holds still
     * between reports, and a value that does not move is read as a frozen app unless the
     * screen says how often it is meant to change.
     */
    val heartRateIntervalMillis: Int = GreenPodsSettings.Default.heartRateIntervalMillis,
) {
    val isEmpty: Boolean get() = pods.isEmpty()

    fun heartRateOf(pod: PodState): HeartRateUi =
        heartRates[pod.address] ?: HeartRateUi.of(pod.heartRate, pod.heartRateSensing)
}

/**
 * State for the accessory list.
 *
 * The view model's real job is turning "no results" into a reason. An empty screen
 * that just spins is indistinguishable from a broken app, and the three causes —
 * permission, Bluetooth, radio failure — need three different instructions.
 *
 * Dependencies arrive as constructor parameters rather than being looked up, so the
 * whole thing is constructible in a JVM test.
 */
class PodsViewModel(
    private val repository: PodRepository,
    environment: Flow<Environment>,
    /**
     * Null where the screen has nothing to switch.
     *
     * The heart-rate card offers "turn on" where it is off, and the full-screen view
     * offers "stop": both are the same setting the Settings screen owns, reachable from
     * where the state is being read. Optional so the view model stays constructible in a
     * test that only cares about the accessory list.
     */
    private val settings: SettingsRepository? = null,
) : ViewModel() {
    val state: StateFlow<PodsUiState> =
        combine(
            repository.pods,
            repository.scanFailure,
            environment,
            settings?.settings ?: flowOf(GreenPodsSettings.Default),
        ) { pods, failure, env, preferences ->
            PodsUiState(
                pods = pods,
                emptyReason =
                    when {
                        !env.scanPermissionGranted -> PodsEmptyReason.NO_PERMISSION
                        !env.bluetoothEnabled -> PodsEmptyReason.BLUETOOTH_OFF
                        failure != null -> PodsEmptyReason.SCAN_FAILED
                        else -> PodsEmptyReason.SEARCHING
                    },
                scanFailure = failure,
                heartRates =
                    pods.associate { pod ->
                        pod.address to HeartRateUi.of(pod.heartRate, pod.heartRateSensing)
                    },
                heartRateIntervalMillis = preferences.heartRateIntervalMillis,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PodsUiState())

    /** The heart-rate card's own switch, so the cost and the control sit together. */
    fun setHeartRateEnabled(enabled: Boolean) {
        val repository = settings ?: return
        viewModelScope.launch { repository.update { it.copy(heartRateEnabled = enabled) } }
    }

    /** Asks the radio to start scanning again after it refused. */
    fun retryScan() = repository.retryScan()

    /**
     * Asks the transport gate about an accessory, once, as soon as one is on screen.
     *
     * This used to be a button — "What this phone can control" — and the button existed
     * because the probe was manual. That got the burden backwards: until someone pressed
     * it, every feature the channel carries was shown locked, so the app's own main screen
     * told the truth only about a state it had never checked. A user pressing a button to
     * find out what their phone can do is the app asking them to do its work.
     *
     * The old worry was battery: probing every device that walks past costs a connection
     * attempt for an answer that is almost always "no". That worry is answered by *which*
     * accessories reach here, not by a button. The list has held only accessories this
     * phone is bonded to since v0.3.0, and the gate enforces probe-once — so this is at
     * most one attempt per accessory the user actually owns.
     */
    private fun probeWhenSeen() {
        viewModelScope.launch {
            repository.primaryPod
                .map { pod -> pod?.address }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { address -> repository.probeAap(address) }
        }
    }

    init {
        probeWhenSeen()
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
