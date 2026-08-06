package io.github.andrewkomkov.greenpods.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.MainActivity
import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.data.battery.LowBatteryNotifier
import io.github.andrewkomkov.greenpods.core.data.battery.LowBatteryWarning
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityDecision
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityGate
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityPolicy
import io.github.andrewkomkov.greenpods.core.data.live.NoSurface
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.PodState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps scanning while the app is in the background so battery levels stay fresh and
 * ear-detection events are not missed.
 *
 * Declared as a `connectedDevice` foreground service: that is the type Android expects
 * for continuous Bluetooth work, and using the right one is what keeps the service
 * alive under Doze.
 *
 * Notifications are treated as optional throughout. On Android 13+ the user can refuse
 * them, and a battery monitor that crashes because it cannot draw a notification would
 * be a poor trade — the scanning, the auto-pause and the low-battery bookkeeping all
 * still work.
 */
class PodMonitorService : LifecycleService() {
    private val app get() = GreenPodsApplication.instance
    private val lowBattery = LowBatteryNotifier()

    /** Decides what the live surface says. Pure, and tested without a device. */
    private val livePolicy = LiveActivityPolicy()

    /** What the surface last said, so an unchanged summary does not repost it. */
    private var lastPosted: String? = null

    /**
     * Which accessory the surface last described.
     *
     * A dismissal is about *that* accessory, not about the feature, so the policy needs to
     * be told which one. Held here rather than carried in the delete intent's extras: the
     * notification is only rebuilt when its rendering changes, so an extra would go stale
     * exactly when two accessories render the same and the pending intent is not refreshed.
     */
    private var lastPostedAddress: String? = null

    /**
     * Held here rather than in the container because it owns a registered receiver, and
     * its lifetime is the service's: while GreenPods is doing continuous Bluetooth work
     * is exactly when the channel is worth holding open.
     */
    private val channelKeeper by lazy {
        AapChannelKeeper(
            context = applicationContext,
            gateway = app.controlGateway,
            identity = app.podIdentity,
            diagnostics = app.diagnostics,
            scope = lifecycleScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_NOTIFICATION_ID, buildOngoingNotification("Scanning…"))

        // Before anything else that might want the channel: the accessory's sensor
        // announcement happens once per connection and cannot be asked for again, so the
        // channel has to be open when the link comes up rather than when a write needs it.
        channelKeeper.start()

        lifecycleScope.launch { app.earDetectionController.run() }

        // Sensing lives with the channel. The heart-rate session runs here rather than in
        // the Activity because it must survive the screen going off — and because Android
        // requires a foreground service for continuous Bluetooth work anyway, so one
        // notification satisfies FR-013 and the platform at once (AD-8, R-8).
        lifecycleScope.launch { app.heartRateController.run() }

        lifecycleScope.launch {
            combine(app.podRepository.pods, app.settingsRepository.settings) { pods, settings ->
                pods to settings
            }.collect { (pods, settings) ->
                val nearest = pods.firstOrNull()
                publishSurface(nearest, settings)
                pods.forEach { pod ->
                    lowBattery.evaluate(pod, settings).forEach(::warn)
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        // Nullable, and re-delivered as null by START_STICKY, so the action is matched
        // rather than assumed.
        if (intent?.action == LiveActivityActions.ACTION_SURFACE_DISMISSED) onSurfaceDismissed()
        return START_STICKY
    }

    /**
     * The user swiped the live surface away.
     *
     * The platform is explicit that a dismissed live update must not be reposted, and the
     * pod flow ticks several times a second — so without this the swipe would be undone
     * before the user's finger left the screen. The policy owns *when* it may come back
     * (a different accessory, or sensing starting); this only tells it that it happened.
     *
     * What the user is left with is the plain ongoing notification, because a foreground
     * service must have one and nothing can take that away. That is the whole of the
     * difference the dismissal buys: the quiet notification this service has always posted,
     * instead of a promoted surface on the lock screen.
     */
    private fun onSurfaceDismissed() {
        livePolicy.onDismissed(lastPostedAddress)
        // The repost key described the surface that has just gone. Leaving it set would
        // suppress the *next* legitimate post — sensing starting, say — as a duplicate of
        // something no longer on screen.
        lastPosted = null
    }

    override fun onDestroy() {
        // The receiver outlives the coroutine scope unless it is unregistered by hand,
        // and a leaked one keeps opening channels for a service that is gone.
        channelKeeper.stop()
        // A decision from a service that has stopped describes a surface that no longer
        // exists. Better for a diagnostic to say "not running" than to answer confidently
        // about the past.
        lastDecision = null
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.low_battery_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The plain notification this service has always posted.
     *
     * Unchanged, and deliberately so: phones below the Live Update API get exactly what
     * they had before, and the fallback path must not drift away from what it replaced.
     */
    private fun buildOngoingNotification(text: String): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .build()

    /**
     * Everything both forms share. `NotificationCompat`, not the platform builder, so
     * `setRequestPromotedOngoing` is a no-op below API 36 rather than a version branch at
     * every call site.
     */
    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(contentIntent())
            .setOngoing(true)

    /**
     * Posts the live surface, or falls back to the plain notification.
     *
     * The decision of *what* to show is not made here — `LiveActivityPolicy` made it, and
     * this only asks which of the two forms to render. Keeping the branch this small is
     * what stops the fallback path from quietly diverging from the promoted one.
     */
    private fun publishSurface(
        nearest: PodState?,
        settings: GreenPodsSettings,
    ) {
        if (!canPostNotifications()) return

        val decision =
            livePolicy.decide(
                availability = app.liveActivityGate.availability(),
                settings = settings,
                pod = nearest,
                monitoring = true,
                nowEpochMillis = System.currentTimeMillis(),
            )
        lastDecision = decision

        when (decision) {
            is LiveActivityDecision.Post -> {
                // The pods flow ticks far faster than this surface should move. Reposting
                // identical content is what a rapid in-and-out of one bud would otherwise
                // turn into: a flickering notification. The key covers the buttons as well
                // as the text — see `LiveActivityNotification.renderKey`.
                val rendered = LiveActivityNotification.renderKey(this, decision.summary)
                if (rendered == lastPosted) return
                lastPosted = rendered
                lastPostedAddress = nearest?.address

                val notification =
                    LiveActivityNotification
                        .apply(baseBuilder(), this, decision.summary, promote = true)
                        .build()

                // Ask the device whether this notification actually qualifies, rather than
                // trusting that it does. The permitted set of styles is something two
                // Android documentation pages disagree about, so the platform is the
                // arbiter — and a refusal is surfaced as a gate reason rather than
                // swallowed, which is the difference between a diagnosable feature and one
                // that silently does nothing.
                if (Build.VERSION.SDK_INT >= LiveActivityGate.MIN_SDK) {
                    if (notification.hasPromotableCharacteristics()) {
                        app.liveActivityGate.recordPromotable()
                    } else {
                        app.liveActivityGate.recordNotPromotable(
                            "the notification does not have promotable characteristics",
                        )
                        // Post it anyway: it is still the foreground service's notification
                        // and the service must have one. It simply will not be promoted.
                        lastPosted = null
                    }
                }

                getSystemService(NotificationManager::class.java)
                    .notify(ONGOING_NOTIFICATION_ID, notification)
            }

            is LiveActivityDecision.Withhold -> {
                // Still a foreground service, so a notification must exist. It falls back
                // to exactly the text this service has always posted — phones that cannot
                // show the richer surface lose nothing they had.
                lastPosted = null

                // Except after a dismissal, which is the one reason that must post nothing
                // at all. The service is already in the foreground and its notification is
                // the one the user just swiped; posting the fallback would put a
                // notification back under the same id within the second, which is the
                // argument with the user that the dismissal rule exists to prevent. Saying
                // nothing leaves the shade as they left it, and the service keeps running.
                if (decision.reason == NoSurface.Dismissed) return

                updateOngoingNotification(fallbackText(nearest))
            }
        }
    }

    /** The pre-existing summary line, unchanged. */
    private fun fallbackText(nearest: PodState?): String =
        when {
            nearest == null -> {
                "No AirPods nearby"
            }

            // Active sensing is discoverable without opening the app. It comes first
            // because it is the thing the user most needs to know is running — it draws
            // the accessory's battery.
            nearest.heartRateSensing.enabled && nearest.heartRate.isSensing -> {
                getString(R.string.monitor_notification_heart_rate, nearest.name)
            }

            nearest.battery.lowestBudPercent == null -> {
                nearest.name
            }

            else -> {
                "${nearest.name} · ${nearest.battery.lowestBudPercent}%"
            }
        }

    private fun updateOngoingNotification(text: String) {
        if (!canPostNotifications()) return
        getSystemService(NotificationManager::class.java)
            .notify(ONGOING_NOTIFICATION_ID, buildOngoingNotification(text))
    }

    private fun warn(warning: LowBatteryWarning) {
        if (!canPostNotifications()) return
        val notification =
            Notification
                .Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(
                    getString(
                        R.string.low_battery_title,
                        warning.component.displayName,
                        warning.deviceName,
                    ),
                ).setContentText(getString(R.string.low_battery_text, warning.levelPercent))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()

        getSystemService(NotificationManager::class.java)
            .notify(warning.address.hashCode() + warning.component.ordinal, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ONGOING_CHANNEL_ID = "pod_monitor"
        private const val ALERT_CHANNEL_ID = "pod_low_battery"
        private const val ONGOING_NOTIFICATION_ID = 1

        /**
         * The decision this service last actually made, for diagnostics to read.
         *
         * Published because a diagnostic that re-derives the answer is not reporting the
         * feature — it is reporting a second implementation that happens to share a class.
         * Most of the time the two agree, and the one place they cannot is the one worth
         * checking: [LiveActivityPolicy] carries the dismissal in instance state, so a
         * freshly constructed policy can never say `Dismissed`, and `gp --es cmd live` used
         * to report a surface as posted seconds after it had been swiped away.
         *
         * Null when the service has published nothing, which a caller must report as such
         * rather than paper over with a decision of its own.
         */
        @Volatile
        var lastDecision: LiveActivityDecision? = null
            private set
    }
}
