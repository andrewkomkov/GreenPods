package io.github.andrewkomkov.greenpods.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.MainActivity
import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.data.battery.LowBatteryNotifier
import io.github.andrewkomkov.greenpods.core.data.battery.LowBatteryWarning
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
                updateOngoingNotification(
                    when {
                        nearest == null -> {
                            "No AirPods nearby"
                        }

                        // FR-013: active sensing is discoverable without opening the app.
                        // It comes first because it is the thing the user most needs to
                        // know is running — it draws the accessory's battery.
                        nearest.heartRateSensing.enabled && nearest.heartRate.isSensing -> {
                            getString(R.string.monitor_notification_heart_rate, nearest.name)
                        }

                        nearest.battery.lowestBudPercent == null -> {
                            nearest.name
                        }

                        else -> {
                            "${nearest.name} · ${nearest.battery.lowestBudPercent}%"
                        }
                    },
                )
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
        return START_STICKY
    }

    override fun onDestroy() {
        // The receiver outlives the coroutine scope unless it is unregistered by hand,
        // and a leaked one keeps opening channels for a service that is gone.
        channelKeeper.stop()
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

    private fun buildOngoingNotification(text: String): Notification =
        Notification
            .Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()

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

    private companion object {
        const val ONGOING_CHANNEL_ID = "pod_monitor"
        const val ALERT_CHANNEL_ID = "pod_low_battery"
        const val ONGOING_NOTIFICATION_ID = 1
    }
}
