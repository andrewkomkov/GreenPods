package io.github.andrewkomkov.greenpods.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persisted user preferences, read as one immutable [GreenPodsSettings].
 *
 * Reading the whole object rather than individual keys keeps every consumer — the
 * auto-pause policy, the scanner, the service — working from a consistent snapshot,
 * which matters because several of them are driven by the same flow at once.
 *
 * A corrupt preference file yields defaults instead of an exception: settings are not
 * worth crashing over, and the alternative is an app that cannot start.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<GreenPodsSettings> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(
                        androidx.datastore.preferences.core
                            .emptyPreferences(),
                    )
                } else {
                    throw error
                }
            }.map(::toSettings)

    suspend fun update(transform: (GreenPodsSettings) -> GreenPodsSettings) {
        dataStore.edit { preferences ->
            val updated = transform(toSettings(preferences)).sanitised()
            preferences[Keys.AUTO_PAUSE] = updated.autoPauseEnabled
            preferences[Keys.AUTO_RESUME] = updated.autoResumeEnabled
            preferences[Keys.PAUSE_ONLY_BOTH_OUT] = updated.pauseOnlyWhenBothOut
            preferences[Keys.BACKGROUND_MONITORING] = updated.backgroundMonitoringEnabled
            preferences[Keys.LOW_BATTERY_WARNING] = updated.lowBatteryWarningEnabled
            preferences[Keys.LOW_BATTERY_THRESHOLD] = updated.lowBatteryThresholdPercent
            preferences[Keys.SCAN_MODE] = updated.scanMode.name
            preferences[Keys.HEAD_GESTURES] = updated.headGesturesEnabled
            preferences[Keys.GESTURE_BINDINGS] = GestureBindingCodec.encode(updated.gestureBindings)
            preferences[Keys.HEART_RATE] = updated.heartRateEnabled
            preferences[Keys.HEART_RATE_HEALTH_CONNECT] = updated.heartRateHealthConnectEnabled
            preferences[Keys.HEART_RATE_INTERVAL_MILLIS] = updated.heartRateIntervalMillis
            preferences[Keys.HEART_RATE_CONFIDENCE] = updated.heartRateConfidenceThreshold
            preferences[Keys.LIVE_ACTIVITY] = updated.liveActivityEnabled
            preferences[Keys.LIVE_ACTIVITY_SHOW_HEART_RATE] = updated.liveActivityShowHeartRate
            preferences[Keys.CALIBRATION_TOLERANCE_UNITS] = updated.calibrationToleranceUnits
            preferences[Keys.CALIBRATION_HOLD_MILLIS] = updated.calibrationHoldMillis
        }
    }

    /** Convenience for the common single-binding edit from the settings screen. */
    suspend fun updateBinding(binding: HeadGestureBinding) {
        update { current ->
            current.copy(
                gestureBindings =
                    current.gestureBindings.map { existing ->
                        if (existing.gesture == binding.gesture) binding else existing
                    },
            )
        }
    }

    private fun toSettings(preferences: Preferences): GreenPodsSettings {
        val defaults = GreenPodsSettings.Default
        return GreenPodsSettings(
            autoPauseEnabled = preferences[Keys.AUTO_PAUSE] ?: defaults.autoPauseEnabled,
            autoResumeEnabled = preferences[Keys.AUTO_RESUME] ?: defaults.autoResumeEnabled,
            pauseOnlyWhenBothOut = preferences[Keys.PAUSE_ONLY_BOTH_OUT] ?: defaults.pauseOnlyWhenBothOut,
            backgroundMonitoringEnabled =
                preferences[Keys.BACKGROUND_MONITORING]
                    ?: defaults.backgroundMonitoringEnabled,
            lowBatteryWarningEnabled = preferences[Keys.LOW_BATTERY_WARNING] ?: defaults.lowBatteryWarningEnabled,
            lowBatteryThresholdPercent =
                preferences[Keys.LOW_BATTERY_THRESHOLD]
                    ?: defaults.lowBatteryThresholdPercent,
            scanMode = preferences[Keys.SCAN_MODE]?.let(::scanModeOrNull) ?: defaults.scanMode,
            headGesturesEnabled = preferences[Keys.HEAD_GESTURES] ?: defaults.headGesturesEnabled,
            gestureBindings = GestureBindingCodec.decode(preferences[Keys.GESTURE_BINDINGS]),
            heartRateEnabled = preferences[Keys.HEART_RATE] ?: defaults.heartRateEnabled,
            heartRateHealthConnectEnabled =
                preferences[Keys.HEART_RATE_HEALTH_CONNECT]
                    ?: defaults.heartRateHealthConnectEnabled,
            heartRateIntervalMillis =
                preferences[Keys.HEART_RATE_INTERVAL_MILLIS]
                    ?: defaults.heartRateIntervalMillis,
            heartRateConfidenceThreshold =
                preferences[Keys.HEART_RATE_CONFIDENCE]
                    ?: defaults.heartRateConfidenceThreshold,
            liveActivityEnabled = preferences[Keys.LIVE_ACTIVITY] ?: defaults.liveActivityEnabled,
            liveActivityShowHeartRate =
                preferences[Keys.LIVE_ACTIVITY_SHOW_HEART_RATE]
                    ?: defaults.liveActivityShowHeartRate,
            calibrationToleranceUnits =
                preferences[Keys.CALIBRATION_TOLERANCE_UNITS]
                    ?: defaults.calibrationToleranceUnits,
            calibrationHoldMillis =
                preferences[Keys.CALIBRATION_HOLD_MILLIS]
                    ?: defaults.calibrationHoldMillis,
        ).sanitised()
    }

    private fun scanModeOrNull(name: String): ScanMode? = ScanMode.entries.firstOrNull { it.name == name }

    private object Keys {
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val PAUSE_ONLY_BOTH_OUT = booleanPreferencesKey("pause_only_both_out")
        val BACKGROUND_MONITORING = booleanPreferencesKey("background_monitoring")
        val LOW_BATTERY_WARNING = booleanPreferencesKey("low_battery_warning")
        val LOW_BATTERY_THRESHOLD = intPreferencesKey("low_battery_threshold")
        val SCAN_MODE = stringPreferencesKey("scan_mode")
        val HEAD_GESTURES = booleanPreferencesKey("head_gestures")
        val GESTURE_BINDINGS = stringPreferencesKey("gesture_bindings")
        val HEART_RATE = booleanPreferencesKey("heart_rate")
        val HEART_RATE_HEALTH_CONNECT = booleanPreferencesKey("heart_rate_health_connect")
        val HEART_RATE_INTERVAL_MILLIS = intPreferencesKey("heart_rate_interval_millis")

        /**
         * Persisted so a calibration set over adb survives a restart — the threshold is
         * provisional (R-4), and re-deriving it must not mean rebuilding the app.
         */
        val HEART_RATE_CONFIDENCE = intPreferencesKey("heart_rate_confidence")

        val LIVE_ACTIVITY = booleanPreferencesKey("live_activity")

        /**
         * Hides the heart-rate **value** on the live surface, never the disclosure that
         * sensing is running. Persisted separately from [HEART_RATE] because declining to
         * display a reading is not declining to measure one.
         */
        val LIVE_ACTIVITY_SHOW_HEART_RATE = booleanPreferencesKey("live_activity_show_heart_rate")

        /**
         * Both persisted for the same reason [HEART_RATE_CONFIDENCE] is: the plateau tolerance
         * and the hold duration are provisional numbers, and finding better ones has to be a
         * measurement someone can run rather than a rebuild.
         */
        val CALIBRATION_TOLERANCE_UNITS = intPreferencesKey("calibration_tolerance_units")
        val CALIBRATION_HOLD_MILLIS = longPreferencesKey("calibration_hold_millis")
    }

    companion object {
        private const val FILE_NAME = "greenpods_settings"

        /** Prefer `GreenPodsStore`, which shares one store with the service memory. */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(
                PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
            )
    }
}
