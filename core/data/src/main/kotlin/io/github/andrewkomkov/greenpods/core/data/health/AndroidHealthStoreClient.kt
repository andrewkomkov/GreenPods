package io.github.andrewkomkov.greenpods.core.data.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

/**
 * The real Health Connect, behind [HealthStoreClient].
 *
 * Every Health Connect type this project touches appears in this one file. Above it the
 * feature speaks in [HeartRateBatch], [HealthDevice] and plain strings, which is what
 * lets `feature/settings` launch the permission request without linking the library
 * (AD-11).
 *
 * Availability is checked on every call rather than cached: Health Connect is a separate,
 * updatable provider, and a cached "available" survives a provider being removed.
 */
class AndroidHealthStoreClient(
    private val context: Context,
) : HealthStoreClient {
    private val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
    }

    private val writePermission = HealthPermission.getWritePermission(HeartRateRecord::class)

    /**
     * Requested for exactly one purpose: FR-029's count-back, which is only meaningful if
     * it can be filtered to GreenPods' own records. Counting everything in the window
     * would report someone else's chest strap as our output, which makes the verification
     * claim false rather than merely imprecise. Reading other apps' heart rate is out of
     * scope and this permission is never used for it.
     */
    private val readPermission = HealthPermission.getReadPermission(HeartRateRecord::class)

    override fun availability(): HealthStoreAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                HealthStoreAvailability.Available
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                HealthStoreAvailability.NeedsProviderUpdate
            }

            else -> {
                HealthStoreAvailability.NotOnThisDevice
            }
        }

    override suspend fun hasWritePermission(): Boolean = granted().contains(writePermission)

    override suspend fun hasReadPermission(): Boolean = granted().contains(readPermission)

    override suspend fun insert(
        batch: HeartRateBatch,
        device: HealthDevice,
    ) {
        val store = client ?: return
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(batch.startEpochMillis)
        val end = Instant.ofEpochMilli(batch.endEpochMillis)

        store.insertRecords(
            listOf(
                HeartRateRecord(
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    samples =
                        batch.samples.map { reading ->
                            HeartRateRecord.Sample(
                                // The reading's own timestamp, anchored against the host
                                // clock — never the moment the write happened, and never
                                // the accessory's raw counter (R-4a).
                                time = Instant.ofEpochMilli(reading.measuredAtEpochMillis),
                                beatsPerMinute = reading.beatsPerMinute.toLong(),
                            )
                        },
                    metadata =
                        Metadata.autoRecorded(
                            // TYPE_HEAD_MOUNTED, so a user reading their history in
                            // another app can tell an earbud measurement from a chest
                            // strap (FR-021).
                            device = Device(Device.TYPE_HEAD_MOUNTED, device.manufacturer, device.model),
                            clientRecordId = batch.clientRecordId,
                            clientRecordVersion = batch.clientRecordVersion,
                        ),
                ),
            ),
        )
    }

    override suspend fun countOwnRecords(
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ): OwnRecordCount {
        val store = client ?: return OwnRecordCount(0, 0)
        val response =
            store.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter =
                        TimeRangeFilter.between(
                            Instant.ofEpochMilli(fromEpochMillis),
                            Instant.ofEpochMilli(toEpochMillis),
                        ),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                ),
            )
        return OwnRecordCount(
            records = response.records.size,
            samples = response.records.sumOf { it.samples.size },
        )
    }

    override suspend fun deleteOwnRecords() {
        val store = client ?: return
        // Our own records only. The filter is the whole point: "delete what GreenPods
        // holds" must not become "delete this user's heart-rate history" (FR-025).
        val own =
            store
                .readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                        dataOriginFilter = setOf(DataOrigin(context.packageName)),
                    ),
                ).records
        if (own.isEmpty()) return
        store.deleteRecords(
            recordType = HeartRateRecord::class,
            recordIdsList = own.map { it.metadata.id },
            clientRecordIdsList = emptyList(),
        )
    }

    override fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    override fun requiredPermissions(): Set<String> = setOf(writePermission, readPermission)

    private suspend fun granted(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions().orEmpty() }
            .getOrDefault(emptySet())
}
