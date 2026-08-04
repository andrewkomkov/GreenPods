package io.github.andrewkomkov.greenpods.health

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * FR-010 over the screen a user reads while deciding whether to trust the app.
 *
 * The rationale is reached from Health Connect's own settings, often *after* the app has
 * been installed for a while, and it is the one place where writing something reassuring
 * about the reader's heart rate would feel most natural and be most wrong.
 */
class HealthRationaleCopyTest {
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
    fun `no sentence on the rationale screen interprets a heart rate`() {
        HealthRationaleCopy.everySentence().forEach { sentence ->
            val lower = sentence.lowercase()
            clinical.forEach { word ->
                if (lower.contains(word)) {
                    throw AssertionError("Clinical framing in rationale copy: \"$sentence\" contains \"$word\"")
                }
            }
        }
    }

    @Test
    fun `every sentence exists and none is blank`() {
        HealthRationaleCopy.everySentence().forEach { sentence -> sentence.isNotBlank() shouldBe true }
    }

    @Test
    fun `the three questions a permission screen has to answer are all answered`() {
        // FR-022. What is written, what is read, and what is not done — a rationale that
        // answers only the first is the kind users are right to decline.
        HealthRationaleCopy.WRITES_BODY shouldContain "Heart-rate readings only"
        HealthRationaleCopy.READS_BODY shouldContain "GreenPods itself wrote"
        HealthRationaleCopy.NOT_DONE_BODY shouldContain "does not interpret"
    }

    @Test
    fun `the confidence gate is disclosed where permission is granted`() {
        // The user is being asked to let readings into their health history; that the
        // untrusted ones never get there is exactly what they need to know here (FR-007).
        HealthRationaleCopy.WRITES_BODY shouldContain "low confidence"
    }
}
