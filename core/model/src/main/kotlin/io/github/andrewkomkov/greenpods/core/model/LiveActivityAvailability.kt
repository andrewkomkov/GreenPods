package io.github.andrewkomkov.greenpods.core.model

/**
 * Whether this phone can show the live status surface, and why not when it cannot.
 *
 * Deliberately the same shape as `AapAvailability`, for the same reason: a user must be
 * able to tell "my phone can't do this", "I turned this off", and "the app is broken"
 * apart. A single boolean collapses all three into a missing feature, which is the exact
 * failure the "locked, not hidden" principle exists to prevent.
 *
 * Every non-available state carries what the user can do about it — including, for
 * [PlatformTooOld], that there is nothing.
 */
sealed interface LiveActivityAvailability {
    /** The surface can be posted. */
    data object Available : LiveActivityAvailability

    /**
     * Below the API level that introduced promoted ongoing notifications.
     *
     * The common case by a wide margin: this app supports Android 8.0 and the surface
     * arrives in Android 16. Monitoring is unaffected and the ordinary notification is
     * unchanged — those phones lose nothing they had.
     */
    data class PlatformTooOld(
        val apiLevel: Int,
    ) : LiveActivityAvailability

    /**
     * The platform can promote notifications, and the user has said not to for this app.
     *
     * A choice, not a fault. Distinct from [PlatformTooOld] because the user can undo this
     * one and there is a system settings screen to send them to.
     */
    data object PromotionRefused : LiveActivityAvailability

    /** Notification permission is not granted, so nothing can be posted at all. */
    data object NotificationsDenied : LiveActivityAvailability

    /**
     * The platform examined the notification and declined to promote it.
     *
     * This exists because the permitted set of notification styles is not something this
     * project could establish from the documentation — two Android pages disagree — so the
     * app asks `hasPromotableCharacteristics()` rather than assuming, and reports what it
     * is told. An answer of "no" is a fact about this device, and a fact is more useful
     * shown than swallowed.
     */
    data class NotPromotable(
        val reason: String,
    ) : LiveActivityAvailability

    val isAvailable: Boolean get() = this is Available
}
