package io.github.andrewkomkov.greenpods.core.data.heartrate

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.data.AddressedAapEvent
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The session, with fakes for everything it talks to.
 *
 * Two properties matter more than the rest, and both are here because getting either
 * wrong is invisible in a demo:
 *
 * - **A write is not a change.** Nothing reaches `MEASURING` because a start command was
 *   accepted. The only edge into it is a report arriving and the policy trusting it.
 * - **Off means off in the accessory.** Every stopping case asserts that a stop command
 *   was *issued*, not merely that the display changed. A UI that hides the number while
 *   the optical sensor keeps draining the buds looks identical and is not the same thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateControllerTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val serviceId = 0x13

    private class FakeCommands : HeartRateController.HeartRateCommands {
        val started = mutableListOf<Pair<Int, Int>>()
        val stopped = mutableListOf<Int>()
        val describeRequests = mutableListOf<String>()
        var accept = true

        override suspend fun describeServices(address: String): Boolean {
            describeRequests += address
            return true
        }

        override suspend fun startHeartRate(
            address: String,
            serviceId: Int,
            intervalMicros: Int,
        ): Boolean {
            started += serviceId to intervalMicros
            return accept
        }

        override suspend fun stopHeartRate(
            address: String,
            serviceId: Int,
        ): Boolean {
            stopped += serviceId
            return true
        }
    }

    private class FakeSink : TrustedReadingSink {
        val trusted = mutableListOf<HeartRateReading>()
        var stops = 0

        override suspend fun onTrusted(
            pod: PodState,
            reading: HeartRateReading,
        ) {
            trusted += reading
        }

        override suspend fun onSensingStopped(address: String) {
            stops++
        }
    }

    private inner class Harness(
        model: PodModel = PodModel.AIRPODS_PRO_3,
        transports: Set<Transport> = setOf(Transport.BLE_ADVERTISEMENT, Transport.AAP_L2CAP),
        wear: EarDetectionState = EarDetectionState(WearState.IN_EAR, WearState.IN_EAR),
        settingsValue: GreenPodsSettings = GreenPodsSettings.Default.copy(heartRateEnabled = true),
        val gatt: GattHeartRateReadings? = null,
    ) {
        val pods =
            MutableStateFlow(
                listOf(
                    PodState(
                        address = address,
                        model = model,
                        activeTransports = transports,
                        earDetection = wear,
                    ),
                ),
            )
        val events = MutableSharedFlow<AddressedAapEvent>(extraBufferCapacity = 32)
        val settings = MutableStateFlow(settingsValue)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val commands = FakeCommands()
        val sink = FakeSink()
        val published = mutableListOf<Pair<HeartRateState, HeartRateSensing>>()
        var nowMillis = 1_770_000_000_000L

        val controller =
            HeartRateController(
                pods = pods,
                aapEvents = events,
                settings = settings,
                commands = commands,
                gatt = gatt,
                sink = sink,
                publish = { _, state, sensing -> published += state to sensing },
                clock = { nowMillis },
                ticks = ticks,
            )

        val state: HeartRateState get() = published.last().first
        val sensing: HeartRateSensing get() = published.last().second

        private lateinit var scope: TestScope

        fun start(scope: TestScope) {
            this.scope = scope
            scope.backgroundScope.launch { controller.run() }
            settle()
        }

        /**
         * Lets every pending coroutine run.
         *
         * `runCurrent`, not `advanceUntilIdle`: the controller runs in `backgroundScope`,
         * and advancing virtual time leaves background work untouched — a test written
         * that way sees no state at all and reads as a broken controller.
         */
        fun settle() {
            scope.testScheduler.runCurrent()
        }

        suspend fun describeServices() {
            events.emit(
                AddressedAapEvent(
                    address,
                    AapEvent.HidServices(
                        listOf(
                            io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidService(
                                id = serviceId,
                                name = "HeartRateService",
                                reportDescriptor = ByteArray(0),
                                isHeartRate = true,
                            ),
                        ),
                    ),
                ),
            )
            settle()
        }

        suspend fun report(
            beatsPerMinute: Int,
            confidence: Int,
        ) {
            events.emit(
                AddressedAapEvent(
                    address,
                    AapEvent.HeartRateReport(
                        serviceId,
                        HeartRateReading(
                            beatsPerMinute = beatsPerMinute,
                            confidence = confidence,
                            source = HeartRateReading.Source.AAP,
                            measuredAtEpochMillis = nowMillis,
                        ),
                    ),
                ),
            )
            settle()
        }

        suspend fun wear(state: EarDetectionState) {
            pods.value = pods.value.map { it.copy(earDetection = state) }
            settle()
        }

        suspend fun transports(set: Set<Transport>) {
            pods.value = pods.value.map { it.copy(activeTransports = set) }
            settle()
        }

        suspend fun tick() {
            ticks.emit(Unit)
            settle()
        }
    }

    @Test
    fun `nothing is started until the accessory has described its own service`() =
        runTest {
            val harness = Harness()
            harness.start(this)

            // FR-002: there is no constant to fall back on, so waiting is correct. What
            // must not happen is a start frame carrying a guessed id.
            harness.commands.started.shouldBeEmpty()
            harness.state.shouldBeInstanceOf<HeartRateState.Starting>()

            harness.describeServices()

            harness.commands.started shouldBe listOf(serviceId to 1_000_000)
            harness.sensing.serviceId shouldBe serviceId
        }

    @Test
    fun `an accepted start command does not make the state measuring`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()

            // The whole of Principle I in one assertion: the write was accepted and the
            // sensor is still not known to be running.
            harness.commands.started.size shouldBe 1
            harness.state.shouldBeInstanceOf<HeartRateState.Starting>()
            harness.state.trustedReading shouldBe null
        }

    @Test
    fun `the settling series shows no number, and the converged one does`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()

            listOf(169 to 20, 147 to 20, 128 to 20, 96 to 20).forEach { (bpm, confidence) ->
                harness.report(bpm, confidence)
                harness.state.shouldBeInstanceOf<HeartRateState.Settling>()
                harness.state.trustedReading shouldBe null
            }

            harness.report(94, 156)

            harness.state.shouldBeInstanceOf<HeartRateState.Measuring>()
            harness.state.trustedReading?.beatsPerMinute shouldBe 94
            harness.sensing.reportsReceived shouldBe 5
            harness.sensing.trustedCount shouldBe 1
        }

    @Test
    fun `only trusted readings reach the health-store sink`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()

            listOf(169 to 20, 147 to 20, 94 to 156, 91 to 233).forEach { (bpm, confidence) ->
                harness.report(bpm, confidence)
            }

            // SC-004: the two wrong readings never leave the decoder, whatever the
            // settings say. The sink cannot filter them because it never sees them.
            harness.sink.trusted.map(HeartRateReading::beatsPerMinute) shouldBe listOf(94, 91)
        }

    @Test
    fun `confidence collapsing withdraws the number without stopping the sensor`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)
            harness.state.shouldBeInstanceOf<HeartRateState.Measuring>()

            harness.report(150, 10)

            harness.state.shouldBeInstanceOf<HeartRateState.Uncertain>()
            harness.state.trustedReading shouldBe null
            // Still measuring — the reading was withdrawn, not the session.
            harness.commands.stopped.shouldBeEmpty()
        }

    @Test
    fun `a sensor that never converges gives up after thirty seconds and stops`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(169, 20)

            harness.nowMillis += 29_000
            harness.tick()
            harness.state.shouldBeInstanceOf<HeartRateState.Settling>()

            harness.nowMillis += 2_000
            harness.tick()

            val unavailable = harness.state.shouldBeInstanceOf<HeartRateState.Unavailable>()
            unavailable.reason.isNotBlank() shouldBe true
            harness.sensing.lastStopReason shouldBe "noConvergence"
            // It must stop drawing the buds' battery to keep not getting a reading.
            harness.commands.stopped shouldBe listOf(serviceId)
        }

    @Test
    fun `an accessory that has not described its sensor is asked, not waited for`() =
        runTest {
            // The bug behind "the toggle does nothing": the announcement carrying the
            // service id is an answer, so a channel nobody asks on never produces one.
            // Switching heart rate on over an already-open channel used to sit in
            // STARTING until the timeout and then advise putting the buds in the case —
            // which only ever worked because it forced a new channel someone did ask on.
            val harness = Harness()
            harness.start(this)

            harness.commands.describeRequests.isNotEmpty() shouldBe true
            harness.state.shouldBeInstanceOf<HeartRateState.Starting>()
            // And nothing was started against a guessed id.
            harness.commands.started.shouldBeEmpty()
        }

    @Test
    fun `asking is rate-limited, not repeated on every pod emission`() =
        runTest {
            // Pod emissions arrive several times a second. An ask per emission would be
            // a request storm on a channel that is already answering.
            val harness = Harness()
            harness.start(this)
            val first = harness.commands.describeRequests.size

            // Alternating so each one is a real emission — a StateFlow set to the value it
            // already holds emits nothing, and a test that did that would pass by
            // accident rather than by rate limiting.
            repeat(6) { i ->
                val secondary = if (i % 2 == 0) WearState.OUT_OF_EAR else WearState.IN_EAR
                harness.wear(EarDetectionState(WearState.IN_EAR, secondary))
            }

            harness.commands.describeRequests.size shouldBe first

            // Once the interval has passed it asks again, so a channel that came up late
            // still gets a question.
            harness.nowMillis += 4_000
            harness.wear(EarDetectionState(WearState.IN_EAR, WearState.OUT_OF_EAR))

            (harness.commands.describeRequests.size > first) shouldBe true
        }

    @Test
    fun `once the services arrive the asking stops and the sensor starts`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            val asksBefore = harness.commands.describeRequests.size

            harness.describeServices()
            harness.nowMillis += 4_000
            harness.tick()

            harness.commands.started.size shouldBe 1
            // No further questions: it has its answer.
            harness.commands.describeRequests.size shouldBe asksBefore
        }

    @Test
    fun `an accessory that never described its sensor says so, and does not blame the fit`() =
        runTest {
            // The failure this separates out, found on hardware: the accessory announces
            // its HID services once when the Bluetooth link comes up, and a channel
            // opened after that never learns which service carries heart rate. Nothing
            // was measured badly — nothing was measured at all, and telling the user to
            // check the fit sends them to fix something that is not broken.
            val harness = Harness()
            harness.start(this)
            // Deliberately no describeServices(): this is the whole point.

            harness.nowMillis += 31_000
            harness.tick()

            val unavailable = harness.state.shouldBeInstanceOf<HeartRateState.Unavailable>()
            harness.sensing.lastStopReason shouldBe "notDiscovered"
            harness.sensing.serviceId shouldBe null
            // The sentence has to name the thing that actually helps.
            unavailable.reason shouldContain "case"
            // And nothing was ever asked of a service that was never found.
            harness.commands.started.shouldBeEmpty()
            harness.commands.stopped.shouldBeEmpty()
        }

    @Test
    fun `taking both buds out stops the sensor in the accessory`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)

            harness.wear(EarDetectionState(WearState.OUT_OF_EAR, WearState.OUT_OF_EAR))

            harness.commands.stopped shouldBe listOf(serviceId)
            harness.sensing.lastStopReason shouldBe "notWorn"
            harness.state.trustedReading shouldBe null
            harness.sink.stops shouldBe 1
        }

    @Test
    fun `one bud in is a case to measure in, not a case to refuse`() =
        runTest {
            val harness =
                Harness(wear = EarDetectionState(WearState.IN_EAR, WearState.IN_CASE))
            harness.start(this)
            harness.describeServices()

            harness.commands.started shouldBe listOf(serviceId to 1_000_000)
            harness.commands.stopped.shouldBeEmpty()
        }

    @Test
    fun `putting a bud back in resumes sensing on its own`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.wear(EarDetectionState(WearState.OUT_OF_EAR, WearState.OUT_OF_EAR))
            harness.commands.stopped.size shouldBe 1

            harness.wear(EarDetectionState(WearState.IN_EAR, WearState.OUT_OF_EAR))

            harness.commands.started.size shouldBe 2
            harness.state.shouldBeInstanceOf<HeartRateState.Starting>()
        }

    @Test
    fun `a session that resumes settles again rather than trusting its old convergence`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)
            harness.state.shouldBeInstanceOf<HeartRateState.Measuring>()

            harness.wear(EarDetectionState(WearState.OUT_OF_EAR, WearState.OUT_OF_EAR))
            harness.wear(EarDetectionState(WearState.IN_EAR, WearState.IN_EAR))
            harness.report(169, 20)

            // Hysteresis is per-session. Carrying it across an interruption would let the
            // next session's first wrong reading through on the strength of the last one.
            harness.state.shouldBeInstanceOf<HeartRateState.Settling>()
        }

    @Test
    fun `the channel going away stops sensing with its own reason`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)

            harness.transports(setOf(Transport.BLE_ADVERTISEMENT))

            harness.sensing.lastStopReason shouldBe "channelGone"
            harness.state.trustedReading shouldBe null
        }

    @Test
    fun `disabling the feature stops the sensor and reports off`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)

            harness.settings.value = harness.settings.value.copy(heartRateEnabled = false)
            harness.settle()

            harness.commands.stopped shouldBe listOf(serviceId)
            harness.state shouldBe HeartRateState.Off
        }

    @Test
    fun `with the feature off nothing is ever started`() =
        runTest {
            val harness = Harness(settingsValue = GreenPodsSettings.Default)
            harness.start(this)
            harness.describeServices()
            harness.tick()

            // FR-011 over the whole lifetime, not just at the first tick.
            harness.commands.started.shouldBeEmpty()
            harness.state shouldBe HeartRateState.Off
        }

    @Test
    fun `with the transport gated nothing is started, and the state says why`() =
        runTest {
            val harness = Harness(transports = setOf(Transport.BLE_ADVERTISEMENT))
            harness.start(this)
            harness.describeServices()

            harness.commands.started.shouldBeEmpty()
            // The gate lives on PodState, so the screen shows LOCKED with the transport's
            // own reason while the session itself is simply not running.
            harness.pods.value
                .single()
                .heartRate
                .shouldBeInstanceOf<HeartRateState.Locked>()
        }

    @Test
    fun `the requested cadence is the one in settings`() =
        runTest {
            val harness =
                Harness(
                    settingsValue =
                        GreenPodsSettings.Default.copy(
                            heartRateEnabled = true,
                            heartRateIntervalMillis = 2_000,
                        ),
                )
            harness.start(this)
            harness.describeServices()

            harness.commands.started shouldBe listOf(serviceId to 2_000_000)
        }

    @Test
    fun `implausible reports are counted and never become a reading`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()

            harness.events.emit(
                AddressedAapEvent(
                    address,
                    AapEvent.UnhandledHidReport(serviceId, reportId = 1, length = 18, reason = "IMPLAUSIBLE"),
                ),
            )
            harness.settle()

            harness.sensing.discardedImplausible shouldBe 1
            harness.sensing.trustedCount shouldBe 0
            harness.state.trustedReading shouldBe null
        }

    @Test
    fun `the standard-profile route reaches the same state, and records its own source`() =
        runTest {
            val readings = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 8)
            val harness =
                Harness(
                    model = PodModel.POWERBEATS_PRO_2,
                    transports = setOf(Transport.BLE_ADVERTISEMENT, Transport.GATT),
                    gatt = GattHeartRateReadings { readings },
                )
            harness.start(this)

            readings.emit(
                HeartRateReading(
                    beatsPerMinute = 72,
                    confidence = null,
                    source = HeartRateReading.Source.GATT,
                    measuredAtEpochMillis = harness.nowMillis,
                ),
            )
            harness.settle()

            harness.state.shouldBeInstanceOf<HeartRateState.Measuring>()
            harness.state.trustedReading?.source shouldBe HeartRateReading.Source.GATT
            harness.sensing.source shouldBe HeartRateReading.Source.GATT
            // The AAP path is demonstrably not involved: no start frame was ever written.
            harness.commands.started.shouldBeEmpty()
        }

    @Test
    fun `the two routes never substitute for one another`() =
        runTest {
            // AirPods Pro 3 has no standard-profile route at all, so a GATT source being
            // present must change nothing. FR-004: no blending, and no falling back.
            val harness =
                Harness(
                    gatt =
                        GattHeartRateReadings {
                            flowOf(
                                HeartRateReading(
                                    beatsPerMinute = 60,
                                    confidence = null,
                                    source = HeartRateReading.Source.GATT,
                                    measuredAtEpochMillis = 1L,
                                ),
                            )
                        },
                )
            harness.start(this)
            harness.describeServices()
            harness.tick()

            harness.state.trustedReading shouldBe null
            harness.sensing.source shouldBe HeartRateReading.Source.AAP
        }

    @Test
    fun `every reading records the route it came from`() =
        runTest {
            val harness = Harness()
            harness.start(this)
            harness.describeServices()
            harness.report(94, 200)

            harness.sink.trusted.forEach { reading -> reading.source shouldBe HeartRateReading.Source.AAP }
        }
}
