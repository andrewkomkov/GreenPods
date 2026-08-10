package io.github.andrewkomkov.greenpods.ui

import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/**
 * Which sentence a user is shown for each reason the live surface is unavailable.
 *
 * This lives in `app` because it is the only module that can name `R`. `feature/settings`
 * tests that each state arrives carrying *a* distinct, non-zero id and its arguments —
 * which is everything it can check, and one thing short of enough: two states wired to
 * each other's reason would satisfy every assertion over there. What would reach the user
 * is a confident, specific, wrong explanation of what their phone cannot do, which is the
 * exact failure Principle II exists to prevent.
 *
 * A resource id is an `Int` at compile time, so none of this needs a device.
 */
class GreenPodsViewModelsTest {
    @Test
    fun `each unavailable reason maps to its own sentence`() {
        val cases =
            listOf(
                LiveActivityAvailability.PlatformTooOld(apiLevel = 34) to R.string.live_unavailable_platform,
                LiveActivityAvailability.NotificationsDenied to R.string.live_unavailable_notifications_denied,
                LiveActivityAvailability.PromotionRefused to R.string.live_unavailable_promotion_refused,
                LiveActivityAvailability.NotPromotable("style") to R.string.live_unavailable_not_promotable,
            )

        cases.forEach { (availability, expected) ->
            val state = GreenPodsViewModels.liveActivityState(availability)

            state.reasonRes shouldBe expected
            state.availability shouldBe availability
            state.isAvailable shouldBe false
        }
    }

    @Test
    fun `an available surface has nothing to explain`() {
        val state = GreenPodsViewModels.liveActivityState(LiveActivityAvailability.Available)

        state.isAvailable shouldBe true
        // Zero rather than a reassuring sentence: the screen branches on this to draw a
        // section instead of a locked card, and there is no "it works" reason to show.
        state.reasonRes shouldBe 0
        state.reasonArgs shouldBe emptyList()
    }

    @Test
    fun `the two interpolated reasons carry what they interpolate`() {
        // The sentence names the phone's own API level, and the device's own words for
        // why it refused. Carried as arguments rather than formatted in, so that they are
        // resolved against the configuration in force when the card is drawn.
        val tooOld = GreenPodsViewModels.liveActivityState(LiveActivityAvailability.PlatformTooOld(apiLevel = 34))
        tooOld.reasonArgs shouldBe listOf(34)

        val declined = GreenPodsViewModels.liveActivityState(LiveActivityAvailability.NotPromotable("bad style"))
        declined.reasonArgs shouldBe listOf("bad style")
    }

    @Test
    fun `no two reasons share a sentence`() {
        // The assertion the settings module cannot make: not merely four distinct ids, but
        // four distinct ids that are *these* four resources. A swap between two states is
        // invisible to any check that only counts them.
        val ids =
            listOf(
                LiveActivityAvailability.PlatformTooOld(apiLevel = 34),
                LiveActivityAvailability.NotificationsDenied,
                LiveActivityAvailability.PromotionRefused,
                LiveActivityAvailability.NotPromotable("style"),
            ).map { GreenPodsViewModels.liveActivityState(it).reasonRes }

        ids.distinct().size shouldBe ids.size
        ids.forEach { it shouldNotBe 0 }
    }

    @Test
    fun `only a refused promotion offers a route back`() {
        // Checked here as well as in the settings module because this is where the pairing
        // of state to reason is fixed: the route-back button sits on the one card whose
        // sentence tells the user to go and turn something on.
        GreenPodsViewModels
            .liveActivityState(LiveActivityAvailability.PromotionRefused)
            .hasRouteBack shouldBe true

        listOf(
            LiveActivityAvailability.PlatformTooOld(apiLevel = 34),
            LiveActivityAvailability.NotificationsDenied,
            LiveActivityAvailability.NotPromotable("style"),
            LiveActivityAvailability.Available,
        ).forEach { GreenPodsViewModels.liveActivityState(it).hasRouteBack shouldBe false }
    }
}
