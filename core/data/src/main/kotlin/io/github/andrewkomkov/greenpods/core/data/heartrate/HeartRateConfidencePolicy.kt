package io.github.andrewkomkov.greenpods.core.data.heartrate

import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading

/** What one reading is worth. The only four answers there are. */
enum class HeartRateVerdict {
    /** Worth showing, and worth writing to the health store. Nothing else is. */
    TRUSTED,

    /** The sensor has not converged yet. No number is shown, and sensing continues. */
    SETTLING,

    /** It had converged and no longer has. The number is withdrawn, not frozen. */
    UNCERTAIN,

    /** Not a measurement of a living person. Discarded, counted, surfaced as unhandled. */
    IMPLAUSIBLE,
}

/**
 * The confidence gate — the spine of this feature, not a detail.
 *
 * The capture that made heart rate possible also showed why this exists: the optical
 * sensor opened by reporting **169 BPM from someone sitting still**, converging to 81
 * over about twenty seconds, and the accessory's own confidence byte read 20 for exactly
 * those wrong readings and 156 or above once the value settled. A version of this feature
 * without the gate works in a demo and is wrong in a way users cannot detect — and once
 * the health-store integration exists, wrong in a way that spreads to every app they
 * trust.
 *
 * Pure, and separate from the controller on purpose: all of FR-006 through FR-009 is
 * decidable here, off-device, against the captured series.
 *
 * **Hysteresis, not a bare comparison.** A reading straddling the threshold would
 * otherwise flip the display between a number and "uncertain" once per second, which
 * reads as a broken app rather than as an honest one.
 *
 * **The threshold is provisional and says so.** The one capture in hand only bounds it
 * between 21 and 156; 128 sits in the middle of that range and is not derived from it.
 * It is a setting so calibration is a measurement rather than a rebuild (R-4).
 */
class HeartRateConfidencePolicy(
    /** At or above this, an unconverged sensor becomes trusted. */
    val enterThreshold: Int = GreenPodsSettings.DEFAULT_HR_CONFIDENCE_THRESHOLD,
    hysteresis: Int = DEFAULT_HYSTERESIS,
) {
    /** Below this, a converged sensor stops being trusted. */
    val leaveThreshold: Int = (enterThreshold - hysteresis).coerceAtLeast(0)

    /**
     * Judges one raw reading.
     *
     * [wasTrusted] is whether the *session* had already converged, which is what
     * separates "not there yet" from "was there and has slipped". They call for two
     * different sentences and two different shapes on screen (FR-008).
     */
    fun verdict(
        beatsPerMinute: Int,
        confidence: Int?,
        wasTrusted: Boolean,
    ): HeartRateVerdict {
        if (beatsPerMinute !in HeartRateReading.PLAUSIBLE_RANGE) return HeartRateVerdict.IMPLAUSIBLE

        // The standard profile publishes no confidence, and per R-10 that is a difference
        // in contract rather than a gap: a SIG device sends a measurement it already
        // considers valid, with no settling series to protect anyone from. Plausibility
        // is the whole gate on that route, and the UI names which route it was.
        if (confidence == null) return HeartRateVerdict.TRUSTED

        return when {
            wasTrusted && confidence >= leaveThreshold -> HeartRateVerdict.TRUSTED
            wasTrusted -> HeartRateVerdict.UNCERTAIN
            confidence >= enterThreshold -> HeartRateVerdict.TRUSTED
            else -> HeartRateVerdict.SETTLING
        }
    }

    /** Convenience for a reading that has already been constructed, so is already plausible. */
    fun verdict(
        reading: HeartRateReading,
        wasTrusted: Boolean,
    ): HeartRateVerdict = verdict(reading.beatsPerMinute, reading.confidence, wasTrusted)

    companion object {
        /**
         * The gap between entering and leaving the trusted state.
         *
         * 32 of the byte's 255, which puts the leave threshold at 96 against the default
         * enter of 128 — the pair R-4 settled on. Wide enough that ordinary noise does
         * not cross both, narrow enough that a genuine collapse in confidence is noticed
         * within a reading or two.
         */
        const val DEFAULT_HYSTERESIS = 32

        fun from(settings: GreenPodsSettings): HeartRateConfidencePolicy =
            HeartRateConfidencePolicy(enterThreshold = settings.heartRateConfidenceThreshold)
    }
}
