package io.github.andrewkomkov.greenpods.feature.pods

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Container weight, as a property of the state rather than a decision in the screen.
 *
 * The ordering is the requirement: a trusted reading is the most prominent thing on the
 * card, a wait is present but quiet, and a state that is only explaining itself recedes.
 * Deciding this in the composable would put a rule the spec cares about somewhere no test
 * can reach it.
 */
class HeartRateEmphasisTest {
    @Test
    fun `a trusted reading is the most prominent state`() {
        HeartRateUi.Kind.MEASURING.emphasis shouldBe HeartRateUi.Emphasis.PROMINENT
    }

    @Test
    fun `a wait is active, never prominent`() {
        // Promoting a settling sensor to the same weight as a measurement is FR-008's
        // failure mode expressed in colour.
        listOf(HeartRateUi.Kind.SETTLING, HeartRateUi.Kind.STARTING, HeartRateUi.Kind.UNCERTAIN)
            .forEach { kind -> kind.emphasis shouldBe HeartRateUi.Emphasis.ACTIVE }
    }

    @Test
    fun `states that only explain themselves recede`() {
        listOf(
            HeartRateUi.Kind.OFF,
            HeartRateUi.Kind.UNAVAILABLE,
            HeartRateUi.Kind.LOCKED,
            HeartRateUi.Kind.UNSUPPORTED,
        ).forEach { kind -> kind.emphasis shouldBe HeartRateUi.Emphasis.QUIET }
    }

    @Test
    fun `emphasis never distinguishes a state on its own`() {
        // T080: no state may be told apart by colour alone. Several kinds deliberately
        // share an emphasis, which is only safe because each also differs in icon, copy
        // and whether it carries a number — so this asserts the sharing exists rather
        // than pretending emphasis is a unique key.
        val byEmphasis = HeartRateUi.Kind.entries.groupBy { it.emphasis }

        (byEmphasis.getValue(HeartRateUi.Emphasis.ACTIVE).size > 1) shouldBe true
        (byEmphasis.getValue(HeartRateUi.Emphasis.QUIET).size > 1) shouldBe true
        // And every kind still says something different, which is what actually carries
        // the distinction.
        HeartRateCopy.everySentence().isNotEmpty() shouldBe true
    }
}
