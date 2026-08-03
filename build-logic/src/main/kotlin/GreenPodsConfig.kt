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

    const val JVM_TOOLCHAIN = 21
}
