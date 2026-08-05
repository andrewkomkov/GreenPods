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

/**
 * Where each bud is, **by side**.
 *
 * Named left and right rather than primary and secondary on purpose. The
 * advertisement describes a primary bud and a secondary one, and which physical side
 * that is flips — either bud can be the one talking to the phone. Battery already
 * resolved that flag and wear did not, so a screen that labelled the primary bud
 * "Left" told half its users the wrong ear. Resolving it once, here at the edge, is
 * the only way a caller cannot get it wrong.
 */
data class EarDetectionState(
    val left: WearState = WearState.UNKNOWN,
    val right: WearState = WearState.UNKNOWN,
) {
    val anyInEar: Boolean get() = left == WearState.IN_EAR || right == WearState.IN_EAR
    val bothInEar: Boolean get() = left == WearState.IN_EAR && right == WearState.IN_EAR
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
    /**
     * What the heart-rate session last published.
     *
     * Read [heartRate] instead of this in the UI: a session's state only means
     * anything once the model has a sensor *and* a live transport can reach it, and
     * [heartRate] is what applies that gate.
     */
    val heartRateSession: HeartRateState = HeartRateState.Off,
    /**
     * Counts and reasons, never values. This is what the state dump and `hr status`
     * print, which is why it is a separate type from the state (FR-028).
     */
    val heartRateSensing: HeartRateSensing = HeartRateSensing.Idle,
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

    /**
     * Which heart-rate route this accessory offers, preferring one that is actually
     * reachable right now. Null when the model has no sensor of either kind.
     *
     * The two routes are listed in preference order rather than merged: FR-004 forbids
     * blending them, and picking one is not the same as falling back between them —
     * whichever is chosen, the reading it produces records its own source.
     */
    val heartRateFeature: PodFeature?
        get() =
            HEART_RATE_ROUTES.firstOrNull { it in usableFeatures }
                ?: HEART_RATE_ROUTES.firstOrNull { it in model.features }

    /**
     * Heart rate as the screen must show it, with the transport gate already applied.
     *
     * SC-008's two cases are two states here, not two strings the UI assembles:
     * [HeartRateState.Unsupported] is a fact about the earbuds, [HeartRateState.Locked]
     * a fact about this phone, and neither can be mistaken for the other or for a
     * session that is simply switched off.
     */
    val heartRate: HeartRateState
        get() {
            val route = heartRateFeature ?: return HeartRateState.Unsupported(NO_SENSOR_REASON)
            if (route !in usableFeatures) {
                return HeartRateState.Locked(reasonFor(route), route.requiredTransport)
            }
            return heartRateSession
        }

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

    /**
     * The same lock, said to a person rather than to a maintainer.
     *
     * [reasonFor] answers "what exactly did the stack refuse", which is the question the
     * diagnostics log and the adb dump exist for. A product screen is answering a
     * different one — "can I do this on this phone, and is anything wrong with my
     * earbuds" — and the precise answer is worse at it, because it needs the reader to
     * know what a PSM is before it means anything.
     */
    fun lockSentenceFor(feature: PodFeature): String =
        if (!feature.isImplemented) feature.explanation else feature.requiredTransport.lockSentence

    private companion object {
        /** AAP first: it is the route more models have, and the one that carries confidence. */
        val HEART_RATE_ROUTES = listOf(PodFeature.HEART_RATE_AAP, PodFeature.HEART_RATE_GATT)

        const val NO_SENSOR_REASON = "These earbuds have no heart-rate sensor."
    }
}
