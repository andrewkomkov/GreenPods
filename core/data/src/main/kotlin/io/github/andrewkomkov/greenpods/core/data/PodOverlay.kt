package io.github.andrewkomkov.greenpods.core.data

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeartRateSample
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodState

/**
 * What the connected transports know that the advertisement cannot say.
 *
 * The advertisement arrives every couple of seconds and rewrites battery and wear
 * state; noise-control mode and heart rate come from AAP and GATT and arrive on their
 * own schedule. Keeping the connected knowledge in a separate overlay is what stops
 * the next advertisement from wiping it — a merge rule that is easy to state and easy
 * to get wrong if it lives inline in a flow operator.
 *
 * Every field is nullable and null means "nothing learned", never "cleared".
 */
data class PodOverlay(
    val name: String? = null,
    val noiseControlMode: NoiseControlMode? = null,
    val conversationalAwarenessEnabled: Boolean? = null,
    val adaptiveNoiseStrength: Int? = null,
    val heartRate: HeartRateSample? = null,
    /**
     * AAP reports battery to the percent; the advertisement only in steps of ten. When
     * the channel is open its reading wins, which is why battery is part of the overlay
     * rather than left to the advertisement.
     */
    val battery: BatteryState? = null,
    val earDetection: EarDetectionState? = null,
) {
    /** Folds one decoded AAP message in. Unknown traffic changes nothing. */
    fun reduce(event: AapEvent): PodOverlay =
        when (event) {
            is AapEvent.NoiseControl -> copy(noiseControlMode = event.mode)

            is AapEvent.ConversationalAwarenessState -> copy(conversationalAwarenessEnabled = event.enabled)

            is AapEvent.AdaptiveNoiseStrength -> copy(adaptiveNoiseStrength = event.level)

            is AapEvent.DeviceInfo -> event.fields.firstOrNull()?.let { copy(name = it) } ?: this

            is AapEvent.Battery -> copy(battery = event.state)

            is AapEvent.EarDetection -> copy(earDetection = event.state)

            // The speech level, head poses and unknown traffic describe a moment rather
            // than a setting, and belong in diagnostics instead of in state.
            else -> this
        }

    fun applyTo(pod: PodState): PodState =
        pod.copy(
            name = name ?: pod.name,
            noiseControlMode = noiseControlMode ?: pod.noiseControlMode,
            conversationalAwarenessEnabled = conversationalAwarenessEnabled ?: pod.conversationalAwarenessEnabled,
            adaptiveNoiseStrength = adaptiveNoiseStrength ?: pod.adaptiveNoiseStrength,
            heartRate = heartRate ?: pod.heartRate,
            battery = battery ?: pod.battery,
            earDetection = earDetection ?: pod.earDetection,
        )

    val isEmpty: Boolean
        get() = this == Empty

    companion object {
        val Empty = PodOverlay()
    }
}
