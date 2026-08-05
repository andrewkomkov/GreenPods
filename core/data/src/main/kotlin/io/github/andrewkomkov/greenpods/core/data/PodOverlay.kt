package io.github.andrewkomkov.greenpods.core.data

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
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
    /**
     * What the heart-rate session published, if one is running. The transport gate is
     * applied later, by [PodState.heartRate] — this is only what was measured.
     */
    val heartRate: HeartRateState? = null,
    val heartRateSensing: HeartRateSensing? = null,
    /**
     * AAP reports battery to the percent; the advertisement only in steps of ten. When
     * the channel is open its reading wins, which is why battery is part of the overlay
     * rather than left to the advertisement.
     */
    val battery: BatteryState? = null,
) {
    /** Folds one decoded AAP message in. Unknown traffic changes nothing. */
    fun reduce(event: AapEvent): PodOverlay =
        when (event) {
            is AapEvent.NoiseControl -> copy(noiseControlMode = event.mode)

            is AapEvent.ConversationalAwarenessState -> copy(conversationalAwarenessEnabled = event.enabled)

            is AapEvent.AdaptiveNoiseStrength -> copy(adaptiveNoiseStrength = event.level)

            is AapEvent.DeviceInfo -> event.fields.firstOrNull()?.let { copy(name = it) } ?: this

            is AapEvent.Battery -> copy(battery = event.state)

            // Wear is deliberately absent, and this is the merge rule that matters most.
            //
            // The channel announces that a bud's wear changed but not which bud — measured
            // 2026-08-05, either bud moves the same byte. The advertisement carries the
            // primary flag and so can say the side. Folding the channel's version in here
            // meant the side-less reading overwrote the side-resolved one, and taking out
            // the left bud made the app say the right one was out.
            //
            // So per-side wear comes from the advertisement alone. The channel's frame is
            // still decoded and still reaches diagnostics as an event; what it must not do
            // is become state that claims a side it does not know.

            // The speech level, head poses, wear changes and unknown traffic describe a
            // moment rather than a setting, and belong in diagnostics instead of in state.
            else -> this
        }

    fun applyTo(pod: PodState): PodState =
        pod.copy(
            name = name ?: pod.name,
            noiseControlMode = noiseControlMode ?: pod.noiseControlMode,
            conversationalAwarenessEnabled = conversationalAwarenessEnabled ?: pod.conversationalAwarenessEnabled,
            adaptiveNoiseStrength = adaptiveNoiseStrength ?: pod.adaptiveNoiseStrength,
            heartRateSession = heartRate ?: pod.heartRateSession,
            heartRateSensing = heartRateSensing ?: pod.heartRateSensing,
            battery = battery ?: pod.battery,
        )

    val isEmpty: Boolean
        get() = this == Empty

    companion object {
        val Empty = PodOverlay()
    }
}
