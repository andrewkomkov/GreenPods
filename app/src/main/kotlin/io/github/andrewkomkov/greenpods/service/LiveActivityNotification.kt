package io.github.andrewkomkov.greenpods.service

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.data.live.ControlState
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivitySummary
import io.github.andrewkomkov.greenpods.core.data.live.Presence
import io.github.andrewkomkov.greenpods.core.data.live.SensingState
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodComponent

/**
 * Turns a [LiveActivitySummary] into notification text.
 *
 * Makes no decisions. Every choice about *what* to say — whether the accessory is in range,
 * whether a control is locked, whether the heart rate may be shown — was already made by
 * `LiveActivityPolicy`, where it is unit-tested. This only renders, so there is nothing here
 * that needs a device to exercise.
 *
 * Text rather than a custom layout, and not for want of ambition: a notification carrying
 * any `RemoteViews` is **disqualified** from being promoted to a Live Update, as is a
 * colorized one. The constraint comes from the platform, not from taste.
 */
object LiveActivityNotification {
    /** The headline: whose earbuds these are. Required for promotion. */
    fun title(summary: LiveActivitySummary): String = summary.accessoryName

    /**
     * The one line worth reading at a glance.
     *
     * Ordered by what a user most needs to know. Sensing comes first because it is a
     * disclosure — something is measuring their body and draining the accessory — and out of
     * range comes next because every number below it would otherwise be a stale claim.
     */
    fun text(
        context: Context,
        summary: LiveActivitySummary,
    ): String {
        val parts = mutableListOf<String>()

        when (val sensing = summary.sensing) {
            is SensingState.DisclosedWithRate -> {
                parts += context.getString(R.string.live_sensing_with_rate, sensing.beatsPerMinute)
            }

            SensingState.Disclosed -> {
                parts += context.getString(R.string.live_sensing)
            }

            SensingState.Idle -> {
                Unit
            }
        }

        if (summary.presence == Presence.OUT_OF_RANGE) {
            parts += context.getString(R.string.live_out_of_range)
            return parts.joinToString(SEPARATOR)
        }

        battery(context, summary)?.let { parts += it }
        noiseControl(context, summary)?.let { parts += it }
        return parts.joinToString(SEPARATOR).ifEmpty { context.getString(R.string.live_no_reading) }
    }

    /**
     * The listening mode **the accessory reports**, or the reason it cannot be reached.
     *
     * Never the mode a button asked for. A write over this transport is accepted and then
     * silently not applied often enough that "accepted" and "applied" have to stay
     * different words, so the only mode that ever reaches this line is one the accessory
     * echoed back (Principle I, FR-013).
     *
     * Nothing is said when the accessory has not reported a mode yet — an empty answer is
     * not a mode, and guessing one here is what a glanceable surface would be believed for.
     * The button is still offered, because whether a control exists is the gate's answer
     * and not a question about what has been heard so far.
     *
     * Skipped entirely while out of range: [text] returns before this is reached, so a mode
     * from before the accessory went away is never presented as current.
     */
    private fun noiseControl(
        context: Context,
        summary: LiveActivitySummary,
    ): String? =
        when (val control = summary.noiseControl) {
            is ControlState.Offered -> {
                control.mode?.let { context.getString(R.string.live_noise_control, context.getString(it.labelRes())) }
            }

            // Locked, with the gate's own words, rather than absent (FR-012, Principle II).
            is ControlState.Locked -> {
                context.getString(R.string.live_noise_control_locked, control.reason)
            }
        }

    /**
     * Battery, with unknown rendered as a dash.
     *
     * A missing level is not a flat one. The accessory reports an explicit unknown sentinel
     * and a case that has not been opened has said nothing at all; showing either as `0%`
     * would tell the user their earbuds are dead.
     */
    private fun battery(
        context: Context,
        summary: LiveActivitySummary,
    ): String? {
        if (summary.hasNoBatteryReading) return null

        fun level(
            percent: Int?,
            component: PodComponent,
        ): String {
            val value = percent?.let { "$it%" } ?: UNKNOWN
            val charging = if (component in summary.charging) CHARGING else ""
            return "${component.displayName} $value$charging"
        }

        return listOf(
            level(summary.leftPercent, PodComponent.LEFT),
            level(summary.rightPercent, PodComponent.RIGHT),
            level(summary.casePercent, PodComponent.CASE),
        ).joinToString(SEPARATOR)
    }

    /**
     * Applies everything promotion requires, and nothing that would disqualify it.
     *
     * The negative half matters as much as the positive: no `RemoteViews`, not colorized,
     * not a group summary. Each of those silently turns a Live Update back into an ordinary
     * notification, and nothing reports it. Actions are not on that list — they are the one
     * kind of interactivity a promoted notification keeps.
     */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        summary: LiveActivitySummary,
        promote: Boolean,
    ): NotificationCompat.Builder {
        val body = text(context, summary)
        builder
            .setContentTitle(title(summary))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setRequestPromotedOngoing(promote)
            // Without this the policy's dismissal rule is unreachable: nothing else tells
            // the service the surface was swiped, so `LiveActivityPolicy.onDismissed` is
            // never called and the next state tick — which arrives within a second —
            // reposts what the user just swiped away.
            .setDeleteIntent(LiveActivityActions.dismissed(context))
        actions(context, summary).forEach(builder::addAction)
        return builder
    }

    /**
     * The buttons, chosen by the gate rather than by the accessory's model.
     *
     * A gated noise control still gets a button — one that opens the app at the explanation
     * instead of writing anything. That is the whole of "locked, not hidden": a control that
     * vanished on phones where the channel does not open would read as a bug in GreenPods,
     * and the user could not tell that apart from a phone that cannot do it.
     */
    private fun actions(
        context: Context,
        summary: LiveActivitySummary,
    ): List<NotificationCompat.Action> =
        buildList {
            when (val control = summary.noiseControl) {
                is ControlState.Offered -> {
                    // Labelled with the mode it asks for, which is not a claim about the
                    // mode the accessory is in — that is the line in the body, and it moves
                    // only on an echo.
                    val next = LiveActivityActions.nextMode(control.mode)
                    add(
                        action(
                            android.R.drawable.stat_sys_headset,
                            context.getString(R.string.live_action_switch_to, context.getString(next.labelRes())),
                            LiveActivityActions.cycleNoiseControl(context),
                        ),
                    )
                }

                is ControlState.Locked -> {
                    add(
                        action(
                            android.R.drawable.ic_lock_lock,
                            context.getString(R.string.live_action_noise_control_locked),
                            LiveActivityActions.openExplanation(context),
                        ),
                    )
                }
            }

            // Offered exactly when the disclosure is showing. A disclosure the user cannot
            // act on is a notice; FR-016 asks for a control, and one that reaches the
            // sensor in the accessory rather than the reading on the screen.
            if (summary.sensing != SensingState.Idle) {
                add(
                    action(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.live_action_stop_sensing),
                        LiveActivityActions.stopSensing(context),
                    ),
                )
            }
        }

    private fun action(
        icon: Int,
        label: String,
        intent: PendingIntent,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(icon, label, intent).build()

    /**
     * Everything the surface currently shows, as one comparable string.
     *
     * The service reposts only when this changes, because the pod flow ticks far faster
     * than a glanceable surface should move. The **buttons are part of it**: a label is
     * derived from state the body does not always render — out of range suppresses the
     * listening-mode line while the button still names the mode it would ask for — so a key
     * built from the text alone would leave a button saying something the surface no longer
     * means. Rendered here rather than in the service so the two cannot drift apart.
     */
    fun renderKey(
        context: Context,
        summary: LiveActivitySummary,
    ): String =
        (listOf(title(summary), text(context, summary)) + actions(context, summary).map { it.title })
            .joinToString("|")

    /** Human labels for the modes. Enum names must never reach a lock screen. */
    private fun NoiseControlMode.labelRes(): Int =
        when (this) {
            NoiseControlMode.OFF -> R.string.live_mode_off
            NoiseControlMode.NOISE_CANCELLATION -> R.string.live_mode_anc
            NoiseControlMode.TRANSPARENCY -> R.string.live_mode_transparency
            NoiseControlMode.ADAPTIVE -> R.string.live_mode_adaptive
        }

    private const val SEPARATOR = " · "
    private const val UNKNOWN = "—"
    private const val CHARGING = "⚡"
}
