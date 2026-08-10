package io.github.andrewkomkov.greenpods.debug

import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.PodModel
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
        /** Everything stored, per model. Empty is a fact about the store, not a gap here. */
        calibrations: List<HeadCalibration> = emptyList(),
        /** Whose calibration is actually being applied, when anything is in range. */
        connectedModel: PodModel? = null,
    ): String =
        obj(
            "version" to str(version),
            "scanFailure" to (scanFailure?.let(::str) ?: "null"),
            "pods" to array(pods.map(::pod)),
            "settings" to settings(settings),
            "headCalibration" to headCalibration(calibrations, connectedModel),
            "diagnostics" to array(diagnostics.map(::diagnostic)),
        )

    /**
     * What is stored per model, per axis, and which of it is in force (FR-027).
     *
     * **Every axis is always present**, and an unmeasured one reads `"verdict":
     * "UNCALIBRATED"` rather than being absent — "never measured" and "the dump forgot it"
     * are different facts, and a reader of a diagnostic has no way to tell them apart if the
     * key is simply missing.
     *
     * The connected model is listed even when nothing is stored for it, because that is the
     * one case where "uncalibrated" is worth saying out loud: it is the model whose angles are
     * currently coming from the labelled approximation.
     */
    private fun headCalibration(
        stored: List<HeadCalibration>,
        connected: PodModel?,
    ): String {
        val models = (stored.map { it.model } + listOfNotNull(connected)).distinct()
        return obj(
            "connectedModel" to (connected?.name?.let(::str) ?: "null"),
            "models" to
                array(
                    models.map { model ->
                        val calibration =
                            stored.firstOrNull { it.model == model } ?: HeadCalibration.uncalibrated(model)
                        obj(
                            "model" to str(model.name),
                            "connected" to (model == connected).toString(),
                            "measuredAtEpochMillis" to calibration.measuredAtEpochMillis.toString(),
                            "axes" to
                                obj(
                                    *HeadAxis.entries
                                        .map { axis ->
                                            axis.name to axis(calibration.forAxis(axis), model == connected)
                                        }.toTypedArray(),
                                ),
                        )
                    },
                ),
        )
    }

    /**
     * One axis: always a verdict, only sometimes a number.
     *
     * `applied` is the answer to the question the dump exists to settle — whether this axis is
     * changing what the app reports right now — and it is false for every verdict that carries
     * no usable scale, including an unconfirmed `SUSPECT`, because `appliedScale` is the single
     * reader of that rule.
     */
    private fun axis(
        calibration: AxisCalibration,
        connected: Boolean,
    ): String =
        obj(
            "verdict" to str(dumpName(calibration.verdict)),
            "field" to (calibration.respondingField?.name?.let(::str) ?: "null"),
            "degreesPerUnit" to
                when (val verdict = calibration.verdict) {
                    is AxisVerdict.Measured -> verdict.degreesPerUnit.toString()
                    is AxisVerdict.Suspect -> verdict.degreesPerUnit.toString()
                    else -> "null"
                },
            "detail" to
                when (val verdict = calibration.verdict) {
                    is AxisVerdict.NotHeld -> {
                        str(verdict.reason)
                    }

                    is AxisVerdict.Suspect -> {
                        str(verdict.why)
                    }

                    is AxisVerdict.Mismatched -> {
                        str("expected ${verdict.expectedField.name}, ${verdict.respondingField.name} responded")
                    }

                    is AxisVerdict.Inconclusive -> {
                        str(verdict.contenders.joinToString(",") { it.name })
                    }

                    is AxisVerdict.CrossCoupled -> {
                        str(verdict.responses.entries.joinToString(",") { "${it.key.name}:${it.value}" })
                    }

                    else -> {
                        "null"
                    }
                },
            "applied" to (connected && calibration.appliedScale != null).toString(),
            "measuredAtEpochMillis" to calibration.measuredAtEpochMillis.toString(),
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
                    "left" to str(pod.earDetection.left.name),
                    "right" to str(pod.earDetection.right.name),
                ),
            "noiseControlMode" to (pod.noiseControlMode?.name?.let(::str) ?: "null"),
            "conversationalAwareness" to (pod.conversationalAwarenessEnabled?.toString() ?: "null"),
            "adaptiveNoiseStrength" to (pod.adaptiveNoiseStrength?.toString() ?: "null"),
            // State and counters, never a value. The field this replaced printed BPM,
            // which put a heart rate in every bug report anyone pasted (FR-023, FR-028).
            "heartRate" to heartRate(pod),
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

    /**
     * The sensing story with no number in it.
     *
     * `trusted` is here on purpose: SC-009 is checked by comparing it against the health
     * store's own sample count, which is a count against a count and never a reading.
     */
    private fun heartRate(pod: PodState): String {
        val sensing = pod.heartRateSensing
        return obj(
            "state" to str(pod.heartRate.stateName),
            "enabled" to sensing.enabled.toString(),
            "source" to (sensing.source?.name?.let(::str) ?: "null"),
            "serviceId" to (sensing.serviceId?.let { str("0x%02X".format(it)) } ?: "null"),
            "requestedIntervalMicros" to (sensing.requestedIntervalMicros?.toString() ?: "null"),
            "reportsReceived" to sensing.reportsReceived.toString(),
            "trustedCount" to sensing.trustedCount.toString(),
            "discardedImplausible" to sensing.discardedImplausible.toString(),
            "lastStopReason" to (sensing.lastStopReason?.let(::str) ?: "null"),
        )
    }

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
            "heartRateEnabled" to settings.heartRateEnabled.toString(),
            "heartRateHealthConnectEnabled" to settings.heartRateHealthConnectEnabled.toString(),
            "heartRateIntervalMillis" to settings.heartRateIntervalMillis.toString(),
            "heartRateConfidenceThreshold" to settings.heartRateConfidenceThreshold.toString(),
            "calibrationToleranceUnits" to settings.calibrationToleranceUnits.toString(),
            "calibrationHoldMillis" to settings.calibrationHoldMillis.toString(),
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

    // The three below are `internal` rather than private so `CalibrationDriver` can emit its
    // export through the same escaping this file already trusts. A second JSON writer in the
    // debug build would be a second thing to get wrong about quoting.
    internal fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (key, value) -> "${str(key)}:$value" }

    internal fun array(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]")

    /** Minimal JSON string escaping — enough for names, reasons and hex dumps. */
    internal fun str(value: String): String =
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
