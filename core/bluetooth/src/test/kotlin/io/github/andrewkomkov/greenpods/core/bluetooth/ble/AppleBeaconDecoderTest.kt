package io.github.andrewkomkov.greenpods.core.bluetooth.ble

import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class AppleBeaconDecoderTest {
    /**
     * Builds a proximity-pairing payload with the interesting fields controllable
     * and everything else zeroed, so each test isolates one decoding rule.
     */
    private fun payload(
        modelId: Int = PodModel.AIRPODS_PRO_2.modelId,
        status: Int = 0,
        batteryByte: Int = 0,
        caseByte: Int = 0,
        lidCounter: Int = 0,
    ): ByteArray =
        ByteArray(AppleBeaconDecoder.PAIRING_MESSAGE_LENGTH).also {
            it[0] = AppleBeaconDecoder.TYPE_PROXIMITY_PAIRING.toByte()
            it[1] = 0x19
            it[3] = ((modelId shr 8) and 0xFF).toByte()
            it[4] = (modelId and 0xFF).toByte()
            it[5] = status.toByte()
            it[6] = batteryByte.toByte()
            it[7] = caseByte.toByte()
            it[8] = lidCounter.toByte()
        }

    @Test
    fun `rejects payloads that are not proximity pairing messages`() {
        AppleBeaconDecoder.decode(ByteArray(10)).shouldBeNull()
        AppleBeaconDecoder.decode(ByteArray(AppleBeaconDecoder.PAIRING_MESSAGE_LENGTH)).shouldBeNull()
    }

    @Test
    fun `model id is read big-endian as it appears on the air`() {
        AppleBeaconDecoder.decode(payload(modelId = 0x0E20))?.model shouldBe PodModel.AIRPODS_PRO_1
        AppleBeaconDecoder.decode(payload(modelId = 0x1420))?.model shouldBe PodModel.AIRPODS_PRO_2
        AppleBeaconDecoder.decode(payload(modelId = 0x2720))?.model shouldBe PodModel.AIRPODS_PRO_3
        AppleBeaconDecoder.decode(payload(modelId = 0x1D20))?.model shouldBe PodModel.POWERBEATS_PRO_2
    }

    @Test
    fun `unrecognised model ids fall back to UNKNOWN rather than failing`() {
        AppleBeaconDecoder.decode(payload(modelId = 0xABCD))?.model shouldBe PodModel.UNKNOWN
    }

    @Test
    fun `battery nibbles scale to percent in ten point steps`() {
        // Primary nibble 0x0A = 100%, secondary 0x05 = 50%; primary flag set = left is primary.
        val beacon = AppleBeaconDecoder.decode(payload(status = 0x20, batteryByte = 0xA5))

        beacon?.battery?.left?.levelPercent shouldBe 100
        beacon?.battery?.right?.levelPercent shouldBe 50
    }

    @Test
    fun `bud nibbles swap when the right bud is primary`() {
        // Same payload, primary flag clear: the high nibble now describes the right bud.
        val beacon = AppleBeaconDecoder.decode(payload(status = 0x00, batteryByte = 0xA5))

        beacon?.battery?.right?.levelPercent shouldBe 100
        beacon?.battery?.left?.levelPercent shouldBe 50
    }

    @Test
    fun `nibble 0x0F means unknown rather than 150 percent`() {
        val beacon = AppleBeaconDecoder.decode(payload(status = 0x20, batteryByte = 0xFF, caseByte = 0x0F))

        beacon
            ?.battery
            ?.left
            ?.levelPercent
            .shouldBeNull()
        beacon
            ?.battery
            ?.right
            ?.levelPercent
            .shouldBeNull()
        beacon
            ?.battery
            ?.case
            ?.levelPercent
            .shouldBeNull()
    }

    @Test
    fun `charging flags live in the high nibble of the case byte`() {
        // 0x70 = case + left + right charging, case level nibble 0.
        val beacon = AppleBeaconDecoder.decode(payload(status = 0x20, batteryByte = 0x55, caseByte = 0x70))

        beacon?.battery?.left?.status shouldBe ChargeStatus.CHARGING
        beacon?.battery?.right?.status shouldBe ChargeStatus.CHARGING
        beacon?.battery?.case?.status shouldBe ChargeStatus.CHARGING
    }

    @Test
    fun `lid counter is exposed so repeated advertisements do not re-trigger popups`() {
        AppleBeaconDecoder.decode(payload(lidCounter = 42))?.lidOpenCounter shouldBe 42
    }

    @Test
    fun `every model id in the registry is unique`() {
        val ids = PodModel.entries.filter { it != PodModel.UNKNOWN }.map { it.modelId }

        ids.distinct().size shouldBe ids.size
    }
}
