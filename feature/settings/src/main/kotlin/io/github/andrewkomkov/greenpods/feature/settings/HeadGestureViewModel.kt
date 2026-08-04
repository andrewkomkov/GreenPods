package io.github.andrewkomkov.greenpods.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.andrewkomkov.greenpods.core.data.head.HeadTrackingController
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * What the head-gesture trainer knows.
 *
 * [nodProgress] and [shakeProgress] are the point of the screen. A gesture either fires
 * or it does not, and the gap between those two outcomes is where people give up — they
 * nod, nothing happens, and they have no way to tell whether they moved too little, too
 * slowly, or in the wrong axis. These are how far the current movement has got towards
 * the threshold the detector is actually using, so "not enough" becomes visible while it
 * is still happening.
 */
data class HeadGestureUiState(
    val streaming: Boolean = false,
    val refusal: HeadTrackingController.Refusal? = null,
    val pose: HeadPose = HeadPose.Level,
    val nodProgress: Float = 0f,
    val shakeProgress: Float = 0f,
    /** The most recent recognised gesture, and when — the screen flashes on a change. */
    val lastGesture: HeadGesture? = null,
    val lastGestureAtMillis: Long = 0L,
    val recognisedCount: Int = 0,
) {
    val isLocked: Boolean get() = refusal != null
}

/**
 * Runs a head-tracking session for as long as the trainer screen is on screen.
 *
 * The session is tied to the view model's own scope rather than started and stopped by
 * the composable, so a recomposition cannot restart the sensor in someone's earbuds and
 * leaving the screen — which clears the view model — is what switches it off.
 */
class HeadGestureViewModel(
    private val controller: HeadTrackingController,
) : ViewModel() {
    private val _state = MutableStateFlow(HeadGestureUiState())
    val state: StateFlow<HeadGestureUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                _state.update { it.copy(streaming = true, refusal = null) }
                controller.stream().collect(::onSample)
            }.onFailure { error ->
                val refusal =
                    (error as? HeadTrackingController.NotStreaming)?.refusal
                        ?: HeadTrackingController.Refusal.UNAVAILABLE
                _state.update { it.copy(streaming = false, refusal = refusal) }
            }
        }
    }

    private fun onSample(sample: HeadTrackingController.Sample) {
        _state.update { current ->
            current.copy(
                streaming = true,
                pose = sample.pose,
                // Absolute excursion from level, as a fraction of what the detector
                // demands. Clamped, because past the threshold there is nothing more to
                // report — the gesture has either completed or it has not.
                nodProgress = (abs(sample.pose.pitchDegrees) / NOD_THRESHOLD_DEGREES).coerceIn(0f, 1f),
                shakeProgress = (abs(sample.pose.yawDegrees) / SHAKE_THRESHOLD_DEGREES).coerceIn(0f, 1f),
                lastGesture = sample.gesture?.gesture ?: current.lastGesture,
                lastGestureAtMillis = sample.gesture?.atEpochMillis ?: current.lastGestureAtMillis,
                recognisedCount = current.recognisedCount + if (sample.gesture != null) 1 else 0,
            )
        }
    }

    private companion object {
        /**
         * Mirrors `HeadGestureDetector.Config`.
         *
         * Duplicated rather than read from the detector because the detector is in
         * `core/bluetooth`, which this module does not depend on and should not start
         * depending on for two numbers. If the detector's defaults move, these must move
         * with them — the screen's whole claim is that the bar it draws is the real one.
         */
        const val NOD_THRESHOLD_DEGREES = 12f
        const val SHAKE_THRESHOLD_DEGREES = 15f
    }
}
