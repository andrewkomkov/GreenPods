package io.github.andrewkomkov.greenpods

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodScanner
import io.github.andrewkomkov.greenpods.core.data.GreenPodsStore
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.AapControlGateway
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.ear.AndroidPlaybackActuator
import io.github.andrewkomkov.greenpods.core.data.ear.EarDetectionController
import io.github.andrewkomkov.greenpods.core.data.environment.AndroidEnvironmentMonitor
import io.github.andrewkomkov.greenpods.core.data.head.HeadCalibrationStore
import io.github.andrewkomkov.greenpods.core.data.health.AndroidHealthStoreClient
import io.github.andrewkomkov.greenpods.core.data.health.HealthConnectLink
import io.github.andrewkomkov.greenpods.core.data.heartrate.AndroidGattHeartRateSource
import io.github.andrewkomkov.greenpods.core.data.heartrate.HeartRateController
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityGate
import io.github.andrewkomkov.greenpods.core.data.live.LiveActivityPlatform
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.transport.AndroidAapProbe
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodIdentity
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.data.transport.HidServiceMemory
import io.github.andrewkomkov.greenpods.core.data.transport.TransportGate
import io.github.andrewkomkov.greenpods.core.data.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container.
 *
 * GreenPods has a handful of singletons and no need for compile-time DI; a plain lazy
 * container keeps the build free of annotation processors, which matters more here
 * than the ergonomics of constructor injection.
 *
 * The construction order encodes the architecture: the diagnostics log depends on
 * nothing, the transport gate depends on it, the repository depends on the gate, and
 * nothing at all depends on the UI.
 */
class GreenPodsApplication : Application() {
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val diagnostics: DiagnosticsLog by lazy { DiagnosticsLog() }

    /** Settings and the accessory's own description of itself, sharing one store. */
    private val store: GreenPodsStore by lazy { GreenPodsStore(this) }

    val settingsRepository: SettingsRepository get() = store.settings

    /**
     * Whether this phone can show the live status surface, and why not when it cannot.
     *
     * In the container rather than in the service because the settings screen and the adb
     * surface both need the same answer, and two places computing it independently is how
     * a user ends up being told two different reasons for one thing.
     */
    val liveActivityGate: LiveActivityGate by lazy {
        LiveActivityGate(
            object : LiveActivityPlatform {
                override val apiLevel: Int get() = Build.VERSION.SDK_INT

                override fun notificationsPermitted(): Boolean =
                    ContextCompat.checkSelfPermission(
                        this@GreenPodsApplication,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                override fun promotedNotificationsPermitted(): Boolean =
                    if (Build.VERSION.SDK_INT >= LiveActivityGate.MIN_SDK) {
                        getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
                    } else {
                        false
                    }
            },
        )
    }

    /**
     * What each accessory has said about its own sensor services.
     *
     * Persisted because the announcement happens once per Bluetooth link: an app that
     * restarts while the earbuds stay connected can never obtain it again on that link,
     * and without this heart rate could not be switched on until they were put back in
     * the case.
     */
    val hidServiceMemory: HidServiceMemory get() = store.hidServices

    /**
     * What a head calibration measured, per accessory model.
     *
     * On the same store as everything else, for the reason `GreenPodsStore` gives: DataStore
     * serialises through one writer per file, so a second store on that file corrupts it.
     * Exposed the way `settingsRepository` and `hidServiceMemory` are — the app module holds
     * the container and never names a `DataStore`.
     *
     * Read by the calibration wizard, by the state dump and by `HeadPoseMapper` through
     * `HeadTrackingController`. A model with nothing stored is not an error here: it reads
     * back as uncalibrated, and the mapper falls through to its labelled approximation.
     */
    val headCalibrationStore: HeadCalibrationStore get() = store.headCalibrations

    val transportGate: TransportGate by lazy {
        TransportGate(diagnostics = diagnostics, aapProbe = AndroidAapProbe(this, diagnostics))
    }

    val podRepository: PodRepository by lazy {
        PodRepository(
            source = PodScanner(this),
            gate = transportGate,
            diagnostics = diagnostics,
            scope = applicationScope,
            settings = settingsRepository.settings,
            // Without this, every address rotation orphans the overlay, the open-channel
            // record and the heart-rate session at once — see PodIdentity.
            identity = podIdentity,
        )
    }

    /** One name for the accessory, across the address rotations it does constantly. */
    val podIdentity: BondedPodIdentity by lazy { BondedPodIdentity(BondedPodResolver(this)) }

    val environmentMonitor: AndroidEnvironmentMonitor by lazy { AndroidEnvironmentMonitor(this) }

    val controlGateway: AapControlGateway by lazy {
        AapControlGateway(
            context = this,
            repository = podRepository,
            diagnostics = diagnostics,
            scope = applicationScope,
            serviceMemory = hidServiceMemory,
        )
    }

    /**
     * The health store, or a link that reports it absent.
     *
     * Constructed unconditionally: availability is a question the link answers, not a
     * reason not to build it. A phone without Health Connect still shows a heart rate,
     * and the settings screen still has a section explaining why the integration is
     * unavailable (FR-020).
     */
    val healthConnectLink: HealthConnectLink by lazy {
        HealthConnectLink(
            client = AndroidHealthStoreClient(this),
            settings = settingsRepository.settings,
        )
    }

    /**
     * The heart-rate session.
     *
     * It is given the repository's event stream rather than a transport of its own: the
     * `0x17` channel is shared, and a second session would not be the session everything
     * else uses.
     */
    val heartRateController: HeartRateController by lazy {
        HeartRateController(
            pods = podRepository.pods,
            aapEvents = podRepository.aapEvents,
            settings = settingsRepository.settings,
            commands = controlGateway,
            gatt = AndroidGattHeartRateSource(this),
            sink = healthConnectLink,
            publish = podRepository::onHeartRateState,
        )
    }

    val earDetectionController: EarDetectionController by lazy {
        EarDetectionController(
            pods = podRepository.pods,
            settings = settingsRepository.settings,
            actuator = AndroidPlaybackActuator(this),
            diagnostics = diagnostics,
        )
    }

    val updateChecker: UpdateChecker by lazy { UpdateChecker(currentVersionName = versionName) }

    /**
     * Read from the package manager rather than from `BuildConfig`, so the value is
     * whatever is actually installed — which is what an update check must compare.
     */
    val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "unknown" }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GreenPodsApplication
            private set
    }
}
