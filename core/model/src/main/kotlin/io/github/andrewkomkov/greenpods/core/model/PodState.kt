package io.github.andrewkomkov.greenpods.core.model

/** Charge state of one battery component, as reported by both transports. */
enum class ChargeStatus { UNKNOWN, CHARGING, DISCHARGING, DISCONNECTED }

/**
 * Battery for a single component. [levelPercent] is null when the component is
 * disconnected or the accessory reports the "unknown" sentinel (0x0F over BLE).
 */
data class BatteryComponent(
    val levelPercent: Int?,
    val status: ChargeStatus,
) {
    val isCharging: Boolean get() = status == ChargeStatus.CHARGING

    companion object {
        val Unknown = BatteryComponent(null, ChargeStatus.UNKNOWN)
    }
}

/** Which physical part of the accessory a battery reading belongs to. */
enum class PodComponent {
    LEFT,
    RIGHT,
    CASE,
    ;

    val displayName: String
        get() =
            when (this) {
                LEFT -> "Left"
                RIGHT -> "Right"
                CASE -> "Case"
            }
}

data class BatteryState(
    val left: BatteryComponent = BatteryComponent.Unknown,
    val right: BatteryComponent = BatteryComponent.Unknown,
    val case: BatteryComponent = BatteryComponent.Unknown,
) {
    /** Lowest known bud level — what a status bar or widget should surface. */
    val lowestBudPercent: Int?
        get() = listOfNotNull(left.levelPercent, right.levelPercent).minOrNull()

    operator fun get(component: PodComponent): BatteryComponent =
        when (component) {
            PodComponent.LEFT -> left
            PodComponent.RIGHT -> right
            PodComponent.CASE -> case
        }
}

/** Where a single bud currently is. */
enum class WearState { IN_EAR, OUT_OF_EAR, IN_CASE, UNKNOWN }

data class EarDetectionState(
    val primary: WearState = WearState.UNKNOWN,
    val secondary: WearState = WearState.UNKNOWN,
) {
    val anyInEar: Boolean get() = primary == WearState.IN_EAR || secondary == WearState.IN_EAR
    val bothInEar: Boolean get() = primary == WearState.IN_EAR && secondary == WearState.IN_EAR
}

/**
 * Listening mode. Wire values match AAP control command 0x0D and are also used as
 * the bitmask positions for 0x1A (ListeningModeConfigs), where the mask is
 * `1 shl (wireValue - 1)`.
 */
enum class NoiseControlMode(
    val wireValue: Int,
) {
    OFF(0x01),
    NOISE_CANCELLATION(0x02),
    TRANSPARENCY(0x03),
    ADAPTIVE(0x04),
    ;

    /** Bit for this mode inside the ListeningModeConfigs (0x1A) bitmask. */
    val configBit: Int get() = 1 shl (wireValue - 1)

    companion object {
        fun fromWire(value: Int): NoiseControlMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** What a long press on the stem cycles through. Wire values for control 0x16. */
enum class StemLongPressAction(
    val wireValue: Int,
) {
    NOISE_CONTROL(0x01),
    VOICE_ASSISTANT(0x05),
    ;

    companion object {
        fun fromWire(value: Int): StemLongPressAction? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Head orientation and acceleration sample from AAP opcode 0x17. */
data class HeadTrackingSample(
    val orientation1: Short,
    val orientation2: Short,
    val orientation3: Short,
    val horizontalAcceleration: Short,
    val verticalAcceleration: Short,
)

/**
 * A heart-rate reading and where it came from.
 *
 * [Source.GATT] is the standard Bluetooth SIG Heart Rate Profile and is the only
 * source obtainable on an unrooted device; Powerbeats Pro 2 is currently the sole
 * Apple-family model that broadcasts it.
 *
 * [Source.AAP] is reachable in principle over L2CAP — the sensor is toggled with
 * control command 0x30 — but the measurement frame layout is not publicly
 * reverse-engineered yet, so no decoder ships for it.
 */
data class HeartRateSample(
    val beatsPerMinute: Int,
    val source: Source,
) {
    enum class Source { GATT, AAP }
}

/** Everything GreenPods currently knows about one accessory. */
data class PodState(
    val address: String,
    val model: PodModel,
    val name: String = model.displayName,
    val battery: BatteryState = BatteryState(),
    val earDetection: EarDetectionState = EarDetectionState(),
    val noiseControlMode: NoiseControlMode? = null,
    val conversationalAwarenessEnabled: Boolean? = null,
    val adaptiveNoiseStrength: Int? = null,
    val heartRate: HeartRateSample? = null,
    val rssi: Int? = null,
    /**
     * Transports currently live for this accessory. These are independent, not a
     * ladder: a GATT heart-rate connection and an AAP channel can be open at once,
     * and passive advertisement decoding continues regardless.
     */
    val activeTransports: Set<Transport> = setOf(Transport.BLE_ADVERTISEMENT),
    /**
     * Why each transport is or is not live. Purely explanatory: [activeTransports]
     * stays the authority for what is usable, so a missing status can never
     * accidentally unlock a feature.
     */
    val transportStatuses: List<TransportStatus> = listOf(TransportStatus.AdvertisementAvailable),
    val lastSeenEpochMillis: Long = 0L,
) {
    /**
     * Hardware features that are *actually usable right now*: the model must have
     * them and a live transport must be able to carry them.
     */
    val usableFeatures: Set<PodFeature>
        get() =
            model.features.filterTo(mutableSetOf()) { feature ->
                feature.isImplemented && feature.requiredTransport in activeTransports
            }

    /** Features the hardware has but no live transport can reach. */
    val gatedFeatures: Set<PodFeature>
        get() = model.features - usableFeatures

    fun statusOf(transport: Transport): TransportStatus =
        transportStatuses.firstOrNull { it.transport == transport }
            ?: TransportStatus.notProbed(transport)

    /**
     * The sentence to show next to a locked feature. Falls back to the transport's own
     * description when nothing more specific was recorded, so a lock is never mute.
     */
    fun reasonFor(feature: PodFeature): String {
        // An unimplemented feature is locked by GreenPods itself, not by the phone, and
        // saying "your Bluetooth stack refuses it" would be a lie.
        if (!feature.isImplemented) return feature.explanation

        val status = statusOf(feature.requiredTransport)
        return status.reason.ifBlank { "Needs the ${feature.requiredTransport.displayName} transport." }
    }
}
