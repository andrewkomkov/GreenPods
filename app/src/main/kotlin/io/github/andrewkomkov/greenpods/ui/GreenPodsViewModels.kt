package io.github.andrewkomkov.greenpods.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.andrewkomkov.greenpods.GreenPodsApplication
import io.github.andrewkomkov.greenpods.R
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.github.andrewkomkov.greenpods.feature.controls.ControlsViewModel
import io.github.andrewkomkov.greenpods.feature.pods.PodsViewModel
import io.github.andrewkomkov.greenpods.feature.settings.HeadCalibrationViewModel
import io.github.andrewkomkov.greenpods.feature.settings.HeadGestureViewModel
import io.github.andrewkomkov.greenpods.feature.settings.LiveActivityUiState
import io.github.andrewkomkov.greenpods.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.map

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
                    settings = app.settingsRepository,
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

    fun headGestures(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                val app = GreenPodsApplication.instance
                HeadGestureViewModel(
                    controller =
                        HeadTrackingController(
                            repository = app.podRepository,
                            gateway = app.controlGateway,
                            serviceMemory = app.hidServiceMemory,
                            diagnostics = app.diagnostics,
                            scope = app.applicationScope,
                        ),
                )
            }
        }

    /**
     * The calibration wizard.
     *
     * Its own [HeadTrackingController], not the trainer's: the controller is a cold flow whose
     * collection starts and stops the sensor in the earbuds, so sharing one instance between
     * two screens would tie one screen's sensor to the other's lifetime.
     *
     * The store is passed to the controller as well as to the view model. The wizard measures
     * from `Sample.raw` and so is unaffected by it — but the pose the controller derives
     * alongside is what every other consumer reads, and a controller built without the store
     * would quietly report uncalibrated angles for the rest of the session.
     */
    fun headCalibration(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                val app = GreenPodsApplication.instance
                val controller =
                    HeadTrackingController(
                        repository = app.podRepository,
                        gateway = app.controlGateway,
                        serviceMemory = app.hidServiceMemory,
                        diagnostics = app.diagnostics,
                        scope = app.applicationScope,
                        calibrations = app.headCalibrationStore,
                    )
                HeadCalibrationViewModel(
                    samples = { controller.stream() },
                    models = app.podRepository.primaryPod.map { it?.model },
                    calibrations = app.headCalibrationStore,
                    settings = app.settingsRepository.settings,
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
                    updateChecker = app.updateChecker,
                    appVersion = app.versionName,
                    // Read on every call, never captured: the gate's answer changes while
                    // the user is away in the system's own notification settings.
                    liveActivity = { liveActivityState(app.liveActivityGate.availability()) },
                    health = app.healthConnectLink,
                )
            }
        }

    /**
     * Which sentence explains an unavailable live surface — as an id, not as text.
     *
     * The mapping lives here because only this module can name `R`; feature modules carry
     * no resources, and having one see `app`'s `R` would invert the dependency direction
     * the constitution fixes. What crosses the boundary is an `Int` and its arguments, so
     * the screen resolves the sentence against the configuration in force when it draws
     * rather than against whatever locale was live when this factory ran.
     *
     * Every branch is spelled out, with no `else`: a new availability state must fail to
     * compile here rather than reach a user as an empty card.
     *
     * `internal` so `GreenPodsViewModelsTest` can pin which sentence belongs to which
     * state. The settings module's tests can only see that each state carries *a* distinct
     * id, because they cannot name `R` — and that check would pass just as happily with two
     * states wired to each other's reason, which is a user being told, confidently, the
     * wrong thing about their own phone.
     */
    internal fun liveActivityState(availability: LiveActivityAvailability): LiveActivityUiState =
        when (availability) {
            LiveActivityAvailability.Available -> {
                LiveActivityUiState(availability)
            }

            is LiveActivityAvailability.PlatformTooOld -> {
                LiveActivityUiState(
                    availability,
                    R.string.live_unavailable_platform,
                    listOf(availability.apiLevel),
                )
            }

            LiveActivityAvailability.PromotionRefused -> {
                LiveActivityUiState(availability, R.string.live_unavailable_promotion_refused)
            }

            LiveActivityAvailability.NotificationsDenied -> {
                LiveActivityUiState(availability, R.string.live_unavailable_notifications_denied)
            }

            is LiveActivityAvailability.NotPromotable -> {
                LiveActivityUiState(
                    availability,
                    R.string.live_unavailable_not_promotable,
                    listOf(availability.reason),
                )
            }
        }
}
