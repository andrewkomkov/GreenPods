package io.github.andrewkomkov.greenpods

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.service.PodMonitorService
import io.github.andrewkomkov.greenpods.ui.GreenPodsApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = GreenPodsApplication.instance

    /**
     * Scanning simply produces nothing until the permission is granted, and the empty
     * state explains that, so there is nothing to handle here beyond re-reading the
     * environment.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            app.environmentMonitor.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        app.environmentMonitor.start()
        requestPermissions()
        observeBackgroundMonitoring()

        // Auto-pause should work while the app is simply open, without forcing the user
        // to accept a foreground service. The controller ignores a second caller, so
        // this and the service can both ask for it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.earDetectionController.run() }
        }

        setContent {
            GreenPodsTheme {
                GreenPodsApp(
                    onRequestPermission = ::requestPermissions,
                    onOpenUrl = ::openUrl,
                    onOpenBluetoothSettings = ::openBluetoothSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can be revoked from system settings while the app is backgrounded.
        app.environmentMonitor.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.environmentMonitor.stop()
    }

    /**
     * Starts or stops the monitoring service to match the user's preference.
     *
     * Driven from the settings flow rather than from the switch's click handler, so the
     * service state stays correct no matter where the setting was changed.
     *
     * **Heart rate implies the service.** The sensing session lives with the Bluetooth
     * channel, and continuous Bluetooth work needs a foreground service — so enabling
     * heart rate starts one even though `backgroundMonitoringEnabled` defaults to off.
     * That default exists because a service the user did not ask for is hostile, and this
     * is not that: the heart-rate toggle states the cost before it can be switched on, so
     * the override is one the user consented to rather than one that happened to them
     * (FR-012, FR-013, AD-8).
     */
    private fun observeBackgroundMonitoring() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsRepository.settings
                    .map { it.backgroundMonitoringEnabled || it.heartRateEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        val intent = Intent(this@MainActivity, PodMonitorService::class.java)
                        if (enabled) startForegroundService(intent) else stopService(intent)
                    }
            }
        }
    }

    /**
     * From API 31 scanning needs the dedicated Bluetooth permissions; before that it is
     * gated behind location instead. Notifications are asked for alongside because the
     * monitoring service is useless without them — but the app works if they are denied.
     */
    private fun requestPermissions() {
        val permissions =
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * Opens the system's Bluetooth settings.
     *
     * Rather than asking to enable Bluetooth directly: that request was deprecated, and
     * an app switching a radio on from under the user is worse behaviour than showing
     * them the switch. Wrapped because a phone with no Bluetooth settings activity is
     * unusual but not impossible, and a crash there would be absurd.
     */
    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }
}
