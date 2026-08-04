package io.github.andrewkomkov.greenpods.feature.pods

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * What the card is allowed to say, and what it is allowed to show.
 *
 * The presentation mapping is a pure function precisely so these two questions can be
 * answered without launching Compose — and because the second of them, FR-010, is a
 * scope boundary that decays one well-meant sentence at a time.
 */
class HeartRateUiTest {
    private val reading =
        HeartRateReading(
            beatsPerMinute = 81,
            confidence = 205,
            source = HeartRateReading.Source.AAP,
            measuredAtEpochMillis = 1_770_000_000_000L,
        )

    @Test
    fun `settling renders no number at all`() {
        val ui = HeartRateUi.of(HeartRateState.Settling(sinceEpochMillis = 1L))

        ui.kind shouldBe HeartRateUi.Kind.SETTLING
        ui.beatsPerMinute.shouldBeNull()
        ui.kind.showsProgress shouldBe true
        ui.title shouldContain "Measuring"
    }

    @Test
    fun `uncertain withdraws the last number rather than keeping it on screen`() {
        val measuring = HeartRateUi.of(HeartRateState.Measuring(reading))
        measuring.beatsPerMinute shouldBe 81

        val uncertain = HeartRateUi.of(HeartRateState.Uncertain(reading.measuredAtEpochMillis))

        uncertain.beatsPerMinute.shouldBeNull()
        uncertain.kind shouldBe HeartRateUi.Kind.UNCERTAIN
        // Withdrawn, not blanked: the card still explains itself and still says the
        // sensor is running.
        uncertain.body shouldContain "Still measuring"
    }

    @Test
    fun `only measuring ever produces a number`() {
        val states =
            listOf(
                HeartRateState.Settling(1L),
                HeartRateState.Starting(1L),
                HeartRateState.Uncertain(1L),
                HeartRateState.Off,
                HeartRateState.Unavailable("Put an earbud in to measure."),
                HeartRateState.Locked("This phone cannot open the channel.", Transport.AAP_L2CAP),
                HeartRateState.Unsupported("These earbuds have no heart-rate sensor."),
            )

        states.forEach { state -> HeartRateUi.of(state).beatsPerMinute.shouldBeNull() }
    }

    @Test
    fun `locked and unsupported are different cards carrying different reasons`() {
        val locked =
            HeartRateUi.of(HeartRateState.Locked("This phone cannot open the channel.", Transport.AAP_L2CAP))
        val unsupported = HeartRateUi.of(HeartRateState.Unsupported("These earbuds have no heart-rate sensor."))

        locked.kind shouldBe HeartRateUi.Kind.LOCKED
        unsupported.kind shouldBe HeartRateUi.Kind.UNSUPPORTED
        locked.body shouldContain "This phone"
        unsupported.body shouldContain "earbuds"
    }

    @Test
    fun `the route that produced a number is named rather than hidden`() {
        val aap = HeartRateUi.of(HeartRateState.Measuring(reading))
        val gatt =
            HeartRateUi.of(
                HeartRateState.Measuring(reading.copy(source = HeartRateReading.Source.GATT, confidence = null)),
            )

        aap.body shouldContain "Apple's protocol"
        gatt.body shouldContain "Bluetooth heart-rate profile"
    }

    @Test
    fun `talkback hears a state, never a bare number`() {
        HeartRateUi.of(HeartRateState.Measuring(reading)).spoken shouldContain "Heart rate, 81 beats per minute"
        HeartRateUi.of(HeartRateState.Settling(1L)).spoken shouldContain "measuring in progress"
        HeartRateUi.of(HeartRateState.Settling(1L)).spoken shouldContain "no reading yet"
    }

    @Test
    fun `an unavailable card shows the reason, not the adb token`() {
        // `notWorn` is what a script greps for. Putting it on a screen is how a
        // diagnostic surface leaks into a product one.
        val ui =
            HeartRateUi.of(
                HeartRateState.Unavailable("Put an earbud in to measure."),
                HeartRateSensing(lastStopReason = "notWorn"),
            )

        ui.body shouldBe "Put an earbud in to measure."
    }

    @Test
    fun `no string the card can show interprets a heart rate`() {
        // FR-010, as a boundary with teeth. Every one of these words would turn a sensor
        // reading into a claim about the reader's health, which is what the out-of-scope
        // list — no zones, no alerts, no interpretation — exists to prevent. If a new
        // state needs one of them, re-read FR-010 rather than editing this list.
        val clinical =
            listOf(
                "normal",
                "abnormal",
                "healthy",
                "unhealthy",
                "resting rate",
                "resting heart rate",
                "target",
                "zone",
                "elevated",
                "high",
                "low heart",
                "too fast",
                "too slow",
                "tachycard",
                "bradycard",
                "diagnos",
                "doctor",
                "medical",
                "condition",
                "fitness level",
                "recovery",
                "average for",
            )

        HeartRateCopy.everySentence().forEach { sentence ->
            val lower = sentence.lowercase()
            clinical.forEach { word ->
                if (lower.contains(word)) {
                    throw AssertionError("Clinical framing in heart-rate copy: \"$sentence\" contains \"$word\"")
                }
            }
        }
    }

    @Test
    fun `every state has copy, and none of it is blank`() {
        HeartRateCopy.everySentence().forEach { sentence -> sentence.isNotBlank() shouldBe true }
    }
}
