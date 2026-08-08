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
    /**
     * Whether the monitoring notification may be promoted to a live status surface.
     *
     * On by default, and that is not the usual "off until asked". It replaces a
     * notification the user already sees rather than adding one, so switching it on
     * interrupts nobody — the argument that keeps [heartRateEnabled] off does not apply.
     */
    val liveActivityEnabled: Boolean = true,
    /**
     * Whether the live surface may show the heart rate itself while a session is running.
     *
     * Turning this off hides the **value** and never the disclosure. A user may decline to
     * display their heart rate on a screen anyone could read; they may not have the
     * accessory's optical sensor run without being told it is running.
     */
    val liveActivityShowHeartRate: Boolean = true,
    /**
     * How far the raw orientation values may wander and still count as a held pose.
     *
     * **Provisional, and a setting for the same reason [heartRateConfidenceThreshold] is.**
     * 900 comes from this feature's own requirements checklist, which recorded that plateaus
     * in the motivating session were found at that tolerance — and that session was captured
     * through a decoder bug that has since been fixed. It is a starting point, not a
     * measurement, and the only way to replace it with one is to run the wizard against a
     * real head at several values. That has to stay a measurement rather than a rebuild:
     * `gp --es cmd set --es key calibrationToleranceUnits --es value 1200`.
     *
     * It is not only the plateau's threshold. `CalibrationSolver` takes the same number as its
     * minimum response, because a response smaller than the amount a *stationary* head is
     * allowed to wander is not a response — so raising it makes both the hold stricter to
     * fail and the response harder to claim, together, which is the coupling that makes it
     * one number rather than two.
     */
    val calibrationToleranceUnits: Int = DEFAULT_CALIBRATION_TOLERANCE_UNITS,
    /**
     * How long a pose must stay inside the tolerance before it counts as held.
     *
     * Provisional on the same evidence, and adjustable for the same reason. Below a second
     * there is not enough of a plateau to take a median from; above about ten the wizard is
     * asking for a pose nobody holds still.
     */
    val calibrationHoldMillis: Long = DEFAULT_CALIBRATION_HOLD_MILLIS,
) {
    /** Clamps anything a corrupted preference file could contain into a usable range. */
    fun sanitised(): GreenPodsSettings =
        copy(
            lowBatteryThresholdPercent = lowBatteryThresholdPercent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
            gestureBindings = gestureBindings.ifEmpty { HeadGestureBinding.Defaults },
            heartRateIntervalMillis = heartRateIntervalMillis.coerceIn(MIN_HR_INTERVAL_MILLIS, MAX_HR_INTERVAL_MILLIS),
            heartRateConfidenceThreshold = heartRateConfidenceThreshold.coerceIn(0, MAX_HR_CONFIDENCE),
            calibrationToleranceUnits =
                calibrationToleranceUnits.coerceIn(MIN_CALIBRATION_TOLERANCE_UNITS, MAX_CALIBRATION_TOLERANCE_UNITS),
            calibrationHoldMillis =
                calibrationHoldMillis.coerceIn(MIN_CALIBRATION_HOLD_MILLIS, MAX_CALIBRATION_HOLD_MILLIS),
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

        /** See [calibrationToleranceUnits] — provisional, from the motivating session. */
        const val DEFAULT_CALIBRATION_TOLERANCE_UNITS = 900

        /**
         * Rails, not policy. Below 50 units nothing a real sensor produces would ever settle;
         * above a sixth of the int16 range the tolerance would swallow the pose itself.
         */
        const val MIN_CALIBRATION_TOLERANCE_UNITS = 50
        const val MAX_CALIBRATION_TOLERANCE_UNITS = 5_000

        /** See [calibrationHoldMillis] — provisional, and the countdown the wizard shows. */
        const val DEFAULT_CALIBRATION_HOLD_MILLIS = 2_000L

        const val MIN_CALIBRATION_HOLD_MILLIS = 500L
        const val MAX_CALIBRATION_HOLD_MILLIS = 10_000L

        val Default = GreenPodsSettings()
    }
}
