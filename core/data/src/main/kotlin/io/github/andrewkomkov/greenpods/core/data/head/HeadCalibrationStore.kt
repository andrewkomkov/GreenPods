package io.github.andrewkomkov.greenpods.core.data.head

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.andrewkomkov.greenpods.core.data.settings.HeadCalibrationCodec
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.PodModel
import kotlinx.coroutines.flow.first

/**
 * What a head calibration is remembered against, and how it is forgotten.
 *
 * Keyed by **model**, not by address. That is a deliberate departure from
 * `DataStoreHidServiceMemory`, which remembers per accessory because a descriptor is a fact
 * about one physical pair. A calibration is arguably a fact about one pair *and* one head,
 * so per-address would be defensible — but the spec says model (FR-021), and the reason is
 * that a scale is meant to be a property of how a model reports orientation, transferable
 * between two identical pairs and never between different ones.
 *
 * Recorded here so that if the model key later proves wrong, it reads as a decision somebody
 * took rather than as something nobody noticed.
 */
interface HeadCalibrationStore {
    suspend fun load(model: PodModel): HeadCalibration

    suspend fun save(calibration: HeadCalibration)

    /** Returns to the uncalibrated fallback for one model (FR-023). */
    suspend fun clear(model: PodModel)

    /** Everything stored, for the state dump — including models with nothing measured. */
    suspend fun all(): List<HeadCalibration>
}

/**
 * [HeadCalibrationStore] on the preferences store the rest of the app already uses.
 *
 * One key per model, prefixed, so a calibration for one accessory can be cleared without
 * rewriting the others — and so the whole set can be swept by prefix.
 *
 * A malformed entry costs its own axis and nothing more; that rule lives in
 * [HeadCalibrationCodec] rather than here, because it is a statement about the format.
 */
class DataStoreHeadCalibrationStore(
    private val store: DataStore<Preferences>,
) : HeadCalibrationStore {
    override suspend fun load(model: PodModel): HeadCalibration {
        val preferences = store.data.first()
        return HeadCalibrationCodec.decode(
            model = model,
            raw = preferences[keyFor(model)],
            measuredAtEpochMillis = preferences[timeKeyFor(model)] ?: 0L,
        )
    }

    override suspend fun save(calibration: HeadCalibration) {
        store.edit { preferences ->
            preferences[keyFor(calibration.model)] = HeadCalibrationCodec.encode(calibration)
            preferences[timeKeyFor(calibration.model)] = calibration.measuredAtEpochMillis
        }
    }

    override suspend fun clear(model: PodModel) {
        store.edit { preferences ->
            preferences.remove(keyFor(model))
            preferences.remove(timeKeyFor(model))
        }
    }

    /**
     * Only models with something stored.
     *
     * A model that was never calibrated has no entry, and inventing one here would make the
     * dump claim knowledge of accessories this phone has never seen. The dump adds the
     * connected model itself, which is the one case where "uncalibrated" is worth saying out
     * loud.
     */
    override suspend fun all(): List<HeadCalibration> {
        val preferences = store.data.first()
        return PodModel.entries
            .filter { preferences[keyFor(it)] != null }
            .map { model ->
                HeadCalibrationCodec.decode(
                    model = model,
                    raw = preferences[keyFor(model)],
                    measuredAtEpochMillis = preferences[timeKeyFor(model)] ?: 0L,
                )
            }
    }

    private fun keyFor(model: PodModel) = stringPreferencesKey("$PREFIX${model.name}")

    private fun timeKeyFor(model: PodModel) = longPreferencesKey("$TIME_PREFIX${model.name}")

    private companion object {
        const val PREFIX = "head_calibration_"
        const val TIME_PREFIX = "head_calibration_at_"
    }
}
