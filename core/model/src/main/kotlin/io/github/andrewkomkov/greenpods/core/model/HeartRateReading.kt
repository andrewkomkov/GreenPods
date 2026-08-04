package io.github.andrewkomkov.greenpods.core.model

/**
 * One heart-rate measurement, at one instant, from one route.
 *
 * Replaces the older `HeartRateSample`, which carried only a number and a route. Two
 * nearly identical reading types is how the AAP and GATT routes would eventually get
 * blended by accident, so there is exactly one — with the fields the AAP route needs
 * left nullable for the route that does not publish them.
 *
 * A reading outside [PLAUSIBLE_RANGE] cannot be constructed. That is deliberate: FR-009
 * says an impossible value is surfaced as unhandled traffic rather than shown, and the
 * only way to guarantee that across every consumer is to make the bad value unmakeable.
 * Decoders use [orNull] and report the miss.
 */
data class HeartRateReading(
    val beatsPerMinute: Int,
    /**
     * The accessory's own confidence byte, 0..255.
     *
     * **Null on the GATT route**, which publishes none — the SIG profile's contract is
     * that a measurement it sends is one it already considers valid (R-10). Null
     * therefore means "this route has no confidence to give", never "zero confidence".
     */
    val confidence: Int?,
    val source: Source,
    /**
     * Wall-clock time of the measurement, **already resolved**.
     *
     * The AAP report's own 8-byte timestamp is an accessory-local monotonic counter —
     * the captured value is about 15 hours, not a date (R-4a). It is anchored once per
     * session against the host clock, and only the anchored result reaches this field,
     * so nothing downstream can mistake the counter for an instant.
     */
    val measuredAtEpochMillis: Long,
    /** The report's own counter, for gap detection. AAP only. */
    val sequence: Int? = null,
) {
    init {
        require(beatsPerMinute in PLAUSIBLE_RANGE) {
            "Implausible heart rate $beatsPerMinute BPM; surface it as unhandled instead"
        }
    }

    /** Which of the two independent acquisition routes produced this. Never inferred (FR-005). */
    enum class Source {
        /** Apple Accessory Protocol, as a HID sensor report over opcode 0x17. */
        AAP,

        /** The standard Bluetooth SIG Heart Rate Profile (0x180D). */
        GATT,
        ;

        val displayName: String
            get() =
                when (this) {
                    AAP -> "Apple protocol"
                    GATT -> "Bluetooth heart-rate profile"
                }
    }

    companion object {
        /**
         * Outside this, a reading is not a measurement of a living person — it is the
         * sensor failing in a way that happens to produce a number. The bounds are wide
         * on purpose: this rejects nonsense, it does not judge fitness.
         */
        val PLAUSIBLE_RANGE = 25..250

        /**
         * Builds a reading, or null when the value is implausible.
         *
         * The null is not a silent drop — every caller of this turns it into unhandled
         * traffic and a `discardedImplausible` count, which is what FR-009 asks for.
         */
        fun orNull(
            beatsPerMinute: Int,
            confidence: Int?,
            source: Source,
            measuredAtEpochMillis: Long,
            sequence: Int? = null,
        ): HeartRateReading? =
            if (beatsPerMinute in PLAUSIBLE_RANGE) {
                HeartRateReading(beatsPerMinute, confidence, source, measuredAtEpochMillis, sequence)
            } else {
                null
            }
    }
}
