package io.github.andrewkomkov.greenpods.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.MainActivity
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The intents behind the live surface's buttons.
 *
 * Actions are the one piece of interactivity a promoted ongoing notification is allowed —
 * `RemoteViews`, a colorized notification and a group summary each disqualify promotion
 * silently, and none of them is needed here.
 *
 * Nothing in this file decides *whether* a button is shown. That is the gate's business and
 * `LiveActivityPolicy` already answered it, as `ControlState.Offered` or `ControlState.Locked`.
 */
object LiveActivityActions {
    /** Ask the accessory for the next listening mode. */
    const val ACTION_CYCLE_NOISE_CONTROL = "io.github.andrewkomkov.greenpods.action.CYCLE_NOISE_CONTROL"

    /** Stop the heart-rate sensor — in the earbuds, not merely on screen. */
    const val ACTION_STOP_SENSING = "io.github.andrewkomkov.greenpods.action.STOP_SENSING"

    /** The user swiped the surface away. Delivered to the service, not to the receiver. */
    const val ACTION_SURFACE_DISMISSED = "io.github.andrewkomkov.greenpods.action.SURFACE_DISMISSED"

    /**
     * The mode a press asks the accessory for, given the mode the accessory reports.
     *
     * Pure, and called twice on purpose: once to label the button, and again inside the
     * receiver against whatever the accessory reports at the moment of the press. The label
     * names the mode it will *ask* for, never a mode anything has confirmed — pressing it
     * changes nothing until the echo arrives (Principle I).
     *
     * All four modes, matching the controls screen, which offers the same four to every
     * accessory that has noise control at all.
     */
    fun nextMode(current: NoiseControlMode?): NoiseControlMode {
        // Nothing reported yet is not the same as "off". Rather than treating an unknown as
        // the head of the cycle and asking for `OFF` — which would switch cancellation off
        // for a user who pressed a button to change something — the first press asks for
        // the mode the button most likely exists for. The label says which, so nothing is
        // implied about where the accessory is now.
        val cycle = NoiseControlMode.entries
        val index = cycle.indexOf(current)
        if (index < 0) return NoiseControlMode.NOISE_CANCELLATION
        return cycle[(index + 1) % cycle.size]
    }

    fun cycleNoiseControl(context: Context): PendingIntent =
        broadcast(
            context,
            ACTION_CYCLE_NOISE_CONTROL,
            CYCLE_REQUEST,
        )

    fun stopSensing(context: Context): PendingIntent = broadcast(context, ACTION_STOP_SENSING, STOP_SENSING_REQUEST)

    /**
     * Opens the app, which is where a locked control is explained.
     *
     * The status screen lists noise control among the accessory's capabilities and, when it
     * is gated, carries the sentence saying why this phone cannot reach it. A locked button
     * that did nothing would read as a broken one — which is precisely the confusion
     * "locked, not hidden" exists to prevent.
     */
    fun openExplanation(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            EXPLANATION_REQUEST,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Tells the service the surface was swiped away.
     *
     * Delivered to [PodMonitorService] rather than to the receiver below, because the
     * `LiveActivityPolicy` that has to be told is an instance the service owns — one
     * dismissal is about the surface that was showing, so the rule lives exactly as long as
     * the service that posted it. Routing this through a broadcast would mean hoisting that
     * policy into the DI container and giving a per-surface decision a process-wide
     * lifetime, which is a worse answer than a differently-shaped `PendingIntent`.
     *
     * `getService` is safe here specifically because the service is already running in the
     * foreground: this notification is its notification, so there is no notification to
     * dismiss unless there is a started service to deliver to.
     */
    fun dismissed(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            DISMISSED_REQUEST,
            Intent(context, PodMonitorService::class.java).setAction(ACTION_SURFACE_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcast(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, LiveActivityActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val CYCLE_REQUEST = 101
    private const val STOP_SENSING_REQUEST = 102
    private const val EXPLANATION_REQUEST = 103
    private const val DISMISSED_REQUEST = 104
}

/**
 * Carries out what a button on the live surface asks for.
 *
 * Both handlers go through machinery that already exists — the control gateway for the
 * listening mode, the heart-rate setting for sensing — rather than reaching the transport
 * themselves. That is not tidiness: the gateway is where the write is bounded, retried and
 * folded back into the repository as the accessory's own echo, and the heart-rate
 * controller is what turns the setting into an interval-zero stop frame. A second path to
 * either would be a second set of rules about when a write counts.
 *
 * **Nothing here touches the surface.** No optimistic update, no "switching…" text. The
 * notification moves when the accessory says so and at no other time (Principle I, FR-013).
 */
class LiveActivityActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = GreenPodsApplication.instance
        // The work is a suspending write over a Bluetooth channel, which outlives onReceive.
        // goAsync keeps the broadcast alive for it; the scope is the application's because
        // the write must not be cancelled by the receiver returning.
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                when (intent.action) {
                    LiveActivityActions.ACTION_CYCLE_NOISE_CONTROL -> cycleNoiseControl(app)
                    LiveActivityActions.ACTION_STOP_SENSING -> stopSensing(app)
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Asks for the next mode after the one the accessory currently reports.
     *
     * Read at the moment of the press rather than carried in the intent's extras: a
     * notification that has not been reposted since the accessory last changed mode would
     * otherwise send a command computed from a mode that is no longer current.
     *
     * The gate is re-checked here even though the button is only offered when it is open.
     * A pending intent outlives the state that built it, and the channel can close between
     * the notification being posted and the button being pressed.
     */
    private suspend fun cycleNoiseControl(app: GreenPodsApplication) {
        val pod =
            app.podRepository.pods
                .first()
                .firstOrNull() ?: return
        if (PodFeature.NOISE_CONTROL !in pod.usableFeatures) return
        app.controlGateway.setNoiseControlMode(pod.address, LiveActivityActions.nextMode(pod.noiseControlMode))
    }

    /**
     * Stops sensing in the accessory, by the one route that does (FR-016).
     *
     * Clearing the setting is what `hr off` does, and it is the whole stop: the heart-rate
     * controller reconciles against it and sends the interval-zero feature report that
     * switches the optical sensor off in the earbuds. Cancelling a collector here instead
     * would leave the sensor running and draining the accessory with nothing on screen
     * saying so — the exact failure the disclosure exists to prevent.
     */
    private suspend fun stopSensing(app: GreenPodsApplication) {
        app.settingsRepository.update { it.copy(heartRateEnabled = false) }
    }
}
