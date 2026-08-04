package io.github.andrewkomkov.greenpods.feature.pods

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * What a user who cannot see the card is told.
 *
 * Accessibility here is not a coat of paint over a finished screen: the card's whole job
 * is to stop a number being mistaken for a trustworthy one, and a screen reader that
 * announces "81" has undone that entirely regardless of how the sighted version looks.
 * These assertions are the same requirements as the visual ones, read aloud.
 */
class HeartRateAccessibilityTest {
    private val reading =
        HeartRateReading(
            beatsPerMinute = 81,
            confidence = 205,
            source = HeartRateReading.Source.AAP,
            measuredAtEpochMillis = 0L,
        )

    private fun spoken(state: HeartRateState) = HeartRateCopy.spoken(state)

    @Test
    fun `every state is announced as a state, not as a value`() {
        // "Heart rate, …" first in every case. A reader that hears the number before the
        // context has already been told the thing FR-006 exists to prevent.
        listOf(
            HeartRateState.Measuring(reading),
            HeartRateState.Settling(0L),
            HeartRateState.Starting(0L),
            HeartRateState.Uncertain(0L),
            HeartRateState.Off,
            HeartRateState.Unavailable("Put an earbud in to measure."),
            HeartRateState.Locked("This phone cannot open the channel.", Transport.AAP_L2CAP),
            HeartRateState.Unsupported("These earbuds have no heart-rate sensor."),
        ).forEach { state -> spoken(state).startsWith("Heart rate") shouldBe true }
    }

    @Test
    fun `settling is announced as measuring in progress, and carries no digits`() {
        val settling = spoken(HeartRateState.Settling(0L))

        settling shouldContain "measuring in progress"
        settling shouldContain "no reading yet"
        settling.any(Char::isDigit) shouldBe false
    }

    @Test
    fun `only a trusted reading is ever spoken as a number`() {
        spoken(HeartRateState.Measuring(reading)) shouldContain "81 beats per minute"

        listOf(
            HeartRateState.Settling(0L),
            HeartRateState.Starting(0L),
            HeartRateState.Uncertain(0L),
            HeartRateState.Off,
            HeartRateState.Unavailable(""),
        ).forEach { state -> spoken(state).any(Char::isDigit) shouldBe false }
    }

    @Test
    fun `uncertain says the reading was withdrawn rather than going silent`() {
        // Visually the number shrinks away; spoken, the equivalent is being told why.
        // Silence would read as the app having lost the reading.
        val uncertain = spoken(HeartRateState.Uncertain(1L))

        uncertain shouldContain "low confidence"
        uncertain shouldContain "withdrawn"
    }

    @Test
    fun `no two states sound the same`() {
        // The visual rule is that each state is a different shape rather than a different
        // string in one shape. Spoken, "different shape" can only mean "different words".
        val spokenStates =
            listOf(
                HeartRateState.Measuring(reading),
                HeartRateState.Settling(0L),
                HeartRateState.Starting(0L),
                HeartRateState.Uncertain(0L),
                HeartRateState.Off,
                HeartRateState.Unavailable("Put an earbud in to measure."),
                HeartRateState.Locked("This phone cannot open the channel.", Transport.AAP_L2CAP),
                HeartRateState.Unsupported("These earbuds have no heart-rate sensor."),
            ).map(::spoken)

        spokenStates.toSet().size shouldBe spokenStates.size
    }

    @Test
    fun `the two routes are distinguishable by ear, not only by a colour or a chip`() {
        val aap = spoken(HeartRateState.Measuring(reading))
        val gatt =
            spoken(HeartRateState.Measuring(reading.copy(source = HeartRateReading.Source.GATT, confidence = null)))

        (aap == gatt) shouldBe false
        gatt shouldContain "Bluetooth heart-rate profile"
    }
}
