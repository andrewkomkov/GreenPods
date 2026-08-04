package io.github.andrewkomkov.greenpods.core.data.health

import androidx.activity.result.contract.ActivityResultContract
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeartRateReading
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The link's rules, against a fake provider.
 *
 * Every case here is one the real Health Connect makes awkward to reach — no provider, a
 * provider needing an update, permission granted then revoked mid-session — and every one
 * of them is a case the user will actually hit. The point of the interface is that they
 * are decidable without a phone.
 */
class HealthConnectLinkTest {
    private class FakeClient(
        var availability: HealthStoreAvailability = HealthStoreAvailability.Available,
        var write: Boolean = true,
        var read: Boolean = true,
    ) : HealthStoreClient {
        val inserted = mutableListOf<Pair<HeartRateBatch, HealthDevice>>()
        var deletes = 0
        var counted: Pair<Long, Long>? = null

        override fun availability() = availability

        override suspend fun hasWritePermission() = write

        override suspend fun hasReadPermission() = read

        override suspend fun insert(
            batch: HeartRateBatch,
            device: HealthDevice,
        ) {
            inserted += batch to device
        }

        override suspend fun countOwnRecords(
            fromEpochMillis: Long,
            toEpochMillis: Long,
        ): OwnRecordCount {
            counted = fromEpochMillis to toEpochMillis
            return OwnRecordCount(records = 10, samples = 59)
        }

        override suspend fun deleteOwnRecords() {
            deletes++
        }

        override fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
            error("not needed off-device")

        override fun requiredPermissions(): Set<String> = setOf("write", "read")
    }

    private val pod = PodState(address = "AA:BB:CC:DD:EE:FF", model = PodModel.AIRPODS_PRO_3)
    private val minuteStart = 1_785_672_000_000L

    private fun reading(atMillis: Long) =
        HeartRateReading(
            beatsPerMinute = 72,
            confidence = 200,
            source = HeartRateReading.Source.AAP,
            measuredAtEpochMillis = atMillis,
        )

    private fun link(
        client: HealthStoreClient?,
        enabled: Boolean = true,
    ) = HealthConnectLink(
        client = client,
        settings = MutableStateFlow(GreenPodsSettings.Default.copy(heartRateHealthConnectEnabled = enabled)),
    )

    @Test
    fun `each availability status maps to its own sentence`() {
        // FR-020 wants a reason, and the two unavailable cases ask different things of
        // the user: one is an update they can install, the other is a phone that will
        // never have it.
        link(FakeClient(availability = HealthStoreAvailability.Available))
            .availability()
            .sentence shouldContain "available"

        link(FakeClient(availability = HealthStoreAvailability.NeedsProviderUpdate))
            .availability()
            .sentence shouldContain "needs an update"

        val absent = link(FakeClient(availability = HealthStoreAvailability.NotOnThisDevice)).availability()
        absent.sentence shouldContain "does not have Health Connect"
        // And the reading on screen is unaffected, which the sentence has to say or the
        // user reads it as the feature being broken.
        absent.sentence shouldContain "still works on screen"
    }

    @Test
    fun `a phone with no provider at all reports not-on-this-device rather than crashing`() {
        val link = link(client = null)

        link.availability() shouldBe HealthStoreAvailability.NotOnThisDevice
        link.requiredPermissions().shouldBeEmpty()
        link.permissionRequestContract() shouldBe null
    }

    @Test
    fun `without write permission nothing is written at all`() =
        runTest {
            val client = FakeClient(write = false)
            val link = link(client)

            (0..60).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }
            link.onSensingStopped(pod.address)

            // US3 scenario 1: the reading is on screen and nothing was written anywhere.
            client.inserted.shouldBeEmpty()
        }

    @Test
    fun `with the integration switched off nothing is written even with permission`() =
        runTest {
            val client = FakeClient()
            val link = link(client, enabled = false)

            (0..60).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }
            link.onSensingStopped(pod.address)

            // FR-017: seeing a number and putting it in someone's health history are two
            // separate decisions, and the second is off by default.
            client.inserted.shouldBeEmpty()
        }

    @Test
    fun `losing permission mid-session stops the very next write`() =
        runTest {
            val client = FakeClient()
            val link = link(client)

            (0..60).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }
            client.inserted.size shouldBe 1

            client.write = false
            (60..120).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }

            // FR-018: revocation happens outside the app, so permission is read per flush
            // rather than once at the start — and nothing prompts.
            client.inserted.size shouldBe 1
        }

    @Test
    fun `a completed window is written, attributed to the accessory that measured it`() =
        runTest {
            val client = FakeClient()
            val link = link(client)

            (0..60).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }

            val (batch, device) = client.inserted.single()
            batch.samples.size shouldBe 60
            batch.clientRecordId shouldBe "greenpods:hr:${minuteStart / 1_000}"
            // FR-021: a user reading their history in another app must be able to tell an
            // earbud measurement from a chest strap.
            device.manufacturer shouldBe "Apple"
            device.model shouldBe PodModel.AIRPODS_PRO_3.displayName
        }

    @Test
    fun `stopping flushes the partial window rather than discarding it`() =
        runTest {
            val client = FakeClient()
            val link = link(client)

            (0 until 40).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }
            client.inserted.shouldBeEmpty()

            link.onSensingStopped(pod.address)

            client.inserted
                .single()
                .first.samples.size shouldBe 40
        }

    @Test
    fun `counting needs read permission and an available provider`() =
        runTest {
            val client = FakeClient(read = false)
            link(client).countOwnRecords(minutes = 10, nowEpochMillis = minuteStart) shouldBe null

            client.read = true
            val count = link(client).countOwnRecords(minutes = 10, nowEpochMillis = minuteStart)

            count shouldBe OwnRecordCount(records = 10, samples = 59)
            client.counted shouldBe (minuteStart - 600_000L to minuteStart)
        }

    @Test
    fun `deleting needs write permission, and only ever touches our own records`() =
        runTest {
            val denied = FakeClient(write = false)
            link(denied).deleteOwnRecords() shouldBe false
            denied.deletes shouldBe 0

            val allowed = FakeClient()
            link(allowed).deleteOwnRecords() shouldBe true
            allowed.deletes shouldBe 1
        }

    @Test
    fun `an unavailable provider is not written to, whatever the settings say`() =
        runTest {
            val client = FakeClient(availability = HealthStoreAvailability.NeedsProviderUpdate)
            val link = link(client)

            (0..60).forEach { second -> link.onTrusted(pod, reading(minuteStart + second * 1_000L)) }

            client.inserted.shouldBeEmpty()
        }
}
