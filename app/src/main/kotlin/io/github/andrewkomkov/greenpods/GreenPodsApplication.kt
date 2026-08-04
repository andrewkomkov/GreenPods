package io.github.andrewkomkov.greenpods

import android.app.Application
import io.github.andrewkomkov.greenpods.core.bluetooth.ble.PodScanner
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.control.AapControlGateway
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.ear.AndroidPlaybackActuator
import io.github.andrewkomkov.greenpods.core.data.ear.EarDetectionController
import io.github.andrewkomkov.greenpods.core.data.environment.AndroidEnvironmentMonitor
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.transport.AndroidAapProbe
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

    val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(this) }

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
        )
    }

    val environmentMonitor: AndroidEnvironmentMonitor by lazy { AndroidEnvironmentMonitor(this) }

    val controlGateway: AapControlGateway by lazy {
        AapControlGateway(
            context = this,
            repository = podRepository,
            diagnostics = diagnostics,
            scope = applicationScope,
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
