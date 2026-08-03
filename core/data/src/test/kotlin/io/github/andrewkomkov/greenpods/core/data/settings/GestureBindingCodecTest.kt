package io.github.andrewkomkov.greenpods.core.data.settings

import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The binding format has to survive a future version writing to it.
 *
 * Someone will install a newer build, downgrade, and expect their settings intact —
 * so an unrecognised row must cost that one row, never the whole configuration.
 */
class GestureBindingCodecTest {
    @Test
    fun `bindings round-trip`() {
        val bindings =
            listOf(
                HeadGestureBinding(
                    HeadGesture.NOD,
                    GestureAction.PLAY_PAUSE,
                    enabled = true,
                    minimumConfidence = 0.85f,
                ),
                HeadGestureBinding(HeadGesture.SHAKE, GestureAction.NONE, enabled = false, minimumConfidence = 0.5f),
            )

        val decoded = GestureBindingCodec.decode(GestureBindingCodec.encode(bindings))

        decoded.first { it.gesture == HeadGesture.NOD } shouldBe bindings[0]
        decoded.first { it.gesture == HeadGesture.SHAKE } shouldBe bindings[1]
    }

    @Test
    fun `an empty or absent value yields the defaults`() {
        GestureBindingCodec.decode(null) shouldBe HeadGestureBinding.Defaults
        GestureBindingCodec.decode("") shouldBe HeadGestureBinding.Defaults
    }

    @Test
    fun `gestures the stored value never mentioned fall back to their default`() {
        val partial = GestureBindingCodec.encode(listOf(HeadGestureBinding(HeadGesture.NOD, GestureAction.VOLUME_UP)))

        val decoded = GestureBindingCodec.decode(partial)

        decoded.size shouldBe HeadGestureBinding.Defaults.size
        decoded.first { it.gesture == HeadGesture.NOD }.action shouldBe GestureAction.VOLUME_UP
        decoded.first { it.gesture == HeadGesture.SHAKE }.action shouldBe GestureAction.REJECT_CALL
    }

    @Test
    fun `a row naming an unknown gesture or action costs only that row`() {
        val raw = "NOD:VOLUME_UP:1:0.80|WIGGLE:PLAY_PAUSE:1:0.70|SHAKE:TELEPORT:1:0.70"

        val decoded = GestureBindingCodec.decode(raw)

        decoded.first { it.gesture == HeadGesture.NOD }.action shouldBe GestureAction.VOLUME_UP
        decoded.first { it.gesture == HeadGesture.SHAKE }.action shouldBe GestureAction.REJECT_CALL
    }

    @Test
    fun `a truncated or corrupt row is dropped without losing the rest`() {
        val raw = "NOD:VOLUME_UP:1:0.80|SHAKE:PLAY_PAUSE|garbage"

        val decoded = GestureBindingCodec.decode(raw)

        decoded.first { it.gesture == HeadGesture.NOD }.action shouldBe GestureAction.VOLUME_UP
        decoded.first { it.gesture == HeadGesture.SHAKE }.action shouldBe GestureAction.REJECT_CALL
    }

    @Test
    fun `confidence outside zero to one is clamped`() {
        val decoded = GestureBindingCodec.decode("NOD:PLAY_PAUSE:1:9.90")

        decoded.first { it.gesture == HeadGesture.NOD }.minimumConfidence shouldBe 1f
    }
}
