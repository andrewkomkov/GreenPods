package io.github.andrewkomkov.greenpods.core.data.ear

import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
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
    /**
     * Records every decision, including the ones that decide to do nothing.
     *
     * Auto-pause fails silently by nature — the user notices that music did *not* stop,
     * and there is nothing to inspect. Writing the inputs down is what turns "it didn't
     * work" into a diagnosable report.
     */
    private val diagnostics: DiagnosticsLog = DiagnosticsLog(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastWear = mutableMapOf<String, EarDetectionState>()
    private var pauseSource = PlaybackSource.UNKNOWN
    private var pausedAtMillis = 0L
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

        releaseOwnershipIfUserIsPlaying()

        val context =
            PlaybackContext(
                isPlaying = actuator.isPlaying(),
                pauseSource = pauseSource,
                isActiveAudioOutput = actuator.isBluetoothOutputActive(),
            )

        val decision = AutoPausePolicy.evaluate(previous, pod.earDetection, context, settings)
        if (previous != pod.earDetection) {
            diagnostics.record(
                category = DiagnosticCategory.MEDIA,
                message =
                    "Wear ${previous.left}/${previous.right} -> " +
                        "${pod.earDetection.left}/${pod.earDetection.right}: ${decision ?: "no action"}",
                detail =
                    "playing=${context.isPlaying}, pausedBy=${context.pauseSource}, " +
                        "bluetoothOutput=${context.isActiveAudioOutput}, autoPause=${settings.autoPauseEnabled}, " +
                        "autoResume=${settings.autoResumeEnabled}, bothOutOnly=${settings.pauseOnlyWhenBothOut}",
            )
        }

        when (decision) {
            MediaAction.PAUSE -> {
                actuator.perform(MediaAction.PAUSE)
                pauseSource = PlaybackSource.GREENPODS
                pausedAtMillis = now()
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

    /**
     * Gives up ownership of the pause once playback is genuinely running again.
     *
     * The subtlety is timing. `AudioManager.isMusicActive` keeps reporting true for a
     * moment after a pause, and advertisements arrive every couple of seconds — so a
     * naive "anything playing means nothing is paused" check hands our own pause away
     * to nobody within milliseconds of making it, and auto-resume then refuses to fire
     * because it correctly declines to resume what it does not own.
     *
     * Ignoring the audio state briefly after our own pause is what makes the two rules
     * coexist: we still never resume a pause the user made, and we no longer forget the
     * ones we made ourselves.
     */
    private fun releaseOwnershipIfUserIsPlaying() {
        if (!actuator.isPlaying()) return
        val settling = pauseSource == PlaybackSource.GREENPODS && now() - pausedAtMillis < PAUSE_SETTLE_MILLIS
        if (!settling) pauseSource = PlaybackSource.UNKNOWN
    }

    /** Forgets an accessory, so reappearing does not look like a wear transition. */
    fun forget(address: String) {
        lastWear.remove(address)
    }

    private companion object {
        /**
         * How long the audio state is allowed to lag our own pause before it is believed.
         * Measured on a Pixel 8: `isMusicActive` can still be true a second later.
         */
        const val PAUSE_SETTLE_MILLIS = 3_000L
    }
}
