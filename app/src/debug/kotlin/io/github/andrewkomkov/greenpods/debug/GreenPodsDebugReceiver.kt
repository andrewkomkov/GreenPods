package io.github.andrewkomkov.greenpods.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeaconDecoder
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.service.PodMonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The adb command surface. Debug builds only — see `src/debug/AndroidManifest.xml`.
 *
 * This exists because the states that matter cannot be produced from a laptop: a bud
 * leaving an ear, a case closing, a battery crossing a threshold, a transport being
 * refused. Driving them through the same pipeline the radio feeds is the difference
 * between verifying the app and photographing it.
 *
 * ```
 * adb shell am broadcast -a io.github.andrewkomkov.greenpods.DEBUG --es cmd dump
 * adb logcat -d -s GreenPodsDebug
 * ```
 *
 * Every command is documented with a runnable example in `docs/adb.md`.
 */
class GreenPodsDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = GreenPodsApplication.instance
        val command = intent.getStringExtra("cmd").orEmpty()

        when (command) {
            "dump" -> {
                dump(app, waitMillis = intent.getLongExtra("waitMs", DEFAULT_WAIT_MILLIS))
            }

            "probe" -> {
                probe(
                    app = app,
                    force = intent.getBooleanExtra("force", true),
                    waitMillis = intent.getLongExtra("waitMs", DEFAULT_WAIT_MILLIS),
                )
            }

            "set" -> {
                set(app, intent.getStringExtra("key").orEmpty(), intent.getStringExtra("value").orEmpty())
            }

            "inject" -> {
                inject(app, intent)
            }

            "monitor" -> {
                monitor(context, intent.getStringExtra("value") == "on")
            }

            "clear" -> {
                app.diagnostics.clear()
                reply("diagnostics cleared")
            }

            else -> {
                reply(
                    "unknown command '$command'. Known: dump, probe, set, inject, monitor, clear. " +
                        "See docs/adb.md",
                )
            }
        }
    }

    /** Prints the whole state as one JSON line. */
    private fun dump(
        app: GreenPodsApplication,
        waitMillis: Long,
    ) {
        app.applicationScope.launch {
            val json =
                StateDump.render(
                    pods = app.awaitPods(waitMillis),
                    settings = app.settingsRepository.settings.first(),
                    diagnostics = app.diagnostics.events.value,
                    scanFailure = app.podRepository.scanFailure.value,
                    version = app.versionName,
                )
            reply(json)
        }
    }

    /**
     * Forces a transport probe on the nearest accessory and prints the resulting status.
     *
     * The normal probe-once rule is bypassed on purpose: an operator asking over adb is
     * asking now, not asking whether the cache is warm.
     */
    private fun probe(
        app: GreenPodsApplication,
        force: Boolean,
        waitMillis: Long,
    ) {
        app.applicationScope.launch {
            val pod = app.awaitPods(waitMillis).firstOrNull()
            if (pod == null) {
                reply("probe: no accessory in range after ${waitMillis}ms")
                return@launch
            }
            val status = app.podRepository.probeAap(pod.address, force = force)
            reply("probe: ${pod.address} -> ${status.availability} :: ${status.reason}")
        }
    }

    private fun set(
        app: GreenPodsApplication,
        key: String,
        value: String,
    ) {
        val on = value.equals("on", ignoreCase = true) || value.equals("true", ignoreCase = true)
        app.applicationScope.launch {
            app.settingsRepository.update { current ->
                when (key) {
                    "autoPause" -> {
                        current.copy(autoPauseEnabled = on)
                    }

                    "autoResume" -> {
                        current.copy(autoResumeEnabled = on)
                    }

                    "pauseOnlyWhenBothOut" -> {
                        current.copy(pauseOnlyWhenBothOut = on)
                    }

                    "backgroundMonitoring" -> {
                        current.copy(backgroundMonitoringEnabled = on)
                    }

                    "lowBatteryWarning" -> {
                        current.copy(lowBatteryWarningEnabled = on)
                    }

                    "lowBatteryThreshold" -> {
                        current.copy(
                            lowBatteryThresholdPercent =
                                value.toIntOrNull() ?: current.lowBatteryThresholdPercent,
                        )
                    }

                    "headGestures" -> {
                        current.copy(headGesturesEnabled = on)
                    }

                    "scanMode" -> {
                        current.copy(
                            scanMode =
                                ScanMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                                    ?: current.scanMode,
                        )
                    }

                    else -> {
                        current
                    }
                }
            }
            reply("set $key=$value")
        }
    }

    /**
     * Injects a synthetic proximity-pairing advertisement.
     *
     * Either a full 27-byte payload as hex, or the interesting fields on their own —
     * model, battery levels, wear state — assembled into a valid payload here so a
     * caller does not have to know the nibble layout by heart.
     */
    private fun inject(
        app: GreenPodsApplication,
        intent: Intent,
    ) {
        val address = intent.getStringExtra("address") ?: DEFAULT_INJECT_ADDRESS
        val rssi = intent.getIntExtra("rssi", -45)

        val payload =
            intent.getStringExtra("payload")?.let(::parseHex)
                ?: SyntheticBeacon.build(
                    modelId = intent.getStringExtra("model")?.let { parseModelId(it) } ?: DEFAULT_MODEL_ID,
                    leftPercent = intent.getIntExtra("left", 70),
                    rightPercent = intent.getIntExtra("right", 70),
                    casePercent = intent.getIntExtra("case", -1).takeIf { it >= 0 },
                    wear = intent.getStringExtra("wear") ?: "in_ear",
                    charging = intent.getBooleanExtra("charging", false),
                )

        val beacon = AppleBeaconDecoder.decode(payload)
        if (beacon == null) {
            reply("inject: payload rejected by the decoder (${payload.size} bytes)")
            return
        }

        val accepted =
            app.podRepository.injectSighting(
                PodSighting(
                    address = address,
                    beacon = beacon,
                    rssi = rssi,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )

        // Label it, loudly. An injected accessory walks the same pipeline as a real one
        // and is indistinguishable on screen otherwise — which turns a test fixture into
        // a bug report about phantom AirPods.
        app.podRepository.onAapEvent(
            address,
            AapEvent.DeviceInfo(listOf("${beacon.model.displayName} (injected)")),
        )
        reply("inject: ${beacon.model.name} at $address, accepted=$accepted")
    }

    private fun monitor(
        context: Context,
        enable: Boolean,
    ) {
        val intent = Intent(context, PodMonitorService::class.java)
        if (enable) context.startForegroundService(intent) else context.stopService(intent)
        reply("monitor: ${if (enable) "started" else "stopped"}")
    }

    /**
     * Waits for the scanner to actually see something.
     *
     * The pods flow emits an empty list the instant it is subscribed, so taking the
     * first emission would report "nothing nearby" for an accessory that is simply one
     * advertisement interval away. Waiting is what makes an adb check trustworthy;
     * falling back to whatever is there keeps it from hanging when nothing ever comes.
     */
    private suspend fun GreenPodsApplication.awaitPods(waitMillis: Long): List<PodState> =
        withTimeoutOrNull(waitMillis) { podRepository.pods.first { it.isNotEmpty() } }
            ?: podRepository.pods.first()

    private fun parseHex(hex: String): ByteArray =
        hex
            .replace(" ", "")
            .chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

    private fun parseModelId(value: String): Int = value.removePrefix("0x").toIntOrNull(16) ?: DEFAULT_MODEL_ID

    private fun reply(message: String) {
        // logcat drops anything past roughly 4 kB in a single message, and a truncated
        // JSON document is worse than none: it parses as far as the cut and then lies.
        // Chunking keeps the dump reassemblable by concatenating the parts in order.
        if (message.length <= CHUNK_BYTES) {
            Log.i(TAG, message)
            return
        }
        val chunks = message.chunked(CHUNK_BYTES)
        chunks.forEachIndexed { index, chunk -> Log.i(TAG, "[${index + 1}/${chunks.size}] $chunk") }
    }

    private companion object {
        const val TAG = "GreenPodsDebug"

        /** Roughly three advertisement intervals — long enough to be fair, short enough to script. */
        const val DEFAULT_WAIT_MILLIS = 8_000L

        /** Comfortably inside logcat's per-message limit. */
        const val CHUNK_BYTES = 2_000
        const val DEFAULT_INJECT_ADDRESS = "DE:B0:60:00:00:01"

        /** AirPods Pro 2 — the model every fixture in this project was captured from. */
        const val DEFAULT_MODEL_ID = 0x1420
    }
}
