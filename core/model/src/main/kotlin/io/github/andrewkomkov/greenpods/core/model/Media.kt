package io.github.andrewkomkov.greenpods.core.model

/**
 * A playback change GreenPods wants to make in response to ear detection.
 *
 * Expressed as intent rather than as a media key so the decision of *whether* to act
 * stays pure and testable, separate from the Android media plumbing that performs it.
 */
enum class MediaAction { PAUSE, RESUME }

/**
 * Who last paused playback.
 *
 * This is the whole reason auto-resume behaves: resuming audio the *user* deliberately
 * paused is worse than not resuming at all, so the source of the pause is remembered.
 */
enum class PlaybackSource {
    /** Nothing is known — treat as user-owned and do not resume. */
    UNKNOWN,

    /** GreenPods paused it because a bud came out. Safe to resume. */
    GREENPODS,

    /** The user or another app paused it. Never resume. */
    USER,
}
