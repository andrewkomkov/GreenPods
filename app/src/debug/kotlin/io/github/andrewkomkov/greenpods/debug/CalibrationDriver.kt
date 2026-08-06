package io.github.andrewkomkov.greenpods.debug

import android.os.Build
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeadPoseMapper
import io.github.andrewkomkov.greenpods.core.data.head.CalibrationSession
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.CalibrationPose
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The calibration wizard, driven from `adb`.
 *
 * Debug builds only. Every action here is one of [CalibrationSession]'s own — `start`,
 * `advance`, `skip`, `repeat`, `confirm`, `finish`, `abandon` — because a second way into the
 * state machine would be a second thing to be wrong, and the whole point of driving the
 * wizard from a terminal is that it is the *same* wizard the screen drives.
 *
 * The run lives here rather than in the receiver because a `BroadcastReceiver` is constructed
 * per broadcast: state that has to survive from `cal start` to `cal finish` cannot live in
 * one.
 *
 * **`feed` addresses the session directly** (research R-9). `HeadTrackingController.stream()`
 * refuses before any sample arrives when the transport is gated, so routing injection through
 * it would make the wizard unverifiable on exactly the phones where verification matters
 * most. Injected samples are still published onto `PodRepository.onAapEvent` — the entry point
 * real frames arrive on — so the rest of the pipeline sees them; the direct hand-off is what
 * makes the run possible with no channel at all. This is the one place the wizard runs without
 * a live stream, and it exists in no release build.
 *
 * Nothing an injected run produces is a measurement of anything. It measures the machinery.
 */
internal object CalibrationDriver {
    /** The run in progress, or null. Survives between broadcasts; cleared by `start`. */
    private var session: CalibrationSession? = null

    /** Which accessory the run belongs to, so an injected sample is attributed to it. */
    private var address: String? = null

    /** The live stream, when one could be started. Cancelled by `finish` and `abandon`. */
    private var streamJob: Job? = null

    /** True once a real sample has arrived, which is what makes injecting it again a double count. */
    private var streamLive = false

    /** When the hold now running began, in real time. Injected timestamps are offsets from it. */
    private var lastAdvanceAtMillis = 0L

    /** Every sample this run saw, with the pose it belonged to. The export's evidence. */
    private val recorded = mutableListOf<Recorded>()

    /**
     * One sample as the run saw it.
     *
     * Carries the whole [HeadTrackingSample] — all five decoded fields, not the three the axes
     * use. The accelerations are what would distinguish a head rotation from the wearer moving,
     * and the repository currently contradicts itself about what bytes 28 and 30 are (research
     * R-3): the decoder calls them accelerations, the research notes describe them as part of a
     * four-int16 near-unit-norm vector. A run of this wizard is the cheapest evidence anyone
     * will get either way, and dropping two columns would throw it away for nothing.
     */
    data class Recorded(
        val poseIndex: Int,
        val pose: String,
        val atMillis: Long,
        val sample: HeadTrackingSample,
        val injected: Boolean,
    )

    // ---------------------------------------------------------------- running a wizard

    /**
     * Begins a run against [pod], and tries — separately — to start a live stream.
     *
     * The two are reported separately on purpose. The session is what `feed` drives, and it
     * starts whether or not this phone can open the Apple protocol channel; the stream is the
     * part that can be refused, and its refusal is the same one the entry point shows.
     */
    @Synchronized
    fun start(
        app: GreenPodsApplication,
        pod: PodState?,
        reply: (String) -> Unit,
    ) {
        if (pod == null) {
            reply(
                "cal: cannot start — NO_ACCESSORY :: nothing in range to ask. Inject one to run " +
                    "the wizard with no hardware: gp --es cmd inject --es model 0x1420",
            )
            return
        }

        stopStream()
        recorded.clear()
        val fresh = CalibrationSession(pod.model)
        fresh.start()
        session = fresh
        address = pod.address

        reply(
            "cal: started — model=${pod.model.name} address=${pod.address} " +
                "poses=${CalibrationPose.Sequence.size} (neutral first, then one per axis)",
        )
        reply("cal: nothing is stored until 'cal finish'; 'cal abandon' leaves any stored calibration untouched")
        reply("cal: next 'cal advance' begins the ${poseName(CalibrationPose.Sequence.first())} hold")
        startStream(app, pod, reply)
    }

    /**
     * Collects the real stream into the run, if it can be started.
     *
     * A refusal is reported and nothing else happens — the run stays driveable by `feed`. The
     * job is cancelled by `finish` and `abandon`, which is what stops the sensor in the
     * earbuds; the controller's `awaitClose` does the actual stop under `NonCancellable`.
     */
    private fun startStream(
        app: GreenPodsApplication,
        pod: PodState,
        reply: (String) -> Unit,
    ) {
        val controller =
            HeadTrackingController(
                repository = app.podRepository,
                gateway = app.controlGateway,
                serviceMemory = app.hidServiceMemory,
                diagnostics = app.diagnostics,
                scope = app.applicationScope,
                calibrations = app.headCalibrationStore,
            )
        streamJob =
            app.applicationScope.launch {
                try {
                    controller.stream().collect { sample ->
                        if (!streamLive) {
                            streamLive = true
                            reply("cal: live stream running — real samples are reaching the run")
                        }
                        onLiveSample(sample.raw)
                    }
                    onStreamEnded("the accessory stopped sending", reply)
                } catch (refused: HeadTrackingController.NotStreaming) {
                    reply("cal: cannot stream — ${refused.refusal.name} :: ${sentence(refused.refusal)}")
                    reply(
                        "cal: the run is still driveable with 'cal feed'. Injected samples exercise " +
                            "the wizard; they measure nothing about this accessory",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    onStreamEnded(failed.message ?: failed::class.java.simpleName, reply)
                }
            }
    }

    @Synchronized
    private fun onLiveSample(sample: HeadTrackingSample) {
        val active = session ?: return
        val at = System.currentTimeMillis()
        record(active, sample, at, injected = false)
        active.onSample(sample, at)
    }

    @Synchronized
    private fun onStreamEnded(
        reason: String,
        reply: (String) -> Unit,
    ) {
        val active = session ?: return
        val before = active.state
        active.onStreamEnded(reason)
        if (active.state !== before) {
            reply("cal: stream ended mid-run — $reason. Nothing is stored; a partial run is not a completed one")
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        streamLive = false
    }

    /** Begins the hold for the pose being shown. */
    @Synchronized
    fun advance(reply: (String) -> Unit) {
        val active = session ?: return reply(idle("advance"))
        val awaiting = active.state as? CalibrationSession.State.Awaiting
        if (awaiting == null) {
            reply("cal: advance ignored — state=${stateName(active.state)}. ${what(active.state)}")
            return
        }
        lastAdvanceAtMillis = System.currentTimeMillis()
        active.advance(lastAdvanceAtMillis)
        reply(
            "cal: holding ${poseName(awaiting.pose)} for ${awaiting.pose.holdMillis}ms — " +
                "hold the pose, or feed samples with 'cal feed'",
        )
    }

    /** Skips the current pose. Its axis stores nothing and says why. */
    @Synchronized
    fun skip(reply: (String) -> Unit) {
        val active = session ?: return reply(idle("skip"))
        val pose = currentPose(active)
        if (pose == null) {
            reply("cal: skip ignored — state=${stateName(active.state)}. ${what(active.state)}")
            return
        }
        active.skip()
        reply("cal: skipped ${poseName(pose)} — that axis will read SKIPPED and carry no number")
        reply(oneLine(active))
    }

    /** Re-runs the current pose, without restarting the run. */
    @Synchronized
    fun repeat(reply: (String) -> Unit) {
        val active = session ?: return reply(idle("repeat"))
        val pose = currentPose(active)
        if (pose == null) {
            reply("cal: repeat ignored — state=${stateName(active.state)}. ${what(active.state)}")
            return
        }
        active.repeat()
        recorded.removeAll { it.pose == poseName(pose) }
        reply("cal: repeating ${poseName(pose)} — 'cal advance' begins the hold again")
    }

    /** Ends the run and stores nothing. */
    @Synchronized
    fun abandon(reply: (String) -> Unit) {
        val active = session ?: return reply(idle("abandon"))
        active.abandon()
        stopStream()
        session = null
        reply("cal: abandoned — nothing stored, and any previously stored calibration is untouched")
    }

    /**
     * Confirms a suspect result so `finish` may store it (FR-018).
     *
     * Without an `axis` extra this confirms every suspect result waiting on a decision, and
     * names each one as it does. A number this app itself called implausible takes two
     * decisions to keep, and it keeps saying it is suspect afterwards.
     */
    @Synchronized
    fun confirm(
        axisName: String?,
        reply: (String) -> Unit,
    ) {
        val active = session ?: return reply(idle("confirm"))
        if (active.state !is CalibrationSession.State.Reviewing) {
            reply(
                "cal: confirm ignored — state=${stateName(active.state)}. A suspect result is " +
                    "confirmed once the run reaches review.",
            )
            return
        }
        val pending = active.unconfirmedSuspects()
        if (pending.isEmpty()) {
            reply("cal: confirm ignored — no suspect result is waiting on a decision")
            return
        }
        val targets =
            if (axisName.isNullOrBlank()) {
                pending
            } else {
                pending.filter { it.name.equals(axisName, ignoreCase = true) }
            }
        if (targets.isEmpty()) {
            reply(
                "cal: confirm ignored — '$axisName' has no suspect result. Waiting: " +
                    pending.joinToString { it.name },
            )
            return
        }
        targets.forEach { axis ->
            active.confirmSuspect(axis)
            reply(
                "cal: confirmed ${axis.name.lowercase()} — it is stored, and it stays flagged SUSPECT wherever it is shown",
            )
        }
    }

    /**
     * Stores the run (FR-018, FR-020), or refuses and says what is missing.
     *
     * The refusal carries the arithmetic. "This looks wrong" without the numbers is an opinion;
     * with them it is something the reader can check.
     */
    suspend fun finish(
        app: GreenPodsApplication,
        reply: (String) -> Unit,
    ) {
        val active = session ?: return reply(idle("finish"))
        val reviewing = active.state as? CalibrationSession.State.Reviewing
        if (reviewing == null) {
            reply(
                "cal: finish ignored — state=${stateName(active.state)}. ${what(active.state)}",
            )
            return
        }

        val pending = active.unconfirmedSuspects()
        if (pending.isNotEmpty()) {
            reply("cal: finish refused — ${pending.size} suspect result(s) not confirmed. Nothing stored")
            pending.forEach { axis ->
                reviewing.axes[axis]?.let {
                    reply(
                        "cal: ${axis.name.lowercase().padEnd(AXIS_PAD)} = ${verdictLine(it)}",
                    )
                }
            }
            reply("cal: 'cal confirm' keeps it anyway; 'cal start' re-runs from the beginning")
            return
        }

        val at = System.currentTimeMillis()
        val calibration = active.finish(at)
        if (calibration == null) {
            reply("cal: finish produced nothing to store — the run was not under review")
            return
        }
        app.headCalibrationStore.save(calibration)
        stopStream()
        // The run is over, and saying `state=IDLE` while still holding it would read as a run
        // that could be advanced. What the run *recorded* survives, because that is what
        // `export` and `fixture` carry.
        session = null
        reply("cal: stored for ${calibration.model.name} at ${iso(at)}")
        HeadAxis.entries.forEach { axis ->
            reply("cal: ${axis.name.lowercase().padEnd(AXIS_PAD)} = ${verdictLine(calibration.forAxis(axis))}")
        }
        if (!calibration.hasAnyScale) {
            reply(
                "cal: no axis carries a usable scale — angles keep the labelled approximation " +
                    "(${HeadPoseMapper.UNCALIBRATED_SCALE} deg/unit). That is a result, not a failure",
            )
        }
        reply("cal: 'cal export' prints it in the form docs/protocol-research.md takes")
    }

    // ---------------------------------------------------------------- reading it back

    /** The run's state, the pose being held, and the verdicts so far. */
    @Synchronized
    fun status(reply: (String) -> Unit) {
        val active = session
        if (active == null) {
            reply("cal: state=IDLE — no run in this process. 'cal start' begins one; 'cal show' prints what is stored")
            return
        }
        reply(oneLine(active))
        when (val state = active.state) {
            is CalibrationSession.State.Reviewing -> {
                HeadAxis.entries.forEach { axis ->
                    val calibration = state.axes[axis] ?: AxisCalibration(axis)
                    reply("cal: ${axis.name.lowercase().padEnd(AXIS_PAD)} = ${verdictLine(calibration)}")
                }
                val pending = active.unconfirmedSuspects()
                if (pending.isNotEmpty()) {
                    reply(
                        "cal: finish will refuse until ${pending.joinToString { it.name.lowercase() }} " +
                            "is confirmed with 'cal confirm'",
                    )
                }
            }

            else -> {
                // Verdicts do not exist before the run is solved, and printing a placeholder
                // number for them would be the one thing this feature must never do.
                HeadAxis.entries.forEach { axis ->
                    reply("cal: ${axis.name.lowercase().padEnd(AXIS_PAD)} = pending")
                }
            }
        }
    }

    /** What is stored, per model and per axis, and which of it is being applied (FR-027). */
    suspend fun show(
        app: GreenPodsApplication,
        pod: PodState?,
        reply: (String) -> Unit,
    ) {
        val stored = app.headCalibrationStore.all()
        val connected = pod?.model
        val models = (stored.map { it.model } + listOfNotNull(connected)).distinct()
        if (models.isEmpty()) {
            reply("cal: nothing stored, and no accessory in range to say what would apply")
            return
        }
        models.forEach { model ->
            val calibration = stored.firstOrNull { it.model == model } ?: HeadCalibration.uncalibrated(model)
            val measured =
                if (calibration.measuredAtEpochMillis >
                    0L
                ) {
                    iso(calibration.measuredAtEpochMillis)
                } else {
                    "never"
                }
            reply("cal: ${model.name}${if (model == connected) " (connected)" else ""} measuredAt=$measured")
            HeadAxis.entries.forEach { axis ->
                val entry = calibration.forAxis(axis)
                val applied = model == connected && entry.appliedScale != null
                reply("cal:   ${axis.name.lowercase().padEnd(AXIS_PAD)} = ${verdictLine(entry)} applied=$applied")
            }
        }
        val connectedCalibration = connected?.let { model -> stored.firstOrNull { it.model == model } }
        if (connected != null && connectedCalibration?.hasAnyScale != true) {
            reply(
                "cal: ${connected.name} reports angles from the labelled approximation " +
                    "(${HeadPoseMapper.UNCALIBRATED_SCALE} deg/unit), not from a measurement",
            )
        }
    }

    /** Returns the connected model to its uncalibrated fallback (FR-023). */
    suspend fun clear(
        app: GreenPodsApplication,
        pod: PodState?,
        reply: (String) -> Unit,
    ) {
        if (pod == null) {
            reply("cal: clear ignored — no accessory in range, so there is no model to clear")
            return
        }
        app.headCalibrationStore.clear(pod.model)
        reply(
            "cal: cleared ${pod.model.name} — every axis reads UNCALIBRATED and angles fall back " +
                "to the labelled approximation",
        )
    }

    /**
     * The stored calibration, in the form `docs/protocol-research.md` takes (FR-028).
     *
     * Three properties are contractual. **A verdict is always present; a scale is not** — a
     * record that silently omitted the failed axes would read as a three-axis calibration.
     * **The evidence travels with the number**, so `deltaUnits`, `referenceDegrees`,
     * `spanUnits`, `holdMillis` and `samples` are carried alongside it and a reader can judge
     * the measurement later or re-derive it under a different assumed reference angle. And
     * **`referenceDegrees` is nominal**: it is what the wearer was asked for, never what their
     * neck did.
     */
    suspend fun export(
        app: GreenPodsApplication,
        pod: PodState?,
        reply: (String) -> Unit,
    ) {
        val connected = pod?.model
        if (connected == null) {
            reply("cal: export ignored — no accessory in range, so there is no model to export")
            return
        }
        val calibration = app.headCalibrationStore.load(connected)
        val evidence = evidenceByAxis()

        reply(
            StateDump.obj(
                "model" to StateDump.str(connected.name),
                "measuredAt" to
                    StateDump.str(
                        if (calibration.measuredAtEpochMillis > 0L) {
                            iso(calibration.measuredAtEpochMillis)
                        } else {
                            "never"
                        },
                    ),
                "app" to StateDump.str(app.versionName),
                "axes" to
                    StateDump.obj(
                        *HeadAxis.entries
                            .map { axis ->
                                axis.name.lowercase() to axisJson(calibration.forAxis(axis), evidence[axis])
                            }.toTypedArray(),
                    ),
                "poses" to StateDump.array(poseRecords()),
            ),
        )
        if (recorded.isEmpty()) {
            reply(
                "cal: no per-pose record — no run in this process, so `poses` is empty and the " +
                    "axes come from storage alone",
            )
        }
    }

    /**
     * One JSON object per pose, carrying **all five decoded fields**.
     *
     * `o1`..`o3` are what the axes are derived from; `horizontalAcceleration` and
     * `verticalAcceleration` are carried because they are the only thing in the frame that
     * could distinguish a head rotation from the wearer moving, and because what bytes 28 and
     * 30 hold is an open question this run is evidence about (research R-3).
     */
    private fun poseRecords(): List<String> =
        recorded
            .groupBy { it.poseIndex }
            .toSortedMap()
            .map { (index, rows) ->
                StateDump.obj(
                    "pose" to StateDump.str(rows.first().pose),
                    "index" to index.toString(),
                    "samples" to rows.size.toString(),
                    "holdMillis" to (rows.last().atMillis - rows.first().atMillis).toString(),
                    "source" to StateDump.str(if (rows.all { it.injected }) "injected" else "stream"),
                    "medians" to
                        StateDump.obj(
                            *FIELDS
                                .map { (name, read) -> name to fmt("%.1f", median(rows.map { read(it.sample) })) }
                                .toTypedArray(),
                        ),
                    "spans" to
                        StateDump.obj(
                            *FIELDS
                                .map { (name, read) -> name to span(rows.map { read(it.sample) }).toString() }
                                .toTypedArray(),
                        ),
                )
            }

    /**
     * Writes the run's samples out as a labelled fixture (FR-030a).
     *
     * Printed rather than written to a file, because the file lives in the repository and the
     * app lives on a phone. Every line is prefixed so it can be lifted straight out of logcat
     * — see `docs/adb.md`.
     *
     * The provenance header is the point. A series with no source is not evidence, and this
     * project's rule is that a fixture is only ever changed by re-capturing from a device and
     * recording where the capture came from.
     */
    suspend fun fixture(
        app: GreenPodsApplication,
        pod: PodState?,
        reply: (String) -> Unit,
    ) {
        if (recorded.isEmpty()) {
            reply("cal: no samples recorded in this process — run 'cal start' and feed or hold a pose first")
            return
        }
        val synthetic = recorded.all { it.injected }
        val serviceId =
            pod?.address?.let { addr ->
                app.hidServiceMemory
                    .remembered(addr)
                    .firstOrNull { it.isHeadTracking }
                    ?.id
            }
        val line = { text: String -> reply("$FIXTURE_PREFIX$text") }

        line("# Head-tracking orientation samples from one calibration run, one row per sample.")
        line("#")
        line(
            "# Source: ${pod?.model?.name ?: "unknown model"}, firmware unknown (not read on this " +
                "path), host ${Build.MODEL} Android ${Build.VERSION.RELEASE}, ${iso(System.currentTimeMillis())},",
        )
        line(
            "#         service ${serviceId?.let { fmt("0x%02X", it) } ?: "unknown"}, " +
                if (synthetic) {
                    "rate synthetic — samples spread evenly across each hold."
                } else {
                    "rate as requested by HeadTrackingController's default interval."
                },
        )
        line("#")
        if (synthetic) {
            line("# SYNTHETIC: every row was injected with `cal feed`. It exercises the wizard and")
            line("# measures nothing about any accessory. It must never be used to pin a decoder.")
        } else {
            line("# CAPTURED: the rows are the samples the accessory sent during the run, verbatim,")
            line("# in the order they arrived, with the pose the wearer was asked to hold.")
        }
        line("#")
        line("# Do not edit these rows to make a test pass. A decoder that disagrees with a capture")
        line("# is wrong, or the capture must be re-taken from a device and its source recorded here.")
        line("#")
        line("# The reference angle each pose stands for is NOMINAL — what the wearer was asked for,")
        line("# never what their neck did.")
        line("#")
        line("# Columns: pose at_millis o1 o2 o3 horizontal vertical")
        line("")
        val origin = recorded.first().atMillis
        recorded.forEach { row ->
            line(
                fmt(
                    "%-7s %6d %7d %7d %7d %7d %7d",
                    row.pose,
                    row.atMillis - origin,
                    row.sample.orientation1.toInt(),
                    row.sample.orientation2.toInt(),
                    row.sample.orientation3.toInt(),
                    row.sample.horizontalAcceleration.toInt(),
                    row.sample.verticalAcceleration.toInt(),
                ),
            )
        }
        reply("cal: ${recorded.size} rows emitted with the '$FIXTURE_PREFIX' prefix")
    }

    // ---------------------------------------------------------------- injection

    /**
     * Injects synthetic orientation samples into the run (FR-026).
     *
     * The spec is `count@o1,o2,o3[,horizontal,vertical][~jitter]`, semicolon-separated. Each
     * segment fills one pose's hold: the samples are spread evenly across it so that `60@…`
     * means sixty samples and a full hold, and `8@…` means a hold too short to measure — both
     * outcomes reproducible to the sample, with no earbuds and no head.
     *
     * `~n` is a **deterministic** alternating jitter of ±n, not a random one, so a not-held run
     * reproduces rather than merely happening.
     */
    @Synchronized
    fun feed(
        app: GreenPodsApplication,
        spec: String,
        reply: (String) -> Unit,
    ) {
        val active = session ?: return reply(idle("feed"))
        val segments = parse(spec, reply) ?: return

        segments.forEachIndexed { index, segment ->
            val state = active.state
            if (state is CalibrationSession.State.Awaiting) {
                lastAdvanceAtMillis = System.currentTimeMillis()
                active.advance(lastAdvanceAtMillis)
            } else if (state !is CalibrationSession.State.Holding) {
                reply(
                    "cal: feed stopped after $index segment(s) — state=${stateName(state)}. " +
                        "${what(state)} Nothing further was injected",
                )
                return
            }
            val holding = active.state as? CalibrationSession.State.Holding ?: return
            val already = holding.samplesCollected
            val total = already + segment.count
            for (step in 0 until segment.count) {
                val at = lastAdvanceAtMillis + offset(already + step, total, holding.pose.holdMillis)
                deliver(app, active, segment.sampleAt(step), at)
            }
            reply(
                "cal: fed ${segment.count} samples to ${poseName(holding.pose)} across " +
                    "${holding.pose.holdMillis}ms" +
                    if (segment.jitter > 0) " with a deterministic +/-${segment.jitter} unit jitter" else "",
            )
        }
        reply(oneLine(active))
    }

    /**
     * One injected sample, to the session and to the pipeline.
     *
     * Both, and for different reasons. The session directly, because that is what makes the
     * wizard runnable with no channel open. `PodRepository.onAapEvent` as well, because it is
     * the entry point real frames arrive on and an injected pose should walk the identical
     * path — except while a live stream is running, when the controller would deliver the same
     * sample a second time and the run would count it twice.
     */
    private fun deliver(
        app: GreenPodsApplication,
        active: CalibrationSession,
        sample: HeadTrackingSample,
        atMillis: Long,
    ) {
        record(active, sample, atMillis, injected = true)
        active.onSample(sample, atMillis)
        if (!streamLive) {
            app.podRepository.onAapEvent(address ?: INJECTED_ADDRESS, AapEvent.HeadTracking(sample))
        }
    }

    private fun record(
        active: CalibrationSession,
        sample: HeadTrackingSample,
        atMillis: Long,
        injected: Boolean,
    ) {
        val holding = active.state as? CalibrationSession.State.Holding ?: return
        recorded += Recorded(holding.index, poseName(holding.pose), atMillis, sample, injected)
    }

    /** One segment of a sample spec. */
    private data class Segment(
        val count: Int,
        val values: List<Short>,
        val jitter: Int,
    ) {
        /** Sample [step], with the jitter alternating so the series is reproducible. */
        fun sampleAt(step: Int): HeadTrackingSample {
            val offset =
                if (jitter == 0) {
                    0
                } else if (step % 2 == 0) {
                    jitter
                } else {
                    -jitter
                }

            fun field(index: Int): Short =
                ((values.getOrElse(index) { 0 }.toInt() + if (index < ORIENTATION_FIELDS) offset else 0))
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            return HeadTrackingSample(
                orientation1 = field(0),
                orientation2 = field(1),
                orientation3 = field(2),
                horizontalAcceleration = field(3),
                verticalAcceleration = field(4),
            )
        }
    }

    private fun parse(
        spec: String,
        reply: (String) -> Unit,
    ): List<Segment>? {
        if (spec.isBlank()) {
            reply("cal: feed needs samples, e.g. --es samples \"60@0,0,0;60@6290,0,0\". See docs/adb.md")
            return null
        }
        val segments =
            spec.split(';').filter { it.isNotBlank() }.map { raw ->
                val jitterSplit = raw.trim().split('~')
                val jitter = jitterSplit.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val body = jitterSplit.first().split('@')
                val count = body.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val values =
                    body
                        .getOrNull(1)
                        ?.split(',')
                        .orEmpty()
                        .map { it.trim().toIntOrNull() }
                val usable = values.filterNotNull()
                if (count <= 0 || usable.size != values.size || usable.size < ORIENTATION_FIELDS) {
                    reply(
                        "cal: cannot parse segment '${raw.trim()}'. The form is " +
                            "count@o1,o2,o3[,horizontal,vertical][~jitter], e.g. 60@0,6290,0~4000",
                    )
                    return null
                }
                Segment(
                    count = count,
                    values = usable.map { it.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() },
                    jitter = jitter.coerceAtLeast(0),
                )
            }
        if (segments.isEmpty()) {
            reply("cal: no segments in '$spec'")
            return null
        }
        return segments
    }

    /**
     * Where sample [step] of [total] falls inside a hold.
     *
     * The last sample lands exactly on the hold's end, which is what makes `8@…` a hold that
     * ends with eight samples in it rather than one that never ends at all: the session
     * analyses when the countdown reaches zero, and a series that stopped short would leave the
     * run waiting for a sample that is never coming.
     */
    private fun offset(
        step: Int,
        total: Int,
        holdMillis: Long,
    ): Long = if (total <= 1) holdMillis else holdMillis * step / (total - 1)

    // ---------------------------------------------------------------- rendering

    /**
     * One axis's verdict, in words, with the responding field wherever one was identified.
     *
     * `field=` appears on `MISMATCHED` too, and deliberately: which field actually moved is the
     * evidence this whole feature exists to produce, and it is the most interesting thing it
     * prints.
     */
    private fun verdictLine(calibration: AxisCalibration): String =
        when (val verdict = calibration.verdict) {
            is AxisVerdict.Measured -> {
                fmt(
                    "MEASURED %.5f deg/unit field=%s delta=%d units from %s",
                    verdict.degreesPerUnit,
                    verdict.field.name,
                    verdict.deltaUnits,
                    calibration.axis.name,
                )
            }

            is AxisVerdict.Suspect -> {
                fmt(
                    "SUSPECT %.5f deg/unit field=%s delta=%d units confirmed=%s \"%s\"",
                    verdict.degreesPerUnit,
                    verdict.field.name,
                    verdict.deltaUnits,
                    verdict.confirmed,
                    verdict.why,
                )
            }

            is AxisVerdict.Mismatched -> {
                fmt(
                    "MISMATCHED expected=%s field=%s delta=%d units — no scale stored for this axis",
                    verdict.expectedField.name,
                    verdict.respondingField.name,
                    verdict.deltaUnits,
                )
            }

            is AxisVerdict.Inconclusive -> {
                "INCONCLUSIVE contenders=${verdict.contenders.joinToString(",") { it.name }}"
            }

            is AxisVerdict.CrossCoupled -> {
                "CROSS_COUPLED responses=" +
                    OrientationField.entries.joinToString(",") { "${it.name}:${verdict.responses[it] ?: 0}" }
            }

            is AxisVerdict.NotHeld -> {
                "NOT_HELD \"${verdict.reason}\""
            }

            AxisVerdict.Skipped -> {
                "SKIPPED"
            }

            AxisVerdict.Uncalibrated -> {
                "UNCALIBRATED"
            }
        }

    /** What the run is doing, in one line. */
    private fun oneLine(active: CalibrationSession): String =
        when (val state = active.state) {
            is CalibrationSession.State.Idle -> {
                "cal: state=IDLE"
            }

            is CalibrationSession.State.Awaiting -> {
                "cal: state=AWAITING pose=${poseName(state.pose)} index=${state.index} " +
                    "hold=${state.pose.holdMillis}ms"
            }

            is CalibrationSession.State.Holding -> {
                "cal: state=HOLDING pose=${poseName(state.pose)} remaining=${state.remainingMillis}ms " +
                    "samples=${state.samplesCollected}"
            }

            is CalibrationSession.State.Reviewing -> {
                "cal: state=REVIEWING — nothing is stored until 'cal finish'"
            }

            is CalibrationSession.State.Stopped -> {
                "cal: state=STOPPED reason=\"${state.reason}\""
            }
        }

    private fun stateName(state: CalibrationSession.State): String =
        when (state) {
            is CalibrationSession.State.Idle -> "IDLE"
            is CalibrationSession.State.Awaiting -> "AWAITING"
            is CalibrationSession.State.Holding -> "HOLDING"
            is CalibrationSession.State.Reviewing -> "REVIEWING"
            is CalibrationSession.State.Stopped -> "STOPPED"
        }

    /**
     * What to do instead, for an action that does not apply.
     *
     * A silently-ignored command is indistinguishable from success — the lesson `set` learned
     * when an unknown key reported a setting as applied while the surface carried on ignoring
     * it — so every refusal here names the state and says what would move it.
     */
    private fun what(state: CalibrationSession.State): String =
        when (state) {
            is CalibrationSession.State.Idle -> "Start a run first: 'cal start'."
            is CalibrationSession.State.Awaiting -> "A pose is waiting to begin: 'cal advance'."
            is CalibrationSession.State.Holding -> "A hold is running; wait for it, or 'cal skip'."
            is CalibrationSession.State.Reviewing -> "The run is under review: 'cal finish' or 'cal abandon'."
            is CalibrationSession.State.Stopped -> "The stream stopped mid-run: 'cal start' to run again."
        }

    private fun idle(action: String): String = "cal: $action ignored — state=IDLE. Start a run first: 'cal start'."

    private fun currentPose(active: CalibrationSession): CalibrationPose? =
        when (val state = active.state) {
            is CalibrationSession.State.Awaiting -> state.pose
            is CalibrationSession.State.Holding -> state.pose
            else -> null
        }

    private fun poseName(pose: CalibrationPose): String = pose.axis?.name ?: "NEUTRAL"

    private fun sentence(refusal: HeadTrackingController.Refusal): String =
        when (refusal) {
            HeadTrackingController.Refusal.NO_ACCESSORY -> {
                "nothing in range to ask"
            }

            HeadTrackingController.Refusal.UNSUPPORTED -> {
                "these earbuds do not track head movement"
            }

            HeadTrackingController.Refusal.UNAVAILABLE -> {
                "this phone cannot carry the commands, or the accessory never described itself"
            }
        }

    // ---------------------------------------------------------------- export helpers

    /** What the run observed for each axis, where this process still holds the run. */
    private fun evidenceByAxis(): Map<HeadAxis, Evidence> =
        recorded
            .groupBy { it.pose }
            .mapNotNull { (pose, rows) ->
                HeadAxis.entries.firstOrNull { it.name == pose }?.let { axis ->
                    axis to
                        Evidence(
                            samples = rows.size,
                            holdMillis = rows.last().atMillis - rows.first().atMillis,
                            spanUnits =
                                OrientationField.entries.maxOf { field ->
                                    span(rows.map { field.of(it.sample).toInt() })
                                },
                        )
                }
            }.toMap()

    /** The conditions a measurement was taken under, so a reader can judge it later. */
    private data class Evidence(
        val samples: Int,
        val holdMillis: Long,
        val spanUnits: Int,
    )

    private fun axisJson(
        calibration: AxisCalibration,
        evidence: Evidence?,
    ): String {
        val fields =
            buildList {
                add("verdict" to StateDump.str(dumpName(calibration.verdict)))
                when (val verdict = calibration.verdict) {
                    is AxisVerdict.Measured -> {
                        add("field" to StateDump.str(verdict.field.name))
                        add("degreesPerUnit" to verdict.degreesPerUnit.toString())
                        add("deltaUnits" to verdict.deltaUnits.toString())
                    }

                    is AxisVerdict.Suspect -> {
                        add("field" to StateDump.str(verdict.field.name))
                        add("degreesPerUnit" to verdict.degreesPerUnit.toString())
                        add("deltaUnits" to verdict.deltaUnits.toString())
                        add("confirmed" to verdict.confirmed.toString())
                        add("why" to StateDump.str(verdict.why))
                    }

                    is AxisVerdict.Mismatched -> {
                        add("expectedField" to StateDump.str(verdict.expectedField.name))
                        add("respondingField" to StateDump.str(verdict.respondingField.name))
                        add("deltaUnits" to verdict.deltaUnits.toString())
                    }

                    is AxisVerdict.Inconclusive -> {
                        add("contenders" to StateDump.array(verdict.contenders.map { StateDump.str(it.name) }))
                    }

                    is AxisVerdict.CrossCoupled -> {
                        add(
                            "responses" to
                                StateDump.obj(
                                    *OrientationField.entries
                                        .map { it.name to (verdict.responses[it] ?: 0).toString() }
                                        .toTypedArray(),
                                ),
                        )
                    }

                    is AxisVerdict.NotHeld -> {
                        add("reason" to StateDump.str(verdict.reason))
                    }

                    AxisVerdict.Skipped, AxisVerdict.Uncalibrated -> {
                        // No number and nothing to explain: the verdict is the whole record.
                    }
                }
                // Nominal, always: the angle the wearer was asked for, never the one their neck
                // reached. Carried on every axis so nobody re-derives a scale without it.
                add("referenceDegrees" to calibration.axis.referenceDegrees.toString())
                add("applied" to (calibration.appliedScale != null).toString())
                evidence?.let {
                    add("holdMillis" to it.holdMillis.toString())
                    add("spanUnits" to it.spanUnits.toString())
                    add("samples" to it.samples.toString())
                }
            }
        return StateDump.obj(*fields.toTypedArray())
    }

    private fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].toFloat() else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private fun span(values: List<Int>): Int = if (values.isEmpty()) 0 else values.max() - values.min()

    private fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    /**
     * Formatting that does not depend on where the phone thinks it is.
     *
     * `"%.5f".format(x)` uses the default locale, which writes `0,01431` in half of Europe —
     * and this output is parsed by scripts and pasted into a research document as JSON.
     */
    private fun fmt(
        format: String,
        vararg args: Any?,
    ): String = String.format(Locale.ROOT, format, *args)

    /** How wide an axis name is padded, so three verdict lines read as a column. */
    private const val AXIS_PAD = 6

    /** The three fields an axis can live in; the other two are carried but never scaled. */
    private const val ORIENTATION_FIELDS = 3

    /** Every line of an emitted fixture, so it can be lifted straight out of logcat. */
    const val FIXTURE_PREFIX = "cal-fixture: "

    /** Where an injected sample is attributed when the run has no address of its own. */
    private const val INJECTED_ADDRESS = "DE:B0:60:00:00:01"

    /** All five decoded fields, in the order the export prints them. */
    private val FIELDS: List<Pair<String, (HeadTrackingSample) -> Int>> =
        listOf(
            "o1" to { sample: HeadTrackingSample -> sample.orientation1.toInt() },
            "o2" to { sample: HeadTrackingSample -> sample.orientation2.toInt() },
            "o3" to { sample: HeadTrackingSample -> sample.orientation3.toInt() },
            "horizontalAcceleration" to { sample: HeadTrackingSample -> sample.horizontalAcceleration.toInt() },
            "verticalAcceleration" to { sample: HeadTrackingSample -> sample.verticalAcceleration.toInt() },
        )
}

/**
 * The wire name of a verdict, declared once.
 *
 * Both the `cal` command surface and the state dump print these, and two spellings of
 * `CROSS_COUPLED` would be two things for a script to get wrong.
 */
internal fun dumpName(verdict: AxisVerdict): String =
    when (verdict) {
        is AxisVerdict.Measured -> "MEASURED"
        is AxisVerdict.Suspect -> "SUSPECT"
        is AxisVerdict.Mismatched -> "MISMATCHED"
        is AxisVerdict.Inconclusive -> "INCONCLUSIVE"
        is AxisVerdict.CrossCoupled -> "CROSS_COUPLED"
        is AxisVerdict.NotHeld -> "NOT_HELD"
        AxisVerdict.Skipped -> "SKIPPED"
        AxisVerdict.Uncalibrated -> "UNCALIBRATED"
    }
