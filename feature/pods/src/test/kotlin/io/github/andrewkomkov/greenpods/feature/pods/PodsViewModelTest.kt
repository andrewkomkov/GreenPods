package io.github.andrewkomkov.greenpods.feature.pods

import app.cash.turbine.test
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeacon
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.environment.Environment
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The view model's job is turning "no results" into a reason, so that is what is
 * tested. A spinner that never resolves is indistinguishable from a broken app, and
 * the three causes need three different instructions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun source(sightings: Flow<PodSighting> = emptyFlow()) =
        PodSightingSource { _: ScanMode -> sightings }

    private fun TestScope.repository(sightings: Flow<PodSighting> = emptyFlow()): PodRepository {
        val diagnostics = DiagnosticsLog(clock = { 0L })
        return PodRepository(
            source = source(sightings),
            gate = TransportGate(diagnostics, AapProbe { ProbeOutcome.NoPairedDevice }),
            diagnostics = diagnostics,
            scope = backgroundScope,
            clock = { 0L },
            ageTicker = flow { emit(Unit) },
        )
    }

    private fun sighting() =
        PodSighting(
            address = "AA:BB:CC:DD:EE:FF",
            beacon =
                AppleBeacon(
                    model = PodModel.AIRPODS_PRO_2,
                    rawModelId = PodModel.AIRPODS_PRO_2.modelId,
                    battery = BatteryState(),
                    earDetection = EarDetectionState(),
                    lidOpenCounter = 0,
                    colorCode = 0,
                    encryptedPayload = ByteArray(16),
                ),
            rssi = -45,
            timestampMillis = 0L,
        )

    @Test
    fun `nothing nearby with everything working reads as still searching`() =
        runTest(dispatcher) {
            val viewModel = PodsViewModel(repository(), MutableStateFlow(Environment.Unknown))

            viewModel.state.test {
                var state = awaitItem()
                while (state.emptyReason != PodsEmptyReason.SEARCHING) state = awaitItem()

                state.isEmpty shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a missing permission outranks everything else`() =
        runTest(dispatcher) {
            val environment =
                MutableStateFlow(Environment(scanPermissionGranted = false, bluetoothEnabled = false))
            val viewModel = PodsViewModel(repository(), environment)

            viewModel.state.test {
                var state = awaitItem()
                while (state.emptyReason == PodsEmptyReason.SEARCHING) state = awaitItem()

                // Both are wrong, but only one of them is the user's next action.
                state.emptyReason shouldBe PodsEmptyReason.NO_PERMISSION
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `Bluetooth being off is reported as such`() =
        runTest(dispatcher) {
            val environment =
                MutableStateFlow(Environment(scanPermissionGranted = true, bluetoothEnabled = false))
            val viewModel = PodsViewModel(repository(), environment)

            viewModel.state.test {
                var state = awaitItem()
                while (state.emptyReason == PodsEmptyReason.SEARCHING) state = awaitItem()

                state.emptyReason shouldBe PodsEmptyReason.BLUETOOTH_OFF
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `granting the permission recovers without a restart`() =
        runTest(dispatcher) {
            val environment =
                MutableStateFlow(Environment(scanPermissionGranted = false, bluetoothEnabled = true))
            val viewModel = PodsViewModel(repository(), environment)

            viewModel.state.test {
                var state = awaitItem()
                while (state.emptyReason != PodsEmptyReason.NO_PERMISSION) state = awaitItem()

                environment.value = Environment.Unknown
                while (state.emptyReason != PodsEmptyReason.SEARCHING) state = awaitItem()

                state.emptyReason shouldBe PodsEmptyReason.SEARCHING
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a sighting reaches the state`() =
        runTest(dispatcher) {
            val viewModel =
                PodsViewModel(repository(flow { emit(sighting()) }), MutableStateFlow(Environment.Unknown))

            viewModel.state.test {
                var state = awaitItem()
                while (state.isEmpty) state = awaitItem()

                state.pods.single().model shouldBe PodModel.AIRPODS_PRO_2
                cancelAndIgnoreRemainingEvents()
            }
        }
}
