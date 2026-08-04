package io.github.andrewkomkov.greenpods.core.designsystem.component

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The one animation in this app whose timing is information rather than taste.
 *
 * `HeartBeatIcon` claims to beat at the rate it displays, which is only true if the
 * period really is derived from the value — so the derivation is pinned here. The
 * clamping matters as much as the arithmetic: an implausible value that somehow reached
 * the UI must produce a slow beat, never a strobe.
 */
class HeartBeatIconTest {
    @Test
    fun `the period is one minute divided by the rate`() {
        beatPeriodMillis(60) shouldBe 1_000
        beatPeriodMillis(120) shouldBe 500
        beatPeriodMillis(80) shouldBe 750
    }

    @Test
    fun `a faster rate always beats sooner`() {
        val periods = (30..200 step 10).map(::beatPeriodMillis)

        periods.zipWithNext().forEach { (slower, faster) -> (faster < slower) shouldBe true }
    }

    @Test
    fun `an implausible value is clamped rather than allowed to strobe`() {
        // Nothing should ever get here — HeartRateReading refuses to be constructed
        // outside 25..250 — but a UI that would flash at 60 Hz if it did is not a UI
        // worth shipping next to a health feature.
        beatPeriodMillis(0) shouldBe beatPeriodMillis(25)
        beatPeriodMillis(-40) shouldBe beatPeriodMillis(25)
        beatPeriodMillis(10_000) shouldBe beatPeriodMillis(250)
        (beatPeriodMillis(0) >= 240) shouldBe true
    }
}
