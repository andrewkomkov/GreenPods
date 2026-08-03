package io.github.andrewkomkov.greenpods.core.data.control

import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode

/**
 * Everything the app can *write* to an accessory.
 *
 * All of it needs the Apple protocol over L2CAP, which most Android phones refuse to
 * open — so every method returns whether the write actually happened rather than
 * throwing. A write that goes nowhere is the expected case on a stock device, and the
 * UI turns that `false` into an explanation rather than an error.
 *
 * An interface because the view models must be testable without a Bluetooth stack, and
 * because a device that *can* open the channel is not available to test against.
 */
interface PodControlGateway {
    suspend fun setNoiseControlMode(
        address: String,
        mode: NoiseControlMode,
    ): Boolean

    suspend fun setAdaptiveNoiseStrength(
        address: String,
        percent: Int,
    ): Boolean

    suspend fun setConversationalAwareness(
        address: String,
        enabled: Boolean,
    ): Boolean

    /** Returns false without writing when [modes] is empty — see `AapCommands`. */
    suspend fun setListeningModeCycle(
        address: String,
        modes: Set<NoiseControlMode>,
    ): Boolean
}
