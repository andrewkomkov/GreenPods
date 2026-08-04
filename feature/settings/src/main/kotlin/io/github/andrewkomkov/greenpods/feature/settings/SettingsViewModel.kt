package io.github.andrewkomkov.greenpods.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.health.HealthConnectLink
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
import kotlinx.coroutines.flow.update
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
    val health: HealthUiState = HealthUiState(),
)

/**
 * What the Health Connect section knows about itself.
 *
 * Availability and permission are separate because they fail differently: a missing
 * provider is a fact about the phone, a denied permission is a decision the user made
 * and may not want asked about again (FR-018, FR-020).
 */
data class HealthUiState(
    val availabilitySentence: String = "",
    val isAvailable: Boolean = false,
    val hasWritePermission: Boolean = false,
    /**
     * True once GreenPods has asked and been refused in this session.
     *
     * The UI then explains where to grant it rather than asking again. "Does not nag" is
     * a requirement, and the only way to honour it is to remember having asked.
     */
    val permissionRefused: Boolean = false,
    val deletionMessage: String = "",
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
    /**
     * Null where no health store is wired at all.
     *
     * The section then reports itself unavailable rather than vanishing — a missing
     * section reads as a bug, a section that explains itself reads as the phone
     * (Principle II, FR-020).
     */
    private val health: HealthConnectLink? = null,
) : ViewModel() {
    private val updateState = MutableStateFlow(UpdateUi())
    private val healthState = MutableStateFlow(HealthUiState())

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
            healthState,
        ) { settings, pod, events, update, healthUi ->
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
                health = healthUi,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SettingsUiState())

    init {
        refreshHealthState()
    }

    /**
     * Re-reads availability and permission from the provider.
     *
     * Never cached across a session: users grant and revoke these permissions from
     * outside GreenPods entirely, and Health Connect can be updated or removed under an
     * app that never restarted.
     */
    fun refreshHealthState() {
        val link = health ?: return
        viewModelScope.launch {
            val availability = link.availability()
            healthState.update { current ->
                current.copy(
                    availabilitySentence = availability.sentence,
                    isAvailable = availability.isAvailable,
                    hasWritePermission = link.hasWritePermission(),
                )
            }
        }
    }

    /** The permissions to hand to the launcher. Plain strings — see AD-11. */
    fun healthPermissions(): Set<String> = health?.requiredPermissions().orEmpty()

    /**
     * Records what the system's permission dialog answered.
     *
     * A refusal is remembered rather than acted on: FR-018 forbids re-prompting, so the
     * section switches to explaining where the permission lives instead of asking again.
     */
    fun onHealthPermissionResult(granted: Set<String>) {
        val wanted = healthPermissions()
        val allowed = wanted.isNotEmpty() && granted.containsAll(wanted)
        healthState.update { it.copy(permissionRefused = !allowed) }
        refreshHealthState()
    }

    fun setHeartRateEnabled(enabled: Boolean) = edit { it.copy(heartRateEnabled = enabled) }

    fun setHeartRateHealthConnect(enabled: Boolean) = edit { it.copy(heartRateHealthConnectEnabled = enabled) }

    fun setHeartRateInterval(millis: Int) = edit { it.copy(heartRateIntervalMillis = millis) }

    /**
     * Deletes the records GreenPods itself wrote.
     *
     * The message afterwards says plainly that anything already in the health store is
     * managed there. Presenting "delete our records" as "clear your history" would be a
     * promise the app cannot keep (FR-025).
     */
    fun deleteHealthRecords() {
        val link = health ?: return
        viewModelScope.launch {
            val deleted = link.deleteOwnRecords()
            healthState.update {
                it.copy(
                    deletionMessage =
                        if (deleted) {
                            "Deleted the heart-rate records GreenPods wrote. Anything else in " +
                                "Health Connect is managed there."
                        } else {
                            "Nothing to delete — GreenPods has not written to Health Connect."
                        },
                )
            }
        }
    }

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
