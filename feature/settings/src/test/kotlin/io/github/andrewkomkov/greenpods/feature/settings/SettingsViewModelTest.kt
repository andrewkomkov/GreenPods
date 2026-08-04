package io.github.andrewkomkov.greenpods.feature.settings

import androidx.activity.result.contract.ActivityResultContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.health.HealthConnectLink
import io.github.andrewkomkov.greenpods.core.data.health.HealthDevice
import io.github.andrewkomkov.greenpods.core.data.health.HealthStoreAvailability
import io.github.andrewkomkov.greenpods.core.data.health.HealthStoreClient
import io.github.andrewkomkov.greenpods.core.data.health.HeartRateBatch
import io.github.andrewkomkov.greenpods.core.data.health.OwnRecordCount
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
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
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Turbine's default 3 s is not enough for the DataStore-backed cases.
     *
     * `PodRepository` runs on `backgroundScope` and the settings store commits on
     * `Dispatchers.IO`, so `advanceUntilIdle()` does not drain either — these tests wait
     * on real time however virtual the dispatcher is, and on a loaded machine the default
     * expires mid-write. The await loops below are already condition-based, so a longer
     * ceiling costs nothing when things are working and removes a flake when they are
     * merely slow.
     */
    private val settled = 30.seconds

    /**
     * A health store that answers without a provider installed.
     *
     * Every case the settings screen has to render — no provider, a provider needing an
     * update, permission granted then refused — is awkward or impossible to reach on a
     * real phone, and all of them are cases users hit. That is what the client interface
     * is for.
     */
    private class FakeHealthClient(
        var availability: HealthStoreAvailability = HealthStoreAvailability.Available,
        var write: Boolean = false,
    ) : HealthStoreClient {
        var deletes = 0

        override fun availability() = availability

        override suspend fun hasWritePermission() = write

        override suspend fun hasReadPermission() = write

        override suspend fun insert(
            batch: HeartRateBatch,
            device: HealthDevice,
        ) = Unit

        override suspend fun countOwnRecords(
            fromEpochMillis: Long,
            toEpochMillis: Long,
        ) = OwnRecordCount(records = 0, samples = 0)

        override suspend fun deleteOwnRecords() {
            deletes++
        }

        override fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
            error("not needed off-device")

        override fun requiredPermissions(): Set<String> = setOf("write-heart-rate", "read-heart-rate")
    }

    private fun TestScope.viewModel(
        diagnostics: DiagnosticsLog = DiagnosticsLog(clock = { 0L }),
        checker: UpdateSource = stub(UpdateStatus.UpToDate),
        health: HealthConnectLink? = null,
    ): SettingsViewModel {
        val settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create(
                    // Tied to the test's scope on purpose. `create` otherwise builds a
                    // store on a scope that outlives the test, and with one per case this
                    // class was leaving a dozen live stores — each with its own file
                    // watcher — competing in the same JVM. That showed up as a flake in
                    // whichever test happened to be waiting on a write.
                    scope = backgroundScope,
                ) { folder.newFile("settings-${counter++}.preferences_pb") },
            )
        val pods =
            PodRepository(
                source = PodSightingSource { _: ScanMode -> emptyFlow() },
                gate = TransportGate(diagnostics, AapProbe { ProbeOutcome.NoPairedDevice }),
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
            health = health,
        )
    }

    @Test
    fun `edits are persisted and observable without a restart`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
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

            viewModel.state.test(timeout = settled) {
                awaitItem()
                viewModel.checkForUpdates()
                advanceUntilIdle()

                var state = awaitItem()
                while (state.updateSummary.isBlank()) state = awaitItem()

                state.updateSummary shouldContain "no network"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `each availability state reaches the screen with its own reason`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(availability = HealthStoreAvailability.NeedsProviderUpdate)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                // FR-020 wants a reason, not a Boolean: an update is something the user
                // can act on, and a phone without the provider is not.
                state.health.availabilitySentence shouldContain "needs an update"
                state.health.isAvailable shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `no health store at all reports itself rather than leaving the section blank`() =
        runTest(dispatcher) {
            // Principle II: a missing section reads as a bug, a section that explains
            // itself reads as the phone.
            val viewModel = viewModel(health = HealthConnectLink(client = null))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                state.health.availabilitySentence shouldContain "does not have Health Connect"
                state.health.isAvailable shouldBe false
                state.health.hasWritePermission shouldBe false
                // And it still says the reading works, or the sentence reads as a failure.
                state.health.availabilitySentence shouldContain "still works on screen"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `granting the permission is reflected without a restart`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(write = false)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()
                state.health.hasWritePermission shouldBe false

                client.write = true
                viewModel.onHealthPermissionResult(viewModel.healthPermissions())
                advanceUntilIdle()

                while (!state.health.hasWritePermission) state = awaitItem()
                state.health.permissionRefused shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a denied permission is remembered and not asked for again`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(write = false)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                // The system dialog came back with nothing granted.
                viewModel.onHealthPermissionResult(emptySet())
                advanceUntilIdle()

                while (!state.health.permissionRefused) state = awaitItem()

                // FR-018: the section now explains where the permission lives instead of
                // asking again. "Does not nag" is only implementable by remembering the
                // refusal, so this flag is the requirement rather than a convenience.
                state.health.permissionRefused shouldBe true
                state.health.hasWritePermission shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a partial grant counts as refused rather than as success`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(write = false)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                // Health Connect lets a user grant write and withhold read. Treating that
                // as a grant would leave FR-029's verification permanently broken while
                // the screen claimed everything was fine.
                viewModel.onHealthPermissionResult(setOf("write-heart-rate"))
                advanceUntilIdle()

                while (!state.health.permissionRefused) state = awaitItem()
                state.health.permissionRefused shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleting says plainly what it did and did not touch`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(write = true)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                viewModel.deleteHealthRecords()
                advanceUntilIdle()

                while (state.health.deletionMessage.isBlank()) state = awaitItem()

                client.deletes shouldBe 1
                // FR-025: deleting our own records is not clearing a user's history, and
                // the sentence must not let it be read that way.
                state.health.deletionMessage shouldContain "GreenPods wrote"
                state.health.deletionMessage shouldContain "managed there"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleting without permission reports nothing to delete instead of claiming success`() =
        runTest(dispatcher) {
            val client = FakeHealthClient(write = false)
            val viewModel = viewModel(health = HealthConnectLink(client))

            viewModel.state.test(timeout = settled) {
                var state = awaitItem()
                while (state.health.availabilitySentence.isBlank()) state = awaitItem()

                viewModel.deleteHealthRecords()
                advanceUntilIdle()

                while (state.health.deletionMessage.isBlank()) state = awaitItem()

                client.deletes shouldBe 0
                state.health.deletionMessage shouldContain "has not written"
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        var counter = 0
    }
}
