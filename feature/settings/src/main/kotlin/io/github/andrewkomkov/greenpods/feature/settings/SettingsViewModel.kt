package io.github.andrewkomkov.greenpods.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.update.UpdateSource
import io.github.andrewkomkov.greenpods.core.data.update.UpdateStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: GreenPodsSettings = GreenPodsSettings.Default,
    val transports: List<TransportStatus> = emptyList(),
    val deviceName: String = "",
    val podAddress: String? = null,
    val diagnostics: List<DiagnosticEvent> = emptyList(),
    val appVersion: String = "",
    val checkingUpdate: Boolean = false,
    val updateSummary: String = "",
    val updateUrl: String? = null,
)

/**
 * Settings, diagnostics and updates.
 *
 * The diagnostics half is not a debugging afterthought — it is where the transport gate
 * explains itself and where undecoded protocol traffic surfaces. For a project built on
 * reverse-engineering, that log is how the next packet definition gets found, so it is
 * a first-class part of the screen rather than a hidden developer option.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val podRepository: PodRepository,
    private val diagnosticsLog: DiagnosticsLog,
    private val updateChecker: UpdateSource,
    private val appVersion: String,
) : ViewModel() {
    private val updateState = MutableStateFlow(UpdateUi())

    private data class UpdateUi(
        val checking: Boolean = false,
        val summary: String = "",
        val url: String? = null,
    )

    val state: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            podRepository.primaryPod,
            diagnosticsLog.events,
            updateState,
        ) { settings, pod, events, update ->
            SettingsUiState(
                settings = settings,
                transports = pod?.transportStatuses.orEmpty(),
                deviceName = pod?.name.orEmpty(),
                podAddress = pod?.address,
                diagnostics = events,
                appVersion = appVersion,
                checkingUpdate = update.checking,
                updateSummary = update.summary,
                updateUrl = update.url,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SettingsUiState())

    fun setAutoPause(enabled: Boolean) = edit { it.copy(autoPauseEnabled = enabled) }

    fun setAutoResume(enabled: Boolean) = edit { it.copy(autoResumeEnabled = enabled) }

    fun setPauseOnlyWhenBothOut(enabled: Boolean) = edit { it.copy(pauseOnlyWhenBothOut = enabled) }

    fun setBackgroundMonitoring(enabled: Boolean) = edit { it.copy(backgroundMonitoringEnabled = enabled) }

    fun setLowBatteryWarning(enabled: Boolean) = edit { it.copy(lowBatteryWarningEnabled = enabled) }

    fun setLowBatteryThreshold(percent: Int) = edit { it.copy(lowBatteryThresholdPercent = percent) }

    fun setScanMode(mode: ScanMode) = edit { it.copy(scanMode = mode) }

    fun setHeadGesturesEnabled(enabled: Boolean) = edit { it.copy(headGesturesEnabled = enabled) }

    fun updateBinding(binding: HeadGestureBinding) {
        viewModelScope.launch { settingsRepository.updateBinding(binding) }
    }

    fun clearDiagnostics() = diagnosticsLog.clear()

    /**
     * Re-runs the transport probe for the accessory currently in range.
     *
     * Uses the address already in state rather than re-reading the pod flow, so that
     * pressing a button in settings cannot start a Bluetooth scan as a side effect.
     */
    fun recheckTransports() {
        val address = state.value.podAddress ?: return
        viewModelScope.launch { podRepository.probeAap(address, force = true) }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateState.value = UpdateUi(checking = true)
            updateState.value =
                when (val status = updateChecker.check()) {
                    is UpdateStatus.UpToDate -> {
                        UpdateUi(summary = "GreenPods $appVersion is the latest release.")
                    }

                    is UpdateStatus.Available -> {
                        UpdateUi(
                            summary = "Version ${status.release.versionName} is available.\n\n${status.release.notes}",
                            url = status.release.apkUrl ?: status.release.htmlUrl,
                        )
                    }

                    is UpdateStatus.Failed -> {
                        UpdateUi(summary = "Could not check for updates: ${status.reason}")
                    }
                }
        }
    }

    private fun edit(transform: (GreenPodsSettings) -> GreenPodsSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
