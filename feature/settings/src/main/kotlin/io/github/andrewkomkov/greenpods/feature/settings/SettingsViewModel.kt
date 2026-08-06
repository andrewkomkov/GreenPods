package io.github.andrewkomkov.greenpods.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.health.HealthConnectLink
import io.github.andrewkomkov.greenpods.core.data.settings.SettingsRepository
import io.github.andrewkomkov.greenpods.core.data.update.UpdateSource
import io.github.andrewkomkov.greenpods.core.data.update.UpdateStatus
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.LiveActivityAvailability
import io.github.andrewkomkov.greenpods.core.model.PodFeature
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: GreenPodsSettings = GreenPodsSettings.Default,
    val deviceName: String = "",
    val podAddress: String? = null,
    val capabilities: CapabilitiesUiState = CapabilitiesUiState(),
    val appVersion: String = "",
    val checkingUpdate: Boolean = false,
    val updateSummary: String = "",
    val updateUrl: String? = null,
    val health: HealthUiState = HealthUiState(),
    val liveActivity: LiveActivityUiState = LiveActivityUiState(),
)

/**
 * Whether this phone can show the live status surface, and the sentence that says why not.
 *
 * The sentence is carried as a **resource id and its arguments**, not as text. Those
 * strings live in `app`, which a feature module must never depend on — but an id is an
 * `Int`, and holding one costs this module no `R` reference at all. The screen resolves it
 * with `stringResource` at the moment it draws, so the reason is rendered against the
 * configuration in force *then*. Text resolved once when the view model was built would be
 * pinned to that moment's locale, and a gate reason left in the previous language while the
 * screen around it changed is exactly the kind of staleness nothing would report.
 *
 * Most phones will never show this surface: it arrives in Android 16 and GreenPods
 * supports Android 8.0. That is exactly why the state carries a reason rather than a
 * Boolean — those phones are still owed the difference between "mine can't" and "GreenPods
 * is broken" (FR-014a, Principle II).
 */
data class LiveActivityUiState(
    val availability: LiveActivityAvailability = LiveActivityAvailability.Available,
    /** Zero when [availability] is `Available`; there is nothing to explain. */
    @StringRes val reasonRes: Int = 0,
    /**
     * What [reasonRes] interpolates, in order.
     *
     * Carried alongside the id rather than pre-formatted into a string, so that a reason
     * naming this phone's API level is as late-resolved as one that names nothing.
     */
    val reasonArgs: List<Any> = emptyList(),
) {
    val isAvailable: Boolean get() = availability.isAvailable

    /**
     * Whether there is a system screen to send the user back to.
     *
     * Only [LiveActivityAvailability.PromotionRefused] has one. An old platform cannot be
     * argued with, a denied notification permission is asked for in the app, and a device
     * that declined to promote *this* notification is reporting a fact about itself — none
     * of those is a screen, and offering a button that lands somewhere unrelated is worse
     * than offering none.
     */
    val hasRouteBack: Boolean get() = availability is LiveActivityAvailability.PromotionRefused
}

/**
 * Reads the live surface's availability, with the id of the reason to show for it.
 *
 * An interface rather than `LiveActivityGate` itself for two reasons: the reason ids are
 * resources this module cannot name, and the answer must be *read again* rather than
 * held — the one state the user can undo is undone on a system screen, so it changes
 * precisely while GreenPods is not in the foreground.
 */
fun interface LiveActivityStatusSource {
    fun read(): LiveActivityUiState
}

/**
 * What this phone can do with these earbuds, in features rather than in transports.
 *
 * The screen used to list the three transports and their availability, which answered a
 * question only a maintainer asks. A user asks whether noise control works, and the
 * honest answer to that has nothing to do with the word L2CAP: it is a list of what
 * works, a list of what does not, and one sentence about the phone.
 */
data class CapabilitiesUiState(
    val alwaysWorks: List<String> = emptyList(),
    val available: List<String> = emptyList(),
    val locked: List<LockedCapabilities> = emptyList(),
    /** True once an accessory has been seen; before that there is nothing to report. */
    val known: Boolean = false,
) {
    /** Head gestures ride head tracking, which is the first thing a stock stack refuses. */
    val headGesturesLocked: Boolean
        get() = locked.any { group -> PodFeature.HEAD_TRACKING.displayName in group.features }
}

/** Features that are out of reach for the same reason, and that reason. */
data class LockedCapabilities(
    val features: List<String>,
    val sentence: String,
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
 * Settings, what this phone can reach, and updates.
 *
 * Undecoded protocol traffic still reaches `DiagnosticsLog` — it is how new protocol
 * behaviour gets found, and nothing about that has changed. What changed is where it is
 * read: from adb, by whoever can act on it, rather than from a card underneath someone's
 * auto-pause switch. A hex dump on a product screen is not transparency; it is a
 * maintainer's console left in the room.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val podRepository: PodRepository,
    private val updateChecker: UpdateSource,
    private val appVersion: String,
    /**
     * Where the live surface's availability comes from, reason and all.
     *
     * Not optional and not defaulted: a default would have to claim one state or the
     * other, and "available" is the answer that would quietly ship a switch this phone
     * cannot honour.
     */
    private val liveActivity: LiveActivityStatusSource,
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

    /**
     * Read once at construction so the section never renders an "unknown" state.
     *
     * The gate answers synchronously — three platform questions, no I/O — so there is no
     * loading state to invent, and inventing one would put a spinner where a sentence
     * belongs.
     */
    private val liveActivityState = MutableStateFlow(liveActivity.read())

    private data class UpdateUi(
        val checking: Boolean = false,
        val summary: String = "",
        val url: String? = null,
    )

    val state: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            podRepository.primaryPod,
            updateState,
            healthState,
            liveActivityState,
        ) { settings, pod, update, healthUi, live ->
            SettingsUiState(
                settings = settings,
                deviceName = pod?.name.orEmpty(),
                podAddress = pod?.address,
                capabilities = pod.capabilities(),
                appVersion = appVersion,
                checkingUpdate = update.checking,
                updateSummary = update.summary,
                updateUrl = update.url,
                health = healthUi,
                liveActivity = live,
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

    /**
     * Re-reads whether the live surface can be shown.
     *
     * Never cached across a resume, for the same reason the health state is not: the one
     * unavailable state a user can undo — promotion refused — is undone on a *system*
     * screen this app sends them to, so the answer changes exactly while GreenPods is in
     * the background. A cached "refused" would then tell them their own fix did not work.
     */
    fun refreshLiveActivity() {
        liveActivityState.value = liveActivity.read()
    }

    fun setLiveActivityEnabled(enabled: Boolean) = edit { it.copy(liveActivityEnabled = enabled) }

    /**
     * Whether the live surface may show the heart-rate *value*.
     *
     * It hides the number and never the disclosure (FR-018a). The two are not one setting
     * with two effects: a user may decline to display their heart rate on a screen anyone
     * could read, and may not have the accessory's optical sensor run without being told
     * that it is running. Nothing here touches `SensingState.Disclosed`.
     */
    fun setLiveActivityShowHeartRate(enabled: Boolean) = edit { it.copy(liveActivityShowHeartRate = enabled) }

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

    /**
     * The accessory's features, split into what works here and what does not.
     *
     * Ear detection is folded into the line that is true on every phone rather than
     * listed as a capability of its own: it rides the advertisement, which needs no
     * pairing and no permission the app does not already hold, so it can be promised
     * without qualification. Everything else is grouped by *why* it is locked, because
     * two features refused by two different transports are two different situations and
     * one of them may be fixable.
     */
    private fun PodState?.capabilities(): CapabilitiesUiState {
        if (this == null) return CapabilitiesUiState()

        val locked =
            gatedFeatures
                .groupBy { feature -> lockSentenceFor(feature) }
                .map { (sentence, features) ->
                    LockedCapabilities(features.map { it.displayName }.distinct().sorted(), sentence)
                }.sortedBy { it.sentence }

        return CapabilitiesUiState(
            alwaysWorks = listOf(ALWAYS_WORKS),
            available =
                usableFeatures
                    .filter { it != PodFeature.EAR_DETECTION }
                    .map { it.displayName }
                    // Both heart-rate routes are called "Heart rate" now, and a model
                    // that offers both must not be told it has two of them.
                    .distinct()
                    .sorted(),
            locked = locked,
            known = true,
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * What no phone can take away.
         *
         * These three ride Apple's proximity advertisement, which is broadcast to
         * everything in range and needs no pairing, no channel and no permission beyond
         * the scan the app already asks for. It is worth stating on its own line: it is
         * the floor under every other answer on this screen.
         */
        const val ALWAYS_WORKS = "Battery, ear detection and auto-pause"
    }
}
