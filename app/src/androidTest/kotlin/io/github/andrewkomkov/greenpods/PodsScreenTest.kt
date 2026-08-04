package io.github.andrewkomkov.greenpods

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.core.model.BatteryComponent
import io.github.andrewkomkov.greenpods.core.model.BatteryState
import io.github.andrewkomkov.greenpods.core.model.ChargeStatus
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import io.github.andrewkomkov.greenpods.feature.pods.PodsEmptyReason
import io.github.andrewkomkov.greenpods.feature.pods.PodsScreen
import io.github.andrewkomkov.greenpods.feature.pods.PodsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen most users will see first, and — with no AirPods in range — the only one
 * they will see at all. Every empty state has to say something actionable.
 */
@RunWith(AndroidJUnit4::class)
class PodsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: PodsUiState,
        onRequestPermission: () -> Unit = {},
    ) {
        compose.setContent {
            GreenPodsTheme(dynamicColor = false) {
                PodsScreen(state = state, onRequestPermission = onRequestPermission)
            }
        }
    }

    @Test
    fun searchingExplainsWhatTheAppIsDoing() {
        show(PodsUiState(emptyReason = PodsEmptyReason.SEARCHING))

        compose.onNodeWithText("Looking for nearby AirPods…").assertIsDisplayed()
        compose.onNode(hasText("Open the case", substring = true)).assertIsDisplayed()
    }

    @Test
    fun missingPermissionOffersAWayToGrantIt() {
        var requested = false
        show(PodsUiState(emptyReason = PodsEmptyReason.NO_PERMISSION)) { requested = true }

        compose.onNodeWithText("Nearby-devices permission needed").assertIsDisplayed()
        compose.onNodeWithText("Grant permission").performClick()

        assert(requested) { "The empty state must actually request the permission" }
    }

    @Test
    fun bluetoothOffIsDistinctFromAMissingPermission() {
        show(PodsUiState(emptyReason = PodsEmptyReason.BLUETOOTH_OFF))

        compose.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        // The two causes need two different instructions: nothing to grant here.
        compose.onNodeWithText("Grant permission").assertDoesNotExist()
    }

    @Test
    fun aScanFailureShowsTheRadiosOwnReason() {
        show(
            PodsUiState(
                emptyReason = PodsEmptyReason.SCAN_FAILED,
                scanFailure = "BLE scan failed: 2",
            ),
        )

        compose.onNodeWithText("Scanning stopped").assertIsDisplayed()
        compose.onNodeWithText("BLE scan failed: 2").assertIsDisplayed()
    }

    @Test
    fun aPodCardShowsBatteryAndLocksWhatThePhoneCannotReach() {
        val pod =
            PodState(
                address = "AA:BB:CC:DD:EE:FF",
                model = PodModel.AIRPODS_PRO_2,
                battery =
                    BatteryState(
                        left = BatteryComponent(80, ChargeStatus.DISCHARGING),
                        right = BatteryComponent(null, ChargeStatus.UNKNOWN),
                        case = BatteryComponent(45, ChargeStatus.CHARGING),
                    ),
                transportStatuses =
                    listOf(
                        TransportStatus.AdvertisementAvailable,
                        TransportStatus(
                            Transport.AAP_L2CAP,
                            TransportAvailability.UNAVAILABLE,
                            "The buds refused the channel mode this phone offers.",
                        ),
                    ),
            )

        show(PodsUiState(pods = listOf(pod)))

        compose.onNodeWithText("AirPods Pro 2").assertIsDisplayed()
        compose.onNodeWithText("80%").assertIsDisplayed()
        // An unknown level must never be rendered as 0 %.
        compose.onNodeWithText("—").assertIsDisplayed()

        // The locked capability is present, and tapping it explains itself.
        compose.onNodeWithText("Noise control").performClick()
        compose.onNode(hasText("refused the channel mode", substring = true)).assertIsDisplayed()
    }
}
