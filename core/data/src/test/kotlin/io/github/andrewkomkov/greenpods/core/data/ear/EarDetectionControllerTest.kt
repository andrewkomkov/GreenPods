package io.github.andrewkomkov.greenpods.core.data.ear

import io.github.andrewkomkov.greenpods.core.model.EarDetectionState
import io.github.andrewkomkov.greenpods.core.model.GreenPodsSettings
import io.github.andrewkomkov.greenpods.core.model.MediaAction
import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.github.andrewkomkov.greenpods.core.model.PodState
import io.github.andrewkomkov.greenpods.core.model.WearState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * The bookkeeping around [AutoPausePolicy]: what the controller remembers between
 * advertisements, which is where double-pauses and phantom resumes come from.
 */
class EarDetectionControllerTest {
    private class FakeActuator(
        var playing: Boolean = true,
        var bluetoothOutput: Boolean = true,
    ) : PlaybackActuator {
        val performed = mutableListOf<MediaAction>()

        override fun isPlaying(): Boolean = playing

        override fun isBluetoothOutputActive(): Boolean = bluetoothOutput

        override fun perform(action: MediaAction) {
            performed += action
            playing = action == MediaAction.RESUME
        }
    }

    private fun controller(actuator: PlaybackActuator) =
        EarDetectionController(pods = emptyFlow(), settings = emptyFlow(), actuator = actuator)

    private fun pod(
        primary: WearState,
        secondary: WearState = primary,
    ) = PodState(
        address = "AA:BB:CC:DD:EE:FF",
        model = PodModel.AIRPODS_PRO_2,
        earDetection = EarDetectionState(primary, secondary),
    )

    @Test
    fun `the first sighting establishes a baseline and acts on nothing`() {
        val actuator = FakeActuator()
        val controller = controller(actuator)

        // An accessory appearing mid-listen must not be read as "just put in".
        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)

        actuator.performed shouldBe emptyList()
    }

    @Test
    fun `a repeated advertisement with the same wear state acts once, not per packet`() {
        val actuator = FakeActuator()
        val controller = controller(actuator)

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)
        repeat(5) { controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default) }

        actuator.performed shouldBe listOf(MediaAction.PAUSE)
    }

    @Test
    fun `a pause we performed is resumed when the bud goes back in`() {
        val actuator = FakeActuator()
        val controller = controller(actuator)

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)
        controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default)
        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)

        actuator.performed shouldBe listOf(MediaAction.PAUSE, MediaAction.RESUME)
    }

    @Test
    fun `playback restarted by the user is not ours to resume afterwards`() {
        val actuator = FakeActuator()
        val controller = controller(actuator)

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)
        controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default)

        // The user hits play with the bud still out, then pauses again themselves.
        actuator.playing = true
        controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default)
        actuator.playing = false

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)

        actuator.performed shouldBe listOf(MediaAction.PAUSE)
    }

    @Test
    fun `forgetting an accessory restores the baseline behaviour`() {
        val actuator = FakeActuator()
        val controller = controller(actuator)

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)
        controller.forget("AA:BB:CC:DD:EE:FF")
        controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default)

        actuator.performed shouldBe emptyList()
    }

    @Test
    fun `nothing is performed while audio is not on a Bluetooth output`() {
        val actuator = FakeActuator(bluetoothOutput = false)
        val controller = controller(actuator)

        controller.onPod(pod(WearState.IN_EAR), GreenPodsSettings.Default)
        controller.onPod(pod(WearState.OUT_OF_EAR), GreenPodsSettings.Default)

        actuator.performed shouldBe emptyList()
    }
}
