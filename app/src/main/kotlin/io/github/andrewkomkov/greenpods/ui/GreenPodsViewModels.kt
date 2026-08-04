package io.github.andrewkomkov.greenpods.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.feature.controls.ControlsViewModel
import io.github.andrewkomkov.greenpods.feature.pods.PodsViewModel
import io.github.andrewkomkov.greenpods.feature.settings.SettingsViewModel

/**
 * View-model factories that pull from the manual container.
 *
 * The view models themselves take plain constructor parameters and know nothing about
 * this file — which is what keeps them constructible in a JVM unit test. All the
 * lookup lives here, in the one layer that is allowed to know the app exists.
 */
object GreenPodsViewModels {
    fun pods(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                val app = GreenPodsApplication.instance
                PodsViewModel(
                    repository = app.podRepository,
                    environment = app.environmentMonitor.state,
                )
            }
        }

    fun controls(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                val app = GreenPodsApplication.instance
                ControlsViewModel(
                    repository = app.podRepository,
                    gateway = app.controlGateway,
                )
            }
        }

    fun settings(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                val app = GreenPodsApplication.instance
                SettingsViewModel(
                    settingsRepository = app.settingsRepository,
                    podRepository = app.podRepository,
                    diagnosticsLog = app.diagnostics,
                    updateChecker = app.updateChecker,
                    appVersion = app.versionName,
                    health = app.healthConnectLink,
                )
            }
        }
}
