package io.github.andrewkomkov.greenpods

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole app, on a real device, with no AirPods anywhere near it.
 *
 * That is the state most installations start in, so it is the one worth asserting: all
 * three destinations reachable, nothing crashing, and every screen saying something
 * true about why it is empty.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule(order = 0)
    val permissions =
        GrantPermissionsRule(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun allThreeDestinationsAreReachable() {
        compose.onNodeWithText("Pods").assertIsDisplayed()

        compose.onNodeWithText("Controls").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("This phone can't change these", substring = true)).assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ear detection").assertIsDisplayed()

        compose.onNodeWithText("Pods").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Looking for your AirPods…").assertIsDisplayed()
    }

    @Test
    fun withNothingInRangeTheControlsScreenExplainsItself() {
        compose.onNodeWithText("Controls").performClick()
        compose.waitForIdle()

        // No accessory means nothing to control, and the screen has to say which of the
        // two reasons applies rather than showing a bare disabled panel.
        compose.onNodeWithText("No accessory is in range.").assertIsDisplayed()
    }
}
