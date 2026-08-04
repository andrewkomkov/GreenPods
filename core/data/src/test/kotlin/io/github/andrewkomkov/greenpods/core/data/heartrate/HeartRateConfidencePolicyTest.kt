package io.github.andrewkomkov.greenpods.core.data.heartrate

import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The gate, against the series that made it necessary.
 *
 * The four readings below with confidence 20 are the ones the sensor produced from
 * someone sitting still: 169, 147, 128 and 96 BPM. Every one of them is a number a user
 * would believe. SC-004 says none of them is ever displayed or recorded, and this is
 * where that stops being a sentence in a document.
 */
class HeartRateConfidencePolicyTest {
    private val policy = HeartRateConfidencePolicy()

    /** bpm and confidence from the 2026-08-04 capture, in order. */
    private val capturedSeries =
        listOf(
            169 to 20,
            147 to 20,
            128 to 20,
            96 to 20,
            94 to 156,
            94 to 205,
            91 to 233,
            81 to 237,
        )

    @Test
    fun `the four settling readings are untrusted and the converged ones are trusted`() {
        var trusted = false
        val verdicts =
            capturedSeries.map { (bpm, confidence) ->
                policy.verdict(bpm, confidence, trusted).also { verdict ->
                    if (verdict == HeartRateVerdict.TRUSTED) trusted = true
                }
            }

        verdicts.take(4).forEach { it shouldBe HeartRateVerdict.SETTLING }
        verdicts.drop(4).forEach { it shouldBe HeartRateVerdict.TRUSTED }
    }

    @Test
    fun `no reading below the threshold is ever trusted, whatever the bpm looks like`() {
        // 169 is not rejected for being 169 — it is rejected for arriving with confidence
        // 20. A plausible-looking number carries no weight of its own here.
        (25..250).forEach { bpm ->
            policy.verdict(bpm, confidence = 20, wasTrusted = false) shouldBe HeartRateVerdict.SETTLING
        }
    }

    @Test
    fun `hysteresis holds around the gate so a straddling reading does not flicker`() {
        // Enter at 128, leave at 96. In between, whichever state the session is in wins.
        policy.enterThreshold shouldBe 128
        policy.leaveThreshold shouldBe 96

        policy.verdict(80, confidence = 127, wasTrusted = false) shouldBe HeartRateVerdict.SETTLING
        policy.verdict(80, confidence = 128, wasTrusted = false) shouldBe HeartRateVerdict.TRUSTED

        policy.verdict(80, confidence = 110, wasTrusted = true) shouldBe HeartRateVerdict.TRUSTED
        policy.verdict(80, confidence = 96, wasTrusted = true) shouldBe HeartRateVerdict.TRUSTED
        policy.verdict(80, confidence = 95, wasTrusted = true) shouldBe HeartRateVerdict.UNCERTAIN
    }

    @Test
    fun `a converged session that slips reads as uncertain, never as settling`() {
        // Two different sentences, and the difference matters: "not yet" and "no longer"
        // ask the user for different things (FR-006, FR-008).
        policy.verdict(80, confidence = 10, wasTrusted = false) shouldBe HeartRateVerdict.SETTLING
        policy.verdict(80, confidence = 10, wasTrusted = true) shouldBe HeartRateVerdict.UNCERTAIN
    }

    @Test
    fun `zero and 251 bpm are implausible whatever the confidence says`() {
        listOf(0, 24, 251, 1_000, -1).forEach { bpm ->
            policy.verdict(bpm, confidence = 255, wasTrusted = true) shouldBe HeartRateVerdict.IMPLAUSIBLE
            policy.verdict(bpm, confidence = null, wasTrusted = true) shouldBe HeartRateVerdict.IMPLAUSIBLE
        }
        policy.verdict(25, confidence = 255, wasTrusted = false) shouldBe HeartRateVerdict.TRUSTED
        policy.verdict(250, confidence = 255, wasTrusted = false) shouldBe HeartRateVerdict.TRUSTED
    }

    @Test
    fun `a route with no confidence is gated on plausibility alone`() {
        // The standard profile publishes no confidence, and null means "this route has
        // none to give" rather than "no confidence in this reading" (R-10).
        policy.verdict(72, confidence = null, wasTrusted = false) shouldBe HeartRateVerdict.TRUSTED
        policy.verdict(72, confidence = null, wasTrusted = true) shouldBe HeartRateVerdict.TRUSTED
    }

    @Test
    fun `the threshold comes from settings, so calibration needs no rebuild`() {
        val calibrated =
            HeartRateConfidencePolicy.from(GreenPodsSettings.Default.copy(heartRateConfidenceThreshold = 200))

        calibrated.enterThreshold shouldBe 200
        calibrated.leaveThreshold shouldBe 168
        calibrated.verdict(94, confidence = 156, wasTrusted = false) shouldBe HeartRateVerdict.SETTLING
        calibrated.verdict(94, confidence = 205, wasTrusted = false) shouldBe HeartRateVerdict.TRUSTED
    }

    @Test
    fun `a threshold below the hysteresis gap does not produce a negative leave point`() {
        val permissive = HeartRateConfidencePolicy(enterThreshold = 10)

        permissive.leaveThreshold shouldBe 0
        permissive.verdict(80, confidence = 0, wasTrusted = true) shouldBe HeartRateVerdict.TRUSTED
    }
}
