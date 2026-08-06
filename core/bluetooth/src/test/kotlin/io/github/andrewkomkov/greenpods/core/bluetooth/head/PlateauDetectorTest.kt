package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * What counts as holding a pose.
 *
 * Every fixture here is synthetic and says so — see [HeadCalibrationFixtures]. These tests
 * are about the predicate, not about any real head: whether a stretch of samples was steady
 * enough, for long enough, across *every* field.
 */
class PlateauDetectorTest {
    private val detector = PlateauDetector()

    @Test
    fun `a clean hold is found, and its median describes it`() {
        val held = detector.longestHold(HeadCalibrationFixtures.steady(o1 = 6_290, count = 60))

        held.shouldNotBeNull()
        held.median(OrientationField.O1) shouldBe 6_290f
        held.sampleCount shouldBeGreaterThanOrEqual 50
        held.durationMillis shouldBeGreaterThanOrEqual PlateauDetector.DEFAULT_MINIMUM_HOLD_MILLIS
    }

    @Test
    fun `jitter inside the tolerance still counts as held`() {
        // A person holding still is not a signal generator. The tolerance exists so that a
        // real hold is recognised, and a detector that demanded exact equality would report
        // every genuine pose as a failure.
        val held = detector.longestHold(HeadCalibrationFixtures.steady(o1 = 6_290, count = 60, jitter = 300))

        held.shouldNotBeNull()
        held.spans[OrientationField.O1]!! shouldBe 600
    }

    @Test
    fun `jitter wider than the tolerance is not held`() {
        detector
            .longestHold(HeadCalibrationFixtures.steady(o1 = 6_290, count = 60, jitter = 4_000))
            .shouldBeNull()
    }

    @Test
    fun `a hold shorter than the minimum duration does not count`() {
        // Eight samples at 40 ms is 320 ms — steady, and nowhere near long enough to be a
        // measurement.
        detector.longestHold(HeadCalibrationFixtures.steady(o1 = 6_290, count = 8)).shouldBeNull()
    }

    @Test
    fun `a continuous drift has no plateau at all`() {
        detector.longestHold(HeadCalibrationFixtures.drifting(count = 60)).shouldBeNull()
    }

    @Test
    fun `the plateau is found inside the collected window, not assumed to be all of it`() {
        // The wearer settles late. The countdown bounds when samples are collected; the
        // detector finds the hold within them. A detector that averaged the whole window
        // would fold the settling noise into the answer and call it a measurement.
        val samples = HeadCalibrationFixtures.settleThenHold(o1 = 6_290, noiseCount = 15, holdCount = 60)
        val held = detector.longestHold(samples)

        held.shouldNotBeNull()

        // The median is the hold, not an average of the approach. That is the assertion that
        // matters: the settling samples exist in the window and do not move the answer.
        held.median(OrientationField.O1) shouldBe 6_290f

        // The span is NOT asserted to be zero, and the first version of this test was wrong
        // to expect that. A wearer approaching a pose passes through values near it, so the
        // last sample or two of the approach are genuinely within tolerance of the hold and
        // belong to it. The rule is the stated tolerance, not perfect stillness.
        held.spans[OrientationField.O1]!! shouldBeLessThanOrEqual PlateauDetector.DEFAULT_TOLERANCE_UNITS
    }

    @Test
    fun `a pose steady in its own axis but drifting in another is not held`() {
        // The case that matters most for the axis check, and the reason the tolerance is
        // applied to every field rather than to the one the pose is about. A wearer who
        // turns their head while slowly nodding has not held anything, and treating it as a
        // yaw measurement would attribute the pitch drift to yaw's scale.
        val samples =
            HeadCalibrationFixtures.steady(o1 = 6_290, count = 60).mapIndexed { index, timed ->
                timed.copy(
                    sample = timed.sample.copy(orientation2 = (index * 200).toShort()),
                )
            }

        detector.longestHold(samples).shouldBeNull()
    }

    @Test
    fun `the longest hold wins when there is more than one`() {
        // Settle, drift, settle again for longer. The second measurement is the better one
        // and taking the first would throw it away.
        val first = HeadCalibrationFixtures.steady(o1 = 1_000, count = 60, startMillis = 0)
        val drift = HeadCalibrationFixtures.drifting(count = 20, startMillis = 2_400)
        val second = HeadCalibrationFixtures.steady(o1 = 6_290, count = 120, startMillis = 3_200)

        val held = detector.longestHold(first + drift + second)

        held.shouldNotBeNull()
        held.median(OrientationField.O1) shouldBe 6_290f
    }

    @Test
    fun `a refusal says what moved and by how much`() {
        // "You moved" is not something anyone can act on. The number is what turns it into
        // an instruction.
        val reason = detector.refusal(HeadCalibrationFixtures.steady(o1 = 6_290, count = 60, jitter = 4_000))

        reason shouldContain "never settled"
        reason shouldContain "O1"
        reason shouldContain "8000"
    }

    @Test
    fun `a refusal distinguishes too-short from too-noisy`() {
        val reason = detector.refusal(HeadCalibrationFixtures.steady(o1 = 6_290, count = 12))

        reason shouldContain "held for only"
    }

    @Test
    fun `a stream that stopped is named as such rather than as a wobble`() {
        val reason = detector.refusal(HeadCalibrationFixtures.steady(o1 = 6_290, count = 3))

        reason shouldContain "the stream may have stopped"
    }
}
