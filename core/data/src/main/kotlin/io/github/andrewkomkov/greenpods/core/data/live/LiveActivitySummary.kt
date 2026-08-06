package io.github.andrewkomkov.greenpods.core.data.live

import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodComponent

/**
 * Is the accessory currently being heard from?
 *
 * A separate axis from battery, and it has to be: when advertisements stop arriving the
 * last known levels are still in memory, and presenting them is indistinguishable from
 * presenting current ones. A glanceable surface is exactly where that would be believed.
 */
enum class Presence { IN_RANGE, OUT_OF_RANGE }

/** A control the surface may carry, and whether this phone can actually offer it. */
sealed interface ControlState {
    data class Offered(
        val mode: NoiseControlMode?,
    ) : ControlState

    /** Shown, with the gate's own words. Never hidden — a missing control reads as a bug. */
    data class Locked(
        val reason: String,
    ) : ControlState
}

/**
 * What the surface says about heart-rate sensing.
 *
 * Three states, and the distinction between the last two is the whole of the decision that
 * a reading may appear on a lock screen: the user's setting chooses between [Disclosed] and
 * [DisclosedWithRate], and can never reach [Idle].
 *
 * That asymmetry is deliberate. A user may decline to display their heart rate on a screen
 * anyone could read. They may not have the accessory's optical sensor running without being
 * told that it is.
 */
sealed interface SensingState {
    /** No session. The surface mentions heart rate not at all. */
    data object Idle : SensingState

    /** A session is running and the user has chosen not to show the number. */
    data object Disclosed : SensingState

    data class DisclosedWithRate(
        val beatsPerMinute: Int,
    ) : SensingState
}

/**
 * Everything the live surface shows at one moment.
 *
 * Derived state that owns nothing — produced by [LiveActivityPolicy] from pod state,
 * settings and the availability gate, and rendered by the service without further
 * decisions. Pure, so the rules below are testable without a notification or a device.
 *
 * The nullable percentages carry the load: **null is "unknown", and must never render as
 * `0`**. The accessory reports an explicit unknown sentinel, a case that has not been
 * opened has no level at all, and a bud at zero is a different fact from either.
 */
data class LiveActivitySummary(
    val accessoryName: String,
    val presence: Presence,
    val leftPercent: Int?,
    val rightPercent: Int?,
    val casePercent: Int?,
    val charging: Set<PodComponent>,
    val wear: EarDetectionState,
    val noiseControl: ControlState,
    val sensing: SensingState,
) {
    /**
     * True when nothing about the battery is known.
     *
     * Separated from [Presence] on purpose: an accessory can be in range and still not
     * have said anything about its case.
     */
    val hasNoBatteryReading: Boolean
        get() = leftPercent == null && rightPercent == null && casePercent == null
}
