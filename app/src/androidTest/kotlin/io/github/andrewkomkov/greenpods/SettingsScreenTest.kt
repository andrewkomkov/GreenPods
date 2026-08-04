package io.github.andrewkomkov.greenpods

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.feature.settings.CapabilitiesUiState
import io.github.andrewkomkov.greenpods.feature.settings.LockedCapabilities
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
    fun whatWorksIsListedAsFeaturesRatherThanTransports() {
        show(
            SettingsUiState(
                deviceName = "AirPods Pro 2",
                capabilities =
                    CapabilitiesUiState(
                        alwaysWorks = listOf("Battery, ear detection and auto-pause"),
                        available = listOf("Heart rate"),
                        locked =
                            listOf(
                                LockedCapabilities(
                                    features = listOf("Head tracking", "Noise control"),
                                    sentence = "This phone won't let GreenPods send commands to your earbuds.",
                                ),
                            ),
                        known = true,
                    ),
            ),
        )

        compose.onNodeWithText("What works with this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Head tracking, Noise control").performScrollTo().assertIsDisplayed()
        compose
            .onNode(hasText("won't let GreenPods send commands", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun noProtocolNamesReachTheSettingsScreen() {
        // The brief's hard rule: nothing here may require knowing how Bluetooth works.
        // These four are the words the old transport section put on screen.
        show(
            SettingsUiState(
                deviceName = "AirPods Pro 2",
                capabilities = CapabilitiesUiState(known = true),
            ),
        )

        listOf("L2CAP", "PSM", "GATT", "Diagnostics").forEach { jargon ->
            compose.onAllNodes(hasText(jargon, substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun withNothingInRangeTheSectionSaysSoRatherThanLookingBroken() {
        show(SettingsUiState())

        compose.onNodeWithText("No earbuds in range.").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Open your case nearby and this fills in.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
