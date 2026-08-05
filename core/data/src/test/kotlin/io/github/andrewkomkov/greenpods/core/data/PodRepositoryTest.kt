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
import io.github.andrewkomkov.greenpods.core.data.transport.PodIdentity
import io.github.andrewkomkov.greenpods.core.data.transport.ProbeOutcome
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.collections.shouldContain
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
        identity: PodIdentity = PodIdentity.Advertised,
    ) = PodRepository(
        source = source,
        gate = TransportGate(diagnostics, AapProbe { ProbeOutcome.NoPairedDevice }),
        diagnostics = diagnostics,
        scope = backgroundScope,
        identity = identity,
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

    @Test
    fun `an address rotation does not orphan what the channel established`() =
        runTest {
            // The bug this exists to prevent, observed on hardware: taking the buds out of
            // the case rotates the advertised address, and everything accumulated under
            // the old one — the overlay, the open-channel record, the heart-rate session
            // — is stranded. Heart rate read LOCKED with reports=0 while sixteen input
            // reports a second were arriving.
            val sightings = MutableSharedFlow<PodSighting>(replay = 8)
            val repository =
                repository(
                    source = FakeSource(sightings),
                    identity = PodIdentity { _, _ -> BONDED },
                )

            repository.pods.test {
                sightings.emit(sighting(address = "AA:AA:AA:AA:AA:01"))
                awaitPods { it.isNotEmpty() }

                // The channel is established and reports a heart rate under the key the
                // repository published.
                repository.onAapChannelOpen(BONDED)
                repository.onHeartRateState(
                    address = BONDED,
                    state = HeartRateState.Settling(sinceEpochMillis = 0L),
                    sensing = HeartRateSensing(enabled = true, serviceId = 0x13, reportsReceived = 4),
                )
                awaitPods { pods -> pods.singleOrNull()?.heartRateSensing?.reportsReceived == 4 }

                // Now the accessory rotates. Same earbuds, new advertised address — and a
                // different RSSI purely so there is something for the pipeline to emit:
                // with the fix in place the rotation changes nothing observable, so an
                // otherwise identical sighting is collapsed by distinctUntilChanged and
                // the test would hang on its own success.
                sightings.emit(sighting(address = "BB:BB:BB:BB:BB:02", rssi = -42))

                val after = awaitPods { pods -> pods.singleOrNull()?.rssi == -42 }
                val pod = after.single()

                // One accessory, not two, and it kept what the channel had established.
                after.size shouldBe 1
                pod.address shouldBe BONDED
                pod.heartRateSensing.serviceId shouldBe 0x13
                pod.heartRateSensing.reportsReceived shouldBe 4
                pod.activeTransports shouldContain Transport.AAP_L2CAP
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `two different accessories are not merged just because one bond is paired`() =
        runTest {
            // The resolver answers "the one paired Apple accessory" for *any* Apple
            // advertisement — correct for its own question, ruinous as an identity. On a
            // real phone a second Apple accessory is usually in range, and collapsing
            // them produced a pod whose model changed with whichever beacon landed last:
            // AirPods Pro 3 reported heart rate as unsupported because the last packet
            // came from something else.
            val sightings = MutableSharedFlow<PodSighting>(replay = 8)
            val repository =
                repository(
                    source = FakeSource(sightings),
                    identity = PodIdentity { _, _ -> BONDED },
                )

            repository.pods.test {
                sightings.emit(
                    sighting(
                        address = "AA:AA:AA:AA:AA:01",
                        beacon = beacon(model = PodModel.AIRPODS_PRO_3),
                    ),
                )
                awaitPods { it.isNotEmpty() }

                sightings.emit(
                    sighting(
                        address = "CC:CC:CC:CC:CC:03",
                        rssi = -70,
                        beacon = beacon(model = PodModel.AIRPODS_2),
                    ),
                )

                val both = awaitPods { it.size == 2 }

                // Two accessories, and the paired one kept the identity that survives
                // rotation. The stranger keeps the address it advertised from — which is
                // the honest limit: a private address cannot be tied to a bond.
                both.map { it.model }.toSet() shouldBe setOf(PodModel.AIRPODS_PRO_3, PodModel.AIRPODS_2)
                both.first { it.model == PodModel.AIRPODS_PRO_3 }.address shouldBe BONDED
                both.first { it.model == PodModel.AIRPODS_2 }.address shouldBe "CC:CC:CC:CC:CC:03"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `without a resolvable bond the advertised address is still used`() =
        runTest {
            // Principle II: an accessory that cannot be resolved to a bond is not hidden,
            // it simply gets the only identity available. Inventing one would be worse
            // than rotating.
            val sightings = MutableSharedFlow<PodSighting>(replay = 8)
            val repository = repository(source = FakeSource(sightings))

            repository.pods.test {
                sightings.emit(sighting(address = "AA:AA:AA:AA:AA:01"))
                val pods = awaitPods { it.isNotEmpty() }

                pods.single().address shouldBe "AA:AA:AA:AA:AA:01"
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        /** Enough headroom for conflation without letting a broken flow hang the suite. */
        const val MAX_EMISSIONS = 10

        /** The paired classic address every advertisement above resolves to. */
        const val BONDED = "74:3F:8E:C5:CD:8E"
    }
}
