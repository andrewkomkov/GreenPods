package io.github.andrewkomkov.greenpods.core.data.ear

import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.MediaAction
import io.github.andrewkomkov.greenpods.core.model.PlaybackSource
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The full wear-transition matrix.
 *
 * Auto-pause is the feature users notice most and forgive least: a spurious pause is
 * infuriating, and resuming a podcast someone deliberately stopped is worse. Every
 * branch is enumerated here rather than sampled.
 */
class AutoPausePolicyTest {
    private val playing = PlaybackContext(isPlaying = true)
    private val pausedByUs = PlaybackContext(isPlaying = false, pauseSource = PlaybackSource.GREENPODS)
    private val pausedByUser = PlaybackContext(isPlaying = false, pauseSource = PlaybackSource.USER)

    private fun wear(
        primary: WearState,
        secondary: WearState = primary,
    ) = EarDetectionState(primary, secondary)

    private val bothIn = wear(WearState.IN_EAR)
    private val oneOut = wear(WearState.IN_EAR, WearState.OUT_OF_EAR)
    private val bothOut = wear(WearState.OUT_OF_EAR)
    private val bothInCase = wear(WearState.IN_CASE)

    private fun evaluate(
        previous: EarDetectionState,
        next: EarDetectionState,
        context: PlaybackContext = playing,
        settings: GreenPodsSettings = GreenPodsSettings.Default,
    ) = AutoPausePolicy.evaluate(previous, next, context, settings)

    @Test
    fun `taking one bud out pauses`() {
        evaluate(bothIn, oneOut) shouldBe MediaAction.PAUSE
    }

    @Test
    fun `putting it back resumes what we paused`() {
        evaluate(oneOut, bothIn, context = pausedByUs) shouldBe MediaAction.RESUME
    }

    @Test
    fun `putting it back does not resume what the user paused`() {
        evaluate(oneOut, bothIn, context = pausedByUser) shouldBe null
    }

    @Test
    fun `an unknown pause source is treated as the user's and never resumed`() {
        evaluate(oneOut, bothIn, context = PlaybackContext(isPlaying = false)) shouldBe null
    }

    @Test
    fun `both buds going into the case is one transition and one pause`() {
        evaluate(bothIn, bothInCase) shouldBe MediaAction.PAUSE
        // The state does not change again, so nothing further is emitted.
        evaluate(bothInCase, bothInCase) shouldBe null
    }

    @Test
    fun `a transition with no change emits nothing`() {
        evaluate(bothIn, bothIn) shouldBe null
        evaluate(bothOut, bothOut, context = pausedByUs) shouldBe null
    }

    @Test
    fun `nothing happens while playback is already stopped`() {
        evaluate(bothIn, bothOut, context = PlaybackContext(isPlaying = false)) shouldBe null
    }

    @Test
    fun `disabling auto-pause silences the whole policy`() {
        val off = GreenPodsSettings.Default.copy(autoPauseEnabled = false)

        evaluate(bothIn, bothOut, settings = off) shouldBe null
        evaluate(bothOut, bothIn, context = pausedByUs, settings = off) shouldBe null
    }

    @Test
    fun `auto-resume can be switched off without losing auto-pause`() {
        val noResume = GreenPodsSettings.Default.copy(autoResumeEnabled = false)

        evaluate(bothIn, bothOut, settings = noResume) shouldBe MediaAction.PAUSE
        evaluate(bothOut, bothIn, context = pausedByUs, settings = noResume) shouldBe null
    }

    @Test
    fun `single-bud listeners can require both buds to be out`() {
        val bothRequired = GreenPodsSettings.Default.copy(pauseOnlyWhenBothOut = true)

        evaluate(bothIn, oneOut, settings = bothRequired) shouldBe null
        evaluate(oneOut, bothOut, settings = bothRequired) shouldBe MediaAction.PAUSE
        evaluate(bothOut, oneOut, context = pausedByUs, settings = bothRequired) shouldBe MediaAction.RESUME
    }

    @Test
    fun `nothing happens when the accessory is not the audio output`() {
        // Music playing from the phone speaker must not stop because a bud moved.
        val speaker = PlaybackContext(isPlaying = true, isActiveAudioOutput = false)

        evaluate(bothIn, bothOut, context = speaker) shouldBe null
    }

    @Test
    fun `an advertisement carrying no wear information changes nothing`() {
        val unknown = wear(WearState.UNKNOWN)

        evaluate(bothIn, unknown) shouldBe null
        evaluate(unknown, bothIn, context = pausedByUs) shouldBe null
        evaluate(unknown, unknown) shouldBe null
    }

    @Test
    fun `a partially unknown reading is not guessed at`() {
        val half = wear(WearState.IN_EAR, WearState.UNKNOWN)

        evaluate(bothIn, half) shouldBe null
    }

    @Test
    fun `going straight from the case to both ears resumes`() {
        evaluate(bothInCase, bothIn, context = pausedByUs) shouldBe MediaAction.RESUME
    }
}
