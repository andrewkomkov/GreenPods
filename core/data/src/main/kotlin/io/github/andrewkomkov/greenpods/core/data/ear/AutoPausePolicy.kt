package io.github.andrewkomkov.greenpods.core.data.ear

import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.MediaAction
import io.github.andrewkomkov.greenpods.core.model.PlaybackSource
import io.github.andrewkomkov.greenpods.core.model.WearState

/**
 * What playback should do right now, given how the buds are being worn.
 *
 * Auto-pause is the one genuinely valuable AirPods behaviour that is reachable over
 * the *advertisement* transport — wear state is broadcast, so this works on a phone
 * that can never open the AAP channel. That makes it worth getting exactly right.
 *
 * All of the judgement lives here, as a pure function of a wear transition; the class
 * that actually presses a media key holds no logic at all. The awkward cases are the
 * point:
 *
 * - Resuming audio the *user* paused is worse than never resuming, so the source of
 *   the pause is part of the input.
 * - Both buds going into the case is one transition, not two, so it must not produce
 *   two pauses.
 * - An advertisement that carries no wear information must change nothing, rather than
 *   being read as "not worn".
 */
data class PlaybackContext(
    val isPlaying: Boolean,
    /** Who paused, when [isPlaying] is false. Irrelevant while playing. */
    val pauseSource: PlaybackSource = PlaybackSource.UNKNOWN,
    /**
     * Whether this accessory is the phone's current audio output. Pausing because a
     * bud left an ear while music plays through the phone speaker would be absurd.
     */
    val isActiveAudioOutput: Boolean = true,
)

object AutoPausePolicy {
    /**
     * Returns the action to take for a wear transition, or null when nothing should
     * happen. Callers pass consecutive states; equal states produce null.
     */
    fun evaluate(
        previous: EarDetectionState,
        next: EarDetectionState,
        context: PlaybackContext,
        settings: GreenPodsSettings,
    ): MediaAction? {
        if (!settings.autoPauseEnabled) return null
        if (!context.isActiveAudioOutput) return null

        val wornBefore = worn(previous, settings.pauseOnlyWhenBothOut) ?: return null
        val wornNow = worn(next, settings.pauseOnlyWhenBothOut) ?: return null
        if (wornBefore == wornNow) return null

        return when {
            wornBefore && !wornNow -> MediaAction.PAUSE.takeIf { context.isPlaying }

            // Only resume what we paused ourselves, and only if the user asked for it.
            !settings.autoResumeEnabled -> null

            context.isPlaying -> null

            context.pauseSource != PlaybackSource.GREENPODS -> null

            else -> MediaAction.RESUME
        }
    }

    /**
     * Whether the accessory counts as "in use". Null means the advertisement did not
     * say — which must leave playback alone rather than being guessed either way.
     *
     * The default matches Apple: taking *one* bud out pauses, so "worn" means both are
     * in. [bothOutRequired] relaxes that for people who listen with a single bud, where
     * playback should survive until neither is in an ear.
     */
    private fun worn(
        state: EarDetectionState,
        bothOutRequired: Boolean,
    ): Boolean? {
        if (state.primary == WearState.UNKNOWN || state.secondary == WearState.UNKNOWN) return null
        return if (bothOutRequired) state.anyInEar else state.bothInEar
    }
}
