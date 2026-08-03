package io.github.andrewkomkov.greenpods

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticEvent
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.Transport
import io.github.andrewkomkov.greenpods.core.model.TransportAvailability
import io.github.andrewkomkov.greenpods.core.model.TransportStatus
import io.github.andrewkomkov.greenpods.feature.settings.SettingsScreen
import io.github.andrewkomkov.greenpods.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: SettingsUiState,
        onAutoPauseChanged: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            GreenPodsTheme(dynamicColor = false) {
                SettingsScreen(state = state, onAutoPauseChanged = onAutoPauseChanged)
            }
        }
    }

    @Test
    fun theEarDetectionSectionIsOfferedRegardlessOfTransport() {
        var toggled: Boolean? = null
        show(SettingsUiState(settings = GreenPodsSettings.Default), onAutoPauseChanged = { toggled = it })

        // Auto-pause rides on the advertisement, so it is available on every phone.
        compose.onNodeWithText("Pause when a bud comes out").assertIsDisplayed()
        compose.onNodeWithText("Pause when a bud comes out").performClick()

        assert(toggled == false) { "Toggling should report the new value" }
    }

    @Test
    fun transportStatusIsShownWithItsReason() {
        show(
            SettingsUiState(
                deviceName = "AirPods Pro 2",
                transports =
                    listOf(
                        TransportStatus.AdvertisementAvailable,
                        TransportStatus(
                            Transport.AAP_L2CAP,
                            TransportAvailability.UNAVAILABLE,
                            "Android refuses PSM 0x1001.",
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("What this phone can do").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Apple protocol (L2CAP) — unavailable")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Android refuses PSM 0x1001.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun undecodedTrafficIsVisibleRatherThanHiddenBehindADeveloperFlag() {
        show(
            SettingsUiState(
                diagnostics =
                    listOf(
                        DiagnosticEvent(
                            atEpochMillis = 0L,
                            category = DiagnosticCategory.UNKNOWN_TRAFFIC,
                            message = "Control CHIME_VOLUME has no decoder yet",
                            detail = "04 00 09 1F",
                        ),
                    ),
            ),
        )

        compose.onNode(hasText("has no decoder yet", substring = true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("04 00 09 1F").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptyDiagnosticsLogSaysSoInsteadOfLookingBroken() {
        show(SettingsUiState())

        compose.onNodeWithText("Nothing recorded yet.").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Transport status appears once an accessory is nearby.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
