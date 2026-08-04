package io.github.andrewkomkov.greenpods.core.data.transport

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.HidService
import kotlinx.coroutines.flow.first

/**
 * [HidServiceMemory] on the preferences store the rest of the app already uses.
 *
 * The encoding is deliberately dull — one line per service, fields separated by `|`, the
 * report descriptor as hex — rather than JSON or a serialisation library. What is stored
 * is a handful of small records that only this class reads, and a format that can be
 * inspected with `strings` is worth more here than one that needs a dependency.
 *
 * A malformed or partly-written entry is treated as no entry at all. The cost of getting
 * it wrong is one round of ordinary discovery; the cost of trusting a half-parsed
 * descriptor is a heart rate read from the wrong bytes.
 */
class DataStoreHidServiceMemory(
    private val store: DataStore<Preferences>,
) : HidServiceMemory {
    override suspend fun remembered(address: String): List<HidService> {
        val raw = store.data.first()[keyFor(address)] ?: return emptyList()
        return raw
            .lineSequence()
            .mapNotNull(::decodeService)
            .toList()
    }

    override suspend fun remember(
        address: String,
        services: List<HidService>,
    ) {
        if (services.isEmpty()) return
        val encoded = services.joinToString("\n", transform = ::encodeService)
        store.edit { it[keyFor(address)] = encoded }
    }

    override suspend fun forget() {
        store.edit { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name.startsWith(PREFIX) }
                .forEach { preferences.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun encodeService(service: HidService): String =
        listOf(
            service.id.toString(),
            service.name.orEmpty(),
            if (service.isHeartRate) "1" else "0",
            if (service.isHeadTracking) "1" else "0",
            service.reportDescriptor.joinToString("") { "%02X".format(it) },
        ).joinToString(SEPARATOR)

    private fun decodeService(line: String): HidService? {
        val parts = line.split(SEPARATOR)
        if (parts.size != FIELD_COUNT) return null
        val id = parts[0].toIntOrNull() ?: return null
        val descriptor = parts[4].hexToBytesOrNull() ?: return null
        return HidService(
            id = id,
            name = parts[1].ifBlank { null },
            reportDescriptor = descriptor,
            isHeartRate = parts[2] == "1",
            isHeadTracking = parts[3] == "1",
        )
    }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    private fun keyFor(address: String) = stringPreferencesKey("$PREFIX$address")

    private companion object {
        const val PREFIX = "hid_services_"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 5
    }
}
