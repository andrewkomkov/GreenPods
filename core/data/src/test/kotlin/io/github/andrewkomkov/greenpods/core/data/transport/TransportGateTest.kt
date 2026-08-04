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
        private val outcome: ProbeOutcome,
    ) : AapProbe {
        var calls = 0

        override suspend fun probe(address: String): ProbeOutcome {
            calls++
            return outcome
        }
    }

    /** Most tests care about a channel attempt, not about resolving the pairing. */
    private fun attempted(availability: AapAvailability) = RecordingProbe(ProbeOutcome.Attempted(availability))

    private fun pod(model: PodModel = PodModel.AIRPODS_PRO_2) = PodState(address = ADDRESS, model = model)

    private fun gate(probe: AapProbe): Pair<TransportGate, DiagnosticsLog> {
        val log = DiagnosticsLog(clock = { 0L })
        return TransportGate(diagnostics = log, aapProbe = probe) to log
    }

    @Test
    fun `before any probe the Apple protocol reads as not checked`() {
        val (gate, _) = gate(attempted(AapAvailability.Available))

        val decorated = gate.decorate(pod())

        decorated.statusOf(Transport.AAP_L2CAP).availability shouldBe TransportAvailability.NOT_PROBED
        decorated.activeTransports shouldBe setOf(Transport.BLE_ADVERTISEMENT)
    }

    @Test
    fun `a successful probe unlocks the write features`() =
        runTest {
            val (gate, _) = gate(attempted(AapAvailability.Available))

            gate.probeAap(ADDRESS)
            val decorated = gate.decorate(pod())

            decorated.activeTransports shouldContain Transport.AAP_L2CAP
            decorated.usableFeatures shouldContain PodFeature.NOISE_CONTROL
        }

    @Test
    fun `a refused channel is data, not an exception, and says what refused it`() =
        runTest {
            val (gate, _) = gate(attempted(AapAvailability.ChannelModeRefused))

            val status = gate.probeAap(ADDRESS)

            status.availability shouldBe TransportAvailability.UNAVAILABLE
            status.reason shouldContainText "channel mode"
            gate.decorate(pod()).gatedFeatures shouldContain PodFeature.NOISE_CONTROL
        }

    @Test
    fun `each failure mode gets its own explanation`() =
        runTest {
            suspend fun reasonFor(outcome: AapAvailability): String {
                val (gate, _) = gate(attempted(outcome))
                return gate.probeAap(ADDRESS).reason
            }

            reasonFor(AapAvailability.PsmRejected) shouldContainText "0x1001"
            reasonFor(AapAvailability.ApiUnavailable) shouldContainText "Android 10"
            reasonFor(AapAvailability.NotPermitted) shouldContainText "permission"
            reasonFor(AapAvailability.Failed("socket closed")) shouldContainText "socket closed"
        }

    @Test
    fun `a channel that never comes up says so, and says what still works`() =
        runTest {
            val (gate, _) =
                gate(attempted(AapAvailability.ChannelNotEstablished("public createInsecureL2capChannel")))

            val reason = gate.probeAap(ADDRESS).reason

            // The user's buds are paired, connected and playing audio — blaming the
            // pairing here would send them off to fix something that is not broken.
            reason shouldContainText "paired and connected"
            reason shouldContainText "never came up"
            reason shouldContainText "auto-pause are unaffected"
            // Which API got that far is the part worth putting in a bug report.
            reason shouldContainText "public createInsecureL2capChannel"
        }

    @Test
    fun `an unpaired accessory is told so, rather than blamed on the phone`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(ProbeOutcome.NoPairedDevice))

            val reason = gate.probeAap(ADDRESS).reason
            reason shouldContainText "No paired AirPods"
            // Battery and ear detection are unaffected, and saying so stops the message
            // reading as "the app is broken".
            reason shouldContainText "work either way"
        }

    @Test
    fun `two paired Apple accessories are reported as ambiguous, not guessed between`() =
        runTest {
            val (gate, _) =
                gate(RecordingProbe(ProbeOutcome.Ambiguous(listOf("AirPods Pro", "Beats Fit Pro"))))

            val reason = gate.probeAap(ADDRESS).reason
            reason shouldContainText "AirPods Pro, Beats Fit Pro"
            // The advertisement's private address is exactly why this cannot be resolved.
            reason shouldContainText "rotating private address"
        }

    @Test
    fun `Bluetooth being off is not reported as a protocol refusal`() =
        runTest {
            val (gate, _) = gate(RecordingProbe(ProbeOutcome.Unavailable))

            gate.probeAap(ADDRESS).reason shouldContainText "Bluetooth is off"
        }

    @Test
    fun `probing twice does not re-open the socket`() =
        runTest {
            val probe = attempted(AapAvailability.ChannelModeRefused)
            val (gate, _) = gate(probe)

            repeat(5) { gate.probeAap(ADDRESS) }

            // Retrying costs battery for an answer that never changes on a stock stack.
            probe.calls shouldBe 1
        }

    @Test
    fun `an explicit re-check does probe again`() =
        runTest {
            val probe = attempted(AapAvailability.ChannelModeRefused)
            val (gate, _) = gate(probe)

            gate.probeAap(ADDRESS)
            gate.probeAap(ADDRESS, force = true)

            probe.calls shouldBe 2
        }

    @Test
    fun `invalidating forgets cached results`() =
        runTest {
            val probe = attempted(AapAvailability.ChannelModeRefused)
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
            val (gate, log) = gate(attempted(AapAvailability.PsmRejected))

            gate.probeAap(ADDRESS)

            log.events.value.map { it.category } shouldBe listOf(DiagnosticCategory.TRANSPORT)
        }

    @Test
    fun `GATT availability follows the model, not the phone`() {
        val (gate, _) = gate(attempted(AapAvailability.ChannelModeRefused))

        gate.decorate(pod(PodModel.POWERBEATS_PRO_2)).statusOf(Transport.GATT).isAvailable shouldBe true
        gate.decorate(pod(PodModel.AIRPODS_PRO_3)).statusOf(Transport.GATT).isAvailable shouldBe false
    }

    @Test
    fun `AirPods Pro 3 heart rate stays locked even with the channel open`() =
        runTest {
            val (gate, _) = gate(attempted(AapAvailability.Available))
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
