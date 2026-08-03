package io.github.andrewkomkov.greenpods.feature.controls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.PodControlGateway
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ControlsUiState(
    val pod: PodState? = null,
    /** True only when the Apple protocol channel is actually open. */
    val controlAvailable: Boolean = false,
    /** Why not, when it is not. Shown verbatim. */
    val gateReason: String = "",
    val probing: Boolean = false,
    val mode: NoiseControlMode? = null,
    val adaptiveStrength: Int = DEFAULT_ADAPTIVE_STRENGTH,
    val conversationalAwareness: Boolean = false,
    val longPressCycle: Set<NoiseControlMode> = DEFAULT_CYCLE,
    val supportsNoiseControl: Boolean = false,
    val supportsAdaptiveAudio: Boolean = false,
    val supportsConversationalAwareness: Boolean = false,
    /** A one-off note, e.g. a rejected edit. Cleared once shown. */
    val notice: String? = null,
) {
    companion object {
        const val DEFAULT_ADAPTIVE_STRENGTH = 50
        val DEFAULT_CYCLE = setOf(NoiseControlMode.NOISE_CANCELLATION, NoiseControlMode.TRANSPARENCY)
    }
}

/**
 * The noise-control screen's state.
 *
 * Two things it must get right, and they pull in opposite directions:
 *
 * - Nothing may be writable unless the transport gate says the channel is open. That is
 *   read from [PodState.activeTransports], never from the model's feature list.
 * - What the *hardware* supports still shapes the screen, so an AirPods 2 owner is not
 *   shown an adaptive-audio slider their buds could never honour even on an iPhone.
 */
class ControlsViewModel(
    private val repository: PodRepository,
    private val gateway: PodControlGateway,
) : ViewModel() {
    private val local = MutableStateFlow(LocalEdits())
    private val probing = MutableStateFlow(false)

    private data class LocalEdits(
        val adaptiveStrength: Int? = null,
        val longPressCycle: Set<NoiseControlMode>? = null,
        val notice: String? = null,
    )

    val state: StateFlow<ControlsUiState> =
        combine(repository.primaryPod, local, probing) { pod, edits, isProbing ->
            val aap = pod?.statusOf(Transport.AAP_L2CAP)
            ControlsUiState(
                pod = pod,
                controlAvailable = aap?.isAvailable == true,
                gateReason =
                    when {
                        pod == null -> {
                            "No accessory is in range."
                        }

                        aap?.availability == TransportAvailability.NOT_PROBED -> {
                            "Not checked yet. Tap to see whether this phone can carry settings commands."
                        }

                        else -> {
                            aap?.reason.orEmpty()
                        }
                    },
                probing = isProbing,
                mode = pod?.noiseControlMode,
                adaptiveStrength =
                    edits.adaptiveStrength
                        ?: pod?.adaptiveNoiseStrength
                        ?: ControlsUiState.DEFAULT_ADAPTIVE_STRENGTH,
                conversationalAwareness = pod?.conversationalAwarenessEnabled == true,
                longPressCycle = edits.longPressCycle ?: ControlsUiState.DEFAULT_CYCLE,
                supportsNoiseControl = pod.supports(PodFeature.NOISE_CONTROL),
                supportsAdaptiveAudio = pod.supports(PodFeature.ADAPTIVE_AUDIO),
                supportsConversationalAwareness = pod.supports(PodFeature.CONVERSATIONAL_AWARENESS),
                notice = edits.notice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ControlsUiState())

    /** Asks whether the channel can be opened, on demand rather than in a loop. */
    fun probe(force: Boolean = false) {
        val address = state.value.pod?.address ?: return
        viewModelScope.launch {
            probing.value = true
            try {
                repository.probeAap(address, force)
            } finally {
                probing.value = false
            }
        }
    }

    fun selectMode(mode: NoiseControlMode) {
        write { address -> gateway.setNoiseControlMode(address, mode) }
    }

    fun setAdaptiveStrength(percent: Int) {
        local.value = local.value.copy(adaptiveStrength = percent.coerceIn(0, 100))
    }

    /** Sliders fire continuously; the write happens once, when the finger lifts. */
    fun commitAdaptiveStrength() {
        val percent = state.value.adaptiveStrength
        write { address -> gateway.setAdaptiveNoiseStrength(address, percent) }
    }

    fun setConversationalAwareness(enabled: Boolean) {
        write { address -> gateway.setConversationalAwareness(address, enabled) }
    }

    /**
     * Edits the set of modes a stem long-press cycles through. An empty set is refused
     * rather than sent: the accessory has to cycle through something, and a zero mask
     * leaves it in a state the phone cannot undo.
     */
    fun toggleCycleMode(mode: NoiseControlMode) {
        val current = state.value.longPressCycle
        val updated = if (mode in current) current - mode else current + mode
        if (updated.isEmpty()) {
            local.value = local.value.copy(notice = "The long press has to cycle through at least one mode.")
            return
        }
        local.value = local.value.copy(longPressCycle = updated, notice = null)
        write { address -> gateway.setListeningModeCycle(address, updated) }
    }

    fun dismissNotice() {
        local.value = local.value.copy(notice = null)
    }

    /**
     * Runs a write only when the gate allows it, and turns a refused write into a
     * notice rather than into silence.
     */
    private fun write(action: suspend (String) -> Boolean) {
        val current = state.value
        val address = current.pod?.address ?: return
        if (!current.controlAvailable) return

        viewModelScope.launch {
            val written = action(address)
            if (!written) {
                local.value = local.value.copy(notice = "The accessory did not accept that command.")
            }
        }
    }

    private fun PodState?.supports(feature: PodFeature): Boolean = this != null && feature in model.features

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
