package io.github.andrewkomkov.greenpods.core.data.live

import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.github.andrewkomkov.greenpods.core.model.PodComponent
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState

/** Why there is no surface to show, when there is not one. */
sealed interface NoSurface {
    data class Unavailable(
        val availability: LiveActivityAvailability,
    ) : NoSurface

    /** The user switched the surface off; monitoring is unaffected. */
    data object TurnedOff : NoSurface

    /**
     * Monitoring is not running, so there is nothing for the surface to be a view onto.
     *
     * Kept apart from [TurnedOff] because they are undone by different actions, and the
     * adb output is read by someone trying to work out which.
     */
    data object MonitoringOff : NoSurface

    /** Nothing to describe. */
    data object NoAccessory : NoSurface

    /**
     * The user swiped it away.
     *
     * The platform is explicit that a dismissed live update must not be reposted, and a
     * state flow that ticks several times a second would otherwise repost it immediately —
     * turning one dismissal into an argument with the user.
     */
    data object Dismissed : NoSurface
}

/** Either what to show, or why nothing is shown. */
sealed interface LiveActivityDecision {
    data class Post(
        val summary: LiveActivitySummary,
    ) : LiveActivityDecision

    data class Withhold(
        val reason: NoSurface,
    ) : LiveActivityDecision
}

/**
 * Decides whether a live surface should be posted, and what it says.
 *
 * Pure. Everything interesting about this feature is a decision — which of battery, wear,
 * sensing and gate reason to show, how to say "not in range" without saying "0%", and what
 * the transport gate permits — and none of it needs a `Notification` to be exercised.
 *
 * The service that renders the result makes no decisions of its own. That split is the same
 * one `FrameLogPolicy` uses, for the same reason: a rule reachable only through a framework
 * object is a rule nobody tests.
 */
class LiveActivityPolicy {
    /**
     * True once the user has dismissed the surface, until something happens worth posting
     * again for.
     */
    private var dismissed = false
    private var dismissedForAddress: String? = null

    /** Records a dismissal so the next state tick does not undo it. */
    fun onDismissed(address: String?) {
        dismissed = true
        dismissedForAddress = address
    }

    fun decide(
        availability: LiveActivityAvailability,
        settings: GreenPodsSettings,
        pod: PodState?,
        monitoring: Boolean,
        nowEpochMillis: Long,
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    ): LiveActivityDecision {
        // Checked before any content is built, so nothing downstream can render a control
        // the gate has locked by forgetting to ask.
        if (!availability.isAvailable) return LiveActivityDecision.Withhold(NoSurface.Unavailable(availability))
        if (!monitoring) return LiveActivityDecision.Withhold(NoSurface.MonitoringOff)
        if (!settings.liveActivityEnabled) return LiveActivityDecision.Withhold(NoSurface.TurnedOff)
        if (pod == null) return LiveActivityDecision.Withhold(NoSurface.NoAccessory)

        // A different accessory is a genuine reason to post again — the dismissal was about
        // the one that was showing, not about the feature.
        if (dismissed && dismissedForAddress == pod.address && !pod.heartRate.isSensing) {
            return LiveActivityDecision.Withhold(NoSurface.Dismissed)
        }
        if (dismissedForAddress != pod.address || pod.heartRate.isSensing) {
            dismissed = false
            dismissedForAddress = null
        }

        return LiveActivityDecision.Post(summarise(pod, settings, nowEpochMillis, staleAfterMillis))
    }

    private fun summarise(
        pod: PodState,
        settings: GreenPodsSettings,
        nowEpochMillis: Long,
        staleAfterMillis: Long,
    ): LiveActivitySummary {
        val inRange =
            pod.lastSeenEpochMillis > 0L &&
                nowEpochMillis - pod.lastSeenEpochMillis <= staleAfterMillis

        // Out of range suppresses the levels rather than carrying them forward. The last
        // known percentage is still in memory and showing it is indistinguishable from
        // showing a current one — on a glanceable surface it would simply be believed.
        val battery = pod.battery.takeIf { inRange }

        return LiveActivitySummary(
            accessoryName = pod.name,
            presence = if (inRange) Presence.IN_RANGE else Presence.OUT_OF_RANGE,
            leftPercent = battery?.left?.levelPercent,
            rightPercent = battery?.right?.levelPercent,
            casePercent = battery?.case?.levelPercent,
            charging =
                buildSet {
                    if (battery?.left?.isCharging == true) add(PodComponent.LEFT)
                    if (battery?.right?.isCharging == true) add(PodComponent.RIGHT)
                    if (battery?.case?.isCharging == true) add(PodComponent.CASE)
                },
            wear = pod.earDetection,
            noiseControl = noiseControlState(pod),
            sensing = sensingState(pod, settings),
        )
    }

    /**
     * Offered only when the transport gate says so, and locked with the gate's own words
     * otherwise — never derived from the model's feature list, which describes hardware
     * rather than what this phone can reach.
     */
    private fun noiseControlState(pod: PodState): ControlState =
        if (PodFeature.NOISE_CONTROL in pod.usableFeatures) {
            ControlState.Offered(pod.noiseControlMode)
        } else {
            ControlState.Locked(pod.reasonFor(PodFeature.NOISE_CONTROL))
        }

    /**
     * The disclosure is unconditional while sensing runs; only the number is optional.
     *
     * `isSensing` is deliberately broader than "has a trusted reading" — settling and
     * uncertain both cost the accessory's battery, and a disclosure that waited for a
     * trustworthy number would stay silent through exactly the period a user most wants to
     * know about.
     */
    private fun sensingState(
        pod: PodState,
        settings: GreenPodsSettings,
    ): SensingState {
        val state = pod.heartRate
        if (!state.isSensing) return SensingState.Idle
        if (!settings.liveActivityShowHeartRate) return SensingState.Disclosed
        val reading = state.trustedReading ?: return SensingState.Disclosed
        return SensingState.DisclosedWithRate(reading.beatsPerMinute)
    }

    companion object {
        /**
         * How long after the last sighting the accessory counts as out of range.
         *
         * Matches the repository's own ageing window, so the surface and the app never
         * disagree about whether the earbuds are there.
         */
        const val DEFAULT_STALE_AFTER_MILLIS = 30_000L
    }
}
