package io.github.andrewkomkov.greenpods.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    }

    companion object {
        private const val FILE_NAME = "greenpods_settings"

        fun create(context: Context): SettingsRepository =
            SettingsRepository(
                PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) },
            )
    }
}
