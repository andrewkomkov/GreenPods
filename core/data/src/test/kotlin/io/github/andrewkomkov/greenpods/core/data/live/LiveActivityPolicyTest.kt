package io.github.andrewkomkov.greenpods.core.data.live

import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.github.andrewkomkov.greenpods.core.model.PodComponent
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * What the live surface says, and when it says nothing.
 *
 * These are the rules that would be invisible once buried in a notification builder: that
 * an unknown battery is not zero, that an accessory out of range does not keep showing its
 * last levels, that a locked control is still shown, and that a heart-rate disclosure is
 * not something a setting can switch off.
 */
class LiveActivityPolicyTest {
    private val now = 1_000_000L

    private fun pod(
        left: Int? = 80,
        right: Int? = 70,
        case: Int? = 50,
        charging: Boolean = false,
        lastSeen: Long = 1_000_000L,
        transports: Set<Transport> = setOf(Transport.BLE_ADVERTISEMENT, Transport.AAP_L2CAP),
        heartRate: HeartRateState = HeartRateState.Off,
        address: String = "AA:BB:CC:DD:EE:FF",
        // Pro 3 rather than Pro 2: `PodState.heartRate` applies the transport gate, so a
        // model with no sensor reports Unsupported and never reaches a sensing state.
        model: PodModel = PodModel.AIRPODS_PRO_3,
    ): PodState {
        val status = if (charging) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING
        return PodState(
            address = address,
            model = model,
            battery =
                BatteryState(
                    left = BatteryComponent(left, status),
                    right = BatteryComponent(right, status),
                    case = BatteryComponent(case, status),
                ),
            earDetection = EarDetectionState(WearState.IN_EAR, WearState.IN_EAR),
            heartRateSession = heartRate,
            activeTransports = transports,
            transportStatuses =
                Transport.entries.map { transport ->
                    if (transport in transports) {
                        TransportStatus(transport, TransportAvailability.AVAILABLE, "Live.")
                    } else {
                        TransportStatus(
                            transport,
                            TransportAvailability.UNAVAILABLE,
                            "The channel is not open on this phone.",
                        )
                    }
                },
            lastSeenEpochMillis = lastSeen,
        )
    }

    private fun decide(
        pod: PodState? = pod(),
        settings: GreenPodsSettings = GreenPodsSettings(),
        availability: LiveActivityAvailability = LiveActivityAvailability.Available,
        monitoring: Boolean = true,
        policy: LiveActivityPolicy = LiveActivityPolicy(),
    ) = policy.decide(availability, settings, pod, monitoring, now)

    private fun summaryOf(
        pod: PodState? = pod(),
        settings: GreenPodsSettings = GreenPodsSettings(),
    ) = decide(pod = pod, settings = settings).shouldBeInstanceOf<LiveActivityDecision.Post>().summary

    @Test
    fun `an unknown battery is not zero`() {
        // The accessory reports an explicit unknown sentinel, and a case that has never
        // been opened has no level at all. Rendering either as 0% would say the earbuds
        // are flat, which is a different and alarming claim.
        val summary = summaryOf(pod(case = null))

        summary.casePercent.shouldBeNull()
        summary.leftPercent shouldBe 80
    }

    @Test
    fun `out of range drops the levels rather than carrying them forward`() {
        // The last known percentages are still in memory. Showing them is indistinguishable
        // from showing current ones, and on a glanceable surface it would be believed.
        val summary = summaryOf(pod(lastSeen = now - 60_000L))

        summary.presence shouldBe Presence.OUT_OF_RANGE
        summary.leftPercent.shouldBeNull()
        summary.rightPercent.shouldBeNull()
        summary.casePercent.shouldBeNull()
    }

    @Test
    fun `an accessory just seen is in range and keeps its levels`() {
        val summary = summaryOf(pod(lastSeen = now - 1_000L))

        summary.presence shouldBe Presence.IN_RANGE
        summary.leftPercent shouldBe 80
    }

    @Test
    fun `charging is reported per component`() {
        summaryOf(pod(charging = true)).charging shouldContainExactly
            setOf(PodComponent.LEFT, PodComponent.RIGHT, PodComponent.CASE)
        summaryOf(pod(charging = false)).charging shouldBe emptySet()
    }

    @Test
    fun `the accessory is always named`() {
        summaryOf().accessoryName shouldBe PodModel.AIRPODS_PRO_3.displayName
    }

    @Test
    fun `noise control is offered when the transport carries it`() {
        summaryOf().noiseControl.shouldBeInstanceOf<ControlState.Offered>()
    }

    @Test
    fun `noise control is locked, with a reason, when the transport does not`() {
        // Both states are tested because the constitution requires transport-dependent
        // behaviour to be exercised live and gated. A missing control reads as a bug; a
        // locked one explains the platform.
        val locked =
            summaryOf(pod(transports = setOf(Transport.BLE_ADVERTISEMENT)))
                .noiseControl
                .shouldBeInstanceOf<ControlState.Locked>()

        locked.reason.isNotBlank() shouldBe true
    }

    @Test
    fun `with no session the surface says nothing about heart rate`() {
        summaryOf().sensing shouldBe SensingState.Idle
    }

    @Test
    fun `hiding the value never hides the disclosure`() {
        // The whole of the decision that put a reading on a lock screen. A user may decline
        // to display their heart rate; they may not have the sensor run without being told.
        val sensing =
            pod(
                heartRate =
                    HeartRateState.Measuring(
                        HeartRateReading(
                            beatsPerMinute = 72,
                            confidence = 200,
                            source = HeartRateReading.Source.AAP,
                            measuredAtEpochMillis = now,
                            sequence = 1,
                        ),
                    ),
            )

        summaryOf(sensing, GreenPodsSettings(liveActivityShowHeartRate = true))
            .sensing
            .shouldBeInstanceOf<SensingState.DisclosedWithRate>()
            .beatsPerMinute shouldBe 72

        summaryOf(sensing, GreenPodsSettings(liveActivityShowHeartRate = false))
            .sensing shouldBe SensingState.Disclosed
    }

    @Test
    fun `sensing is disclosed before there is a trustworthy number`() {
        // Settling costs the accessory's battery too. A disclosure that waited for a
        // trusted reading would stay silent through exactly the period worth knowing about.
        summaryOf(pod(heartRate = HeartRateState.Settling(now))).sensing shouldBe SensingState.Disclosed
    }

    @Test
    fun `an unavailable gate withholds the surface before any content is built`() {
        decide(availability = LiveActivityAvailability.PlatformTooOld(34))
            .shouldBeInstanceOf<LiveActivityDecision.Withhold>()
            .reason
            .shouldBeInstanceOf<NoSurface.Unavailable>()
            .availability
            .shouldBeInstanceOf<LiveActivityAvailability.PlatformTooOld>()
    }

    @Test
    fun `monitoring off and switched off are different reasons`() {
        // Undone by different actions, and the adb output is read by someone working out
        // which one applies.
        decide(monitoring = false)
            .shouldBeInstanceOf<LiveActivityDecision.Withhold>()
            .reason shouldBe NoSurface.MonitoringOff

        decide(settings = GreenPodsSettings(liveActivityEnabled = false))
            .shouldBeInstanceOf<LiveActivityDecision.Withhold>()
            .reason shouldBe NoSurface.TurnedOff
    }

    @Test
    fun `no accessory means nothing to describe`() {
        decide(pod = null)
            .shouldBeInstanceOf<LiveActivityDecision.Withhold>()
            .reason shouldBe NoSurface.NoAccessory
    }

    @Test
    fun `a dismissed surface is not reposted on the next tick`() {
        // The state flow ticks several times a second. Without this, one swipe becomes an
        // argument with the user — and the platform explicitly forbids reposting.
        val policy = LiveActivityPolicy()
        decide(policy = policy).shouldBeInstanceOf<LiveActivityDecision.Post>()

        policy.onDismissed("AA:BB:CC:DD:EE:FF")

        decide(policy = policy)
            .shouldBeInstanceOf<LiveActivityDecision.Withhold>()
            .reason shouldBe NoSurface.Dismissed
    }

    @Test
    fun `a different accessory is a reason to post again`() {
        val policy = LiveActivityPolicy()
        policy.onDismissed("AA:BB:CC:DD:EE:FF")

        decide(pod = pod(address = "11:22:33:44:55:66"), policy = policy)
            .shouldBeInstanceOf<LiveActivityDecision.Post>()
    }

    @Test
    fun `sensing starting is a reason to post again after a dismissal`() {
        // The disclosure obligation outranks the dismissal: the user swiped away a battery
        // readout, not their right to know the sensor turned on.
        val policy = LiveActivityPolicy()
        policy.onDismissed("AA:BB:CC:DD:EE:FF")

        decide(pod = pod(heartRate = HeartRateState.Settling(now)), policy = policy)
            .shouldBeInstanceOf<LiveActivityDecision.Post>()
    }
}
