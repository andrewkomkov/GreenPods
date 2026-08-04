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
        onRetryScan: () -> Unit = {},
    ) {
        compose.setContent {
            GreenPodsTheme(dynamicColor = false) {
                PodsScreen(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    onRetryScan = onRetryScan,
                )
            }
        }
    }

    @Test
    fun searchingExplainsWhatTheAppIsDoing() {
        show(PodsUiState(emptyReason = PodsEmptyReason.SEARCHING))

        compose.onNodeWithText("Looking for your AirPods…").assertIsDisplayed()
        compose.onNode(hasText("Open the case", substring = true)).assertIsDisplayed()
    }

    @Test
    fun missingPermissionOffersAWayToGrantIt() {
        // Named, not trailing: `show` has more than one callback now, and a trailing
        // lambda binds to the last parameter — which is how this test spent a run
        // asserting that the retry handler grants permissions.
        var requested = false
        show(
            PodsUiState(emptyReason = PodsEmptyReason.NO_PERMISSION),
            onRequestPermission = { requested = true },
        )

        compose.onNodeWithText("GreenPods needs to see nearby devices").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()

        assert(requested) { "The empty state must actually request the permission" }
    }

    @Test
    fun bluetoothOffIsDistinctFromAMissingPermission() {
        show(PodsUiState(emptyReason = PodsEmptyReason.BLUETOOTH_OFF))

        compose.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        // The two causes need two different instructions: nothing to grant here.
        compose.onNodeWithText("Allow").assertDoesNotExist()
        compose.onNodeWithText("Open Bluetooth settings").assertIsDisplayed()
    }

    @Test
    fun aScanFailureOffersTheOneThingThatRecoversIt() {
        // Not the radio's own error text. `BLE scan failed: 2` is a true sentence about a
        // Bluetooth stack and a useless one about a phone in someone's hand — and the
        // recovery is the same whichever code came back. The code is kept in the
        // diagnostics log, where whoever can act on it will look.
        var retried = false
        show(
            PodsUiState(
                emptyReason = PodsEmptyReason.SCAN_FAILED,
                scanFailure = "BLE scan failed: 2",
            ),
            onRetryScan = { retried = true },
        )

        compose.onNodeWithText("GreenPods stopped listening").assertIsDisplayed()
        compose.onNodeWithText("BLE scan failed: 2").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()

        assert(retried) { "The empty state must actually restart the scan" }
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

        // The locked capability is present, and tapping it explains itself — as a fact
        // about the phone, not as the Bluetooth layer's account of what it was refused.
        compose.onNodeWithText("Noise control").performClick()
        compose.onNode(hasText("won't let GreenPods send commands", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("channel mode", substring = true)).assertDoesNotExist()
    }
}
