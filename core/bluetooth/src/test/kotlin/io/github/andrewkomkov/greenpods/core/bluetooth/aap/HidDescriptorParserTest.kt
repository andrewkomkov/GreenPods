package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Discovery, and the one test that keeps it honest.
 *
 * "Read the service id from the descriptors" is easy to write and easy to quietly undo:
 * a constant that happens to match the observed value passes every test that uses the
 * captured frame unaltered. The renumbering case below is the enforcement of FR-002, and
 * it is the reason this file exists at all (AD-1).
 */
class HidDescriptorParserTest {
    private val body = AapFixtures.descriptorBody()

    @Test
    fun `the captured frame yields the four services the accessory announced`() {
        val services = HidDescriptorParser.services(body)

        services.map(HidService::id) shouldContainExactly listOf(0x10, 0x11, 0x12, 0x13)
        services.map(HidService::name) shouldContainExactly
            listOf("devmotion", "SPL0", "HostLibHID", "HeartRateService")
    }

    @Test
    fun `the heart-rate service is found by name, and its id read from beside it`() {
        val heartRate = HidDescriptorParser.services(body).single(HidService::isHeartRate)

        heartRate.id shouldBe 0x13
        heartRate.name shouldBe "HeartRateService"
    }

    @Test
    fun `the same frame with the ids renumbered still finds the sensor`() {
        // FR-002 in one test. Every service id in the frame is changed; nothing else is.
        // A parser carrying a hard-coded 0x13 passes every other test in this file and
        // fails this one, which is exactly what it is for.
        val renumbered = renumberServiceIds(body, offset = 0x20)

        val services = HidDescriptorParser.services(renumbered)
        val heartRate = services.single(HidService::isHeartRate)

        services.map(HidService::id) shouldContainExactly listOf(0x30, 0x31, 0x32, 0x33)
        heartRate.id shouldBe 0x33
        heartRate.layout?.heartRateReport().shouldNotBeNull()
    }

    @Test
    fun `the entitlement identifies the sensor when the service name is gone`() {
        // A model that renames the service is far likelier than one that changes an
        // entitlement other software depends on, so the fallback carries real weight.
        val withoutName = replaceAscii(body, "HeartRateService", "SensorServiceXYZ")

        val heartRate = HidDescriptorParser.services(withoutName).single(HidService::isHeartRate)

        heartRate.id shouldBe 0x13
        heartRate.name shouldBe "SensorServiceXYZ"
    }

    @Test
    fun `a frame with neither identifier finds no heart-rate service at all`() {
        val stripped =
            replaceAscii(
                replaceAscii(body, "HeartRateService", "SensorServiceXYZ"),
                "com.apple.hid.heartrate-access",
                "com.apple.hid.something-else00",
            )

        HidDescriptorParser.services(stripped).count(HidService::isHeartRate) shouldBe 0
    }

    @Test
    fun `the head-tracking service is recognised too, so 0x17 can be routed by service`() {
        val headTracking = HidDescriptorParser.services(body).single(HidService::isHeadTracking)

        headTracking.id shouldBe 0x10
        headTracking.name shouldBe "devmotion"
    }

    @Test
    fun `the heart-rate service carries the report descriptor, lifted out intact`() {
        val heartRate = HidDescriptorParser.services(body).single(HidService::isHeartRate)

        heartRate.reportDescriptor shouldBe AapFixtures.heartRateReportDescriptor
    }

    @Test
    fun `the readiness list is read as service ids`() {
        val ready = HidDescriptorParser.readyServiceIds(AapFixtures.descriptorBody(AapFixtures.readyFrame))

        ready shouldContainExactly listOf(0x10, 0x11, 0x12, 0x13)
    }

    @Test
    fun `a body with no descriptors yields no services rather than throwing`() {
        HidDescriptorParser.services(ByteArray(0)) shouldBe emptyList()
        HidDescriptorParser.services(byteArrayOf(0x2A, 0x7F)) shouldBe emptyList()
    }

    /**
     * Re-encodes the body with every service id shifted, leaving the property blobs
     * untouched.
     *
     * Done through the protobuf codec rather than by patching bytes, so the renumbered
     * frame is a real frame and the test cannot pass because a byte substitution
     * happened to land somewhere harmless.
     */
    private fun renumberServiceIds(
        source: ByteArray,
        offset: Int,
    ): ByteArray {
        var out = ByteArray(0)
        Protobuf.fields(source).forEach { field ->
            out +=
                when {
                    field.number == 5 && field.wireType == Protobuf.WIRE_LENGTH_DELIMITED -> {
                        val inner = Protobuf.fields(field.bytes)
                        val id = checkNotNull(Protobuf.varintOf(inner, 1)) + offset
                        val properties = checkNotNull(Protobuf.bytesOf(inner, 2))
                        Protobuf.bytesField(
                            5,
                            Protobuf.varintField(1, id) + Protobuf.bytesField(2, properties),
                        )
                    }

                    field.wireType == Protobuf.WIRE_VARINT -> {
                        Protobuf.varintField(field.number, field.varint)
                    }

                    else -> {
                        Protobuf.bytesField(field.number, field.bytes)
                    }
                }
        }
        return out
    }

    /** Overwrites an ASCII literal in place. Lengths must match so nothing else moves. */
    private fun replaceAscii(
        source: ByteArray,
        from: String,
        to: String,
    ): ByteArray {
        require(from.length == to.length) { "in-place replacement must not change any length" }
        val copy = source.copyOf()
        val needle = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)
        var start = 0
        while (start <= copy.size - needle.size) {
            if ((0 until needle.size).all { copy[start + it] == needle[it] }) {
                replacement.copyInto(copy, start)
                start += needle.size
            } else {
                start++
            }
        }
        return copy
    }
}
