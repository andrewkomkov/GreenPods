package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * The dump is the verification tool, so it is the one thing that must not quietly lie.
 */
class StateDumpTest {
    private val pod =
        PodState(
            address = "AA:BB:CC:DD:EE:FF",
            model = PodModel.AIRPODS_PRO_2,
            battery =
                BatteryState(
                    left = BatteryComponent(70, ChargeStatus.DISCHARGING),
                    right = BatteryComponent(null, ChargeStatus.UNKNOWN),
                    case = BatteryComponent(30, ChargeStatus.CHARGING),
                ),
            rssi = -44,
            transportStatuses =
                listOf(
                    TransportStatus.AdvertisementAvailable,
                    TransportStatus(
                        Transport.AAP_L2CAP,
                        TransportAvailability.UNAVAILABLE,
                        "The buds refused the channel mode this phone offers.",
                    ),
                ),
        )

    private fun render(
        pods: List<PodState> = listOf(pod),
        diagnostics: List<DiagnosticEvent> = emptyList(),
        scanFailure: String? = null,
    ) = StateDump.render(
        pods = pods,
        settings = GreenPodsSettings.Default,
        diagnostics = diagnostics,
        scanFailure = scanFailure,
        version = "1.2.3",
    )

    @Test
    fun `the dump carries identity, battery and signal`() {
        val json = render()

        json shouldContain "\"model\":\"AIRPODS_PRO_2\""
        json shouldContain "\"modelId\":\"0x1420\""
        json shouldContain "\"rssi\":-44"
        json shouldContain "\"version\":\"1.2.3\""
    }

    @Test
    fun `an unknown battery level is null, never zero`() {
        val json = render()

        // "right" must not be reported as a real reading; that would make an adb-driven
        // check pass while the UI correctly showed a dash.
        json shouldContain "\"right\":{\"percent\":null"
        json shouldContain "\"left\":{\"percent\":70"
    }

    @Test
    fun `every locked feature carries the reason that locked it`() {
        val json = render()

        json shouldContain "NOISE_CONTROL"
        json shouldContain "refused the channel mode"
        json shouldContain "\"availability\":\"UNAVAILABLE\""
        json shouldContain "\"availability\":\"AVAILABLE\""
    }

    @Test
    fun `an absent scan failure is null rather than an empty string`() {
        render() shouldContain "\"scanFailure\":null"
        render(scanFailure = "BLE scan failed: 2") shouldContain "\"scanFailure\":\"BLE scan failed: 2\""
    }

    @Test
    fun `diagnostics survive quoting`() {
        val json =
            render(
                diagnostics =
                    listOf(
                        DiagnosticEvent(
                            atEpochMillis = 42L,
                            category = DiagnosticCategory.UNKNOWN_TRAFFIC,
                            message = "Control \"CHIME_VOLUME\" has no decoder",
                            detail = "04 00\n09 1F",
                        ),
                    ),
            )

        json shouldContain "\\\"CHIME_VOLUME\\\""
        json shouldContain "04 00\\n09 1F"
        // A raw newline would break the one-line-per-dump contract adb relies on.
        json shouldNotContain "04 00\n09"
    }

    @Test
    fun `an empty state still produces a parseable document`() {
        val json = render(pods = emptyList())

        json shouldContain "\"pods\":[]"
        json shouldContain "\"diagnostics\":[]"
        json shouldContain "\"settings\":{"
    }
}
