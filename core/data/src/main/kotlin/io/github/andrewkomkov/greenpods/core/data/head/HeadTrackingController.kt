package io.github.andrewkomkov.greenpods.core.data.head

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidTransport
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeadGestureDetector
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeadPoseMapper
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.PodControlGateway
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.HidServiceMemory
import io.github.andrewkomkov.greenpods.core.model.HeadGestureEvent
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A live head-orientation session, for as long as someone is watching it.
 *
 * Head tracking is the only feature in this app that is *only* worth having while it is
 * on screen: it costs the earbuds' battery continuously and drives nothing when nobody is
 * looking. So this is deliberately not a long-lived controller like the heart-rate one —
 * it is a cold flow that starts the sensor when collection starts and stops it, in the
 * buds, when collection ends. Leaving the screen is the stop command.
 *
 * What it publishes is a pose and, separately, the gestures a detector recognised in that
 * pose stream. Both, because the screen it feeds has to answer two different questions:
 * where is your head right now, and did that count.
 */
class HeadTrackingController(
    private val repository: PodRepository,
    private val gateway: PodControlGateway,
    private val serviceMemory: HidServiceMemory,
    private val diagnostics: DiagnosticsLog = DiagnosticsLog(),
    /**
     * Outlives the screen on purpose.
     *
     * The stop command is sent while the collector's own scope is being cancelled, so it
     * cannot run there. A sensor left running in someone's earbuds because they pressed
     * Back is the exact failure this whole session model exists to prevent.
     */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One update: where the head is, and whether that sample completed a gesture. */
    data class Sample(
        val pose: HeadPose,
        val gesture: HeadGestureEvent?,
    )

    /** Why no stream could be started. Each is a different sentence on screen. */
    enum class Refusal {
        /** Nothing in range to ask. */
        NO_ACCESSORY,

        /** The model has no head tracking at all. */
        UNSUPPORTED,

        /** This phone cannot carry the commands, or the accessory never described itself. */
        UNAVAILABLE,
    }

    class NotStreaming(
        val refusal: Refusal,
    ) : Exception(refusal.name)

    /**
     * Poses for as long as this flow is collected.
     *
     * Throws [NotStreaming] rather than completing empty, because "nothing is arriving"
     * and "this cannot work here" look identical to a collector and need different
     * screens. The stop is in a `finally` under [NonCancellable]: the common way to leave
     * this screen is to navigate away, which cancels the scope, and a stop command that
     * gets cancelled leaves the sensor running in the earbuds.
     */
    fun stream(intervalMillis: Int = DEFAULT_INTERVAL_MILLIS): Flow<Sample> =
        callbackFlow {
            val pod = repository.primaryPod.first() ?: throw NotStreaming(Refusal.NO_ACCESSORY)
            if (PodFeature.HEAD_TRACKING !in pod.model.features) throw NotStreaming(Refusal.UNSUPPORTED)
            if (PodFeature.HEAD_TRACKING !in pod.usableFeatures) throw NotStreaming(Refusal.UNAVAILABLE)

            // Recorded rather than merely refused. "Head tracking is unavailable" has
            // three quite different causes here — the accessory never described itself,
            // it described no motion service, or it described one without an interval
            // report to write — and on screen they are one sentence. The log is where
            // they stay distinguishable.
            val known = serviceMemory.remembered(pod.address)
            val serviceId =
                known.firstOrNull { it.isHeadTracking }?.id
                    ?: run {
                        diagnostics.record(
                            DiagnosticCategory.TRANSPORT,
                            "Head tracking: no motion service for ${pod.address}",
                            if (known.isEmpty()) {
                                "The accessory has not described its services on this link."
                            } else {
                                "Described: " + known.joinToString { "0x%02X %s".format(it.id, it.name ?: "unnamed") }
                            },
                        )
                        throw NotStreaming(Refusal.UNAVAILABLE)
                    }

            // Ask the accessory to describe itself before starting.
            //
            // Remembering a service is not remembering how to start it. The stored copy
            // carries a service's identity — its id, its name, that it is the motion one
            // — but not its report descriptor, and the interval is written into a feature
            // report that only the descriptor names. So a restored service can be found
            // and not started, which is exactly what happened here: the stream ran right
            // after a live announcement and refused on the next link.
            //
            // Asking is idempotent and carries no setting, and the answer arrives within
            // a second on a live channel. The heart-rate session does the same thing for
            // the same reason.
            gateway.describeServices(pod.address)
            withTimeoutOrNull(ANNOUNCEMENT_WAIT_MILLIS) {
                repository.aapEvents
                    .filter { it.address == pod.address }
                    .first { it.event is AapEvent.HidServices }
            }

            val started =
                gateway.startHeadTracking(
                    address = pod.address,
                    serviceId = serviceId,
                    intervalMicros = HidTransport.intervalMicros(intervalMillis),
                )
            if (!started) {
                diagnostics.record(
                    DiagnosticCategory.TRANSPORT,
                    "Head tracking: service 0x%02X would not start".format(serviceId),
                    "The write was refused or the service describes no report-interval " +
                        "feature report. Nothing is guessed in its place.",
                )
                throw NotStreaming(Refusal.UNAVAILABLE)
            }

            // A fresh detector per session: its history is a few hundred milliseconds of
            // movement, and carrying yesterday's across a screen entry would let a
            // gesture fire from motion the user never made in this sitting.
            val detector = HeadGestureDetector()

            val reader =
                launch {
                    repository.aapEvents
                        .filter { it.address == pod.address }
                        .mapNotNull { (it.event as? AapEvent.HeadTracking)?.sample }
                        .collect { sample ->
                            val pose = HeadPoseMapper.toPose(sample)
                            trySend(Sample(pose, detector.onPose(pose, clock())))
                        }
                }

            awaitClose {
                reader.cancel()
                // Not in this scope: it is being cancelled. The stop has to outlive it.
                scope.launch {
                    withContext(NonCancellable) { gateway.stopHeadTracking(pod.address, serviceId) }
                }
            }
        }

    private companion object {
        /**
         * 20 Hz.
         *
         * Fast enough that a nod — a movement of roughly half a second — is described by
         * ten samples rather than by three, which is the difference between a detector
         * seeing an arc and seeing two points. Faster costs the earbuds more for motion no
         * one can perform.
         */
        const val DEFAULT_INTERVAL_MILLIS = 50

        /**
         * How long to wait for the accessory to describe itself.
         *
         * Long enough for an answer on a live channel, short enough that a channel which
         * will not answer — the announcement is once per Bluetooth connection, and this
         * one may already have been missed — falls through to the refusal instead of
         * leaving the screen spinning.
         */
        const val ANNOUNCEMENT_WAIT_MILLIS = 2_500L
    }
}
