package io.github.andrewkomkov.greenpods.core.data.battery

import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodComponent
import io.github.andrewkomkov.greenpods.core.model.PodState

/** One warning worth showing the user. */
data class LowBatteryWarning(
    val address: String,
    val deviceName: String,
    val component: PodComponent,
    val levelPercent: Int,
)

/**
 * Decides when a low-battery warning is genuinely new.
 *
 * Advertisements repeat every couple of seconds, so the naive check —
 * `level < threshold` — would fire a notification continuously for as long as the buds
 * are low. What the user wants is one warning per *crossing*, which means remembering
 * what has already been warned about and re-arming only when the component is charged
 * back above the threshold.
 *
 * State is per accessory *and* per component: a low case does not suppress a warning
 * about a low left bud.
 */
class LowBatteryNotifier {
    private val warned = mutableSetOf<Key>()

    private data class Key(
        val address: String,
        val component: PodComponent,
    )

    /** Returns the warnings this reading newly justifies — usually none. */
    fun evaluate(
        pod: PodState,
        settings: GreenPodsSettings,
    ): List<LowBatteryWarning> {
        if (!settings.lowBatteryWarningEnabled) {
            // Re-arm everything, so turning the setting back on does not immediately
            // fire warnings for crossings that happened while it was off.
            warned.removeAll { it.address == pod.address }
            return emptyList()
        }

        val threshold = settings.lowBatteryThresholdPercent
        val fresh = mutableListOf<LowBatteryWarning>()

        PodComponent.entries.forEach { component ->
            val battery = pod.battery[component]
            val level = battery.levelPercent
            val key = Key(pod.address, component)

            if (level == null) return@forEach

            // Charging back up re-arms the warning; a plugged-in bud is not a problem.
            val healthy = level > threshold || battery.status == ChargeStatus.CHARGING
            if (healthy) {
                warned.remove(key)
            } else if (warned.add(key)) {
                fresh += LowBatteryWarning(pod.address, pod.name, component, level)
            }
        }
        return fresh
    }

    /** Forgets everything — used when an accessory disappears for good. */
    fun forget(address: String) {
        warned.removeAll { it.address == address }
    }
}
