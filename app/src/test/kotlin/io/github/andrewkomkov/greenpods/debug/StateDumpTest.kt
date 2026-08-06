package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.OrientationField
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
        calibrations: List<HeadCalibration> = emptyList(),
        connectedModel: PodModel? = pods.firstOrNull()?.model,
    ) = StateDump.render(
        pods = pods,
        settings = GreenPodsSettings.Default,
        diagnostics = diagnostics,
        scanFailure = scanFailure,
        version = "1.2.3",
        calibrations = calibrations,
        connectedModel = connectedModel,
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
    fun `a measuring pod puts its state in the dump and its heart rate nowhere`() {
        // The field this replaced printed BPM outright, which put a heart rate into
        // every bug report anyone pasted. FR-023 and SC-005 are checked here because
        // the dump is the one output the project asks people to send in.
        val measuring =
            pod.copy(
                model = PodModel.AIRPODS_PRO_3,
                activeTransports = setOf(Transport.BLE_ADVERTISEMENT, Transport.AAP_L2CAP),
                transportStatuses =
                    listOf(
                        TransportStatus.AdvertisementAvailable,
                        TransportStatus(Transport.AAP_L2CAP, TransportAvailability.AVAILABLE, "Channel open."),
                    ),
                heartRateSession =
                    HeartRateState.Measuring(
                        HeartRateReading(
                            beatsPerMinute = 137,
                            confidence = 233,
                            source = HeartRateReading.Source.AAP,
                            measuredAtEpochMillis = 1_770_000_000_000L,
                        ),
                    ),
                heartRateSensing =
                    HeartRateSensing(
                        enabled = true,
                        requestedIntervalMicros = 1_000_000,
                        serviceId = 0x13,
                        source = HeartRateReading.Source.AAP,
                        reportsReceived = 63,
                        trustedCount = 59,
                    ),
            )

        val json = render(pods = listOf(measuring))

        json shouldContain "\"state\":\"MEASURING\""
        json shouldContain "\"trustedCount\":59"
        json shouldContain "\"serviceId\":\"0x13\""
        json shouldNotContain "137"
        json shouldNotContain "heartRateBpm"
        json shouldNotContain "233"
    }

    @Test
    fun `a never-calibrated axis is present and says so, rather than being absent`() {
        // The whole reason this object is in the dump. "Never measured" and "the dump forgot
        // it" are different facts, and a missing key gives a reader no way to tell them apart.
        val json = render()

        json shouldContain "\"connectedModel\":\"AIRPODS_PRO_2\""
        json shouldContain "\"YAW\":{\"verdict\":\"UNCALIBRATED\""
        json shouldContain "\"PITCH\":{\"verdict\":\"UNCALIBRATED\""
        json shouldContain "\"ROLL\":{\"verdict\":\"UNCALIBRATED\""
        json shouldContain "\"applied\":false"
    }

    @Test
    fun `only the connected model's measured axes are reported as applied`() {
        val measured =
            HeadCalibration(
                model = PodModel.AIRPODS_PRO_2,
                axes =
                    mapOf(
                        HeadAxis.YAW to
                            AxisCalibration(
                                HeadAxis.YAW,
                                AxisVerdict.Measured(0.01431f, OrientationField.O1, 6290),
                            ),
                        // A scale the app itself called implausible, and nobody has confirmed:
                        // it carries a number and must still not be in force.
                        HeadAxis.PITCH to
                            AxisCalibration(
                                HeadAxis.PITCH,
                                AxisVerdict.Suspect(2.25f, OrientationField.O2, 40, "90 degrees from 40 units"),
                            ),
                        HeadAxis.ROLL to
                            AxisCalibration(HeadAxis.ROLL, AxisVerdict.NotHeld("never settled")),
                    ),
            )
        val elsewhere = HeadCalibration.uncalibrated(PodModel.AIRPODS_PRO_3)

        val json = render(calibrations = listOf(measured, elsewhere))

        json shouldContain "\"verdict\":\"MEASURED\""
        json shouldContain "\"field\":\"O1\""
        json shouldContain "\"applied\":true"
        json shouldContain "\"verdict\":\"SUSPECT\""
        json shouldContain "\"verdict\":\"NOT_HELD\""
        json shouldContain "never settled"
        // The other model is stored and idle; it is listed, and nothing of it is in force.
        json shouldContain "\"model\":\"AIRPODS_PRO_3\", \"connected\":false"
    }

    @Test
    fun `an unmeasurable verdict carries no number for anyone to mistake for one`() {
        val refused =
            HeadCalibration(
                model = PodModel.AIRPODS_PRO_2,
                axes =
                    HeadAxis.entries.associateWith { axis ->
                        AxisCalibration(
                            axis,
                            AxisVerdict.CrossCoupled(
                                mapOf(OrientationField.O1 to 6290, OrientationField.O2 to 5900),
                            ),
                        )
                    },
            )

        val json = render(calibrations = listOf(refused))

        json shouldContain "\"verdict\":\"CROSS_COUPLED\""
        json shouldContain "\"degreesPerUnit\":null"
        json shouldNotContain "\"applied\":true"
    }

    @Test
    fun `an empty state still produces a parseable document`() {
        val json = render(pods = emptyList())

        json shouldContain "\"pods\":[]"
        json shouldContain "\"diagnostics\":[]"
        json shouldContain "\"settings\":{"
    }
}
