package io.github.andrewkomkov.greenpods.core.data.ear

import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.MediaAction
import io.github.andrewkomkov.greenpods.core.model.PlaybackSource
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The part of auto-pause that touches the media stack.
 *
 * An interface so the controller's bookkeeping — which is where the bugs live — can be
 * tested without an `AudioManager`.
 */
interface PlaybackActuator {
    fun isPlaying(): Boolean

    /** Whether the phone is currently routing audio to a Bluetooth output. */
    fun isBluetoothOutputActive(): Boolean

    fun perform(action: MediaAction)
}

/**
 * Watches wear state and drives playback through [AutoPausePolicy].
 *
 * The controller holds exactly two pieces of memory — the previous wear state per
 * accessory, and whether the current pause was ours — and no rules. Everything that
 * could be argued about lives in the policy, where a test can enumerate it.
 */
class EarDetectionController(
    private val pods: Flow<List<PodState>>,
    private val settings: Flow<GreenPodsSettings>,
    private val actuator: PlaybackActuator,
) {
    private val lastWear = mutableMapOf<String, EarDetectionState>()
    private var pauseSource = PlaybackSource.UNKNOWN
    private val running =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Collects until cancelled.
     *
     * Both the foreground Activity and the monitoring service want auto-pause running,
     * and either may be alive without the other. The guard makes a second caller a
     * no-op rather than a second collector — two collectors would each see the same
     * wear transition and press pause twice.
     */
    suspend fun run() {
        if (!running.compareAndSet(false, true)) return
        try {
            combine(pods, settings) { pods, settings -> pods to settings }
                .collect { (pods, settings) -> pods.forEach { pod -> onPod(pod, settings) } }
        } finally {
            running.set(false)
        }
    }

    /** Exposed for tests and for driving a single update from the service. */
    fun onPod(
        pod: PodState,
        settings: GreenPodsSettings,
    ) {
        val previous = lastWear.put(pod.address, pod.earDetection) ?: return

        // Anything playing means nothing is paused, so a pause we own cannot be pending.
        if (actuator.isPlaying()) pauseSource = PlaybackSource.UNKNOWN

        val context =
            PlaybackContext(
                isPlaying = actuator.isPlaying(),
                pauseSource = pauseSource,
                isActiveAudioOutput = actuator.isBluetoothOutputActive(),
            )

        when (AutoPausePolicy.evaluate(previous, pod.earDetection, context, settings)) {
            MediaAction.PAUSE -> {
                actuator.perform(MediaAction.PAUSE)
                pauseSource = PlaybackSource.GREENPODS
            }

            MediaAction.RESUME -> {
                actuator.perform(MediaAction.RESUME)
                pauseSource = PlaybackSource.UNKNOWN
            }

            null -> {
                Unit
            }
        }
    }

    /** Forgets an accessory, so reappearing does not look like a wear transition. */
    fun forget(address: String) {
        lastWear.remove(address)
    }
}
