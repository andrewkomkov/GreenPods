package io.github.andrewkomkov.greenpods.core.data.live

import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability

/**
 * What the platform will tell us about promoting a notification.
 *
 * An interface rather than direct calls so the resolution below can be unit-tested. The
 * three questions are genuinely independent and a real device answers them from three
 * different places, which is exactly why they are easy to conflate in a single `if`.
 */
interface LiveActivityPlatform {
    /** This device's API level. */
    val apiLevel: Int

    /** Whether notifications can be posted at all. */
    fun notificationsPermitted(): Boolean

    /**
     * Whether the user allows *promoted* notifications for this app.
     *
     * Only meaningful at or above [LiveActivityGate.MIN_SDK]; below it the platform has no
     * such concept and callers must not ask.
     */
    fun promotedNotificationsPermitted(): Boolean
}

/**
 * Resolves whether the live surface can be shown, and why not when it cannot.
 *
 * Pure given a [LiveActivityPlatform]. The ordering of the checks is the substance here,
 * not an implementation detail: each answer must be the *most specific true thing*, because
 * the reason is shown to a user who is trying to work out whether their phone, their
 * settings, or the app is at fault.
 *
 * Concretely, an Android 14 phone with notifications denied is reported as
 * [LiveActivityAvailability.PlatformTooOld] and not as `NotificationsDenied`: granting the
 * permission would change nothing, so telling them to grant it would be a lie of omission.
 */
class LiveActivityGate(
    private val platform: LiveActivityPlatform,
) {
    fun availability(): LiveActivityAvailability {
        // First, because it is the only one the user can do nothing about, and every other
        // answer would imply an action that would not help.
        if (platform.apiLevel < MIN_SDK) {
            return LiveActivityAvailability.PlatformTooOld(platform.apiLevel)
        }

        // Before promotion, because a promoted notification is still a notification.
        if (!platform.notificationsPermitted()) {
            return LiveActivityAvailability.NotificationsDenied
        }

        if (!platform.promotedNotificationsPermitted()) {
            return LiveActivityAvailability.PromotionRefused
        }

        return LiveActivityAvailability.Available
    }

    companion object {
        /**
         * Android 16, where promoted ongoing notifications arrive.
         *
         * Mirrors `GreenPodsConfig.LIVE_ACTIVITY_MIN_SDK`; duplicated rather than depended
         * on because `core/data` does not see the build-logic module, and a single wrong
         * digit here would be caught by [LiveActivityGateTest].
         */
        const val MIN_SDK = 36
    }
}
