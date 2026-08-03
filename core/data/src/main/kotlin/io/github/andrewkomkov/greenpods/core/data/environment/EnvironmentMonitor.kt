package io.github.andrewkomkov.greenpods.core.data.environment

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two facts that decide whether scanning can work at all.
 *
 * Kept as one value because the UI has to distinguish them: "grant the permission" and
 * "turn Bluetooth on" are different instructions, and showing the wrong one is worse
 * than showing nothing.
 */
data class Environment(
    val scanPermissionGranted: Boolean,
    val bluetoothEnabled: Boolean,
) {
    val canScan: Boolean get() = scanPermissionGranted && bluetoothEnabled

    companion object {
        /** Assume the best until told otherwise, so the UI does not flash an error on start. */
        val Unknown = Environment(scanPermissionGranted = true, bluetoothEnabled = true)
    }
}

/**
 * Watches Bluetooth state and scan-permission state.
 *
 * Bluetooth state arrives by broadcast; permission state does not change without the
 * user leaving the app, so it is re-read on [refresh] — which the Activity calls when
 * it resumes. Polling for a permission would be all cost and no benefit.
 */
class AndroidEnvironmentMonitor(
    private val context: Context,
) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Environment> = _state.asStateFlow()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) refresh()
            }
        }

    fun start() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() {
        _state.value = read()
    }

    private fun read(): Environment =
        Environment(
            scanPermissionGranted = hasScanPermission(),
            bluetoothEnabled = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true,
        )

    /**
     * From API 31 scanning is gated behind `BLUETOOTH_SCAN`; before that it counts as
     * a location capability and needs `ACCESS_FINE_LOCATION`. GreenPods declares
     * `neverForLocation`, so on modern Android it never asks for location at all.
     */
    private fun hasScanPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
