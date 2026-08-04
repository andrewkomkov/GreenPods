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

    /**
     * Heart rate as a HID sensor report over AAP opcode 0x17. Requires the L2CAP
     * transport. The sensor's service id is discovered from the accessory's own
     * descriptors, never assumed — see `docs/protocol-research.md`.
     */
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

    /**
     * Whether GreenPods can actually *do* this feature once its transport is live.
     *
     * A transport being open is necessary but not sufficient. Every feature listed here
     * currently clears both bars; the property stays because the moment a model is added
     * with a capability GreenPods can see but not use, reporting it as usable would be a
     * promise the app cannot keep.
     */
    val isImplemented: Boolean
        get() = true

    /** Human label. Enum names must never reach the screen. */
    val displayName: String
        get() =
            when (this) {
                EAR_DETECTION -> "Ear detection"

                NOISE_CONTROL -> "Noise control"

                ADAPTIVE_AUDIO -> "Adaptive audio"

                CONVERSATIONAL_AWARENESS -> "Conversational awareness"

                SPATIAL_AUDIO -> "Spatial audio"

                HEAD_TRACKING -> "Head tracking"

                EAR_TIP_FIT_TEST -> "Ear tip fit test"

                CASE_SPEAKER -> "Case speaker"

                VOLUME_SWIPE -> "Volume swipe"

                HEARING_AID -> "Hearing aid"

                // Both routes are called what they are. Which one carried a given
                // reading is a difference the *card* states, in terms of whether the
                // earbuds grade their own readings — never by putting a protocol's name
                // on screen, which is the one thing the product UI may not do.
                HEART_RATE_AAP -> "Heart rate"

                HEART_RATE_GATT -> "Heart rate"
            }

    /** One line on what the feature is, shown under a locked chip. */
    val explanation: String
        get() =
            when (this) {
                EAR_DETECTION -> {
                    "Knows when a bud is in your ear, in the case, or out."
                }

                NOISE_CONTROL -> {
                    "Switch between off, noise cancellation and transparency."
                }

                ADAPTIVE_AUDIO -> {
                    "Blends cancellation and transparency by surroundings."
                }

                CONVERSATIONAL_AWARENESS -> {
                    "Lowers the volume when you start speaking."
                }

                SPATIAL_AUDIO -> {
                    "Fixed-in-space audio rendering, set by the source device."
                }

                HEAD_TRACKING -> {
                    "Streams head orientation, which head gestures are built on."
                }

                EAR_TIP_FIT_TEST -> {
                    "Checks the seal of the silicone tips."
                }

                CASE_SPEAKER -> {
                    "Plays a locating tone from the case."
                }

                VOLUME_SWIPE -> {
                    "Volume by sliding a finger along the stem."
                }

                HEARING_AID -> {
                    "Clinical-grade hearing assistance profile."
                }

                HEART_RATE_AAP -> {
                    "Reads the optical sensor over Apple's protocol — no workout and " +
                        "no Apple device needed."
                }

                HEART_RATE_GATT -> {
                    "Standard Bluetooth heart-rate profile — works without root."
                }
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
    ;

    val displayName: String
        get() =
            when (this) {
                BLE_ADVERTISEMENT -> "Bluetooth advertisement"
                GATT -> "Bluetooth GATT"
                AAP_L2CAP -> "Apple protocol (L2CAP)"
            }

    /**
     * What a locked feature is allowed to say on a product screen.
     *
     * The precise reason a transport failed — a refused PSM, a blocked reflective call —
     * is real and worth keeping, and [TransportStatus.reason] keeps it for the diagnostics
     * log and for adb. It is not, however, something a person who wanted to see their
     * battery level should have to read. This is the same fact stated as a property of
     * their phone, which is the only part of it they can act on.
     */
    val lockSentence: String
        get() =
            when (this) {
                BLE_ADVERTISEMENT -> {
                    "Every phone can hear what AirPods broadcast, so this always works."
                }

                GATT -> {
                    "This phone can't reach the sensor in your earbuds."
                }

                AAP_L2CAP -> {
                    "This phone won't let GreenPods send commands to your earbuds."
                }
            }
}
