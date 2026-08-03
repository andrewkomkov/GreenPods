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
) {
    /** Clamps anything a corrupted preference file could contain into a usable range. */
    fun sanitised(): GreenPodsSettings =
        copy(
            lowBatteryThresholdPercent = lowBatteryThresholdPercent.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
            gestureBindings = gestureBindings.ifEmpty { HeadGestureBinding.Defaults },
        )

    companion object {
        const val DEFAULT_LOW_BATTERY_THRESHOLD = 20
        const val MIN_THRESHOLD = 5
        const val MAX_THRESHOLD = 50

        val Default = GreenPodsSettings()
    }
}
