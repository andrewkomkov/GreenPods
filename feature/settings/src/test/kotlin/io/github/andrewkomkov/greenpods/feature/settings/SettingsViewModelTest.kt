package io.github.andrewkomkov.greenpods.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.data.update.ReleaseInfo
import io.github.andrewkomkov.greenpods.core.data.update.UpdateSource
import io.github.andrewkomkov.greenpods.core.data.update.UpdateStatus
import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** An update source that answers without touching the network. */
    private fun stub(status: UpdateStatus) = UpdateSource { status }

    private fun TestScope.viewModel(
        diagnostics: DiagnosticsLog = DiagnosticsLog(clock = { 0L }),
        checker: UpdateSource = stub(UpdateStatus.UpToDate),
    ): SettingsViewModel {
        val settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create { folder.newFile("settings-${counter++}.preferences_pb") },
            )
        val pods =
            PodRepository(
                source = PodSightingSource { _: ScanMode -> emptyFlow() },
                gate = TransportGate(diagnostics, AapProbe { null }),
                diagnostics = diagnostics,
                scope = backgroundScope,
                clock = { 0L },
                ageTicker = flow { emit(Unit) },
            )
        return SettingsViewModel(
            settingsRepository = settings,
            podRepository = pods,
            diagnosticsLog = diagnostics,
            updateChecker = checker,
            appVersion = "1.0.0",
        )
    }

    @Test
    fun `edits are persisted and observable without a restart`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                viewModel.setAutoPause(false)
                viewModel.setScanMode(ScanMode.LOW_LATENCY)
                viewModel.setLowBatteryThreshold(35)
                advanceUntilIdle()

                var state = awaitItem()
                while (state.settings.autoPauseEnabled || state.settings.lowBatteryThresholdPercent != 35) {
                    state = awaitItem()
                }

                state.settings.autoPauseEnabled shouldBe false
                state.settings.scanMode shouldBe ScanMode.LOW_LATENCY
                state.settings.lowBatteryThresholdPercent shouldBe 35
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a gesture binding edit leaves the other bindings alone`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                viewModel.updateBinding(HeadGestureBinding(HeadGesture.NOD, GestureAction.VOLUME_UP))
                advanceUntilIdle()

                var state = awaitItem()
                while (state.settings.gestureBindings
                        .first { it.gesture == HeadGesture.NOD }
                        .action !=
                    GestureAction.VOLUME_UP
                ) {
                    state = awaitItem()
                }

                state.settings.gestureBindings
                    .first { it.gesture == HeadGesture.SHAKE }
                    .action shouldBe GestureAction.REJECT_CALL
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undecoded traffic is visible on the settings screen`() =
        runTest(dispatcher) {
            val diagnostics = DiagnosticsLog(clock = { 0L })
            val viewModel = viewModel(diagnostics = diagnostics)

            viewModel.state.test {
                awaitItem()
                diagnostics.record(DiagnosticCategory.UNKNOWN_TRAFFIC, "Control 0x99 has no decoder", "04 00 99")

                var state = awaitItem()
                while (state.diagnostics.isEmpty()) state = awaitItem()

                state.diagnostics.single().message shouldContain "0x99"
                viewModel.clearDiagnostics()
                advanceUntilIdle()

                while (state.diagnostics.isNotEmpty()) state = awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `being current is stated plainly`() =
        runTest(dispatcher) {
            val viewModel = viewModel(checker = stub(UpdateStatus.UpToDate))

            viewModel.state.test {
                awaitItem()
                viewModel.checkForUpdates()
                advanceUntilIdle()

                var state = awaitItem()
                while (state.updateSummary.isBlank()) state = awaitItem()

                state.updateSummary shouldContain "latest release"
                state.updateUrl shouldBe null
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a newer release offers its notes and a download`() =
        runTest(dispatcher) {
            val release =
                ReleaseInfo(
                    versionName = "1.1.0",
                    notes = "Adds head gestures",
                    apkUrl = "https://example.invalid/greenpods.apk",
                    htmlUrl = "https://example.invalid/release",
                )
            val viewModel = viewModel(checker = stub(UpdateStatus.Available(release)))

            viewModel.state.test {
                awaitItem()
                viewModel.checkForUpdates()
                advanceUntilIdle()

                var state = awaitItem()
                while (state.updateSummary.isBlank()) state = awaitItem()

                state.updateSummary shouldContain "1.1.0"
                state.updateSummary shouldContain "Adds head gestures"
                state.updateUrl shouldBe "https://example.invalid/greenpods.apk"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a release with no APK attached falls back to its web page`() =
        runTest(dispatcher) {
            val release =
                ReleaseInfo(
                    versionName = "1.1.0",
                    notes = "",
                    apkUrl = null,
                    htmlUrl = "https://example.invalid/release",
                )
            val viewModel = viewModel(checker = stub(UpdateStatus.Available(release)))

            viewModel.state.test {
                awaitItem()
                viewModel.checkForUpdates()
                advanceUntilIdle()

                var state = awaitItem()
                while (state.updateSummary.isBlank()) state = awaitItem()

                state.updateUrl shouldBe "https://example.invalid/release"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failed check is reported as text, not as a crash`() =
        runTest(dispatcher) {
            val viewModel = viewModel(checker = stub(UpdateStatus.Failed("no network")))

            viewModel.state.test {
                awaitItem()
                viewModel.checkForUpdates()
                advanceUntilIdle()

                var state = awaitItem()
                while (state.updateSummary.isBlank()) state = awaitItem()

                state.updateSummary shouldContain "no network"
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        var counter = 0
    }
}
