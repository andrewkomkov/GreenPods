package io.github.andrewkomkov.greenpods.core.data.battery

import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodComponent
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * One warning per crossing, not one per advertisement.
 *
 * Advertisements repeat every couple of seconds, so a naive threshold check would post
 * a notification continuously for as long as the buds were low — which is how a useful
 * warning becomes a reason to turn notifications off.
 */
class LowBatteryNotifierTest {
    private val settings = GreenPodsSettings.Default.copy(lowBatteryThresholdPercent = 20)

    private fun pod(
        left: Int? = null,
        right: Int? = null,
        case: Int? = null,
        leftStatus: ChargeStatus = ChargeStatus.DISCHARGING,
        address: String = "AA:BB:CC:DD:EE:FF",
    ) = PodState(
        address = address,
        model = PodModel.AIRPODS_PRO_2,
        battery =
            BatteryState(
                left = BatteryComponent(left, leftStatus),
                right = BatteryComponent(right, ChargeStatus.DISCHARGING),
                case = BatteryComponent(case, ChargeStatus.DISCHARGING),
            ),
    )

    @Test
    fun `crossing the threshold warns once`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 30), settings) shouldBe emptyList()

        val warnings = notifier.evaluate(pod(left = 19), settings)
        warnings.map { it.component } shouldBe listOf(PodComponent.LEFT)
        warnings.single().levelPercent shouldBe 19
    }

    @Test
    fun `staying low does not warn again`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 19), settings).size shouldBe 1
        repeat(10) { notifier.evaluate(pod(left = 19), settings) shouldBe emptyList() }
        notifier.evaluate(pod(left = 5), settings) shouldBe emptyList()
    }

    @Test
    fun `charging back up re-arms the warning`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 15), settings).size shouldBe 1
        notifier.evaluate(pod(left = 80), settings) shouldBe emptyList()
        notifier.evaluate(pod(left = 15), settings).size shouldBe 1
    }

    @Test
    fun `a bud on charge is not a problem worth a notification`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 5, leftStatus = ChargeStatus.CHARGING), settings) shouldBe emptyList()
    }

    @Test
    fun `each component is tracked separately`() {
        val notifier = LowBatteryNotifier()

        val first = notifier.evaluate(pod(left = 10, right = 90, case = 90), settings)
        first.map { it.component } shouldBe listOf(PodComponent.LEFT)

        val second = notifier.evaluate(pod(left = 10, right = 10, case = 90), settings)
        second.map { it.component } shouldBe listOf(PodComponent.RIGHT)
    }

    @Test
    fun `each accessory is tracked separately`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 10, address = "AA:AA:AA:AA:AA:AA"), settings).size shouldBe 1
        notifier.evaluate(pod(left = 10, address = "BB:BB:BB:BB:BB:BB"), settings).size shouldBe 1
    }

    @Test
    fun `an unknown level never warns`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(), settings) shouldBe emptyList()
    }

    @Test
    fun `switching warnings off re-arms them, so turning it back on is not a flood`() {
        val notifier = LowBatteryNotifier()
        val off = settings.copy(lowBatteryWarningEnabled = false)

        notifier.evaluate(pod(left = 10), settings).size shouldBe 1
        notifier.evaluate(pod(left = 10), off) shouldBe emptyList()
        notifier.evaluate(pod(left = 10), settings).size shouldBe 1
    }

    @Test
    fun `forgetting an accessory clears its history`() {
        val notifier = LowBatteryNotifier()

        notifier.evaluate(pod(left = 10), settings).size shouldBe 1
        notifier.forget("AA:BB:CC:DD:EE:FF")
        notifier.evaluate(pod(left = 10), settings).size shouldBe 1
    }
}
