/**
 * Single source of truth for SDK levels and the JVM toolchain.
 *
 * `minSdk` is 26 because the BLE advertisement transport needs
 * `ScanFilter.setManufacturerData` semantics that are stable from Oreo onward.
 * The AAP/L2CAP transport additionally requires API 29 and is gated at runtime,
 * not at compile time — see `AapTransport`.
 */
object GreenPodsConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 26

    /** API level at which `BluetoothDevice.createInsecureL2capChannel` exists. */
    const val L2CAP_MIN_SDK = 29

    /**
     * API level at which a notification can be promoted to a Live Update.
     *
     * Android 16. Well above [MIN_SDK], so most installs will never see the richer
     * surface — gated at runtime like L2CAP, and reported as a gate with a reason rather
     * than as a missing feature.
     */
    const val LIVE_ACTIVITY_MIN_SDK = 36

    const val JVM_TOOLCHAIN = 21
}
