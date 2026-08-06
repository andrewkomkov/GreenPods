package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.HeadGestureEvent
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.abs

/**
 * Gesture recognition, driven by synthetic pose streams.
 *
 * A nod and a "turned to look at something" differ only in whether the movement comes
 * back, so the detector is tested on the *shape* of the motion rather than on peaks.
 */
class HeadGestureTest {
    private fun poses(
        detector: HeadGestureDetector,
        samples: List<HeadPose>,
        startMillis: Long = 0L,
        stepMillis: Long = 60L,
    ): List<HeadGestureEvent> =
        samples.mapIndexedNotNull { index, pose ->
            detector.onPose(pose, startMillis + index * stepMillis)
        }

    private fun nodStream(amplitude: Float): List<HeadPose> =
        listOf(0f, -amplitude / 2, -amplitude, -amplitude / 2, 0f, amplitude / 4, 0f)
            .map { HeadPose(pitchDegrees = it) }

    @Test
    fun `a pitch excursion that returns to level is a nod`() {
        val events = poses(HeadGestureDetector(), nodStream(30f))

        events.map { it.gesture } shouldBe listOf(HeadGesture.NOD)
    }

    @Test
    fun `a movement smaller than the threshold is not a gesture`() {
        val events = poses(HeadGestureDetector(), nodStream(4f))

        events.shouldBe(emptyList())
    }

    @Test
    fun `turning the head once is not a shake`() {
        // A shake needs the direction to reverse twice. One sweep is someone looking
        // at something, and firing "reject call" for that would be unforgivable.
        val sweep = listOf(0f, 10f, 20f, 30f, 40f, 50f).map { HeadPose(yawDegrees = it) }

        poses(HeadGestureDetector(), sweep).shouldBe(emptyList())
    }

    @Test
    fun `yaw that reverses twice is a shake`() {
        val shake =
            listOf(0f, -25f, 0f, 25f, 0f, -25f, 0f)
                .map { HeadPose(yawDegrees = it) }

        poses(HeadGestureDetector(), shake).map { it.gesture } shouldBe listOf(HeadGesture.SHAKE)
    }

    @Test
    fun `a second gesture inside the cooldown window is suppressed`() {
        val detector = HeadGestureDetector()
        val first = poses(detector, nodStream(30f))
        // Immediately replay the same motion; the refractory period should swallow it.
        val second = poses(detector, nodStream(30f), startMillis = 420L)

        first.size shouldBe 1
        second.shouldBe(emptyList())
    }

    @Test
    fun `a sustained roll fires only after it is held`() {
        val detector = HeadGestureDetector()
        val held = List(12) { HeadPose(rollDegrees = -35f) }

        val events = poses(detector, held, stepMillis = 100L)

        events.map { it.gesture } shouldBe listOf(HeadGesture.TILT_LEFT)
    }

    @Test
    fun `confidence rises with how far past the threshold the movement went`() {
        val gentle = poses(HeadGestureDetector(), nodStream(14f)).single()
        val emphatic = poses(HeadGestureDetector(), nodStream(45f)).single()

        gentle.confidence shouldBeGreaterThanOrEqual 0.5f
        (emphatic.confidence > gentle.confidence) shouldBe true
        (emphatic.confidence <= 1f) shouldBe true
    }

    @Test
    fun `the uncalibrated pose mapper spans plus or minus 180 degrees across the raw range`() {
        val zero: Short = 0
        val mapper = HeadPoseMapper.Uncalibrated
        val level = mapper.toPose(HeadTrackingSample(zero, zero, zero, zero, zero))
        val extreme = mapper.toPose(HeadTrackingSample(Short.MAX_VALUE, zero, zero, zero, zero))

        level shouldBe HeadPose.Level
        (abs(extreme.yawDegrees - 180f) < 0.01f) shouldBe true
    }

    @Test
    fun `an unbound gesture dispatches nothing`() {
        val action =
            GestureDispatcher.resolve(
                event = HeadGestureEvent(HeadGesture.LOOK_UP, confidence = 1f, atEpochMillis = 0L),
                bindings = listOf(HeadGestureBinding(HeadGesture.NOD, GestureAction.PLAY_PAUSE)),
            )

        action.shouldBeNull()
    }

    @Test
    fun `a disabled binding dispatches nothing`() {
        val action =
            GestureDispatcher.resolve(
                event = HeadGestureEvent(HeadGesture.NOD, confidence = 1f, atEpochMillis = 0L),
                bindings = listOf(HeadGestureBinding(HeadGesture.NOD, GestureAction.ACCEPT_CALL, enabled = false)),
            )

        action.shouldBeNull()
    }

    @Test
    fun `a detection below the binding's minimum confidence dispatches nothing`() {
        val binding = HeadGestureBinding(HeadGesture.SHAKE, GestureAction.REJECT_CALL, minimumConfidence = 0.9f)

        GestureDispatcher
            .resolve(
                event = HeadGestureEvent(HeadGesture.SHAKE, confidence = 0.6f, atEpochMillis = 0L),
                bindings = listOf(binding),
            ).shouldBeNull()

        GestureDispatcher
            .resolve(
                event = HeadGestureEvent(HeadGesture.SHAKE, confidence = 0.95f, atEpochMillis = 0L),
                bindings = listOf(binding),
            ) shouldBe GestureAction.REJECT_CALL
    }

    @Test
    fun `gestures switched off globally dispatch nothing`() {
        GestureDispatcher
            .resolve(
                event = HeadGestureEvent(HeadGesture.NOD, confidence = 1f, atEpochMillis = 0L),
                bindings = HeadGestureBinding.Defaults,
                gesturesEnabled = false,
            ).shouldBeNull()
    }

    @Test
    fun `a binding mapped to NONE dispatches nothing`() {
        GestureDispatcher
            .resolve(
                event = HeadGestureEvent(HeadGesture.NOD, confidence = 1f, atEpochMillis = 0L),
                bindings = listOf(HeadGestureBinding(HeadGesture.NOD, GestureAction.NONE)),
            ).shouldBeNull()
    }
}
