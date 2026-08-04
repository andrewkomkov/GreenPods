package io.github.andrewkomkov.greenpods.core.data

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.ControlCommand
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeacon
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSightingSource
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.AapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The merge rules: accumulate, age, rank, and never let one transport erase another.
 *
 * The clock is injected because staleness is the one behaviour that cannot be tested
 * against a wall clock, and the sighting source is an interface so a whole session of
 * advertisements can be replayed in milliseconds.
 */
class PodRepositoryTest {
    private class FakeSource(
        private val upstream: Flow<PodSighting>,
    ) : PodSightingSource {
        var lastScanMode: ScanMode? = null

        override fun sightings(scanMode: ScanMode): Flow<PodSighting> {
            lastScanMode = scanMode
            return upstream
        }
    }

    private fun beacon(
        model: PodModel = PodModel.AIRPODS_PRO_2,
        left: Int? = 80,
        right: Int? = 70,
        wear: WearState = WearState.IN_EAR,
        rawModelId: Int = model.modelId,
    ) = AppleBeacon(
        model = model,
        rawModelId = rawModelId,
        battery =
            BatteryState(
                left = BatteryComponent(left, ChargeStatus.DISCHARGING),
                right = BatteryComponent(right, ChargeStatus.DISCHARGING),
                case = BatteryComponent(50, ChargeStatus.DISCHARGING),
            ),
        earDetection = EarDetectionState(wear, wear),
        lidOpenCounter = 1,
        colorCode = 0,
        encryptedPayload = ByteArray(16),
    )

    private fun sighting(
        address: String = "AA:BB:CC:DD:EE:FF",
        rssi: Int = -50,
        atMillis: Long = 0L,
        beacon: AppleBeacon = beacon(),
    ) = PodSighting(address = address, beacon = beacon, rssi = rssi, timestampMillis = atMillis)

    private fun TestScope.repository(
        source: PodSightingSource,
        now: () -> Long = { 0L },
        diagnostics: DiagnosticsLog = DiagnosticsLog(clock = { 0L }),
    ) = PodRepository(
        source = source,
        gate = TransportGate(diagnostics, AapProbe { ProbeOutcome.NoPairedDevice }),
        diagnostics = diagnostics,
        scope = backgroundScope,
        clock = now,
        // A single tick: the age filter runs once, and the flow then completes rather
        // than leaving the test waiting on a heartbeat.
        ageTicker = flow { emit(Unit) },
    )

    /**
     * Waits for an emission that satisfies [predicate].
     *
     * The pipeline conflates: a sighting and the age tick may land in the same combine
     * pass or in two, so asserting on a fixed emission index tests the scheduler rather
     * than the repository.
     */
    private suspend fun ReceiveTurbine<List<PodState>>.awaitPods(
        predicate: (List<PodState>) -> Boolean,
    ): List<PodState> {
        repeat(MAX_EMISSIONS) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
        error("No emission matched after $MAX_EMISSIONS items")
    }

    @Test
    fun `a sighting becomes a decorated pod`() =
        runTest {
            val repository = repository(FakeSource(flow { emit(sighting()) }))

            repository.pods.test {
                val pods = awaitPods { it.isNotEmpty() }

                pods.size shouldBe 1
                pods.single().model shouldBe PodModel.AIRPODS_PRO_2
                pods
                    .single()
                    .battery.left.levelPercent shouldBe 80
                // The gate has not probed, so only the advertisement transport is live.
                pods.single().usableFeatures.size shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `accessories are ranked by signal strength, closest first`() =
        runTest {
            val source =
                FakeSource(
                    flow {
                        emit(sighting(address = "AA:AA:AA:AA:AA:AA", rssi = -80))
                        emit(sighting(address = "BB:BB:BB:BB:BB:BB", rssi = -40))
                    },
                )
            val repository = repository(source)

            repository.pods.test {
                val latest = awaitPods { it.size == 2 }

                latest.map { it.address } shouldBe listOf("BB:BB:BB:BB:BB:BB", "AA:AA:AA:AA:AA:AA")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an accessory that stopped advertising ages out`() =
        runTest {
            // Seen at t=0, evaluated a full minute later.
            val repository = repository(FakeSource(flow { emit(sighting(atMillis = 0L)) }), now = { 60_000L })

            repository.pods.test {
                // Nothing but empty lists can ever be emitted, and consecutive
                // duplicates are collapsed, so one empty emission is the whole story.
                awaitItem() shouldBe emptyList()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an accessory seen recently survives`() =
        runTest {
            val repository = repository(FakeSource(flow { emit(sighting(atMillis = 0L)) }), now = { 10_000L })

            repository.pods.test {
                awaitPods { it.isNotEmpty() }.size shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an unrecognised model is not listed but is recorded for the registry`() =
        runTest {
            val diagnostics = DiagnosticsLog(clock = { 0L })
            val repository =
                repository(
                    FakeSource(flow { emit(sighting(beacon = beacon(model = PodModel.UNKNOWN, rawModelId = 0xABCD))) }),
                    diagnostics = diagnostics,
                )

            repository.pods.test {
                awaitItem() shouldBe emptyList()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            val recorded = diagnostics.events.value.single()
            recorded.category shouldBe DiagnosticCategory.UNKNOWN_DEVICE
            recorded.message shouldBe "Unrecognised Apple model id 0xABCD"
        }

    @Test
    fun `an advertisement cannot erase what only the Apple protocol knows`() =
        runTest {
            val sightings = MutableSharedFlow<PodSighting>(replay = 1)
            val repository = repository(FakeSource(sightings))

            sightings.emit(sighting(atMillis = 0L))
            repository.onAapEvent("AA:BB:CC:DD:EE:FF", AapEvent.NoiseControl(NoiseControlMode.TRANSPARENCY))

            repository.pods.test {
                awaitPods { it.isNotEmpty() && it.single().noiseControlMode != null }
                    .single()
                    .noiseControlMode shouldBe NoiseControlMode.TRANSPARENCY

                // A fresh advertisement arrives; it carries no noise-control mode at all.
                sightings.emit(sighting(atMillis = 1_000L, beacon = beacon(left = 60)))

                awaitPods {
                    it
                        .singleOrNull()
                        ?.battery
                        ?.left
                        ?.levelPercent == 60
                }.single()
                    .noiseControlMode shouldBe NoiseControlMode.TRANSPARENCY
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearing the overlay drops connected-transport knowledge`() =
        runTest {
            val sightings = MutableSharedFlow<PodSighting>(replay = 1)
            val repository = repository(FakeSource(sightings))

            sightings.emit(sighting())
            repository.onAapEvent("AA:BB:CC:DD:EE:FF", AapEvent.NoiseControl(NoiseControlMode.ADAPTIVE))
            repository.clearOverlay("AA:BB:CC:DD:EE:FF")

            repository.pods.test {
                awaitPods { it.isNotEmpty() }.single().noiseControlMode shouldBe null
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undecoded traffic reaches diagnostics rather than being dropped`() =
        runTest {
            val diagnostics = DiagnosticsLog(clock = { 0L })
            val repository = repository(FakeSource(emptyFlow()), diagnostics = diagnostics)

            repository.onAapEvent("AA:BB:CC:DD:EE:FF", AapEvent.Unknown(byteArrayOf(0x04, 0x00, 0x7F)))
            repository.onAapEvent(
                "AA:BB:CC:DD:EE:FF",
                AapEvent.UnhandledControl(ControlCommand.CHIME_VOLUME, byteArrayOf(0x03)),
            )

            val events = diagnostics.events.value
            events.size shouldBe 2
            events.all { it.category == DiagnosticCategory.UNKNOWN_TRAFFIC } shouldBe true
            events.last().detail shouldBe "04 00 7F"
        }

    @Test
    fun `the scan mode follows the user's preference`() =
        runTest {
            val source = FakeSource(emptyFlow())
            val repository =
                PodRepository(
                    source = source,
                    gate = TransportGate(DiagnosticsLog(), AapProbe { ProbeOutcome.NoPairedDevice }),
                    diagnostics = DiagnosticsLog(),
                    scope = backgroundScope,
                    settings =
                        flow {
                            emit(
                                io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
                                    .Default
                                    .copy(scanMode = ScanMode.LOW_POWER),
                            )
                        },
                    clock = { 0L },
                    ageTicker = flow { emit(Unit) },
                )

            repository.pods.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            source.lastScanMode shouldBe ScanMode.LOW_POWER
        }

    private companion object {
        /** Enough headroom for conflation without letting a broken flow hang the suite. */
        const val MAX_EMISSIONS = 10
    }
}
