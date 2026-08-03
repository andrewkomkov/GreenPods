package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapAvailability
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import io.kotest.matchers.string.shouldContain as shouldContainText

/**
 * The gate, driven through every outcome — including the ones no phone available to
 * this project can produce.
 *
 * The probe is injected precisely so `Available` can be tested: without that, the one
 * branch that unlocks the entire control surface would never be exercised.
 */
class TransportGateTest {
    private class RecordingProbe(
        private val outcome: AapAvailability?,
    ) : AapProbe {
        var calls = 0

        override suspend fun probe(address: String): AapAvailability? {
            calls++
            return outcome
        }
    }

    private fun pod(model: PodModel = PodModel.AIRPODS_PRO_2) = PodState(address = ADDRESS, model = model)

    private fun gate(probe: AapProbe): Pair<TransportGate, DiagnosticsLog> {
        val log = DiagnosticsLog(clock = { 0L })
        return TransportGate(diagnostics = log, aapProbe = probe) to log
    }

    @Test
    fun `before any probe the Apple protocol reads as not checked`() {
        val (gate, _) = gate(RecordingProbe(AapAvailability.Available))

        val decorated = gate.decorate(pod())

        decorated.statusOf(Transport.AAP_L2CAP).availability shouldBe TransportAvailability.NOT_PROBED
        decorated.activeTransports shouldBe setOf(Transport.BLE_ADVERTISEMENT)
    }

    @Test
    fun `a successful probe unlocks the write features`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(AapAvailability.Available))

            gate.probeAap(ADDRESS)
            val decorated = gate.decorate(pod())

            decorated.activeTransports shouldContain Transport.AAP_L2CAP
            decorated.usableFeatures shouldContain PodFeature.NOISE_CONTROL
        }

    @Test
    fun `a refused channel is data, not an exception, and says what refused it`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(AapAvailability.ChannelModeRefused))

            val status = gate.probeAap(ADDRESS)

            status.availability shouldBe TransportAvailability.UNAVAILABLE
            status.reason shouldContainText "channel mode"
            gate.decorate(pod()).gatedFeatures shouldContain PodFeature.NOISE_CONTROL
        }

    @Test
    fun `each failure mode gets its own explanation`() =
        runTest {
            suspend fun reasonFor(outcome: AapAvailability): String {
                val (gate, _) = gate(RecordingProbe(outcome))
                return gate.probeAap(ADDRESS).reason
            }

            reasonFor(AapAvailability.PsmRejected) shouldContainText "0x1001"
            reasonFor(AapAvailability.ApiUnavailable) shouldContainText "Android 10"
            reasonFor(AapAvailability.NotPermitted) shouldContainText "permission"
            reasonFor(AapAvailability.Failed("socket closed")) shouldContainText "socket closed"
        }

    @Test
    fun `an unpaired accessory is told so, rather than blamed on the phone`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(null))

            gate.probeAap(ADDRESS).reason shouldContainText "not paired"
        }

    @Test
    fun `probing twice does not re-open the socket`() =
        runTest {
            val probe = RecordingProbe(AapAvailability.ChannelModeRefused)
            val (gate, _) = gate(probe)

            repeat(5) { gate.probeAap(ADDRESS) }

            // Retrying costs battery for an answer that never changes on a stock stack.
            probe.calls shouldBe 1
        }

    @Test
    fun `an explicit re-check does probe again`() =
        runTest {
            val probe = RecordingProbe(AapAvailability.ChannelModeRefused)
            val (gate, _) = gate(probe)

            gate.probeAap(ADDRESS)
            gate.probeAap(ADDRESS, force = true)

            probe.calls shouldBe 2
        }

    @Test
    fun `invalidating forgets cached results`() =
        runTest {
            val probe = RecordingProbe(AapAvailability.ChannelModeRefused)
            val (gate, _) = gate(probe)

            gate.probeAap(ADDRESS)
            gate.invalidate()
            gate.probeAap(ADDRESS)

            probe.calls shouldBe 2
            gate.decorate(pod()).statusOf(Transport.AAP_L2CAP).availability shouldBe TransportAvailability.UNAVAILABLE
        }

    @Test
    fun `every probe is recorded in diagnostics`() =
        runTest {
            val (gate, log) = gate(RecordingProbe(AapAvailability.PsmRejected))

            gate.probeAap(ADDRESS)

            log.events.value.map { it.category } shouldBe listOf(DiagnosticCategory.TRANSPORT)
        }

    @Test
    fun `GATT availability follows the model, not the phone`() {
        val (gate, _) = gate(RecordingProbe(AapAvailability.ChannelModeRefused))

        gate.decorate(pod(PodModel.POWERBEATS_PRO_2)).statusOf(Transport.GATT).isAvailable shouldBe true
        gate.decorate(pod(PodModel.AIRPODS_PRO_3)).statusOf(Transport.GATT).isAvailable shouldBe false
    }

    @Test
    fun `AirPods Pro 3 heart rate stays locked even with the channel open`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(AapAvailability.Available))
            gate.probeAap(ADDRESS)

            val decorated = gate.decorate(pod(PodModel.AIRPODS_PRO_3))

            // The sensor can be switched on over AAP, but the measurement frame has
            // never been decoded — so the transport being live is not enough, and the
            // reason shown must be that gap rather than a Bluetooth excuse.
            decorated.usableFeatures shouldNotContain PodFeature.HEART_RATE_AAP
            decorated.gatedFeatures shouldContain PodFeature.HEART_RATE_AAP
            decorated.reasonFor(PodFeature.HEART_RATE_AAP) shouldContainText "not publicly decoded"
            decorated.statusOf(Transport.GATT).reason shouldContainText "does not expose"
        }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
