package io.github.andrewkomkov.greenpods.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HiddenApiAccess
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.AppleBeaconDecoder
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodSighting
import io.github.andrewkomkov.greenpods.core.data.live.ControlState
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityDecision
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityGate
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityPolicy
import io.github.andrewkomkov.greenpods.core.data.live.SensingState
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.service.LiveActivityActionReceiver
import io.github.andrewkomkov.greenpods.service.LiveActivityActions
import io.github.andrewkomkov.greenpods.service.PodMonitorService
import kotlinx.coroutines.delay
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

            // Answerable with no accessory present, which is the point: it separates
            // "this phone will never reach the Apple protocol" from "the buds are not here".
            "hiddenapi" -> {
                reply("hiddenapi: ${HiddenApiAccess.ensureBluetoothSocketReachable()}")
            }

            "anc" -> {
                anc(app, intent.getStringExtra("value").orEmpty())
            }

            "raw" -> {
                raw(app, intent.getStringExtra("hex").orEmpty())
            }

            "hid" -> {
                hid(app)
            }

            "live" -> {
                val action = intent.getStringExtra("action").orEmpty()
                if (action.isEmpty()) live(app) else liveAction(context, action)
            }

            "hr" -> {
                heartRate(app, intent.getStringExtra("value").orEmpty())
            }

            "health" -> {
                health(
                    app = app,
                    action = intent.getStringExtra("value").orEmpty(),
                    minutes = intent.getLongExtra("minutes", DEFAULT_HEALTH_WINDOW_MINUTES).toInt(),
                )
            }

            "cal" -> {
                cal(app, intent)
            }

            else -> {
                reply(
                    "unknown command '$command'. Known: dump, probe, set, inject, monitor, clear, " +
                        "hiddenapi, anc, raw, hid, live, hr, health, cal. See docs/adb.md",
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
            val pods = app.awaitPods(waitMillis)
            val json =
                StateDump.render(
                    pods = pods,
                    settings = app.settingsRepository.settings.first(),
                    diagnostics = app.diagnostics.events.value,
                    scanFailure = app.podRepository.scanFailure.value,
                    version = app.versionName,
                    calibrations = app.headCalibrationStore.all(),
                    connectedModel = pods.firstOrNull()?.model,
                )
            reply(json)
        }
    }

    /**
     * The head-tracking calibration wizard, driven from a terminal.
     *
     * The actions are [io.github.andrewkomkov.greenpods.core.data.head.CalibrationSession]'s own,
     * one for one. There is no adb-only path into the state machine — a second path would be a
     * second thing to be wrong, and the value of driving the wizard from here comes entirely
     * from it being the same wizard the screen drives.
     *
     * `feed` is the exception that proves it: it addresses the session directly, because the
     * head-tracking stream refuses before a single sample arrives when the transport is gated,
     * and a calibration path that could only be exercised on a phone with a live Apple protocol
     * channel would be unverifiable on precisely the phones where that matters. It exists in no
     * release build, and nothing it produces is a measurement of an accessory.
     */
    private fun cal(
        app: GreenPodsApplication,
        intent: Intent,
    ) {
        val out: (String) -> Unit = ::reply
        when (val value = intent.getStringExtra("value").orEmpty().lowercase()) {
            // These need nothing in range: the run is already bound to a model.
            "advance" -> {
                CalibrationDriver.advance(out)
            }

            "skip" -> {
                CalibrationDriver.skip(out)
            }

            "repeat" -> {
                CalibrationDriver.repeat(out)
            }

            "abandon" -> {
                CalibrationDriver.abandon(out)
            }

            "status" -> {
                CalibrationDriver.status(out)
            }

            "confirm" -> {
                CalibrationDriver.confirm(intent.getStringExtra("axis"), out)
            }

            "feed" -> {
                CalibrationDriver.feed(app, intent.getStringExtra("samples").orEmpty(), out)
            }

            "finish" -> {
                app.applicationScope.launch { CalibrationDriver.finish(app, out) }
            }

            // These name an accessory, so they wait for the scanner the way every other
            // command that needs one does.
            "start", "show", "export", "clear", "fixture" -> {
                app.applicationScope.launch {
                    val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
                    when (value) {
                        "start" -> CalibrationDriver.start(app, pod, out)
                        "show" -> CalibrationDriver.show(app, pod, out)
                        "export" -> CalibrationDriver.export(app, pod, out)
                        "clear" -> CalibrationDriver.clear(app, pod, out)
                        else -> CalibrationDriver.fixture(app, pod, out)
                    }
                }
            }

            else -> {
                reply("cal: unknown value '$value'. Known: ${CAL_ACTIONS.joinToString()}. See docs/adb.md")
            }
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

    /**
     * Writes a listening mode over the Apple protocol and reports what came back.
     *
     * This is the only check that proves the channel end to end: a probe shows a socket
     * opened, whereas a mode change that the accessory echoes back as a control update
     * shows it is being talked to.
     */
    private fun anc(
        app: GreenPodsApplication,
        value: String,
    ) {
        val mode = NoiseControlMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        if (mode == null) {
            reply("anc: unknown mode '$value'. Known: ${NoiseControlMode.entries.joinToString { it.name }}")
            return
        }
        app.applicationScope.launch {
            val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
            if (pod == null) {
                reply("anc: no accessory in range")
                return@launch
            }
            val written = app.controlGateway.setNoiseControlMode(pod.address, mode)
            // Give the accessory a moment to answer, then report what it actually says
            // its mode is — not what we asked for.
            delay(ECHO_WAIT_MILLIS)
            val echoed =
                app.podRepository.pods
                    .first()
                    .firstOrNull { it.address == pod.address }
                    ?.noiseControlMode
            reply(
                "anc: wrote $mode to ${pod.address} -> accepted=$written, accessory reports ${echoed ?: "nothing yet"}",
            )
        }
    }

    /**
     * Sends a hand-assembled AAP frame over the live session.
     *
     * The protocol is only partly decoded, so the way to find out what a frame does is to
     * send it and watch the reply — turn frame logging on first:
     *
     * ```
     * adb shell setprop log.tag.AapTransport DEBUG
     * ```
     */
    private fun raw(
        app: GreenPodsApplication,
        hex: String,
    ) {
        val packet = parseHex(hex)
        if (packet.isEmpty()) {
            reply("raw: no bytes parsed from '$hex'")
            return
        }
        app.applicationScope.launch {
            val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
            if (pod == null) {
                reply("raw: no accessory in range")
                return@launch
            }
            val sent = app.controlGateway.sendRaw(pod.address, packet)
            reply("raw: ${packet.size} bytes to ${pod.address} -> accepted=$sent")
        }
    }

    /**
     * Prints the accessory's own description of its sensor services.
     *
     * Every offset and every scale this app reads out of a sensor report is supposed to
     * come from here rather than from a constant, so when a decoded value looks wrong the
     * first question is what the descriptor actually declares. Without a way to see it,
     * that question can only be answered by guessing — which is how a head pose came to be
     * read at three offsets nobody had derived.
     *
     * Descriptors are not measurements: they carry no heart rate and no orientation, only
     * the shape of the reports that will. Printing them is safe in a way that printing a
     * report body is not.
     */
    private fun hid(app: GreenPodsApplication) {
        app.applicationScope.launch {
            val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
            if (pod == null) {
                reply("hid: no accessory in range")
                return@launch
            }

            // The announcement happens once per connection, so ask for it rather than
            // assuming this channel has already seen one.
            app.controlGateway.describeServices(pod.address)
            delay(DESCRIBE_WAIT_MILLIS)

            val services = app.controlGateway.describedServices
            if (services.isEmpty()) {
                reply("hid: ${pod.address} described no services")
                return@launch
            }

            reply("hid: ${pod.address} describes ${services.size} services")
            services.forEach { service ->
                val tags =
                    buildList {
                        if (service.isHeartRate) add("heartRate")
                        if (service.isHeadTracking) add("headTracking")
                    }.joinToString(",").ifEmpty { "-" }
                reply(
                    "hid: service 0x%02X name=%s tags=%s descriptor=%d bytes".format(
                        service.id,
                        service.name ?: "?",
                        tags,
                        service.reportDescriptor.size,
                    ),
                )
                reply("hid: 0x%02X descriptor %s".format(service.id, hex(service.reportDescriptor)))

                val layout = service.layout
                if (layout == null) {
                    reply("hid: 0x%02X descriptor did not parse".format(service.id))
                    return@forEach
                }
                layout.reports.forEach { report ->
                    reply(
                        "hid: 0x%02X report %d input=%d bytes".format(
                            service.id,
                            report.reportId,
                            report.inputByteSize,
                        ),
                    )
                    report.inputFields.forEach { field ->
                        reply(
                            "hid: 0x%02X   in  page=0x%04X usage=0x%04X bits=%d x%d at byte %d".format(
                                service.id,
                                field.usagePage,
                                field.usage,
                                field.bitSize,
                                field.count,
                                field.byteOffset,
                            ),
                        )
                    }
                    report.featureFields.forEach { field ->
                        reply(
                            "hid: 0x%02X   fea page=0x%04X usage=0x%04X bits=%d x%d at byte %d".format(
                                service.id,
                                field.usagePage,
                                field.usage,
                                field.bitSize,
                                field.count,
                                field.byteOffset,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * Prints what the live status surface would show, and why it would not.
     *
     * **Prints no heart rate.** `sensing=DISCLOSED valueShown=true` says the surface would
     * carry a number; it never says which. That looks inconsistent with allowing the value
     * on a lock screen and is not: FR-023 forbids a reading in any diagnostic path, and
     * this output is the most diagnostic thing in the app — people paste it into bug
     * reports. A lock screen shows a user their own body's data; this does not.
     */
    private fun live(app: GreenPodsApplication) {
        app.applicationScope.launch {
            val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
            val settings = app.settingsRepository.settings.first()
            val availability = app.liveActivityGate.availability()

            // The service's own decision, when it has made one.
            //
            // Re-deriving it here would not be reporting the feature; it would be reporting
            // a second implementation that happens to share a class. Most of the time the
            // two agree, and the one place they cannot is the one worth checking: the
            // dismissal lives in the policy's instance state, so a policy constructed here
            // can never say `Dismissed` — this command used to report a surface as posted
            // seconds after it had been swiped away.
            //
            // The fallback is labelled rather than silent. `monitoring=true` in it is an
            // assumption, not an observation, and a reader has to be able to tell the two
            // apart.
            val live = PodMonitorService.lastDecision
            val decision =
                live ?: LiveActivityPolicy().decide(
                    availability = availability,
                    settings = settings,
                    pod = pod,
                    monitoring = true,
                    nowEpochMillis = System.currentTimeMillis(),
                )
            if (live == null) {
                reply("live: the monitoring service has published nothing — this is a prediction, not its state")
            }

            val name =
                when (availability) {
                    is LiveActivityAvailability.PlatformTooOld -> "PLATFORM_TOO_OLD"
                    LiveActivityAvailability.PromotionRefused -> "PROMOTION_REFUSED"
                    LiveActivityAvailability.NotificationsDenied -> "NOTIFICATIONS_DENIED"
                    is LiveActivityAvailability.NotPromotable -> "NOT_PROMOTABLE"
                    LiveActivityAvailability.Available -> "AVAILABLE"
                }
            val reason =
                when (availability) {
                    is LiveActivityAvailability.PlatformTooOld -> {
                        "API ${availability.apiLevel}, needs ${LiveActivityGate.MIN_SDK}"
                    }

                    LiveActivityAvailability.PromotionRefused -> {
                        "promoted notifications are off for this app"
                    }

                    LiveActivityAvailability.NotificationsDenied -> {
                        "notification permission not granted"
                    }

                    is LiveActivityAvailability.NotPromotable -> {
                        availability.reason
                    }

                    LiveActivityAvailability.Available -> {
                        "none"
                    }
                }

            when (decision) {
                is LiveActivityDecision.Withhold -> {
                    reply("live: availability=$name reason=\"$reason\" posted=false withheld=${decision.reason}")
                }

                is LiveActivityDecision.Post -> {
                    val summary = decision.summary
                    reply("live: availability=$name reason=\"$reason\" posted=true")
                    reply("live: accessory=\"${summary.accessoryName}\" presence=${summary.presence}")
                    reply(
                        "live: battery left=${percent(summary.leftPercent)} " +
                            "right=${percent(summary.rightPercent)} case=${percent(summary.casePercent)} " +
                            "charging=${summary.charging}",
                    )
                    reply("live: wear left=${summary.wear.left} right=${summary.wear.right}")
                    reply(
                        when (val control = summary.noiseControl) {
                            is ControlState.Offered -> "live: noiseControl=OFFERED mode=${control.mode ?: "unknown"}"
                            is ControlState.Locked -> "live: noiseControl=LOCKED reason=\"${control.reason}\""
                        },
                    )
                    reply(
                        when (summary.sensing) {
                            SensingState.Idle -> "live: sensing=IDLE"

                            SensingState.Disclosed -> "live: sensing=DISCLOSED valueShown=false"

                            // The value itself is deliberately absent — see the note above.
                            is SensingState.DisclosedWithRate -> "live: sensing=DISCLOSED valueShown=true"
                        },
                    )
                }
            }
        }
    }

    /**
     * Presses a button on the live surface, without a finger.
     *
     * The surface's controls are user-visible behaviour, and Principle VI does not exempt
     * behaviour for being behind a notification: a control that can only be exercised by
     * tapping the lock screen cannot be verified, only photographed. Before this existed,
     * `quickstart.md` sections 3 and 4 both read `# tap the action on the device`, which is
     * the admission that the check was manual.
     *
     * These send the **same intents the buttons send**, to the same receiver and the same
     * service. Not a shortcut past them into the gateway: a second route would prove the
     * gateway works and say nothing about whether the button reaches it, which is the half
     * that is actually new.
     *
     * `dismiss` is worth having for a reason the other two are not — it is the only way to
     * reach the dismissal rule at all, since the alternative is swiping a notification that
     * a script cannot swipe.
     */
    private fun liveAction(
        context: Context,
        action: String,
    ) {
        when (action.lowercase()) {
            "cycle" -> {
                context.sendBroadcast(surfaceIntent(context, LiveActivityActions.ACTION_CYCLE_NOISE_CONTROL))
                reply("live: pressed cycle — the mode moves only when the accessory echoes it")
            }

            "stop" -> {
                context.sendBroadcast(surfaceIntent(context, LiveActivityActions.ACTION_STOP_SENSING))
                reply("live: pressed stop — check `hr status` for reports ceasing in the accessory")
            }

            // Delivered to the service rather than the receiver, because that is where the
            // policy instance that owns the dismissal actually lives.
            "dismiss" -> {
                context.startService(
                    Intent(context, PodMonitorService::class.java)
                        .setAction(LiveActivityActions.ACTION_SURFACE_DISMISSED),
                )
                reply("live: dismissed — the surface must stay gone until a reason to repost")
            }

            else -> {
                reply("live: unknown action '$action'. Known: cycle, stop, dismiss")
            }
        }
    }

    /**
     * The button's intent, with the flag the button itself gets for free.
     *
     * `FLAG_RECEIVER_FOREGROUND` is not optional here, and leaving it off produced exactly
     * the failure this file's own documentation warns about: modern Android defers a
     * background broadcast while the app is not foregrounded, so `live --es action stop`
     * replied "pressed stop" and then nothing happened — sometimes. When the app happened to
     * be foreground it worked, which is the worst version of a bug, because the first run
     * confirms the feature and the second contradicts it.
     *
     * A real press does not need this: a notification action's `PendingIntent` is delivered
     * under the temporary allowlist the system grants the notification. So the flag makes the
     * adb path *equal* to the button rather than privileged over it.
     */
    private fun surfaceIntent(
        context: Context,
        action: String,
    ): Intent =
        Intent(context, LiveActivityActionReceiver::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    private fun percent(value: Int?): String = value?.let { "$it%" } ?: "unknown"

    /**
     * Turns heart-rate sensing on or off, or prints what it is doing.
     *
     * **Prints no value, ever.** Not here, not in `dump`, not in a diagnostic. The
     * counters are what make the feature verifiable — `trusted` here against `samples`
     * in `health count` is SC-009, checked in two commands — and a count is not a
     * reading (FR-028).
     */
    private fun heartRate(
        app: GreenPodsApplication,
        value: String,
    ) {
        when (value.lowercase()) {
            "on", "off" -> {
                val enable = value.equals("on", ignoreCase = true)
                app.applicationScope.launch {
                    app.settingsRepository.update { it.copy(heartRateEnabled = enable) }
                    // Enabling implies the monitoring service, because the session lives
                    // with the Bluetooth channel and continuous Bluetooth work needs one.
                    val intent = Intent(app, PodMonitorService::class.java)
                    if (enable) app.startForegroundService(intent) else Unit
                    reply("hr: ${if (enable) "enabled" else "disabled"}")
                }
            }

            "status", "" -> {
                app.applicationScope.launch {
                    val pod = app.awaitPods(DEFAULT_WAIT_MILLIS).firstOrNull()
                    if (pod == null) {
                        reply("hr: no accessory in range")
                        return@launch
                    }
                    val sensing = pod.heartRateSensing
                    reply(
                        "hr: ticks=${sensing.ticksSeen} state=${pod.heartRate.stateName} enabled=${sensing.enabled} " +
                            "source=${sensing.source ?: "none"} " +
                            "service=${sensing.serviceId?.let { "0x%02X".format(it) } ?: "none"} " +
                            "interval=${sensing.requestedIntervalMicros?.div(1000) ?: 0}ms " +
                            "reports=${sensing.reportsReceived} trusted=${sensing.trustedCount} " +
                            "discarded=${sensing.discardedImplausible} " +
                            "lastStop=${sensing.lastStopReason ?: "none"}",
                    )
                }
            }

            else -> {
                reply("hr: unknown value '$value'. Known: on, off, status")
            }
        }
    }

    /**
     * The health store, counted rather than read out.
     *
     * `count` filters on GreenPods' own `DataOrigin`. Counting everything in the window
     * would report a chest strap someone else's app is writing as our output, which makes
     * the verification claim false rather than merely imprecise (FR-029).
     */
    private fun health(
        app: GreenPodsApplication,
        action: String,
        minutes: Int,
    ) {
        val link = app.healthConnectLink
        app.applicationScope.launch {
            when (action.lowercase()) {
                "status", "" -> {
                    // The outcome counters are what make "nothing is in Health Connect"
                    // diagnosable: they separate never-attempted from attempted-and-
                    // skipped from attempted-and-failed, which used to look identical.
                    // Counts and reasons only — never a sample (FR-023).
                    val outcome = link.outcome
                    reply(
                        "health: available=${link.availability().isAvailable} " +
                            "write=${link.hasWritePermission()} read=${link.hasReadPermission()} " +
                            "flushes=${outcome.flushesAttempted} records=${outcome.recordsWritten} " +
                            "samples=${outcome.samplesWritten} " +
                            "lastSkip=${outcome.lastSkipReason ?: "none"} " +
                            "lastError=${outcome.lastError ?: "none"} " +
                            ":: ${link.availability().sentence}",
                    )
                }

                "count" -> {
                    val count = link.countOwnRecords(minutes)
                    if (count == null) {
                        reply("health: cannot count — provider unavailable or read permission not held")
                        return@launch
                    }
                    reply(
                        "health: own records in last ${minutes}m: ${count.records} records, " +
                            "${count.samples} samples",
                    )
                }

                "clear" -> {
                    reply("health: deleted own records = ${link.deleteOwnRecords()}")
                }

                else -> {
                    reply("health: unknown value '$action'. Known: status, count, clear")
                }
            }
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

                    "hrIntervalMs" -> {
                        current.copy(
                            heartRateIntervalMillis =
                                value.toIntOrNull() ?: current.heartRateIntervalMillis,
                        )
                    }

                    // The threshold ships provisional (R-4). Overriding it from adb is
                    // what makes calibration a measurement rather than a rebuild.
                    "hrConfidenceThreshold" -> {
                        current.copy(
                            heartRateConfidenceThreshold =
                                value.toIntOrNull() ?: current.heartRateConfidenceThreshold,
                        )
                    }

                    "hrHealthConnect" -> {
                        current.copy(heartRateHealthConnectEnabled = on)
                    }

                    "scanMode" -> {
                        current.copy(
                            scanMode =
                                ScanMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                                    ?: current.scanMode,
                        )
                    }

                    "liveActivityEnabled" -> {
                        current.copy(liveActivityEnabled = on)
                    }

                    "liveActivityShowHeartRate" -> {
                        current.copy(liveActivityShowHeartRate = on)
                    }

                    else -> {
                        current
                    }
                }
            }
            // An unknown key used to answer "set foo=bar" and change nothing, which is
            // indistinguishable from success — and cost exactly that: a setting reported as
            // applied while the surface carried on ignoring it. Say so instead.
            if (key in KNOWN_SETTING_KEYS) {
                reply("set $key=$value")
            } else {
                reply("set: unknown key '$key'. Known: ${KNOWN_SETTING_KEYS.joinToString()}")
            }
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

        /** How long to let the accessory answer a write before reporting what it said. */
        const val ECHO_WAIT_MILLIS = 1_500L

        /**
         * How long to let the service announcement arrive.
         *
         * Longer than an echo: the announcement is several kilobytes across two frames,
         * and it is sent once per connection, so a short wait here reports "no services"
         * for an accessory that was about to describe itself.
         */
        const val DESCRIBE_WAIT_MILLIS = 3_000L

        /**
         * Every key `set` actually acts on.
         *
         * Listed rather than derived because the `when` above is the only other place that
         * knows, and a key present in one and missing from the other is precisely the bug
         * this exists to catch.
         */
        val KNOWN_SETTING_KEYS =
            listOf(
                "autoPause",
                "autoResume",
                "pauseOnlyWhenBothOut",
                "backgroundMonitoring",
                "lowBatteryWarning",
                "lowBatteryThreshold",
                "scanMode",
                "headGestures",
                "hrIntervalMs",
                "hrConfidenceThreshold",
                "hrHealthConnect",
                "liveActivityEnabled",
                "liveActivityShowHeartRate",
            )

        /**
         * Every action `cal` takes, listed for the refusal that names them.
         *
         * Same reason `KNOWN_SETTING_KEYS` is listed: an unknown value that silently did
         * nothing would be indistinguishable from one that worked.
         */
        val CAL_ACTIONS =
            listOf(
                "start",
                "status",
                "advance",
                "skip",
                "repeat",
                "feed",
                "confirm",
                "finish",
                "abandon",
                "show",
                "export",
                "clear",
                "fixture",
            )

        /** The window `health count` looks back over when none is given. */
        const val DEFAULT_HEALTH_WINDOW_MINUTES = 10L

        /** Comfortably inside logcat's per-message limit. */
        const val CHUNK_BYTES = 2_000
        const val DEFAULT_INJECT_ADDRESS = "DE:B0:60:00:00:01"

        /** AirPods Pro 2 — the model every fixture in this project was captured from. */
        const val DEFAULT_MODEL_ID = 0x1420
    }
}
