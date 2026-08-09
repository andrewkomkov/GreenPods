package io.github.andrewkomkov.greenpods.feature.settings

import io.github.andrewkomkov.greenpods.core.data.head.CalibrationSession
import io.github.andrewkomkov.greenpods.core.data.head.HeadCalibrationStore
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.CalibrationPose
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The wizard's view model, in both transport states.
 *
 * Both, because that is the constitution's rule for anything that depends on a transport, and
 * because the two outcomes are the ones a person actually meets: the phone that cannot read
 * head movement at all — which must produce a locked entry carrying a reason, never a blank
 * screen — and the phone that can, which must produce a run that walks pose by pose to a
 * result.
 *
 * `runCurrent()` throughout, never `advanceUntilIdle()`. The subject collects the sample
 * stream in a coroutine that never completes, and `advanceUntilIdle` would sit waiting on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadCalibrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a gated stream leaves a locked wizard carrying its reason`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val subject =
                subject(
                    store = store,
                    samples = {
                        flow { throw HeadTrackingController.NotStreaming(HeadTrackingController.Refusal.UNAVAILABLE) }
                    },
                )
            runCurrent()

            val state = subject.state.value
            state.isLocked shouldBe true
            state.refusal shouldBe HeadTrackingController.Refusal.UNAVAILABLE
            state.streaming shouldBe false

            // Locked, not merely inert: the wizard cannot be started, and nothing was stored
            // on the way to finding that out.
            state.canStart shouldBe false
            store.saved shouldBe null
        }

    @Test
    fun `an unsupported accessory says which refusal it was`() =
        runTest(dispatcher) {
            val subject =
                subject(
                    samples = {
                        flow { throw HeadTrackingController.NotStreaming(HeadTrackingController.Refusal.UNSUPPORTED) }
                    },
                )
            runCurrent()

            // The screen renders a different sentence per refusal, so carrying "locked"
            // without carrying *which* would make all three phones read the same.
            subject.state.value.refusal shouldBe HeadTrackingController.Refusal.UNSUPPORTED
        }

    @Test
    fun `a live stream yields a wizard that counts down on arriving samples`() =
        runTest(dispatcher) {
            val stream = MutableSharedFlow<HeadTrackingController.Sample>(extraBufferCapacity = BUFFER)
            val subject = subject(samples = { stream })
            runCurrent()

            subject.state.value.isLocked shouldBe false
            subject.state.value.canStart shouldBe true
            subject.state.value.streaming shouldBe true

            subject.start()
            val awaiting =
                subject.state.value.step
                    .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
            // Neutral first: every scale is a difference from it.
            awaiting.pose.isNeutral shouldBe true
            awaiting.index shouldBe 0

            subject.beginHold()
            subject.state.value.step
                .shouldBeInstanceOf<CalibrationSession.State.Holding>()
                .remainingMillis shouldBe POSE_HOLD_MILLIS

            // Two samples in, and the countdown has moved because the data has — not because
            // a timer ran. A bar that advanced without samples would be claiming a
            // measurement that never arrived.
            emit(stream, sample())
            emit(stream, sample())
            val holding =
                subject.state.value.step
                    .shouldBeInstanceOf<CalibrationSession.State.Holding>()
            holding.samplesCollected shouldBe 2
            // One step, not two: the first sample lands at the moment the hold began, so it
            // is the interval *between* the samples that has elapsed.
            holding.remainingMillis shouldBe POSE_HOLD_MILLIS - STEP_MILLIS
        }

    @Test
    fun `a full run reaches a reviewable result and stores it only when saved`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val stream = MutableSharedFlow<HeadTrackingController.Sample>(extraBufferCapacity = BUFFER)
            val subject = subject(store = store, samples = { stream })
            runCurrent()

            subject.start()
            hold(subject, stream)
            hold(subject, stream, o1 = YAW_UNITS)
            hold(subject, stream, o2 = PITCH_UNITS)
            hold(subject, stream, o3 = ROLL_UNITS)

            val reviewing =
                subject.state.value.step
                    .shouldBeInstanceOf<CalibrationSession.State.Reviewing>()
            reviewing.axes.keys shouldContainExactly HeadAxis.entries.toSet()
            HeadAxis.entries.forEach { axis ->
                reviewing.axes
                    .getValue(axis)
                    .verdict
                    .shouldBeInstanceOf<AxisVerdict.Measured>()
            }
            subject.state.value.unconfirmedSuspects shouldBe emptySet()

            // Reviewing is not storing. Until save() the store is untouched, so a run that
            // is abandoned or navigated away from cannot destroy a good stored result.
            store.saved shouldBe null

            subject.save()
            runCurrent()

            val saved = store.saved.shouldNotBeNull()
            saved.model shouldBe MODEL
            saved.hasAnyScale shouldBe true
            saved
                .forAxis(HeadAxis.YAW)
                .appliedScale
                .shouldNotBeNull()
                .toDouble() shouldBeGreaterThan 0.0
            subject.state.value.stored shouldBe saved
        }

    @Test
    fun `the stream going away mid-run stops it and stores nothing`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val ended = CompletableDeferred<Unit>()
            val subject = subject(store = store, samples = { flow { ended.await() } })
            runCurrent()

            subject.start()
            subject.beginHold()

            ended.complete(Unit)
            runCurrent()

            subject.state.value.step
                .shouldBeInstanceOf<CalibrationSession.State.Stopped>()
                .reason
                .isNotBlank() shouldBe true
            subject.state.value.streaming shouldBe false
            store.saved shouldBe null
        }

    @Test
    fun `a pose that did not hold stays put, names itself, and keeps its siblings`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val stream = MutableSharedFlow<HeadTrackingController.Sample>(extraBufferCapacity = BUFFER)
            val subject = subject(store = store, samples = { stream })
            runCurrent()

            subject.start()
            subject.beginHold()
            // A head that never settles: far more movement than the detector's tolerance
            // allows, so there is no plateau anywhere in the window.
            repeat(SAMPLES_PER_HOLD) { index ->
                emit(stream, sample(o1 = if (index % 2 == 0) 0 else WANDER_UNITS))
            }

            // Still on the pose that failed, rather than three poses further on. A wearer told
            // at the end that the *neutral* did not hold has performed the rest for nothing.
            val awaiting =
                subject.state.value.step
                    .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
            awaiting.pose.isNeutral shouldBe true
            awaiting.index shouldBe 0
            // Named, with the measurement — not "that did not work", which nobody can act on.
            awaiting.refusal.shouldNotBeNull() shouldContain "O1"
            store.saved shouldBe null

            // Repeating clears the failed attempt, so the retry is judged on its own readings.
            subject.repeat()
            subject.state.value.step
                .shouldBeInstanceOf<CalibrationSession.State.Awaiting>()
                .refusal shouldBe null
        }

    @Test
    fun `swapping the accessory mid-run abandons it and says so`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val models = MutableStateFlow<PodModel?>(MODEL)
            val stream = MutableSharedFlow<HeadTrackingController.Sample>(extraBufferCapacity = BUFFER)
            val subject = subject(store = store, models = models, samples = { stream })
            runCurrent()

            subject.start()
            hold(subject, stream)
            subject.state.value.abandonedOnSwap shouldBe false

            models.value = OTHER_MODEL
            runCurrent()

            // Abandoned, not carried over. A calibration is stored per model, so finishing
            // this run would file one pair's poses under the other pair's name — and that
            // would be indistinguishable from a real measurement of it afterwards.
            subject.state.value.step shouldBe CalibrationSession.State.Idle
            subject.state.value.model shouldBe OTHER_MODEL
            subject.state.value.abandonedOnSwap shouldBe true
            store.saved shouldBe null

            // Saying so is part of it: a run that vanished silently reads as the app losing it.
            subject.save()
            runCurrent()
            store.saved shouldBe null

            subject.start()
            subject.state.value.abandonedOnSwap shouldBe false
        }

    /** One pose: start the countdown, then feed a still head until the hold completes. */
    private fun TestScope.hold(
        subject: HeadCalibrationViewModel,
        stream: MutableSharedFlow<HeadTrackingController.Sample>,
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ) {
        subject.beginHold()
        repeat(SAMPLES_PER_HOLD) { emit(stream, sample(o1, o2, o3)) }
    }

    private fun TestScope.emit(
        stream: MutableSharedFlow<HeadTrackingController.Sample>,
        sample: HeadTrackingController.Sample,
    ) {
        stream.tryEmit(sample) shouldBe true
        runCurrent()
        now += STEP_MILLIS
    }

    private var now = 0L

    private fun TestScope.subject(
        store: HeadCalibrationStore = FakeStore(),
        models: Flow<PodModel?> = flowOf(MODEL),
        samples: () -> Flow<HeadTrackingController.Sample>,
    ) = HeadCalibrationViewModel(
        samples = samples,
        models = models,
        calibrations = store,
        clock = { now },
    )

    private fun sample(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
    ) = HeadTrackingController.Sample(
        pose = HeadPose.Level,
        gesture = null,
        raw =
            HeadTrackingSample(
                orientation1 = o1.toShort(),
                orientation2 = o2.toShort(),
                orientation3 = o3.toShort(),
                horizontalAcceleration = 0,
                verticalAcceleration = 0,
            ),
    )

    private class FakeStore : HeadCalibrationStore {
        var stored: HeadCalibration? = null
        var saved: HeadCalibration? = null

        override suspend fun load(model: PodModel): HeadCalibration =
            stored ?: HeadCalibration.uncalibrated(model)

        override suspend fun save(calibration: HeadCalibration) {
            stored = calibration
            saved = calibration
        }

        override suspend fun clear(model: PodModel) {
            stored = null
        }

        override suspend fun all(): List<HeadCalibration> = listOfNotNull(stored)
    }

    private companion object {
        val MODEL = PodModel.AIRPODS_PRO_3

        /** A different pair entirely — different units are not assumed to be the same units. */
        val OTHER_MODEL = PodModel.AIRPODS_PRO_2

        /** 10 Hz in test time — fast enough to satisfy the detector, slow enough to read. */
        const val STEP_MILLIS = 100L

        /**
         * The countdown a pose actually shows: the plateau the detector needs, plus the settle
         * margin the wearer is given.
         *
         * The two were the same number until hardware refused a perfectly still pose for being
         * seven milliseconds short of its own window.
         */
        val POSE_HOLD_MILLIS = CalibrationPose.DEFAULT_HOLD_MILLIS + CalibrationSession.SETTLE_MARGIN_MILLIS

        /**
         * One more than the countdown divided by the step.
         *
         * The last sample is the one that lands on zero remaining and triggers the analysis.
         */
        val SAMPLES_PER_HOLD = (POSE_HOLD_MILLIS / STEP_MILLIS).toInt() + 1

        /** 90° of yaw and 45° each of pitch and roll, at a plausible scale. */
        const val YAW_UNITS = 6_290
        const val PITCH_UNITS = 3_145
        const val ROLL_UNITS = 3_145

        /** Far past the plateau detector's tolerance, so no stretch of it counts as held. */
        const val WANDER_UNITS = 5_000

        const val BUFFER = 64
    }
}
