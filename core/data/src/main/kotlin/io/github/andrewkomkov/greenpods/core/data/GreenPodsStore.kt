package io.github.andrewkomkov.greenpods.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.andrewkomkov.greenpods.core.data.head.DataStoreHeadCalibrationStore
import io.github.andrewkomkov.greenpods.core.data.head.HeadCalibrationStore
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.transport.DataStoreHidServiceMemory
import io.github.andrewkomkov.greenpods.core.data.transport.HidServiceMemory

/**
 * Everything the app persists, behind one door.
 *
 * Two things need the preference store — the user's settings, and what each accessory has
 * told us about its own sensor services — and DataStore serialises through a single
 * writer per file, so a second store on the same file corrupts it and a second file is
 * two things to keep in step. They share one here.
 *
 * It also keeps the storage library on this side of a module boundary, the same way
 * `HealthConnectLink` keeps Health Connect on this side of one (AD-11): the app module
 * holds this object and never names a `DataStore`.
 */
class GreenPodsStore(
    context: Context,
) {
    private val store =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) }

    val settings: SettingsRepository = SettingsRepository(store)

    val hidServices: HidServiceMemory = DataStoreHidServiceMemory(store)

    /**
     * Measured head-tracking scales, per accessory model.
     *
     * Here rather than constructed in the app module for the reason the whole class exists:
     * a second `PreferenceDataStoreFactory` on the same file is a second writer, and DataStore
     * serialises through one writer per file.
     */
    val headCalibrations: HeadCalibrationStore = DataStoreHeadCalibrationStore(store)

    private companion object {
        /** The name the settings store has always had; changing it would orphan settings. */
        const val FILE_NAME = "greenpods_settings"
    }
}
