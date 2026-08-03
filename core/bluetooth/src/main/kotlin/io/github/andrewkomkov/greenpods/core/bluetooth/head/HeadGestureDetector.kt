package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureEvent
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import kotlin.math.abs

/**
 * Converts raw AAP orientation values into a normalised [HeadPose].
 *
 * The buds report fixed-point integers, not degrees. [SCALE] maps the observed
 * full-scale range onto ±180°; it is a working approximation, not a calibrated
 * constant, and should be refined once real captures are available.
 */
object HeadPoseMapper {
    private const val SCALE = 180f / Short.MAX_VALUE

    fun toPose(sample: HeadTrackingSample): HeadPose =
        HeadPose(
            yawDegrees = sample.orientation1 * SCALE,
            pitchDegrees = sample.orientation2 * SCALE,
            rollDegrees = sample.orientation3 * SCALE,
        )
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
    private var lastEventMillis = 0L

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

        if (atMillis - lastEventMillis < config.cooldownMillis) return null

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
