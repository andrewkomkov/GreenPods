package io.github.andrewkomkov.greenpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps scanning while the app is in the background so battery levels stay fresh
 * and ear-detection events are not missed.
 *
 * Declared as a `connectedDevice` foreground service: that is the type Android
 * expects for continuous Bluetooth work, and using the right one is what keeps the
 * service alive under Doze.
 */
class PodMonitorService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Scanning…"))

        lifecycleScope.launch {
            GreenPodsApplication.instance.podRepository.observePrimaryPod().collectLatest { pod ->
                val level = pod?.battery?.lowestBudPercent
                updateNotification(
                    when {
                        pod == null -> "No AirPods nearby"
                        level == null -> pod.name
                        else -> "${pod.name} · $level%"
                    },
                )
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private companion object {
        const val CHANNEL_ID = "pod_monitor"
        const val NOTIFICATION_ID = 1
    }
}
