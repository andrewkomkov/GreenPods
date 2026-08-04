package io.github.andrewkomkov.greenpods.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The two rules the whole feature rests on, tested where they are defined.
 *
 * FR-006 and FR-007 say a reading below the confidence gate is neither displayed nor
 * recorded, and FR-009 says an impossible value is discarded. Both are enforced by the
 * shape of these types rather than by every consumer remembering — which is only true
 * if the shape actually forbids the alternative, and that is what this pins.
 */
class HeartRateStateTest {
    private val reading =
        HeartRateReading(
            beatsPerMinute = 81,
            confidence = 205,
            source = HeartRateReading.Source.AAP,
            measuredAtEpochMillis = 1_770_000_000_000L,
            sequence = 12,
        )

    @Test
    fun `measuring is the only state that can produce a number`() {
        val states =
            listOf(
                HeartRateState.Unsupported("no sensor"),
                HeartRateState.Locked("channel refused", Transport.AAP_L2CAP),
                HeartRateState.Off,
                HeartRateState.Starting(sinceEpochMillis = 1L),
                HeartRateState.Settling(sinceEpochMillis = 1L),
                HeartRateState.Uncertain(lastTrustedAtEpochMillis = 1L),
                HeartRateState.Unavailable("not worn"),
            )

        states.forEach { state -> state.trustedReading.shouldBeNull() }
        HeartRateState.Measuring(reading).trustedReading shouldBe reading
    }

    @Test
    fun `uncertain withdraws the number rather than carrying a stale one`() {
        // The distinction matters: a state that kept the last reading would let a UI
        // render it as current, which is exactly what FR-006 forbids.
        val uncertain = HeartRateState.Uncertain(lastTrustedAtEpochMillis = reading.measuredAtEpochMillis)

        uncertain.trustedReading.shouldBeNull()
        uncertain.lastTrustedAtEpochMillis shouldBe reading.measuredAtEpochMillis
    }

    @Test
    fun `an implausible reading cannot be constructed at all`() {
        shouldThrow<IllegalArgumentException> { reading.copy(beatsPerMinute = 0) }
        shouldThrow<IllegalArgumentException> { reading.copy(beatsPerMinute = 24) }
        shouldThrow<IllegalArgumentException> { reading.copy(beatsPerMinute = 251) }
        shouldThrow<IllegalArgumentException> { reading.copy(beatsPerMinute = -5) }
    }

    @Test
    fun `the safe factory answers null instead of throwing, so a decoder can report it`() {
        HeartRateReading
            .orNull(
                beatsPerMinute = 251,
                confidence = 200,
                source = HeartRateReading.Source.AAP,
                measuredAtEpochMillis = 1L,
            ).shouldBeNull()

        HeartRateReading
            .orNull(
                beatsPerMinute = 25,
                confidence = null,
                source = HeartRateReading.Source.GATT,
                measuredAtEpochMillis = 1L,
            )?.beatsPerMinute shouldBe 25
    }

    @Test
    fun `every state prints a distinct name for the dump and the adb surface`() {
        val names =
            listOf(
                HeartRateState.Unsupported(""),
                HeartRateState.Locked("", Transport.AAP_L2CAP),
                HeartRateState.Off,
                HeartRateState.Starting(0),
                HeartRateState.Settling(0),
                HeartRateState.Measuring(reading),
                HeartRateState.Uncertain(null),
                HeartRateState.Unavailable(""),
            ).map(HeartRateState::stateName)

        names.toSet().size shouldBe names.size
        names.forEach { name -> name shouldBe name.uppercase() }
    }

    @Test
    fun `a model with no sensor reports unsupported, not locked`() {
        val pod =
            PodState(
                address = "AA:BB:CC:DD:EE:FF",
                model = PodModel.AIRPODS_PRO_2,
                activeTransports = Transport.entries.toSet(),
            )

        pod.heartRateFeature.shouldBeNull()
        (pod.heartRate is HeartRateState.Unsupported) shouldBe true
    }

    @Test
    fun `a model with a sensor and no transport reports locked, carrying the transport`() {
        val pod =
            PodState(
                address = "AA:BB:CC:DD:EE:FF",
                model = PodModel.AIRPODS_PRO_3,
                activeTransports = setOf(Transport.BLE_ADVERTISEMENT),
            )

        val locked = pod.heartRate
        (locked is HeartRateState.Locked) shouldBe true
        (locked as HeartRateState.Locked).transport shouldBe Transport.AAP_L2CAP
        locked.reason.isNotBlank() shouldBe true
    }

    @Test
    fun `the session state is only honoured once the gate is open`() {
        val gated =
            PodState(
                address = "AA:BB:CC:DD:EE:FF",
                model = PodModel.AIRPODS_PRO_3,
                activeTransports = setOf(Transport.BLE_ADVERTISEMENT),
                heartRateSession = HeartRateState.Measuring(reading),
            )

        // A session that somehow published a reading cannot show one through a gated
        // transport: the gate is applied after the session, not before it.
        gated.heartRate.trustedReading.shouldBeNull()

        val live = gated.copy(activeTransports = setOf(Transport.BLE_ADVERTISEMENT, Transport.AAP_L2CAP))
        live.heartRate.trustedReading shouldBe reading
    }
}
