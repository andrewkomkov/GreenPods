package io.github.andrewkomkov.greenpods.core.data.live

import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Which of four unavailable answers the user is given.
 *
 * The ordering of the checks is the substance, not an implementation detail: each answer
 * must be the most specific true thing, because someone reads it to work out whether their
 * phone, their settings, or the app is at fault. Telling a user to grant a permission that
 * would change nothing is a lie of omission.
 */
class LiveActivityGateTest {
    private fun platform(
        api: Int,
        notifications: Boolean = true,
        promoted: Boolean = true,
    ) = object : LiveActivityPlatform {
        override val apiLevel = api

        override fun notificationsPermitted() = notifications

        override fun promotedNotificationsPermitted() = promoted
    }

    @Test
    fun `a supporting phone with everything granted is available`() {
        LiveActivityGate(platform(api = 36)).availability() shouldBe LiveActivityAvailability.Available
    }

    @Test
    fun `an older phone is told its Android cannot do this, and nothing else`() {
        val availability = LiveActivityGate(platform(api = 35)).availability()

        availability.shouldBeInstanceOf<LiveActivityAvailability.PlatformTooOld>().apiLevel shouldBe 35
        availability.isAvailable shouldBe false
    }

    @Test
    fun `an older phone with notifications denied is still told the platform is the problem`() {
        // The decisive ordering case. Granting notifications on Android 14 would change
        // nothing, so reporting NotificationsDenied would send the user to fix something
        // that is not the obstacle.
        LiveActivityGate(platform(api = 34, notifications = false))
            .availability()
            .shouldBeInstanceOf<LiveActivityAvailability.PlatformTooOld>()
    }

    @Test
    fun `refusing promotion is not the same as being unable to promote`() {
        // A supporting phone where the user turned promoted notifications off. This is a
        // choice they can undo, and there is a settings screen to send them to — which is
        // exactly why it must not be reported as PlatformTooOld.
        LiveActivityGate(platform(api = 36, promoted = false))
            .availability() shouldBe LiveActivityAvailability.PromotionRefused
    }

    @Test
    fun `denied notifications outrank refused promotion`() {
        // Both are false. A promoted notification is still a notification, so the one that
        // must be fixed first is the one reported.
        LiveActivityGate(platform(api = 36, notifications = false, promoted = false))
            .availability() shouldBe LiveActivityAvailability.NotificationsDenied
    }

    @Test
    fun `every unavailable state reports itself unavailable`() {
        listOf(
            LiveActivityAvailability.PlatformTooOld(30),
            LiveActivityAvailability.PromotionRefused,
            LiveActivityAvailability.NotificationsDenied,
            LiveActivityAvailability.NotPromotable("declined"),
        ).forEach { it.isAvailable shouldBe false }

        LiveActivityAvailability.Available.isAvailable shouldBe true
    }

    @Test
    fun `the minimum is the API that introduced promoted notifications`() {
        // A wrong digit here would silently deny the feature to every phone that has it,
        // or offer it to phones that do not and fail at the call site.
        LiveActivityGate.MIN_SDK shouldBe 36
    }
}
