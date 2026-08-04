package io.github.andrewkomkov.greenpods.core.model

/**
 * What the user is shown, and what the state dump prints.
 *
 * A sealed hierarchy rather than a nullable reading, because the distinctions the spec
 * makes — settling versus measured, unsupported by the model versus unreachable from
 * this phone — are exactly the cases here, and a nullable number cannot express any of
 * them.
 *
 * Two rules are structural rather than advisory:
 *
 * - **Only [Measuring] carries a reading.** No other state can be rendered as a number,
 *   because no other state holds one. FR-006, FR-007 and FR-023 stop being three rules
 *   three components must remember and become one property of the type.
 * - **A transition into [Measuring] requires an arriving report**, never an accepted
 *   write. "The start command was accepted" is this transport's characteristic failure
 *   (Principle I), and the state machine has no edge that would let it mean success.
 */
sealed interface HeartRateState {
    /** `MEASURING`, `SETTLING`… — the token the adb surface and the dump print. */
    val stateName: String

    /**
     * The reading, when there is one to show. Derived, not a field: [Measuring] is the
     * only variant that can answer with anything but null.
     */
    val trustedReading: HeartRateReading?
        get() = (this as? Measuring)?.reading

    /**
     * Whether the accessory's sensor is being asked to run right now.
     *
     * Deliberately broader than [Measuring]: settling and uncertain both cost the
     * accessory's battery, so FR-013's "active sensing is discoverable" has to cover
     * them. A notification that only appeared once a number was trustworthy would hide
     * exactly the period a user most wants to know about.
     */
    val isSensing: Boolean
        get() = this is Starting || this is Settling || this is Measuring || this is Uncertain

    /** This model has no heart-rate sensor of either kind (FR-027). */
    data class Unsupported(
        val reason: String,
    ) : HeartRateState {
        override val stateName: String get() = "UNSUPPORTED"
    }

    /**
     * The model has a sensor; this phone cannot reach it (SC-008).
     *
     * Distinct from [Unsupported] on purpose — "these earbuds don't have it" and "this
     * phone can't" must never be shown as the same sentence.
     */
    data class Locked(
        val reason: String,
        val transport: Transport,
    ) : HeartRateState {
        override val stateName: String get() = "LOCKED"
    }

    /** Supported, reachable, not enabled. The default (FR-011). */
    data object Off : HeartRateState {
        override val stateName: String get() = "OFF"
    }

    /** Start requested, no report has arrived yet. Explicitly **not** "measuring". */
    data class Starting(
        val sinceEpochMillis: Long,
    ) : HeartRateState {
        override val stateName: String get() = "STARTING"
    }

    /** Reports are arriving and confidence is below the gate. No number is shown (FR-008). */
    data class Settling(
        val sinceEpochMillis: Long,
    ) : HeartRateState {
        override val stateName: String get() = "SETTLING"
    }

    /** A trusted reading. The only state that carries a number. */
    data class Measuring(
        val reading: HeartRateReading,
    ) : HeartRateState {
        override val stateName: String get() = "MEASURING"
    }

    /**
     * Confidence fell below the gate mid-session. The number is withdrawn and sensing
     * continues — the user is told the reading is uncertain rather than shown a stale
     * one as though it were current (FR-006).
     */
    data class Uncertain(
        val lastTrustedAtEpochMillis: Long?,
    ) : HeartRateState {
        override val stateName: String get() = "UNCERTAIN"
    }

    /** Buds removed, channel dropped, or settling never converged. */
    data class Unavailable(
        val reason: String,
    ) : HeartRateState {
        override val stateName: String get() = "UNAVAILABLE"
    }
}

/**
 * The bookkeeping half of the same story.
 *
 * Kept separate from [HeartRateState] because this is what the state dump and
 * `hr status` print, and it must contain no values at all — only counts, the discovered
 * service id, and why sensing is not running (FR-028).
 */
data class HeartRateSensing(
    /** The user's setting, not whether reports are arriving. */
    val enabled: Boolean = false,
    /** What was asked of the accessory. `0` is the stop request, not an absence. */
    val requestedIntervalMicros: Int? = null,
    /** Discovered from the accessory's own descriptors, never hard-coded (FR-002, R-1). */
    val serviceId: Int? = null,
    val source: HeartRateReading.Source? = null,
    val reportsReceived: Int = 0,
    /** SC-009 compares this against the health store's own count. */
    val trustedCount: Int = 0,
    /** Readings outside the plausible range, counted rather than recorded (FR-009). */
    val discardedImplausible: Int = 0,
    val lastStopReason: String? = null,
) {
    companion object {
        val Idle = HeartRateSensing()
    }
}
