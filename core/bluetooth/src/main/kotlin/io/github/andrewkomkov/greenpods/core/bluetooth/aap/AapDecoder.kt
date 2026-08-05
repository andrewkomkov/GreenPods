package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.WearState

/** A decoded message from the accessory. */
sealed interface AapEvent {
    data class Battery(
        val state: BatteryState,
    ) : AapEvent

    data class EarDetection(
        val state: EarDetectionState,
    ) : AapEvent

    data class NoiseControl(
        val mode: NoiseControlMode,
    ) : AapEvent

    data class ConversationalAwarenessState(
        val enabled: Boolean,
    ) : AapEvent

    data class ConversationalAwarenessLevel(
        val level: Int,
    ) : AapEvent

    data class AdaptiveNoiseStrength(
        val level: Int,
    ) : AapEvent

    data class HeadTracking(
        val sample: HeadTrackingSample,
    ) : AapEvent

    data class DeviceInfo(
        val fields: List<String>,
    ) : AapEvent

    /** A `0x17` descriptor frame: the accessory describing its own sensor services. */
    data class HidServices(
        val services: List<HidService>,
    ) : AapEvent

    /** Field 12 — the accessory saying those services are up and will answer. */
    data class HidServicesReady(
        val serviceIds: List<Int>,
    ) : AapEvent

    /**
     * Field 9 — the accessory acknowledging a start, naming the service it acted on.
     *
     * An acknowledgement of a **command**, never evidence of a measurement. Nothing may
     * derive a running sensor from this: on this transport "the write was accepted" is
     * the characteristic false positive, and only an arriving report means the sensor is
     * on (Principle I).
     */
    data class HidServiceStarted(
        val serviceId: Int,
    ) : AapEvent

    /** One trusted-or-not heart-rate measurement, straight off the wire. */
    data class HeartRateReport(
        val serviceId: Int,
        val reading: io.github.andrewkomkov.greenpods.core.model.HeartRateReading,
    ) : AapEvent

    /**
     * A HID input report that did not become a reading — an undecoded report id, a
     * length that disagrees with the descriptor, an implausible value.
     *
     * Carries **shape only**: which service, which report id, how many bytes and why.
     * That is what lets Principle IV ("nothing is dropped silently") and FR-023 ("no
     * heart rate in any diagnostic") both hold at once — nothing is lost, and no value
     * is written down (R-9).
     */
    data class UnhandledHidReport(
        val serviceId: Int,
        val reportId: Int,
        val length: Int,
        val reason: String,
    ) : AapEvent

    /** A control packet we recognise the id of but have no typed model for yet. */
    data class UnhandledControl(
        val command: ControlCommand,
        val payload: ByteArray,
    ) : AapEvent {
        override fun equals(other: Any?): Boolean =
            other is UnhandledControl && command == other.command && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * command.hashCode() + payload.contentHashCode()
    }

    /** Traffic we cannot interpret at all. Surfaced in diagnostics, never dropped silently. */
    data class Unknown(
        val raw: ByteArray,
    ) : AapEvent {
        override fun equals(other: Any?): Boolean = other is Unknown && raw.contentEquals(other.raw)

        override fun hashCode(): Int = raw.contentHashCode()
    }
}

/**
 * Turns raw AAP packets into [AapEvent]s.
 *
 * Everything except opcode `0x17` is stateless, and the codec half is pure, so the
 * protocol surface stays unit-testable without a device — the only practical way to
 * validate a reverse-engineered format.
 *
 * `0x17` is the exception, and it has to be: it is a HID transport carrying several
 * sensor services at once, and which service a report belongs to is only knowable from
 * the descriptors the accessory sent earlier in the same session. So the decoder is an
 * instance with a session's worth of memory — one per channel — rather than an object.
 *
 * That memory is also what fixes an existing defect. `0x17` frames used to be routed by
 * *packet length*: anything 55 bytes or longer went to the head-tracking decoder, which
 * reads fixed offsets. Every service descriptor the accessory sends is longer than that,
 * so every one of them was decoded as a head pose made of garbage. Nothing noticed only
 * because head gestures are off by default (R-3).
 */
class AapDecoder(
    /** Injected so the timestamp anchor is testable; never read for anything else. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var services: List<HidService> = emptyList()
    private var heartRateServiceId: Int? = null
    private var headTrackingServiceId: Int? = null
    private var heartRateDecoder: HeartRateReportDecoder? = null
    private val anchor = HeartRateTimestampAnchor()

    /** Services the accessory has described so far this session. */
    val knownServices: List<HidService> get() = services

    /** The discovered heart-rate service id, or null until descriptors arrive (FR-002). */
    val discoveredHeartRateServiceId: Int? get() = heartRateServiceId

    /** The feature report id that carries the interval, from the accessory's descriptor. */
    val heartRateFeatureReportId: Int?
        get() = services.firstOrNull { it.isHeartRate }?.layout?.intervalFeatureReportId

    /**
     * The same, for head tracking.
     *
     * Read from the accessory's own descriptors rather than assumed. LibrePods hard-codes
     * `0x0E` here; that constant is true of one firmware and is exactly the bug FR-002
     * exists to prevent, so a model that does not describe the report gets no stream
     * rather than a guessed one.
     */
    val headTrackingFeatureReportId: Int?
        get() = services.firstOrNull { it.isHeadTracking }?.layout?.intervalFeatureReportId

    /**
     * True when a heart-rate service was found **and** its descriptor declares a
     * confidence field. Without one there is nothing to gate on and the feature reports
     * itself unsupported rather than showing numbers it cannot vouch for (R-2).
     */
    val canMeasureHeartRate: Boolean get() = heartRateDecoder?.isUsable == true

    /**
     * Forgets everything session-scoped.
     *
     * The timestamp anchor especially: nothing guarantees the accessory's counter
     * survives a disconnect, and carrying an anchor across sessions would put readings
     * at times derived from a counter that no longer means the same thing (R-4a).
     */
    fun resetSession() {
        services = emptyList()
        heartRateServiceId = null
        headTrackingServiceId = null
        heartRateDecoder = null
        anchor.reset()
    }

    fun decode(packet: ByteArray): AapEvent {
        val opcode = AapProtocol.opcodeOf(packet) ?: return AapEvent.Unknown(packet)
        val payload = AapProtocol.payloadOf(packet)

        return when (opcode) {
            Opcode.BATTERY -> decodeBattery(payload) ?: AapEvent.Unknown(packet)
            Opcode.EAR_DETECTION -> decodeEarDetection(payload) ?: AapEvent.Unknown(packet)
            Opcode.CONTROL -> decodeControl(payload) ?: AapEvent.Unknown(packet)
            Opcode.CONVERSATIONAL_AWARENESS -> decodeAwarenessLevel(payload) ?: AapEvent.Unknown(packet)
            Opcode.HEAD_TRACKING -> decodeHid(packet) ?: AapEvent.Unknown(packet)
            Opcode.DEVICE_INFO -> AapEvent.DeviceInfo(decodeNullTerminatedStrings(payload))
            else -> AapEvent.Unknown(packet)
        }
    }

    /**
     * Routes a `0x17` frame on the protobuf field it carries — descriptors, readiness or
     * an input report — and never on how long it is.
     */
    private fun decodeHid(packet: ByteArray): AapEvent? {
        if (packet.size <= HID_BODY_OFFSET) return null
        val body = packet.copyOfRange(HID_BODY_OFFSET, packet.size)

        HidDescriptorParser.services(body).takeIf { it.isNotEmpty() }?.let { discovered ->
            rememberServices(discovered)
            return AapEvent.HidServices(discovered)
        }

        HidDescriptorParser.readyServiceIds(body).takeIf { it.isNotEmpty() }?.let { ready ->
            return AapEvent.HidServicesReady(ready)
        }

        HidDescriptorParser.inputReport(body)?.let { (serviceId, report) ->
            return decodeInputReport(packet, serviceId, report)
        }

        // The accessory confirming which service it started. Surfaced so it stops being
        // unknown traffic — but deliberately *not* a state change: this says a command
        // was acted on, and only a report says the sensor is running (Principle I).
        HidDescriptorParser.startedServiceId(body)?.let { serviceId ->
            return AapEvent.HidServiceStarted(serviceId)
        }

        // Field 8 is our own start/stop request coming back. Nothing to report, and
        // certainly not a state change — a write is not a change (Principle I).
        if (HidDescriptorParser.isRequest(body)) return null
        return null
    }

    private fun decodeInputReport(
        packet: ByteArray,
        serviceId: Int,
        report: ByteArray,
    ): AapEvent {
        val reportId = report.firstOrNull()?.toInt()?.and(0xFF) ?: -1

        if (serviceId == heartRateServiceId) {
            val decoder =
                heartRateDecoder
                    ?: return AapEvent.UnhandledHidReport(serviceId, reportId, report.size, "no usable layout")
            return when (val result = decoder.decode(report, anchor, clock())) {
                is HeartRateDecodeResult.Decoded -> {
                    AapEvent.HeartRateReport(serviceId, result.reading)
                }

                is HeartRateDecodeResult.Unhandled -> {
                    AapEvent.UnhandledHidReport(serviceId, result.reportId, result.length, result.reason.name)
                }
            }
        }

        // Head tracking, once the descriptors have said which service it is. Before they
        // arrive, the historical length rule stands in — but only for frames that are
        // actually input reports, which is what stops descriptor frames reaching it.
        val isHeadTracking =
            serviceId == headTrackingServiceId ||
                (headTrackingServiceId == null && packet.size >= HEAD_TRACKING_MIN_BYTES)
        if (isHeadTracking) {
            return decodeHeadTracking(packet)
                ?: AapEvent.UnhandledHidReport(serviceId, reportId, report.size, "head pose too short")
        }

        return AapEvent.UnhandledHidReport(serviceId, reportId, report.size, "no decoder for this service")
    }

    /**
     * Seeds the decoder with services this accessory announced on an **earlier** channel.
     *
     * Not a shortcut, and not a violation of FR-002. The accessory announces its services
     * once per Bluetooth link, in answer to the first request after the link comes up — so
     * an app that restarts mid-link has lost the announcement and cannot get another one,
     * and until now that meant heart rate could not be switched on until the earbuds were
     * put back in the case. Remembering what *this accessory said about itself* is still
     * discovery; what FR-002 forbids is a constant that happens to match, and a
     * remembered descriptor is neither constant nor assumed.
     *
     * Ignored once a live announcement has arrived: the accessory's current word about
     * itself always outranks a remembered one.
     */
    fun restoreServices(remembered: List<HidService>) {
        if (services.isNotEmpty() || remembered.isEmpty()) return
        rememberServices(remembered)
    }

    /**
     * Merges an announcement into what this link has already described.
     *
     * **Not a replacement.** AirPods Pro 3 describe themselves in more than one frame —
     * one carrying `SPL0`, `HostLibHID` and the heart-rate service, another carrying
     * `devmotion6` alone — and treating each frame as the complete set means whichever
     * arrived last is the only one this decoder can see. That silently cost head tracking
     * its service id, and heart rate would have lost its own on any firmware that happened
     * to order the frames the other way.
     *
     * Newest wins per id, so a service that redescribes itself still updates. The whole
     * map is cleared in [reset] when the link goes, so nothing survives into a session
     * where it might no longer be true.
     */
    private fun rememberServices(discovered: List<HidService>) {
        val merged = LinkedHashMap<Int, HidService>()
        services.forEach { merged[it.id] = it }
        discovered.forEach { merged[it.id] = it }
        services = merged.values.toList()

        heartRateServiceId = services.firstOrNull { it.isHeartRate }?.id
        headTrackingServiceId = services.firstOrNull { it.isHeadTracking }?.id
        heartRateDecoder =
            services
                .firstOrNull { it.isHeartRate }
                ?.layout
                ?.let(::HeartRateReportDecoder)
                ?.takeIf { it.isUsable }
    }

    /**
     * Battery payload: a count, then that many 5-byte records of
     * `[component] 01 [level] [status] 01`.
     */
    private fun decodeBattery(payload: ByteArray): AapEvent.Battery? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        if (payload.size < 1 + count * RECORD_BYTES) return null

        var state = BatteryState()
        for (index in 0 until count) {
            val offset = 1 + index * RECORD_BYTES
            val component = payload[offset].toInt() and 0xFF
            val level = payload[offset + 2].toInt() and 0xFF
            val status = chargeStatus(payload[offset + 3].toInt() and 0xFF)

            val battery =
                BatteryComponent(
                    levelPercent = level.takeIf { it in 0..100 },
                    status = status,
                )
            state =
                when (component) {
                    COMPONENT_LEFT -> state.copy(left = battery)
                    COMPONENT_RIGHT -> state.copy(right = battery)
                    COMPONENT_CASE -> state.copy(case = battery)
                    else -> state
                }
        }
        return AapEvent.Battery(state)
    }

    private fun decodeEarDetection(payload: ByteArray): AapEvent.EarDetection? {
        if (payload.size < 2) return null
        return AapEvent.EarDetection(
            EarDetectionState(
                primary = wearState(payload[0].toInt() and 0xFF),
                secondary = wearState(payload[1].toInt() and 0xFF),
            ),
        )
    }

    /** Control packets are `[identifier] [data1] [data2] 00 00`. */
    private fun decodeControl(payload: ByteArray): AapEvent? {
        if (payload.size < 2) return null
        val command = ControlCommand.fromId(payload[0].toInt() and 0xFF) ?: return null
        val data1 = payload[1].toInt() and 0xFF

        return when (command) {
            ControlCommand.LISTENING_MODE -> {
                NoiseControlMode.fromWire(data1)?.let(AapEvent::NoiseControl)
            }

            ControlCommand.CONVERSATION_DETECT_CONFIG -> {
                Toggle.toBoolean(data1)?.let(AapEvent::ConversationalAwarenessState)
            }

            ControlCommand.AUTO_ANC_STRENGTH -> {
                AapEvent.AdaptiveNoiseStrength(data1.coerceIn(0, 100))
            }

            else -> {
                AapEvent.UnhandledControl(command, payload.copyOfRange(1, payload.size))
            }
        }
    }

    /** Awareness level packet: `02 00 01 [level]`, level 1..2 speaking, 8..9 normal. */
    private fun decodeAwarenessLevel(payload: ByteArray): AapEvent? {
        if (payload.size < 4) return null
        return AapEvent.ConversationalAwarenessLevel(payload[3].toInt() and 0xFF)
    }

    /**
     * Head-tracking sample.
     *
     * The offsets are absolute within the packet and are **unchanged** from before the
     * `0x17` routing was fixed. That is deliberate: where a head pose sits inside its
     * field-7 entry has never been captured, so re-deriving these offsets from the
     * protobuf structure would be arithmetic on an assumption. What changed is only
     * *which* frames reach here — input reports on the head-tracking service, rather
     * than anything at all that happened to be 55 bytes long.
     *
     * Re-deriving them properly needs one capture of a head-tracking frame alongside its
     * descriptors; until then, leaving them alone is the honest option.
     */
    private fun decodeHeadTracking(packet: ByteArray): AapEvent? {
        if (packet.size < HEAD_TRACKING_MIN_BYTES) return null

        fun le16(offset: Int): Short =
            (
                ((packet[offset + 1].toInt() and 0xFF) shl 8) or
                    (packet[offset].toInt() and 0xFF)
            ).toShort()

        return AapEvent.HeadTracking(
            HeadTrackingSample(
                orientation1 = le16(43),
                orientation2 = le16(45),
                orientation3 = le16(47),
                horizontalAcceleration = le16(51),
                verticalAcceleration = le16(53),
            ),
        )
    }

    /**
     * Device-info payload is a run of null-terminated UTF-8 strings — name, model,
     * manufacturer, serial and firmware — followed by an encrypted tail. Keeping
     * only printable segments drops that tail.
     */
    private fun decodeNullTerminatedStrings(payload: ByteArray): List<String> =
        payload
            .decodeToString()
            .split('\u0000')
            .map(String::trim)
            .filter { field -> field.isNotEmpty() && field.all { it.code in 0x20..0x7E } }

    private fun chargeStatus(value: Int): ChargeStatus =
        when (value) {
            0x01 -> ChargeStatus.CHARGING
            0x02 -> ChargeStatus.DISCHARGING
            0x04 -> ChargeStatus.DISCONNECTED
            else -> ChargeStatus.UNKNOWN
        }

    private fun wearState(value: Int): WearState =
        when (value) {
            0x00 -> WearState.IN_EAR
            0x01 -> WearState.OUT_OF_EAR
            0x02 -> WearState.IN_CASE
            else -> WearState.UNKNOWN
        }

    private companion object {
        const val RECORD_BYTES = 5
        const val COMPONENT_RIGHT = 0x02
        const val COMPONENT_LEFT = 0x04
        const val COMPONENT_CASE = 0x08

        /** Header, opcode and the `00 00 10 00 <length>` prefix a `0x17` frame carries. */
        const val HID_BODY_OFFSET = 12

        /**
         * The historical length rule, kept only as a fallback for frames that arrive
         * before the accessory has described its services. It is no longer the primary
         * test, and it no longer sees anything but input reports.
         */
        const val HEAD_TRACKING_MIN_BYTES = 55
    }
}
