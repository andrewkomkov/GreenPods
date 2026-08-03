package io.github.andrewkomkov.greenpods.core.model

/**
 * A recognised head movement. Derived from the [HeadTrackingSample] stream rather
 * than reported by the buds — AAP sends raw orientation, not gestures.
 */
enum class HeadGesture {
    /** Pitch down then back up. The natural "yes". */
    NOD,

    /** Yaw left-right-left. The natural "no". */
    SHAKE,

    TILT_LEFT,
    TILT_RIGHT,
    LOOK_UP,
    LOOK_DOWN,
}

/**
 * Something a gesture can be bound to. Deliberately expressed as intent rather
 * than implementation so bindings survive changes in how each action is performed.
 */
enum class GestureAction {
    NONE,
    PLAY_PAUSE,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    VOLUME_UP,
    VOLUME_DOWN,
    ACCEPT_CALL,
    REJECT_CALL,
    CYCLE_NOISE_CONTROL,
    TOGGLE_ANC,
    TOGGLE_TRANSPARENCY,
    TOGGLE_CONVERSATIONAL_AWARENESS,
    ANNOUNCE_BATTERY,
}

/**
 * User-configured mapping. [minimumConfidence] lets a binding demand a cleaner
 * gesture before firing, which matters for destructive actions like rejecting a call.
 */
data class HeadGestureBinding(
    val gesture: HeadGesture,
    val action: GestureAction,
    val enabled: Boolean = true,
    val minimumConfidence: Float = 0.7f,
) {
    companion object {
        /** Sensible starting set: the two gestures people perform without being taught. */
        val Defaults: List<HeadGestureBinding> =
            listOf(
                HeadGestureBinding(HeadGesture.NOD, GestureAction.ACCEPT_CALL),
                HeadGestureBinding(HeadGesture.SHAKE, GestureAction.REJECT_CALL),
                HeadGestureBinding(HeadGesture.TILT_LEFT, GestureAction.PREVIOUS_TRACK, enabled = false),
                HeadGestureBinding(HeadGesture.TILT_RIGHT, GestureAction.NEXT_TRACK, enabled = false),
                HeadGestureBinding(HeadGesture.LOOK_UP, GestureAction.NONE, enabled = false),
                HeadGestureBinding(HeadGesture.LOOK_DOWN, GestureAction.NONE, enabled = false),
            )
    }
}

/** A detected gesture with the confidence the detector assigned to it. */
data class HeadGestureEvent(
    val gesture: HeadGesture,
    val confidence: Float,
    val atEpochMillis: Long,
)

/**
 * Normalised head pose, in degrees, for driving UI. Raw AAP orientation values are
 * device-specific fixed-point integers; this is what the app actually animates against.
 */
data class HeadPose(
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
) {
    companion object {
        val Level = HeadPose()
    }
}
