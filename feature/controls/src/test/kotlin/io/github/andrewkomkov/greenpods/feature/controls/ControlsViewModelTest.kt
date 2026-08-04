package io.github.andrewkomkov.greenpods.feature.controls

import app.cash.turbine.test
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapAvailability
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeacon
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.PodControlGateway
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The gate, seen from the screen that depends on it most.
 *
 * The critical property is negative: with the Apple protocol channel closed — the
 * common case on stock Android — *nothing* may be written, no matter what the user
 * taps. The gateway records every call, so a leak shows up as a non-empty list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class RecordingGateway(
        private val accepts: Boolean = true,
    ) : PodControlGateway {
        /** The controls screen never asks for this; it exists on the shared gateway. */
        override suspend fun describeServices(address: String): Boolean = true

        val writes = mutableListOf<String>()

        override suspend fun setNoiseControlMode(
            address: String,
            mode: NoiseControlMode,
        ): Boolean {
            writes += "mode=$mode"
            return accepts
        }

        override suspend fun setAdaptiveNoiseStrength(
            address: String,
            percent: Int,
        ): Boolean {
            writes += "strength=$percent"
            return accepts
        }

        override suspend fun setConversationalAwareness(
            address: String,
            enabled: Boolean,
        ): Boolean {
            writes += "awareness=$enabled"
            return accepts
        }

        override suspend fun setListeningModeCycle(
            address: String,
            modes: Set<NoiseControlMode>,
        ): Boolean {
            writes += "cycle=${modes.map { it.name }.sorted()}"
            return accepts
        }

        override suspend fun startHeartRate(
            address: String,
            serviceId: Int,
            intervalMicros: Int,
        ): Boolean {
            writes += "hrStart=$serviceId@$intervalMicros"
            return accepts
        }

        override suspend fun stopHeartRate(
            address: String,
            serviceId: Int,
        ): Boolean {
            writes += "hrStop=$serviceId"
            return accepts
        }
    }

    private fun sighting(model: PodModel) =
        PodSighting(
            address = ADDRESS,
            beacon =
                AppleBeacon(
                    model = model,
                    rawModelId = model.modelId,
                    battery = BatteryState(),
                    earDetection = EarDetectionState(),
                    lidOpenCounter = 0,
                    colorCode = 0,
                    encryptedPayload = ByteArray(16),
                ),
            rssi = -40,
            timestampMillis = 0L,
        )

    private fun TestScope.repository(
        model: PodModel = PodModel.AIRPODS_PRO_2,
        aap: AapAvailability = AapAvailability.ChannelModeRefused,
    ): Pair<PodRepository, TransportGate> {
        val diagnostics = DiagnosticsLog(clock = { 0L })
        val gate = TransportGate(diagnostics, AapProbe { ProbeOutcome.Attempted(aap) })
        val sightings: Flow<PodSighting> = flow { emit(sighting(model)) }
        val repository =
            PodRepository(
                source = PodSightingSource { _: ScanMode -> sightings },
                gate = gate,
                diagnostics = diagnostics,
                scope = backgroundScope,
                clock = { 0L },
                ageTicker = flow { emit(Unit) },
            )
        return repository to gate
    }

    @Test
    fun `with the channel closed every control is inert`() =
        runTest(dispatcher) {
            val (repository, _) = repository()
            val gateway = RecordingGateway()
            val viewModel = ControlsViewModel(repository, gateway)

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()

                state.controlAvailable shouldBe false

                viewModel.selectMode(NoiseControlMode.TRANSPARENCY)
                viewModel.setConversationalAwareness(true)
                viewModel.commitAdaptiveStrength()
                advanceUntilIdle()

                gateway.writes shouldBe emptyList()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the reason is stated before anything has been probed`() =
        runTest(dispatcher) {
            val (repository, _) = repository()
            val viewModel = ControlsViewModel(repository, RecordingGateway())

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()

                state.gateReason shouldContain "Not checked yet"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `probing a stock stack produces the explanation, not an error`() =
        runTest(dispatcher) {
            val (repository, _) = repository()
            val viewModel = ControlsViewModel(repository, RecordingGateway())

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()

                viewModel.probe()
                advanceUntilIdle()
                while (!state.gateReason.contains("channel mode")) state = awaitItem()

                state.controlAvailable shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `with the channel open a mode selection is written`() =
        runTest(dispatcher) {
            val (repository, _) = repository(aap = AapAvailability.Available)
            val gateway = RecordingGateway()
            val viewModel = ControlsViewModel(repository, gateway)

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()

                viewModel.probe()
                advanceUntilIdle()
                while (!state.controlAvailable) state = awaitItem()

                viewModel.selectMode(NoiseControlMode.TRANSPARENCY)
                advanceUntilIdle()

                gateway.writes shouldBe listOf("mode=TRANSPARENCY")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the adaptive slider writes once, when the finger lifts`() =
        runTest(dispatcher) {
            val (repository, _) = repository(aap = AapAvailability.Available)
            val gateway = RecordingGateway()
            val viewModel = ControlsViewModel(repository, gateway)

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()
                viewModel.probe()
                advanceUntilIdle()
                while (!state.controlAvailable) state = awaitItem()

                // Dragging produces a continuous stream of values; none of them are sent.
                (0..100 step 10).forEach(viewModel::setAdaptiveStrength)
                advanceUntilIdle()
                gateway.writes shouldBe emptyList()

                viewModel.commitAdaptiveStrength()
                advanceUntilIdle()
                gateway.writes shouldBe listOf("strength=100")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `emptying the long-press cycle is refused rather than written`() =
        runTest(dispatcher) {
            val (repository, _) = repository(aap = AapAvailability.Available)
            val gateway = RecordingGateway()
            val viewModel = ControlsViewModel(repository, gateway)

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()
                viewModel.probe()
                advanceUntilIdle()
                while (!state.controlAvailable) state = awaitItem()

                // The default cycle holds exactly two modes; remove both.
                viewModel.toggleCycleMode(NoiseControlMode.NOISE_CANCELLATION)
                advanceUntilIdle()
                viewModel.toggleCycleMode(NoiseControlMode.TRANSPARENCY)
                advanceUntilIdle()

                while (state.notice == null) state = awaitItem()
                state.notice!! shouldContain "at least one mode"
                gateway.writes shouldBe listOf("cycle=[TRANSPARENCY]")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `hardware without noise control is not offered any`() =
        runTest(dispatcher) {
            val (repository, _) = repository(model = PodModel.AIRPODS_2, aap = AapAvailability.Available)
            val viewModel = ControlsViewModel(repository, RecordingGateway())

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()

                state.supportsNoiseControl shouldBe false
                state.supportsAdaptiveAudio shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a write the accessory refuses becomes a notice, not silence`() =
        runTest(dispatcher) {
            val (repository, _) = repository(aap = AapAvailability.Available)
            val gateway = RecordingGateway(accepts = false)
            val viewModel = ControlsViewModel(repository, gateway)

            viewModel.state.test {
                var state = awaitItem()
                while (state.pod == null) state = awaitItem()
                viewModel.probe()
                advanceUntilIdle()
                while (!state.controlAvailable) state = awaitItem()

                viewModel.selectMode(NoiseControlMode.ADAPTIVE)
                advanceUntilIdle()
                while (state.notice == null) state = awaitItem()

                state.notice!! shouldContain "did not accept"
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
