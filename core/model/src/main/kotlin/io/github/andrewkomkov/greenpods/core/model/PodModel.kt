package io.github.andrewkomkov.greenpods.core.model

/**
 * Known Apple / Beats accessories, keyed by the model id Apple puts in its
 * BLE proximity-pairing advertisement (manufacturer data, company 0x004C, type 0x07).
 *
 * The id is bytes 3..4 of the payload read big-endian, exactly as they appear on
 * the air — so AirPods Pro read as `0x0E20`, not the byte-swapped `0x200E` that
 * some older write-ups use. Codes cross-checked against CAPod's device registry.
 */
enum class PodModel(
    val modelId: Int,
    val displayName: String,
    val features: Set<PodFeature>,
) {
    AIRPODS_1(0x0220, "AirPods (1st gen)", setOf(PodFeature.EAR_DETECTION)),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)", setOf(PodFeature.EAR_DETECTION)),
    AIRPODS_3(0x1320, "AirPods (3rd gen)", setOf(PodFeature.EAR_DETECTION, PodFeature.SPATIAL_AUDIO)),
    AIRPODS_4(0x1920, "AirPods 4", setOf(PodFeature.EAR_DETECTION, PodFeature.SPATIAL_AUDIO)),
    AIRPODS_4_ANC(
        0x1B20,
        "AirPods 4 (ANC)",
        setOf(
            PodFeature.EAR_DETECTION,
            PodFeature.SPATIAL_AUDIO,
            PodFeature.NOISE_CONTROL,
            PodFeature.ADAPTIVE_AUDIO,
            PodFeature.CONVERSATIONAL_AWARENESS,
            PodFeature.CASE_SPEAKER,
        ),
    ),
    AIRPODS_PRO_1(
        0x0E20,
        "AirPods Pro",
        setOf(
            PodFeature.EAR_DETECTION,
            PodFeature.SPATIAL_AUDIO,
            PodFeature.NOISE_CONTROL,
            PodFeature.EAR_TIP_FIT_TEST,
        ),
    ),
    AIRPODS_PRO_2(
        0x1420,
        "AirPods Pro 2",
        setOf(
            PodFeature.EAR_DETECTION,
            PodFeature.SPATIAL_AUDIO,
            PodFeature.HEAD_TRACKING,
            PodFeature.NOISE_CONTROL,
            PodFeature.ADAPTIVE_AUDIO,
            PodFeature.CONVERSATIONAL_AWARENESS,
            PodFeature.EAR_TIP_FIT_TEST,
            PodFeature.CASE_SPEAKER,
            PodFeature.VOLUME_SWIPE,
            PodFeature.HEARING_AID,
        ),
    ),
    AIRPODS_PRO_2_USBC(0x2420, "AirPods Pro 2 (USB-C)", AIRPODS_PRO_2.features),
    AIRPODS_PRO_3(0x2720, "AirPods Pro 3", AIRPODS_PRO_2.features + PodFeature.HEART_RATE_AAP),
    AIRPODS_MAX(
        0x0A20,
        "AirPods Max",
        setOf(PodFeature.NOISE_CONTROL, PodFeature.SPATIAL_AUDIO, PodFeature.HEAD_TRACKING),
    ),
    AIRPODS_MAX_USBC(0x1F20, "AirPods Max (USB-C)", AIRPODS_MAX.features),
    AIRPODS_MAX_2(0x2D20, "AirPods Max (2nd gen)", AIRPODS_MAX.features),
    POWERBEATS_3(0x0320, "Powerbeats 3", emptySet()),
    POWERBEATS_4(0x0D20, "Powerbeats 4", emptySet()),
    POWERBEATS_PRO(0x0B20, "Powerbeats Pro", setOf(PodFeature.EAR_DETECTION)),
    POWERBEATS_PRO_2(
        0x1D20,
        "Powerbeats Pro 2",
        setOf(
            PodFeature.EAR_DETECTION,
            PodFeature.NOISE_CONTROL,
            PodFeature.CONVERSATIONAL_AWARENESS,
            PodFeature.HEART_RATE_AAP,
            // The only Apple-family model that also exposes the *standard* BLE Heart
            // Rate Profile, making it the one heart-rate source reachable without AAP.
            PodFeature.HEART_RATE_GATT,
        ),
    ),
    BEATS_FIT_PRO(0x1220, "Beats Fit Pro", setOf(PodFeature.EAR_DETECTION, PodFeature.NOISE_CONTROL)),
    BEATS_FLEX(0x1020, "Beats Flex", emptySet()),
    BEATS_SOLO_3(0x0620, "Beats Solo 3", emptySet()),
    BEATS_SOLO_4(0x2520, "Beats Solo 4", emptySet()),
    BEATS_SOLO_BUDS(0x2620, "Beats Solo Buds", emptySet()),
    BEATS_SOLO_PRO(0x0C20, "Beats Solo Pro", setOf(PodFeature.NOISE_CONTROL)),
    BEATS_STUDIO_BUDS(0x1120, "Beats Studio Buds", setOf(PodFeature.NOISE_CONTROL)),
    BEATS_STUDIO_BUDS_PLUS(0x1620, "Beats Studio Buds +", setOf(PodFeature.NOISE_CONTROL)),
    BEATS_STUDIO_PRO(0x1720, "Beats Studio Pro", setOf(PodFeature.NOISE_CONTROL)),
    BEATS_X(0x0520, "BeatsX", emptySet()),
    UNKNOWN(-1, "Unknown Apple accessory", emptySet()),
    ;

    /** Over-ear models report a single battery pair instead of two independent buds. */
    val isSplitBud: Boolean
        get() =
            this !in
                setOf(AIRPODS_MAX, AIRPODS_MAX_USBC, AIRPODS_MAX_2, BEATS_SOLO_3, BEATS_SOLO_4, BEATS_STUDIO_PRO)

    companion object {
        private val byId: Map<Int, PodModel> = entries.associateBy(PodModel::modelId)

        fun fromModelId(modelId: Int): PodModel = byId[modelId] ?: UNKNOWN
    }
}

/**
 * A capability the *hardware* may have. Whether GreenPods can actually reach it
 * additionally depends on the active transport — see [PodFeature.requiredTransport].
 */
enum class PodFeature {
    EAR_DETECTION,
    NOISE_CONTROL,
    ADAPTIVE_AUDIO,
    CONVERSATIONAL_AWARENESS,
    SPATIAL_AUDIO,
    HEAD_TRACKING,
    EAR_TIP_FIT_TEST,
    CASE_SPEAKER,
    VOLUME_SWIPE,
    HEARING_AID,

    /** Heart rate over AAP control command 0x30. Requires the L2CAP transport. */
    HEART_RATE_AAP,

    /** Heart rate over the standard BLE Heart Rate Profile (0x180D). Works unrooted. */
    HEART_RATE_GATT,
    ;

    /** The minimum transport needed to observe or control this feature. */
    val requiredTransport: Transport
        get() =
            when (this) {
                EAR_DETECTION -> Transport.BLE_ADVERTISEMENT
                HEART_RATE_GATT -> Transport.GATT
                else -> Transport.AAP_L2CAP
            }
}

/**
 * How GreenPods talks to the accessory, ordered from "always available" to
 * "available only on a permissive Bluetooth stack".
 */
enum class Transport {
    /**
     * Passive decoding of Apple's proximity-pairing BLE advertisements.
     * Read-only, needs no pairing, works on every unrooted Android device.
     */
    BLE_ADVERTISEMENT,

    /**
     * Standard GATT connection, used for the Bluetooth SIG Heart Rate Profile that
     * Powerbeats Pro 2 exposes. Works on every unrooted Android device.
     */
    GATT,

    /**
     * Apple Accessory Protocol over an L2CAP channel on PSM 0x1001. The only
     * transport that can *write* settings (noise control, gestures, head tracking…).
     *
     * Android's public `createInsecureL2capChannel` rejects PSM >= 0x0100, and the
     * hidden `createInsecureL2capSocket` is refused by real AirPods on most stacks.
     * Availability is probed at runtime and never assumed.
     */
    AAP_L2CAP,
}
