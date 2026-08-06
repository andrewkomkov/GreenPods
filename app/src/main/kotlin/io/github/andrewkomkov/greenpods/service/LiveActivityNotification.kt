package io.github.andrewkomkov.greenpods.service

import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivitySummary
import io.github.andrewkomkov.greenpods.core.data.live.Presence
import io.github.andrewkomkov.greenpods.core.data.live.SensingState
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
        return parts.joinToString(SEPARATOR).ifEmpty { context.getString(R.string.live_no_reading) }
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
     * notification, and nothing reports it.
     */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        summary: LiveActivitySummary,
        promote: Boolean,
    ): NotificationCompat.Builder {
        val body = text(context, summary)
        return builder
            .setContentTitle(title(summary))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setRequestPromotedOngoing(promote)
    }

    private const val SEPARATOR = " · "
    private const val UNKNOWN = "—"
    private const val CHARGING = "⚡"
}
