package io.github.andrewkomkov.greenpods.feature.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * FR-010 over the settings copy, which is where the requirement is most likely to slip.
 *
 * The pods card says almost nothing; this screen has to *explain* the feature, and every
 * explanatory sentence is an opportunity to reassure the reader about their heart rate
 * rather than describe the sensor. The word list is deliberately the same one
 * `HeartRateUiTest` uses — kept in step by hand rather than shared, because a list a
 * module cannot see is a list that module does not enforce.
 */
class HeartRateSettingsCopyTest {
    private val clinical =
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

    @Test
    fun `no sentence in the settings copy interprets a heart rate`() {
        HeartRateSettingsCopy.everySentence().forEach { sentence ->
            val lower = sentence.lowercase()
            clinical.forEach { word ->
                if (lower.contains(word)) {
                    throw AssertionError("Clinical framing in settings copy: \"$sentence\" contains \"$word\"")
                }
            }
        }
    }

    @Test
    fun `every sentence exists and none is blank`() {
        HeartRateSettingsCopy.everySentence().forEach { sentence -> sentence.isNotBlank() shouldBe true }
    }

    @Test
    fun `the cost and the background service are both stated in the subtitle`() {
        // FR-012 and AD-8. The subtitle is what renders above the switch, so asserting
        // the sentences are *there* is asserting they are read before the switch moves —
        // which is the half of the requirement a screenshot cannot check.
        HeartRateSettingsCopy.SECTION_SUBTITLE shouldContain "battery"
        HeartRateSettingsCopy.SECTION_SUBTITLE shouldContain "background"
    }

    @Test
    fun `the notification limitation is disclosed rather than left silent`() {
        // FR-013 says active sensing is discoverable, and the ongoing notification is how.
        // Where POST_NOTIFICATIONS is denied the service still runs, so without this
        // sentence the requirement has a hole the user cannot see.
        HeartRateSettingsCopy.NOTIFICATION_CAVEAT shouldContain "still runs"
    }

    @Test
    fun `deleting never claims more than it does`() {
        // FR-025: our records are not the user's history, and the copy must not let one
        // be read as the other.
        HeartRateSettingsCopy.DELETE_DESCRIPTION shouldContain "GreenPods wrote"
        HeartRateSettingsCopy.DELETE_DESCRIPTION shouldContain "managed there"
    }
}
