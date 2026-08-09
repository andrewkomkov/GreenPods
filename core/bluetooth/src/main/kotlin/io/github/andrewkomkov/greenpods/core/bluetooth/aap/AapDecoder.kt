package io.github.andrewkomkov.greenpods.core.bluetooth.aap

import android.util.Log
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.WearState

/** A decoded message from the accessory. */
sealed interface AapEvent {
    data class Battery(
        val state: BatteryState,
    ) : AapEvent

    /**
     * Two buds' wear, in an order the frame does not explain — **and not by side**.
     *
     * Deliberately not an `EarDetectionState`: that type is keyed by side, and the side is
     * not in this frame. See [AapDecoder.decodeEarDetection].
     */
    data class EarDetection(
        val first: WearState,
        val second: WearState,
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
        if (packet.size <= AapProtocol.HID_BODY_OFFSET) return null
        val body = packet.copyOfRange(AapProtocol.HID_BODY_OFFSET, packet.size)

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
            return decodeHeadTracking(report)
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

    /**
     * Two buds' wear states, in an order that tracks **role, not side**.
     *
     * This used to be read as left-then-right, in the order the advertisement uses, on the
     * reasoning that the only thing that could be wrong was the swap. Two captures on
     * 2026-08-05 (Pixel 8, Android 17, AirPods Pro 3, live channel) say otherwise.
     *
     * First: taking out the **left** bud moved the second byte, and so did taking out the
     * **right** one, with the first byte at `0x00` throughout. That alone kills both
     * left-then-right and right-then-left — a pair where either bud drives the same byte
     * cannot be a pair of sides.
     *
     * Then, with the left bud out and left out, the frame changed from `01 00` to `00 01`
     * ten seconds later — the same physical state, encoded the other way round. The order
     * had moved while nothing physical had. The reading that fits both captures is that
     * these are the primary and secondary buds, and the role passes between them; a bud
     * leaving an ear is exactly when it would.
     *
     * So both states are decoded and **neither is attributed to a side**, because within
     * this frame nothing can be. Per-side wear comes from the advertisement, which carries
     * the primary flag and can therefore say which bud is which.
     */
    private fun decodeEarDetection(payload: ByteArray): AapEvent.EarDetection? {
        if (payload.size < 2) return null
        return AapEvent.EarDetection(
            first = wearState(payload[0].toInt() and 0xFF),
            second = wearState(payload[1].toInt() and 0xFF),
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
     * Head-tracking sample, read from the **input report** rather than from the packet.
     *
     * The offsets used to be absolute within the packet, and that was wrong in a way that
     * hid itself. The protobuf carries a sequence counter in field 1, and a varint takes
     * one byte up to 127 and two from 128 — so everything after it, the report included,
     * shifts by a byte partway through every stream, about five seconds in at 25 Hz.
     * Measured on 2026-08-05: the report starts at packet offset 22 while the counter is
     * 16..127 and at 23 from 128 onward, exactly at the boundary. A decoder reading
     * packet offsets therefore read one pair of bytes for the first few seconds and a
     * different, one-byte-shifted pair thereafter, for the same physical pose. Half of
     * those reads straddled two values, which is where a 195° head tilt came from.
     *
     * Reading from the report removes the drift entirely: the protobuf says where the
     * report begins, so nothing has to be assumed about what precedes it.
     *
     * The offsets **within** the report are still not derived, and are deliberately named
     * `orientation1..3` rather than yaw, pitch and roll. The devmotion descriptor declares
     * this report as an 8-byte timestamp followed by an opaque vendor blob (usage page
     * `0xFF0C`) — it does **not** break out orientation fields, so there is no declared
     * range or unit to read and no scale to derive from it. What is known is empirical:
     * these three signed 16-bit values move with the head, they are where this decoder
     * already read for most of a stream, and they are cross-coupled — nodding moves more
     * than one of them. That last fact is why `HeadPoseMapper.SCALE` cannot be fixed by
     * choosing a better constant: a per-axis linear scale is the wrong model for values
     * that do not vary independently. See `docs/protocol-research.md` and
     * `specs/004-head-tracking-calibration`.
     */
    private fun decodeHeadTracking(report: ByteArray): AapEvent? {
        if (report.size < HEAD_TRACKING_MIN_REPORT_BYTES) return null

        // The whole report, when somebody explicitly asks for it.
        //
        // Five int16 are decoded below out of a report the descriptor declares as 182 bytes,
        // so about 170 bytes have never been looked at — and one candidate in there, four
        // consecutive int16 holding a near-constant norm, is the shape of a quaternion. If it
        // is one, the accessory is already sending an orientation and none of the calibration
        // machinery is needed.
        //
        // `AapTransport` withholds HID report bodies from its log, and rightly: heart rate
        // rides the same opcode and no path may print one. This is scoped to the **motion**
        // report specifically, so a heart-rate report can never reach it — the privacy floor
        // is about heart rate, not about bytes in general. It is off unless the tag is
        // enabled by hand (`adb shell setprop log.tag.AapMotionRaw DEBUG`), which is a
        // deliberate act by someone at a terminal.
        if (Log.isLoggable(MOTION_RAW_TAG, Log.DEBUG)) {
            Log.d(MOTION_RAW_TAG, report.joinToString(" ") { "%02X".format(it) })
        }

        fun le16(offset: Int): Short =
            (
                ((report[offset + 1].toInt() and 0xFF) shl 8) or
                    (report[offset].toInt() and 0xFF)
            ).toShort()

        return AapEvent.HeadTracking(
            HeadTrackingSample(
                orientation1 = le16(20),
                orientation2 = le16(22),
                orientation3 = le16(24),
                horizontalAcceleration = le16(28),
                verticalAcceleration = le16(30),
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

        /**
         * The historical length rule, kept only as a fallback for frames that arrive
         * before the accessory has described its services. It is no longer the primary
         * test, and it no longer sees anything but input reports.
         */
        const val HEAD_TRACKING_MIN_BYTES = 55

        /**
         * The shortest report the head-pose fields fit in — the last one read ends at 31.
         *
         * Against the report, not the packet: what precedes the report is a varint whose
         * width changes with the sequence counter, so a length test on the packet moves
         * with it. Captured reports from AirPods Pro 3 are 58 bytes.
         */
        const val HEAD_TRACKING_MIN_REPORT_BYTES = 32

        /**
         * Tag for the raw motion report, off unless enabled by hand.
         *
         * Deliberately not the transport's tag: enabling that one would print every frame,
         * heart-rate reports included, which is the thing that must never happen.
         */
        const val MOTION_RAW_TAG = "AapMotionRaw"
    }
}
