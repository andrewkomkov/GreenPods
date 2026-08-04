package io.github.andrewkomkov.greenpods.core.data.heartrate

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidTransport
import io.github.andrewkomkov.greenpods.core.data.AddressedAapEvent
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.HeartRateSensing
import io.github.andrewkomkov.greenpods.core.model.HeartRateState
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Heart rate over the standard Bluetooth profile, as the controller needs it. */
fun interface GattHeartRateReadings {
    /** Readings for one accessory, for as long as collection continues. */
    fun readings(address: String): Flow<HeartRateReading>
}

/**
 * Where a reading goes once — and only once — it has been judged trustworthy.
 *
 * Deliberately narrow. The health-store writer accepts what came out of
 * [HeartRateState.Measuring] and nothing else can produce one, so FR-007 ("never record
 * what you would not display") is enforced by the type rather than by remembering.
 */
interface TrustedReadingSink {
    suspend fun onTrusted(
        pod: PodState,
        reading: HeartRateReading,
    )

    /** Sensing ended. A partial batch is flushed here rather than discarded. */
    suspend fun onSensingStopped(address: String)
}

/**
 * Owns the heart-rate session: settings plus wear state plus transport, in, start and
 * stop commands and a published [HeartRateState], out.
 *
 * The controller does no judging — [HeartRateConfidencePolicy] does that — and it holds
 * no protocol knowledge; it holds a session. What it is careful about is the one thing
 * this transport punishes: **a write is not a change**. Nothing here reaches
 * [HeartRateState.Measuring] because a start command was accepted. The only edge into it
 * is a report arriving and the policy trusting it (Principle I).
 *
 * Every input arrives on one merged stream and every piece of state is mutated by one
 * collector, so there is no lock and no window in which two updates race. The interesting
 * failures — never converging, confidence collapsing, the accessory vanishing
 * mid-measurement — are all reachable from fakes.
 */
class HeartRateController(
    private val pods: Flow<List<PodState>>,
    private val aapEvents: Flow<AddressedAapEvent>,
    private val settings: Flow<GreenPodsSettings>,
    private val commands: HeartRateCommands,
    /** Null where no standard-profile source exists, which is every build without one wired. */
    private val gatt: GattHeartRateReadings? = null,
    private val sink: TrustedReadingSink? = null,
    private val publish: (String, HeartRateState, HeartRateSensing) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Drives the no-convergence timeout. A flow rather than a delay loop so a test can
     * make thirty seconds pass without waiting for them.
     */
    private val ticks: Flow<Unit> =
        flow {
            while (true) {
                delay(TICK_MILLIS)
                emit(Unit)
            }
        },
    private val settleTimeoutMillis: Long = SETTLE_TIMEOUT_MILLIS,
) {
    /** What the controller can ask a transport to do. Nothing else. */
    interface HeartRateCommands {
        suspend fun startHeartRate(
            address: String,
            serviceId: Int,
            intervalMicros: Int,
        ): Boolean

        suspend fun stopHeartRate(
            address: String,
            serviceId: Int,
        ): Boolean
    }

    private val sessions = mutableMapOf<String, Session>()
    private val gattJobs = mutableMapOf<String, Job>()
    private val gattReadings = MutableSharedFlow<Pair<String, HeartRateReading>>(extraBufferCapacity = 32)

    /**
     * Health-store work, queued rather than awaited.
     *
     * Found on hardware: calling the sink inline from [stop] wedged the entire controller.
     * The state machine runs on **one** collector, so any suspend that does not return
     * takes heart rate with it — the stop frame had already gone out, but the state was
     * never published and no later input was ever processed, leaving the screen frozen on
     * `MEASURING` with the sensor off and no way to re-enable it short of restarting.
     *
     * A channel rather than a bare `launch` per call, because the batcher is stateful:
     * a trusted reading and the stop that flushes its window must reach it in the order
     * they happened, and a single consumer is what guarantees that. Capacity is bounded
     * and the oldest is dropped — a health store that has stopped answering must cost a
     * bounded amount of memory, and losing a queued reading is a missed sample rather
     * than a wrong one.
     *
     * This is what [HealthConnectLink]'s own rule — the health store is an *output* of
     * this feature, not a dependency of it — actually requires in the caller.
     */
    private val sinkTasks =
        Channel<SinkTask>(capacity = SINK_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private sealed interface SinkTask {
        data class Trusted(
            val pod: PodState,
            val reading: HeartRateReading,
        ) : SinkTask

        data class Stopped(
            val address: String,
        ) : SinkTask
    }

    private var latestSettings: GreenPodsSettings = GreenPodsSettings.Default
    private var latestPods: List<PodState> = emptyList()

    /** Collects until cancelled. */
    suspend fun run(): Unit =
        coroutineScope {
            // Drains on its own coroutine so a slow or wedged health store cannot stall
            // the state machine. Failures are swallowed here for the same reason they are
            // swallowed inside the link: a provider that is updating, or a write that
            // races a revocation, must not take the reading off the screen.
            launch {
                for (task in sinkTasks) {
                    runCatching {
                        when (task) {
                            is SinkTask.Trusted -> sink?.onTrusted(task.pod, task.reading)
                            is SinkTask.Stopped -> sink?.onSensingStopped(task.address)
                        }
                    }
                }
            }

            merge(
                combine(pods, settings) { pods, settings -> Input.Snapshot(pods, settings) },
                aapEvents.map(Input::Event),
                gattReadings.map { (address, reading) -> Input.Gatt(address, reading) },
                ticks.map { Input.Tick },
            ).collect { input ->
                when (input) {
                    is Input.Snapshot -> {
                        latestPods = input.pods
                        latestSettings = input.settings
                        input.pods.forEach { pod -> reconcile(pod, this) }
                    }

                    is Input.Event -> {
                        onAapEvent(input.addressed, this)
                    }

                    is Input.Gatt -> {
                        onReading(input.address, input.reading)
                    }

                    Input.Tick -> {
                        latestPods.forEach { pod -> checkForStall(pod) }
                    }
                }
            }
        }

    /**
     * Brings one accessory's session in line with what the user asked for.
     *
     * Wear gating is **`anyInEar`**, not `bothInEar`: one bud in is a case to measure in,
     * not a case to refuse. If the worn bud is not the one carrying the sensor, no reports
     * arrive and the ordinary no-convergence path says so — which is a different sentence
     * from "you are not wearing them", and the right one.
     */
    private suspend fun reconcile(
        pod: PodState,
        scope: CoroutineScope,
    ) {
        val session = sessions.getOrPut(pod.address) { Session() }
        val route = pod.heartRateFeature
        val reachable = route != null && route in pod.usableFeatures
        val wanted = latestSettings.heartRateEnabled && reachable && pod.earDetection.anyInEar

        session.enabled = latestSettings.heartRateEnabled

        if (!wanted) {
            if (session.running || session.state !is HeartRateState.Off) {
                stop(pod, session, reasonFor(pod, reachable))
            }
            publish(pod)
            return
        }

        if (session.running) {
            publish(pod)
            return
        }

        when (route) {
            PodFeature.HEART_RATE_GATT -> startGatt(pod, session, scope)
            else -> startAap(pod, session)
        }
        publish(pod)
    }

    private suspend fun startAap(
        pod: PodState,
        session: Session,
    ) {
        session.source = HeartRateReading.Source.AAP
        session.lastStopReason = null
        if (session.state !is HeartRateState.Starting && session.state !is HeartRateState.Settling) {
            session.state = HeartRateState.Starting(clock())
            session.startedAtMillis = clock()
        }

        // No service id yet means the accessory has not described itself on this channel.
        // Waiting is the correct behaviour and not a failure: descriptors arrive
        // unprompted, and there is no constant to fall back on (FR-002).
        val serviceId = session.serviceId ?: return

        val intervalMicros = HidTransport.intervalMicros(latestSettings.heartRateIntervalMillis)
        session.requestedIntervalMicros = intervalMicros
        // running is set from the write being *sent*, not from the sensor being on. The
        // sensor is on when reports arrive, and only Measuring says that.
        session.running = commands.startHeartRate(pod.address, serviceId, intervalMicros)
    }

    private fun startGatt(
        pod: PodState,
        session: Session,
        scope: CoroutineScope,
    ) {
        val source = gatt ?: return
        session.source = HeartRateReading.Source.GATT
        session.lastStopReason = null
        session.state = HeartRateState.Starting(clock())
        session.startedAtMillis = clock()
        session.running = true

        // A separate job per accessory, emitting into the same merged stream, so the
        // state below is still mutated by exactly one collector.
        gattJobs[pod.address]?.cancel()
        gattJobs[pod.address] =
            scope.launch {
                source
                    .readings(pod.address)
                    .catch { }
                    .collect { reading -> gattReadings.emit(pod.address to reading) }
            }
    }

    private suspend fun stop(
        pod: PodState,
        session: Session,
        reason: StopReason,
    ) {
        val serviceId = session.serviceId
        if (session.running && session.source == HeartRateReading.Source.AAP && serviceId != null) {
            commands.stopHeartRate(pod.address, serviceId)
        }
        gattJobs.remove(pod.address)?.cancel()

        session.running = false
        session.requestedIntervalMicros = HidTransport.INTERVAL_STOPPED_MICROS
        session.trustedThisSession = false
        session.lastStopReason = reason.token
        session.state =
            if (reason == StopReason.DISABLED) HeartRateState.Off else HeartRateState.Unavailable(reason.sentence)

        // Queued, never awaited — see [sinkTasks]. The partial window still gets flushed;
        // it just cannot hold the state machine hostage while it happens.
        sinkTasks.trySend(SinkTask.Stopped(pod.address))
    }

    private suspend fun onAapEvent(
        addressed: AddressedAapEvent,
        scope: CoroutineScope,
    ) {
        val pod = latestPods.firstOrNull { it.address == addressed.address }
        val session = sessions.getOrPut(addressed.address) { Session() }

        when (val event = addressed.event) {
            is AapEvent.HidServices -> {
                session.serviceId = event.services.firstOrNull { it.isHeartRate }?.id
                // Discovery is also the moment a start becomes possible: the session may
                // have been asked for before the accessory had said anything about itself.
                if (pod != null) reconcile(pod, scope)
            }

            is AapEvent.HeartRateReport -> {
                session.serviceId = event.serviceId
                onReading(addressed.address, event.reading)
            }

            is AapEvent.UnhandledHidReport -> {
                session.reportsReceived++
                if (event.reason == IMPLAUSIBLE_REASON) session.discardedImplausible++
                pod?.let(::publish)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * Folds one arriving reading through the policy.
     *
     * This is the only path into [HeartRateState.Measuring], which is the point.
     */
    private suspend fun onReading(
        address: String,
        reading: HeartRateReading,
    ) {
        val session = sessions.getOrPut(address) { Session() }
        val pod = latestPods.firstOrNull { it.address == address }
        val policy = HeartRateConfidencePolicy.from(latestSettings)

        session.reportsReceived++
        session.running = true
        session.source = reading.source

        when (policy.verdict(reading, session.trustedThisSession)) {
            HeartRateVerdict.TRUSTED -> {
                session.trustedThisSession = true
                session.trustedCount++
                session.lastTrustedAtMillis = reading.measuredAtEpochMillis
                session.state = HeartRateState.Measuring(reading)
                // Queued for the same reason the stop flush is: this runs once a second
                // on the only collector the feature has.
                if (pod != null) sinkTasks.trySend(SinkTask.Trusted(pod, reading))
            }

            HeartRateVerdict.SETTLING -> {
                if (session.state !is HeartRateState.Settling) {
                    session.state = HeartRateState.Settling(session.startedAtMillis.takeIf { it > 0 } ?: clock())
                }
            }

            HeartRateVerdict.UNCERTAIN -> {
                session.state = HeartRateState.Uncertain(session.lastTrustedAtMillis)
            }

            // Unreachable from a constructed reading — the type forbids it — but the
            // branch exists so adding a route that skips construction cannot slip past.
            HeartRateVerdict.IMPLAUSIBLE -> {
                session.discardedImplausible++
            }
        }

        pod?.let(::publish)
    }

    /**
     * Gives up on a sensor that never converges — or was never found.
     *
     * Two different waits end here and they are told apart by whether a service id was
     * ever discovered. A loose fit or a cold ear keeps confidence low no matter how long
     * anyone waits; an accessory that never described its sensor is not measuring badly,
     * it is not measuring at all, and telling that user to check the fit sends them to
     * fix something that is not broken. Waiting silently forever is the failure mode the
     * spec names, so either way the session says what happened and stops drawing the
     * buds' battery to keep not getting a reading.
     */
    private suspend fun checkForStall(pod: PodState) {
        val session = sessions[pod.address] ?: return
        if (!session.running && session.state !is HeartRateState.Starting) return
        val settling = session.state is HeartRateState.Starting || session.state is HeartRateState.Settling
        if (!settling) return
        if (clock() - session.startedAtMillis < settleTimeoutMillis) return

        val reason =
            if (session.source == HeartRateReading.Source.AAP && session.serviceId == null) {
                StopReason.NOT_DISCOVERED
            } else {
                StopReason.NO_CONVERGENCE
            }
        stop(pod, session, reason)
        publish(pod)
    }

    private fun reasonFor(
        pod: PodState,
        reachable: Boolean,
    ): StopReason =
        when {
            !latestSettings.heartRateEnabled -> StopReason.DISABLED
            !reachable -> StopReason.CHANNEL_GONE
            !pod.earDetection.anyInEar -> StopReason.NOT_WORN
            else -> StopReason.DISABLED
        }

    private fun publish(pod: PodState) {
        val session = sessions[pod.address] ?: return
        publish(pod.address, session.state, session.sensing())
    }

    private class Session {
        var serviceId: Int? = null
        var running: Boolean = false
        var enabled: Boolean = false
        var source: HeartRateReading.Source? = null
        var requestedIntervalMicros: Int? = null
        var state: HeartRateState = HeartRateState.Off
        var startedAtMillis: Long = 0L
        var lastTrustedAtMillis: Long? = null

        /** Whether this session has *ever* converged — the input to the policy's hysteresis. */
        var trustedThisSession: Boolean = false
        var reportsReceived: Int = 0
        var trustedCount: Int = 0
        var discardedImplausible: Int = 0
        var lastStopReason: String? = null

        fun sensing(): HeartRateSensing =
            HeartRateSensing(
                enabled = enabled,
                requestedIntervalMicros = requestedIntervalMicros,
                serviceId = serviceId,
                source = source,
                reportsReceived = reportsReceived,
                trustedCount = trustedCount,
                discardedImplausible = discardedImplausible,
                lastStopReason = lastStopReason,
            )
    }

    /**
     * Why sensing is not running.
     *
     * The token is what `hr status` prints and what a script greps for; the sentence is
     * what the user reads. Keeping them together is what stops the two drifting apart.
     */
    private enum class StopReason(
        val token: String,
        val sentence: String,
    ) {
        DISABLED("disabled", "Heart rate is off."),
        NOT_WORN("notWorn", "Put an earbud in to measure."),
        CHANNEL_GONE("channelGone", "The connection to the earbuds went away."),
        NO_CONVERGENCE(
            "noConvergence",
            "The earbuds could not get a reliable reading. Check the fit and try again.",
        ),

        /**
         * The accessory never said which service carries heart rate.
         *
         * A different failure from [NO_CONVERGENCE] and it must not borrow its sentence:
         * nothing was measured badly, nothing was measured at all. The accessory announces
         * its sensor services once when the Bluetooth link comes up and offers no way to
         * ask again, so a channel opened after that point can never learn the id — and
         * reconnecting really is the fix, which is why the sentence says so rather than
         * blaming the fit.
         */
        NOT_DISCOVERED(
            "notDiscovered",
            "These earbuds have not described their heart-rate sensor on this connection. " +
                "Put them back in the case and take them out again.",
        ),
    }

    private sealed interface Input {
        data class Snapshot(
            val pods: List<PodState>,
            val settings: GreenPodsSettings,
        ) : Input

        data class Event(
            val addressed: AddressedAapEvent,
        ) : Input

        data class Gatt(
            val address: String,
            val reading: HeartRateReading,
        ) : Input

        data object Tick : Input
    }

    companion object {
        /**
         * SC-001 gives the user thirty seconds to see a trustworthy reading. A sensor
         * still settling at that point is one that is not going to settle, and saying so
         * beats a spinner that never resolves.
         */
        const val SETTLE_TIMEOUT_MILLIS = 30_000L

        private const val TICK_MILLIS = 1_000L

        /**
         * Two minutes of readings at the fastest cadence.
         *
         * Enough that an ordinary provider hiccup loses nothing, small enough that a
         * provider which has stopped answering entirely cannot grow without bound.
         */
        private const val SINK_QUEUE_CAPACITY = 128

        /** [io.github.andrewkomkov.greenpods.core.bluetooth.aap.HeartRateDecodeResult.Unhandled.Reason]. */
        private const val IMPLAUSIBLE_REASON = "IMPLAUSIBLE"
    }
}
