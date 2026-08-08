package io.github.andrewkomkov.greenpods.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.head.CalibrationSession
import io.github.andrewkomkov.greenpods.core.data.head.HeadCalibrationStore
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.PodModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the calibration wizard shows.
 *
 * [step] is the session's own state, passed through rather than translated. The wizard is a
 * state machine that lives in `core/data` precisely so that the same run can be driven from
 * `adb`, and a second copy of "where the run is" kept here would be a second thing to be
 * wrong about it.
 *
 * [stored] is what is already remembered for this accessory — the thing the wizard is
 * offering to replace. It is deliberately separate from [step]: a run in progress must not
 * appear to have changed anything until it is saved, because nothing is.
 */
data class HeadCalibrationUiState(
    val model: PodModel? = null,
    val streaming: Boolean = false,
    val refusal: HeadTrackingController.Refusal? = null,
    val step: CalibrationSession.State = CalibrationSession.State.Idle,
    val stored: HeadCalibration? = null,
    /** Axes whose implausible scale is still waiting on a decision — [save] refuses while any remain. */
    val unconfirmedSuspects: Set<HeadAxis> = emptySet(),
    val savedAtEpochMillis: Long = 0L,
    /**
     * A run was thrown away because the accessory changed under it.
     *
     * Carried beside [step] rather than folded into it, because [step] is the session's own
     * state and this is a fact about the session that no longer exists. Cleared when the next
     * run starts.
     */
    val abandonedOnSwap: Boolean = false,
) {
    /** Locked, not hidden: the screen still says what it would do and why it cannot. */
    val isLocked: Boolean get() = refusal != null

    val canStart: Boolean get() = !isLocked && model != null
}

/**
 * Runs the calibration wizard for as long as its screen is on screen.
 *
 * Two collections, both tied to the view model's own scope. One is the orientation stream —
 * a cold flow, so collecting it is what starts the sensor in the earbuds and leaving the
 * screen is what stops it. The other is which accessory is primary, because a calibration
 * belongs to a model and a run that changed accessory half way through would attribute one
 * head's poses to whichever pair happened to be nearest at the end.
 *
 * The **raw** sample is what reaches the session, never the mapped pose. Calibration derives
 * the units-to-degrees mapping, so feeding it values that have already been through that
 * mapping would measure the app's own approximation and report it as a measurement of a head.
 */
class HeadCalibrationViewModel(
    private val samples: () -> Flow<HeadTrackingController.Sample>,
    models: Flow<PodModel?>,
    private val calibrations: HeadCalibrationStore,
    private val clock: () -> Long = System::currentTimeMillis,
    settings: Flow<GreenPodsSettings> = flowOf(GreenPodsSettings.Default),
    private val newSession: (PodModel, GreenPodsSettings) -> CalibrationSession = CalibrationSession::from,
) : ViewModel() {
    /**
     * The settings a session is built from, kept as a plain value.
     *
     * The plateau tolerance and the hold duration are provisional (research R-6) and settable,
     * and a session is configured once when it is created — so what matters is the value in
     * hand at that moment, not a stream. Changing either mid-run would judge the second half
     * of a run by different numbers from the first, which is worse than needing to start
     * again.
     */
    private var settingsNow: GreenPodsSettings = GreenPodsSettings.Default
    private val _state = MutableStateFlow(HeadCalibrationUiState())
    val state: StateFlow<HeadCalibrationUiState> = _state.asStateFlow()

    private var session: CalibrationSession? = null

    init {
        viewModelScope.launch { settings.collect { settingsNow = it } }
        viewModelScope.launch { models.collect(::onModel) }

        viewModelScope.launch {
            runCatching {
                _state.update { it.copy(streaming = true, refusal = null) }
                samples().collect(::onSample)
            }.onSuccess {
                // The flow completed rather than refusing: the stream went away while it was
                // running. A partial run is never stored as a completed one.
                _state.update { it.copy(streaming = false) }
                session?.onStreamEnded("the earbuds stopped sending head movement")
                publish()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val refusal =
                    (error as? HeadTrackingController.NotStreaming)?.refusal
                        ?: HeadTrackingController.Refusal.UNAVAILABLE
                _state.update { it.copy(streaming = false, refusal = refusal) }
                session?.onStreamEnded("head movement is not being read here")
                publish()
            }
        }
    }

    /**
     * The primary accessory changed (T058).
     *
     * A run under way is abandoned rather than carried over, and said to have been. A
     * calibration is stored per model, so poses performed while one pair was reporting and
     * finished while another was would be written under the second pair's name — not a stale
     * measurement of it but a fabricated one, indistinguishable afterwards from a real one.
     *
     * The check is on the model rather than on the address for the same reason the store is
     * keyed that way (FR-021): two identical pairs report identically, and swapping between
     * them changes nothing a scale depends on.
     */
    private suspend fun onModel(model: PodModel?) {
        val previous = _state.value.model
        if (model == previous) return

        // A first observation is not a swap: the wizard has no run to lose before it knows
        // which accessory it is talking to.
        val lostRun = previous != null && session?.state != CalibrationSession.State.Idle

        session?.abandon()
        session = model?.let { newSession(it, settingsNow) }
        val stored = model?.let { calibrations.load(it) }
        _state.update { it.copy(model = model, stored = stored, abandonedOnSwap = lostRun) }
        publish()
    }

    private fun onSample(sample: HeadTrackingController.Sample) {
        session?.onSample(sample.raw, clock())
        _state.update { it.copy(streaming = true) }
        publish()
    }

    /** Begins a run. Anything already stored stays stored until [save]. */
    fun start() {
        session?.start()
        _state.update { it.copy(abandonedOnSwap = false) }
        publish()
    }

    /** Starts the countdown for the pose currently being shown. */
    fun beginHold() {
        session?.advance(clock())
        publish()
    }

    /** Skips the current pose. Its axis stores nothing and says on the final screen that it was skipped. */
    fun skip() {
        session?.skip()
        publish()
    }

    /** Re-runs the current pose, without restarting the whole wizard. */
    fun repeat() {
        session?.repeat()
        publish()
    }

    /** Ends the run and stores nothing. */
    fun abandon() {
        session?.abandon()
        publish()
    }

    /** Keeps an implausible scale, after the arithmetic behind it has been shown. */
    fun confirmSuspect(axis: HeadAxis) {
        session?.confirmSuspect(axis)
        publish()
    }

    /**
     * Stores the run.
     *
     * Refuses while an unconfirmed suspect is present — the session decides that, not this,
     * so the adb path and the screen cannot disagree about when a number may be kept.
     */
    fun save() {
        val current = session ?: return
        val now = clock()
        val calibration = current.finish(now)
        publish()
        if (calibration == null) return
        viewModelScope.launch {
            calibrations.save(calibration)
            _state.update { it.copy(stored = calibration, savedAtEpochMillis = now) }
        }
    }

    private fun publish() {
        val current = session
        _state.update {
            it.copy(
                step = current?.state ?: CalibrationSession.State.Idle,
                unconfirmedSuspects = current?.unconfirmedSuspects() ?: emptySet(),
            )
        }
    }
}
