package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodState

/**
 * Renders the app's whole state as JSON, for `adb` to read out of logcat.
 *
 * Hand-rolled rather than using `org.json` so it is a pure function that can be unit
 * tested on the JVM — the Android JSON classes are stubs under unit tests and would
 * silently produce empty output, which is the one failure mode a diagnostic tool must
 * not have.
 *
 * The dump is deliberately complete, and includes the *reason* attached to every
 * transport. A dump that says "unavailable" without saying why would leave adb-driven
 * verification exactly as blind as a screenshot.
 */
object StateDump {
    fun render(
        pods: List<PodState>,
        settings: GreenPodsSettings,
        diagnostics: List<DiagnosticEvent>,
        scanFailure: String?,
        version: String,
    ): String =
        obj(
            "version" to str(version),
            "scanFailure" to (scanFailure?.let(::str) ?: "null"),
            "pods" to array(pods.map(::pod)),
            "settings" to settings(settings),
            "diagnostics" to array(diagnostics.map(::diagnostic)),
        )

    private fun pod(pod: PodState): String =
        obj(
            "address" to str(pod.address),
            "name" to str(pod.name),
            "model" to str(pod.model.name),
            "modelId" to str("0x%04X".format(pod.model.modelId)),
            "rssi" to (pod.rssi?.toString() ?: "null"),
            "lastSeenEpochMillis" to pod.lastSeenEpochMillis.toString(),
            "battery" to
                obj(
                    "left" to battery(pod.battery.left),
                    "right" to battery(pod.battery.right),
                    "case" to battery(pod.battery.case),
                ),
            "wear" to
                obj(
                    "primary" to str(pod.earDetection.primary.name),
                    "secondary" to str(pod.earDetection.secondary.name),
                ),
            "noiseControlMode" to (pod.noiseControlMode?.name?.let(::str) ?: "null"),
            "conversationalAwareness" to (pod.conversationalAwarenessEnabled?.toString() ?: "null"),
            "adaptiveNoiseStrength" to (pod.adaptiveNoiseStrength?.toString() ?: "null"),
            "heartRateBpm" to (pod.heartRate?.beatsPerMinute?.toString() ?: "null"),
            "transports" to
                array(
                    pod.transportStatuses.map { status ->
                        obj(
                            "transport" to str(status.transport.name),
                            "availability" to str(status.availability.name),
                            "reason" to str(status.reason),
                        )
                    },
                ),
            "usableFeatures" to array(pod.usableFeatures.map { str(it.name) }),
            "gatedFeatures" to
                array(
                    pod.gatedFeatures.map { feature ->
                        obj("feature" to str(feature.name), "reason" to str(pod.reasonFor(feature)))
                    },
                ),
        )

    private fun battery(component: BatteryComponent): String =
        obj(
            "percent" to (component.levelPercent?.toString() ?: "null"),
            "status" to str(component.status.name),
        )

    private fun settings(settings: GreenPodsSettings): String =
        obj(
            "autoPauseEnabled" to settings.autoPauseEnabled.toString(),
            "autoResumeEnabled" to settings.autoResumeEnabled.toString(),
            "pauseOnlyWhenBothOut" to settings.pauseOnlyWhenBothOut.toString(),
            "backgroundMonitoringEnabled" to settings.backgroundMonitoringEnabled.toString(),
            "lowBatteryWarningEnabled" to settings.lowBatteryWarningEnabled.toString(),
            "lowBatteryThresholdPercent" to settings.lowBatteryThresholdPercent.toString(),
            "scanMode" to str(settings.scanMode.name),
            "headGesturesEnabled" to settings.headGesturesEnabled.toString(),
            "gestureBindings" to
                array(
                    settings.gestureBindings.map { binding ->
                        obj(
                            "gesture" to str(binding.gesture.name),
                            "action" to str(binding.action.name),
                            "enabled" to binding.enabled.toString(),
                            "minimumConfidence" to binding.minimumConfidence.toString(),
                        )
                    },
                ),
        )

    private fun diagnostic(event: DiagnosticEvent): String =
        obj(
            "atEpochMillis" to event.atEpochMillis.toString(),
            "category" to str(event.category.name),
            "message" to str(event.message),
            "detail" to str(event.detail),
        )

    private fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (key, value) -> "${str(key)}:$value" }

    private fun array(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]")

    /** Minimal JSON string escaping — enough for names, reasons and hex dumps. */
    private fun str(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
}
