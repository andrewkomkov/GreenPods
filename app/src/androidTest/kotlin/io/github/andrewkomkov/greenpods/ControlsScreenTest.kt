package io.github.andrewkomkov.greenpods

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.feature.controls.ControlsScreen
import io.github.andrewkomkov.greenpods.feature.controls.ControlsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gated screen, which on this phone — and on most phones — is the *only* way it
 * will ever be seen. A disabled control with no explanation is the failure mode this
 * whole project is organised around avoiding.
 */
@RunWith(AndroidJUnit4::class)
class ControlsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val pod = PodState(address = "AA:BB:CC:DD:EE:FF", model = PodModel.AIRPODS_PRO_2)

    private fun show(
        state: ControlsUiState,
        onModeSelected: (NoiseControlMode) -> Unit = {},
    ) {
        compose.setContent {
            GreenPodsTheme(dynamicColor = false) {
                ControlsScreen(state = state, onModeSelected = onModeSelected)
            }
        }
    }

    @Test
    fun aGatedScreenStatesTheReasonAndDisablesTheControls() {
        var selected: NoiseControlMode? = null
        show(
            ControlsUiState(
                pod = pod,
                controlAvailable = false,
                gateReason = "The buds refused the channel mode this phone's Bluetooth stack offers.",
                supportsNoiseControl = true,
                supportsAdaptiveAudio = true,
            ),
            onModeSelected = { selected = it },
        )

        compose.onNodeWithText("Controls unavailable").assertIsDisplayed()
        compose.onNode(hasText("refused the channel mode", substring = true)).assertIsDisplayed()

        // The chips are visible — hiding them would read as a missing feature — but inert.
        // "Transparency" labels both the mode chip and the long-press cycle chip; the
        // first is the mode selector.
        compose.onAllNodesWithText("Transparency").onFirst().assertIsNotEnabled()
        compose.onAllNodesWithText("Transparency").onFirst().performClick()

        assert(selected == null) { "A gated control must not write anything" }
    }

    @Test
    fun anOpenChannelEnablesTheControls() {
        var selected: NoiseControlMode? = null
        show(
            ControlsUiState(
                pod = pod,
                controlAvailable = true,
                gateReason = "Channel open. Settings can be read and written.",
                supportsNoiseControl = true,
            ),
            onModeSelected = { selected = it },
        )

        compose.onNodeWithText("Controls are live").assertIsDisplayed()
        compose.onAllNodesWithText("ANC").onFirst().assertIsEnabled()
        compose.onAllNodesWithText("ANC").onFirst().performClick()

        assert(selected == NoiseControlMode.NOISE_CANCELLATION)
    }

    @Test
    fun hardwareWithoutNoiseControlIsNotOfferedAny() {
        show(
            ControlsUiState(
                pod = PodState(address = "AA:BB:CC:DD:EE:FF", model = PodModel.AIRPODS_2),
                controlAvailable = true,
                supportsNoiseControl = false,
            ),
        )

        compose.onNodeWithText("Nothing to control here").assertIsDisplayed()
        compose.onAllNodesWithText("Transparency").assertCountEquals(0)
    }

    @Test
    fun aRejectedEditIsExplainedRatherThanSwallowed() {
        show(
            ControlsUiState(
                pod = pod,
                controlAvailable = true,
                supportsNoiseControl = true,
                notice = "The long press has to cycle through at least one mode.",
            ),
        )

        compose.onNodeWithText("Not applied").assertIsDisplayed()
        compose.onNode(hasText("at least one mode", substring = true)).assertIsDisplayed()
    }
}
