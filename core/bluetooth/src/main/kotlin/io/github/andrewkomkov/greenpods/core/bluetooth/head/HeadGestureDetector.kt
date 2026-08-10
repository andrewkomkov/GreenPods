package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidReportField
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureEvent
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import kotlin.math.abs

/**
 * Converts raw AAP orientation values into a normalised [HeadPose].
 *
 * The buds report fixed-point integers, not degrees, and nothing in the protocol says what
 * those integers mean: the devmotion descriptor declares an opaque vendor blob with no
 * logical or physical range and no unit, so there is nothing to read the scale off.
 *
 * Three sources of a scale, in descending order of how much they can be trusted:
 *
 * 1. **A declared physical range**, where an accessory publishes one. Nothing does today,
 *    but `HidReportField.toPhysical` already implements HID's own conversion, and an
 *    accessory describing itself outranks anything inferred from a person holding a pose.
 * 2. **A stored calibration**, measured by the wizard in
 *    `specs/004-head-tracking-calibration` from labelled poses.
 * 3. **[Uncalibrated]** — the shared constant this project has always used. It maps the
 *    observed full-scale range onto ±180°, and it is **an approximation, not a measurement**.
 *    Worse than imprecise: the three raw values are cross-coupled, so a single per-axis
 *    scale is not a wrong constant but a model that does not fit. It is retained because
 *    reporting nothing at all would be less useful than reporting something labelled.
 *
 * Constructed from a calibration rather than reading a global, so what a pose means is
 * visible at the point that decides it and cannot be changed underneath a running session.
 */
class HeadPoseMapper(
    private val calibration: HeadCalibration? = null,
    /**
     * Scales the accessory declared for itself, per axis.
     *
     * Empty on every accessory this project has seen — the devmotion descriptor breaks out
     * no orientation fields at all. It exists anyway because the ordering is the part worth
     * fixing in advance: the day some firmware does declare a range, the right behaviour is
     * for it to win, and retrofitting that precedence into a codebase already full of
     * calibration lookups is much harder than putting it here now.
     */
    private val declaredScales: Map<HeadAxis, Float> = emptyMap(),
) {
    fun toPose(sample: HeadTrackingSample): HeadPose =
        HeadPose(
            yawDegrees = degrees(sample, HeadAxis.YAW),
            pitchDegrees = degrees(sample, HeadAxis.PITCH),
            rollDegrees = degrees(sample, HeadAxis.ROLL),
        )

    /**
     * True when this axis is being reported from a measurement rather than the fallback.
     *
     * Exposed so a screen can say which of the two it is showing. An angle that is an
     * approximation and an angle that was measured look identical, and the difference is the
     * one thing a reader needs in order to know how much to believe it.
     */
    fun isCalibrated(axis: HeadAxis): Boolean =
        declaredScales[axis] != null || calibration?.forAxis(axis)?.appliedScale != null

    private fun degrees(
        sample: HeadTrackingSample,
        axis: HeadAxis,
    ): Float {
        val stored = calibration?.forAxis(axis)
        val field = stored?.respondingField ?: OrientationField.currentlyMappedTo(axis)
        val raw = field.of(sample)

        // Declared first. An accessory saying what its own numbers mean outranks a scale
        // inferred from a person holding a pose at roughly the angle they were asked for —
        // and `appliedScale` is the single reader of "may this number be used", so a
        // skipped, unheld, inconclusive, mismatched or cross-coupled axis returns null there
        // and falls through to the labelled approximation rather than borrowing a
        // neighbour's.
        val scale = declaredScales[axis] ?: stored?.appliedScale ?: UNCALIBRATED_SCALE
        return raw * scale
    }

    companion object {
        /**
         * The historical shared constant: ±180° across the full int16 range.
         *
         * Kept public and named so that anything reporting an uncalibrated angle can say
         * which number it used, and so a test can pin that calibration changes it.
         */
        const val UNCALIBRATED_SCALE = 180f / Short.MAX_VALUE

        /** The fallback every accessory starts on. */
        val Uncalibrated = HeadPoseMapper()

        /**
         * Reads any per-axis scale the accessory declared for its orientation fields.
         *
         * Returns empty for every descriptor this project has captured, and that is the
         * honest answer rather than a gap: `devmotion6` declares report 1 as a timestamp
         * followed by one opaque vendor blob on usage page `0xFF0C`, with no logical or
         * physical range and no unit anywhere in its 96 bytes. The heart-rate service in the
         * same capture *does* declare a range, which is how we know the reading side works
         * and this service simply says nothing.
         *
         * A field must declare a usable mapping **and** be signed to be believed here. An
         * orientation read as unsigned is not slightly wrong, it is wrong by a whole turn on
         * half its inputs, so a declaration that omits that is not a declaration this can
         * act on.
         */
        fun declaredScalesFrom(fields: Map<HeadAxis, HidReportField>): Map<HeadAxis, Float> =
            fields
                .filterValues { it.hasPhysicalScale && it.isSigned }
                .mapValues { (_, field) ->
                    // Degrees per unit, from HID's own conversion across one raw step.
                    (field.toPhysical(1) - field.toPhysical(0)).toFloat()
                }.filterValues { it != 0f }
    }
}

/**
 * Recognises nods, shakes and tilts from a stream of head poses.
 *
 * A nod is a pitch excursion that returns to baseline; a shake is the same in yaw
 * but must reverse direction at least twice, which is what separates "no" from
 * simply turning to look at something. Tilts are sustained roll rather than
 * oscillation, so they are reported only after the pose holds.
 *
 * The detector is a pure state machine — feed it samples, get events — so its
 * behaviour is testable without a device.
 */
class HeadGestureDetector(
    private val config: Config = Config(),
) {
    data class Config(
        /** Minimum excursion, in degrees, before a movement counts at all. */
        val nodThresholdDegrees: Float = 12f,
        val shakeThresholdDegrees: Float = 15f,
        val tiltThresholdDegrees: Float = 20f,
        /** Longest a gesture may take before the window resets. */
        val gestureWindowMillis: Long = 1_200L,
        /** How long a tilt must be held before it fires. */
        val tiltHoldMillis: Long = 500L,
        /** Refractory period so one movement cannot fire twice. */
        val cooldownMillis: Long = 800L,
    )

    private val pitchHistory = ArrayDeque<Reading>()
    private val yawHistory = ArrayDeque<Reading>()
    private var tiltStartMillis: Long? = null
    private var tiltDirection: HeadGesture? = null

    /**
     * Null until the first gesture fires.
     *
     * Storing zero here would put the detector inside its own refractory period at
     * startup, silently swallowing anything the user did in the first second — and
     * "the first gesture after opening the app never works" is exactly the kind of
     * fault that gets blamed on the hardware.
     */
    private var lastEventMillis: Long? = null

    private data class Reading(
        val value: Float,
        val atMillis: Long,
    )

    /** Feeds one sample and returns a gesture if this sample completed one. */
    fun onPose(
        pose: HeadPose,
        atMillis: Long,
    ): HeadGestureEvent? {
        prune(pitchHistory, atMillis)
        prune(yawHistory, atMillis)
        pitchHistory += Reading(pose.pitchDegrees, atMillis)
        yawHistory += Reading(pose.yawDegrees, atMillis)

        lastEventMillis?.let { last -> if (atMillis - last < config.cooldownMillis) return null }

        detectTilt(pose, atMillis)?.let { return emit(it, atMillis) }
        detectOscillation(pitchHistory, config.nodThresholdDegrees, minReversals = 1)?.let { amplitude ->
            return emit(HeadGesture.NOD to confidence(amplitude, config.nodThresholdDegrees), atMillis)
        }
        detectOscillation(yawHistory, config.shakeThresholdDegrees, minReversals = 2)?.let { amplitude ->
            return emit(HeadGesture.SHAKE to confidence(amplitude, config.shakeThresholdDegrees), atMillis)
        }
        return null
    }

    fun reset() {
        pitchHistory.clear()
        yawHistory.clear()
        tiltStartMillis = null
        tiltDirection = null
    }

    private fun emit(
        pair: Pair<HeadGesture, Float>,
        atMillis: Long,
    ): HeadGestureEvent {
        lastEventMillis = atMillis
        reset()
        return HeadGestureEvent(pair.first, pair.second, atMillis)
    }

    private fun prune(
        history: ArrayDeque<Reading>,
        now: Long,
    ) {
        while (history.isNotEmpty() && now - history.first().atMillis > config.gestureWindowMillis) {
            history.removeFirst()
        }
    }

    /**
     * Returns the peak-to-peak amplitude when the signal swings past [threshold]
     * and changes direction at least [minReversals] times inside the window.
     */
    private fun detectOscillation(
        history: ArrayDeque<Reading>,
        threshold: Float,
        minReversals: Int,
    ): Float? {
        if (history.size < 3) return null

        val values = history.map(Reading::value)
        val amplitude = (values.max() - values.min())
        if (amplitude < threshold) return null

        var reversals = 0
        var previousDelta = 0f
        for (index in 1 until values.size) {
            val delta = values[index] - values[index - 1]
            if (abs(delta) < threshold / 4f) continue
            if (previousDelta != 0f && (delta > 0) != (previousDelta > 0)) reversals++
            previousDelta = delta
        }
        return amplitude.takeIf { reversals >= minReversals }
    }

    private fun detectTilt(
        pose: HeadPose,
        now: Long,
    ): Pair<HeadGesture, Float>? {
        val direction =
            when {
                pose.rollDegrees > config.tiltThresholdDegrees -> HeadGesture.TILT_RIGHT
                pose.rollDegrees < -config.tiltThresholdDegrees -> HeadGesture.TILT_LEFT
                else -> null
            }

        if (direction == null || direction != tiltDirection) {
            tiltDirection = direction
            tiltStartMillis = if (direction == null) null else now
            return null
        }

        val heldSince = tiltStartMillis ?: return null
        if (now - heldSince < config.tiltHoldMillis) return null
        return direction to confidence(abs(pose.rollDegrees), config.tiltThresholdDegrees)
    }

    /** Scales how far past the threshold the movement went into a 0.5..1 confidence. */
    private fun confidence(
        amplitude: Float,
        threshold: Float,
    ): Float = (0.5f + 0.5f * ((amplitude - threshold) / threshold)).coerceIn(0.5f, 1f)
}
