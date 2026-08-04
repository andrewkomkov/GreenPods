package io.github.andrewkomkov.greenpods.core.model

/** How aggressively the BLE radio is asked to scan. */
enum class ScanMode {
    /** Longest battery life, slowest to notice a case opening. */
    LOW_POWER,

    /** The default: a case opening is noticed within a few seconds. */
    BALANCED,

    /** Near-instant updates, noticeably more battery. */
    LOW_LATENCY,
}

/**
 * Every user preference, as one immutable value.
 *
 * Kept in `core/model` — free of Android and of I/O — so policies can be driven
 * directly by it in unit tests without touching DataStore.
 *
 * Defaults are chosen for the device this app actually runs on: auto-pause on, because
 * it works over the advertisement transport that every phone has; background monitoring
 * off, because a foreground service the user did not ask for is a hostile default.
 */
data class GreenPodsSettings(
    val autoPauseEnabled: Boolean = true,
    val autoResumeEnabled: Boolean = true,
    /**
     * When true, playback only pauses once *both* buds are out. Useful for people who
     * habitually listen with one bud; false matches Apple's own behaviour.
     */
    val pauseOnlyWhenBothOut: Boolean = false,
    val backgroundMonitoringEnabled: Boolean = false,
    val lowBatteryWarningEnabled: Boolean = true,
    val lowBatteryThresholdPercent: Int = DEFAULT_LOW_BATTERY_THRESHOLD,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val headGesturesEnabled: Boolean = false,
    val gestureBindings: List<HeadGestureBinding> = HeadGestureBinding.Defaults,
    /**
     * Off until the user asks for it (FR-011). An optical sensor costs the accessory's
     * battery, and a sensor that starts unasked and is discovered later is a worse
     * outcome than not shipping the feature at all.
     */
    val heartRateEnabled: Boolean = false,
    /** Separately controllable from displaying the reading (FR-017). */
    val heartRateHealthConnectEnabled: Boolean = false,
    /** The cadence asked of the accessory. A setting, so the battery cost is measurable. */
    val heartRateIntervalMillis: Int = DEFAULT_HR_INTERVAL_MILLIS,
    /**
     * The confidence byte at or above which a reading may be shown.
     *
     * **Provisional, and re-examined on 2026-08-04 after the feature ran on hardware.**
     * It stands, for a reason worth writing down rather than re-deriving each time.
     *
     * The bound has not moved: the settling readings carry 20 and the converged ones 156
     * and above, so anything in 21..156 separates them on the data that exists. 128 sits
     * in the middle of that interval, which is the honest choice when the interval is all
     * you know — it is equidistant from both failure modes, and neither a tighter nor a
     * looser value can be justified without a second session to argue from.
     *
     * Calibrating it properly needs the reference-monitor and battery sessions (T071,
     * T072) that have not been run. Choosing a number from *one* capture would replace a
     * value that is admittedly provisional with one that merely looks measured, which is
     * worse: the same digit carrying an unearned claim. It is a setting precisely so that
     * calibration stays a measurement rather than a rebuild — `gp --es cmd set --es key
     * hrConfidenceThreshold` (R-4).
     */
    val heartRateConfidenceThreshold: Int = DEFAULT_HR_CONFIDENCE_THRESHOLD,
) {
    /** Clamps anything a corrupted preference file could contain into a usable range. */
    fun sanitised(): GreenPodsSettings =
        copy(
            lowBatteryThresholdPercent = lowBatteryThresholdPercent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
            gestureBindings = gestureBindings.ifEmpty { HeadGestureBinding.Defaults },
            heartRateIntervalMillis = heartRateIntervalMillis.coerceIn(MIN_HR_INTERVAL_MILLIS, MAX_HR_INTERVAL_MILLIS),
            heartRateConfidenceThreshold = heartRateConfidenceThreshold.coerceIn(0, MAX_HR_CONFIDENCE),
        )

    companion object {
        const val DEFAULT_LOW_BATTERY_THRESHOLD = 20
        const val MIN_THRESHOLD = 5
        const val MAX_THRESHOLD = 50

        /** The cadence the 2026-08-04 capture ran at, and the one the decoder is pinned to. */
        const val DEFAULT_HR_INTERVAL_MILLIS = 1_000

        /** Faster than this is more traffic than the sensor produces readings. */
        const val MIN_HR_INTERVAL_MILLIS = 200

        /** Slower than a reading a minute stops being a live heart rate. */
        const val MAX_HR_INTERVAL_MILLIS = 60_000

        /** The confidence field is one byte, so this is its ceiling, not a policy choice. */
        const val MAX_HR_CONFIDENCE = 255

        const val DEFAULT_HR_CONFIDENCE_THRESHOLD = 128

        val Default = GreenPodsSettings()
    }
}
