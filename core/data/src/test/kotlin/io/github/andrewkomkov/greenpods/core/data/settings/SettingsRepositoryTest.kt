package io.github.andrewkomkov.greenpods.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.ScanMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-tripping preferences through a real DataStore in a temp directory.
 *
 * The interesting property is not that a Boolean survives — it is that the *decoding*
 * is total: a file written by another version, or half-written, must still produce a
 * usable settings object rather than an exception on startup.
 */
class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun repository() =
        SettingsRepository(
            PreferenceDataStoreFactory.create { folder.newFile("settings-${counter++}.preferences_pb") },
        )

    @Test
    fun `an untouched store yields the documented defaults`() =
        runTest {
            val settings = repository().settings.first()

            settings shouldBe GreenPodsSettings.Default
            // Auto-pause works over the advertisement transport that every phone has,
            // so it is on; a foreground service nobody asked for is not.
            settings.autoPauseEnabled shouldBe true
            settings.backgroundMonitoringEnabled shouldBe false
        }

    @Test
    fun `every field round-trips`() =
        runTest {
            val repository = repository()

            repository.update {
                it.copy(
                    autoPauseEnabled = false,
                    autoResumeEnabled = false,
                    pauseOnlyWhenBothOut = true,
                    backgroundMonitoringEnabled = true,
                    lowBatteryWarningEnabled = false,
                    lowBatteryThresholdPercent = 35,
                    scanMode = ScanMode.LOW_LATENCY,
                    headGesturesEnabled = true,
                )
            }

            val stored = repository.settings.first()

            stored.autoPauseEnabled shouldBe false
            stored.autoResumeEnabled shouldBe false
            stored.pauseOnlyWhenBothOut shouldBe true
            stored.backgroundMonitoringEnabled shouldBe true
            stored.lowBatteryWarningEnabled shouldBe false
            stored.lowBatteryThresholdPercent shouldBe 35
            stored.scanMode shouldBe ScanMode.LOW_LATENCY
            stored.headGesturesEnabled shouldBe true
        }

    @Test
    fun `an out-of-range threshold is clamped rather than stored`() =
        runTest {
            val repository = repository()

            repository.update { it.copy(lowBatteryThresholdPercent = 900) }

            repository.settings.first().lowBatteryThresholdPercent shouldBe GreenPodsSettings.MAX_THRESHOLD
        }

    @Test
    fun `a single binding can be edited without disturbing the others`() =
        runTest {
            val repository = repository()

            repository.updateBinding(
                HeadGestureBinding(HeadGesture.NOD, GestureAction.PLAY_PAUSE, minimumConfidence = 0.9f),
            )

            val bindings = repository.settings.first().gestureBindings
            bindings.first { it.gesture == HeadGesture.NOD }.action shouldBe GestureAction.PLAY_PAUSE
            bindings.first { it.gesture == HeadGesture.NOD }.minimumConfidence shouldBe 0.9f
            bindings.first { it.gesture == HeadGesture.SHAKE }.action shouldBe GestureAction.REJECT_CALL
            bindings.size shouldBe HeadGestureBinding.Defaults.size
        }

    private companion object {
        var counter = 0
    }
}
