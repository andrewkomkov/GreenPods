package io.github.andrewkomkov.greenpods.feature.pods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.environment.Environment
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) {
    val isEmpty: Boolean get() = pods.isEmpty()
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
) : ViewModel() {
    val state: StateFlow<PodsUiState> =
        combine(repository.pods, repository.scanFailure, environment) { pods, failure, env ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PodsUiState())

    /**
     * Asks the transport gate about an accessory the user tapped. Probing every device
     * that walks past would drain the battery for an answer that is almost always "no".
     */
    fun probeTransports(address: String) {
        viewModelScope.launch { repository.probeAap(address) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
