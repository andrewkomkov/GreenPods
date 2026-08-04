package io.github.andrewkomkov.greenpods.feature.pods

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.Transport

/**
 * How the heart-rate card is drawn, decided outside Compose.
 *
 * The one field that matters is [beatsPerMinute], and it is null in every kind but
 * [Kind.MEASURING]. That is not a convention the screen has to honour — it is the only
 * value the mapping can produce, because [HeartRateState.Measuring] is the only state
 * that holds a reading. A settling sensor cannot render a number here for the same
 * reason it cannot upstream: there is none to render.
 */
data class HeartRateUi(
    val kind: Kind,
    val beatsPerMinute: Int?,
    val title: String,
    val body: String,
    val spoken: String,
) {
    /** Each kind is a different *shape* on screen, not a different string in one shape. */
    enum class Kind {
        MEASURING,
        SETTLING,
        STARTING,
        UNCERTAIN,
        OFF,
        UNAVAILABLE,
        LOCKED,
        UNSUPPORTED,
        ;

        /** Settling and starting are waits with a reason, and read as progress. */
        val showsProgress: Boolean get() = this == SETTLING || this == STARTING

        /**
         * How much the card should stand out from the rest of the pod card.
         *
         * Three levels, and the ordering is the requirement rather than the styling: a
         * trusted reading is the most prominent thing on the card, a wait is present but
         * quiet, and a state that is merely explaining itself recedes. Colour is never the
         * *only* difference — the icon, the copy and the presence of a number all change
         * too — because a user who cannot distinguish the containers must still be able to
         * tell a measurement from a wait (T080).
         */
        val emphasis: Emphasis
            get() =
                when (this) {
                    MEASURING -> Emphasis.PROMINENT
                    SETTLING, STARTING, UNCERTAIN -> Emphasis.ACTIVE
                    OFF, UNAVAILABLE, LOCKED, UNSUPPORTED -> Emphasis.QUIET
                }
    }

    /** The container weight a state is drawn with. */
    enum class Emphasis {
        PROMINENT,
        ACTIVE,
        QUIET,
    }

    companion object {
        fun of(
            state: HeartRateState,
            sensing: HeartRateSensing = HeartRateSensing.Idle,
        ): HeartRateUi =
            HeartRateUi(
                kind = kindOf(state),
                beatsPerMinute = state.trustedReading?.beatsPerMinute,
                title = HeartRateCopy.title(state),
                body = HeartRateCopy.body(state, sensing),
                spoken = HeartRateCopy.spoken(state, sensing),
            )

        private fun kindOf(state: HeartRateState): Kind =
            when (state) {
                is HeartRateState.Measuring -> Kind.MEASURING
                is HeartRateState.Settling -> Kind.SETTLING
                is HeartRateState.Starting -> Kind.STARTING
                is HeartRateState.Uncertain -> Kind.UNCERTAIN
                is HeartRateState.Off -> Kind.OFF
                is HeartRateState.Unavailable -> Kind.UNAVAILABLE
                is HeartRateState.Locked -> Kind.LOCKED
                is HeartRateState.Unsupported -> Kind.UNSUPPORTED
            }
    }
}

/**
 * Every word the heart-rate card can say, in one object.
 *
 * Here rather than inline so a unit test can read all of it at once and assert what it
 * does **not** contain. FR-010 forbids presenting this as a medical measurement, and the
 * way that requirement decays is one well-meant sentence at a time — "normal", "resting
 * rate", a range, a comparison. A list a test can walk is the only version of that rule
 * that survives the next person adding a state.
 */
object HeartRateCopy {
    const val UNIT = "bpm"

    fun title(state: HeartRateState): String =
        when (state) {
            is HeartRateState.Measuring -> "${state.reading.beatsPerMinute} $UNIT"
            is HeartRateState.Settling -> "Measuring…"
            is HeartRateState.Starting -> "Starting the sensor…"
            is HeartRateState.Uncertain -> "Reading uncertain"
            is HeartRateState.Off -> "Heart rate is off"
            is HeartRateState.Unavailable -> "Not measuring"
            is HeartRateState.Locked -> "Heart rate is locked"
            is HeartRateState.Unsupported -> "No heart-rate sensor"
        }

    fun body(
        state: HeartRateState,
        sensing: HeartRateSensing = HeartRateSensing.Idle,
    ): String =
        when (state) {
            is HeartRateState.Measuring -> {
                route(state.reading.source)
            }

            is HeartRateState.Settling -> {
                "The sensor is still settling. No number is shown until it is worth showing."
            }

            is HeartRateState.Starting -> {
                "Waiting for the first report from the earbuds."
            }

            is HeartRateState.Uncertain -> {
                "The earbuds report low confidence, so the last number has been withdrawn. " +
                    "Still measuring."
            }

            is HeartRateState.Off -> {
                "Turn it on in Settings. It draws on the earbuds' battery."
            }

            // The state's sentence, never `sensing.lastStopReason` — that is a token for
            // adb to grep (`notWorn`), and putting machine words on a screen is how a
            // diagnostic surface leaks into a product one.
            is HeartRateState.Unavailable -> {
                state.reason.ifBlank { "Sensing stopped." }
            }

            is HeartRateState.Locked -> {
                state.reason
            }

            is HeartRateState.Unsupported -> {
                state.reason
            }
        }

    /**
     * Which route produced the number.
     *
     * Named rather than hidden: the two routes reach the phone completely differently
     * and only one of them publishes a confidence value, so a user comparing readings
     * deserves to know which they are looking at (FR-026, R-10).
     */
    fun route(source: HeartRateReading.Source): String =
        when (source) {
            HeartRateReading.Source.AAP -> "From the earbuds, over Apple's protocol."
            HeartRateReading.Source.GATT -> "From the earbuds, over the Bluetooth heart-rate profile."
        }

    /** What TalkBack says: the state first, and a number only inside one. */
    fun spoken(
        state: HeartRateState,
        sensing: HeartRateSensing = HeartRateSensing.Idle,
    ): String =
        when (state) {
            is HeartRateState.Measuring -> "Heart rate, ${state.reading.beatsPerMinute} beats per minute"
            is HeartRateState.Settling -> "Heart rate, measuring in progress, no reading yet"
            is HeartRateState.Starting -> "Heart rate, starting the sensor"
            else -> "Heart rate, ${title(state).lowercase()}"
        } + ". " + body(state, sensing)

    /** Everything the card can say, for the test that checks none of it is clinical. */
    fun everySentence(): List<String> {
        val reading =
            HeartRateReading(
                beatsPerMinute = 81,
                confidence = 205,
                source = HeartRateReading.Source.AAP,
                measuredAtEpochMillis = 0L,
            )
        val states =
            listOf(
                HeartRateState.Measuring(reading),
                HeartRateState.Measuring(reading.copy(source = HeartRateReading.Source.GATT, confidence = null)),
                HeartRateState.Settling(0L),
                HeartRateState.Starting(0L),
                HeartRateState.Uncertain(0L),
                HeartRateState.Off,
                HeartRateState.Unavailable("Put an earbud in to measure."),
                HeartRateState.Locked("This phone cannot open the Apple protocol channel.", Transport.AAP_L2CAP),
                HeartRateState.Unsupported("These earbuds have no heart-rate sensor."),
            )
        return states.flatMap { state -> listOf(title(state), body(state), spoken(state)) } + UNIT
    }
}
