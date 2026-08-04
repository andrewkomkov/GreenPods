package io.github.andrewkomkov.greenpods.core.data.head

import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapEvent
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidTransport
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeadGestureDetector
import io.github.andrewkomkov.greenpods.core.bluetooth.head.HeadPoseMapper
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.PodControlGateway
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

            val serviceId =
                serviceMemory
                    .remembered(pod.address)
                    .firstOrNull { it.isHeadTracking }
                    ?.id
                    ?: throw NotStreaming(Refusal.UNAVAILABLE)

            val started =
                gateway.startHeadTracking(
                    address = pod.address,
                    serviceId = serviceId,
                    intervalMicros = HidTransport.intervalMicros(intervalMillis),
                )
            if (!started) throw NotStreaming(Refusal.UNAVAILABLE)

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
    }
}
