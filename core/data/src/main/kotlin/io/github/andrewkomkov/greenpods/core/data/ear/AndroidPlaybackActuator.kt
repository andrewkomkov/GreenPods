package io.github.andrewkomkov.greenpods.core.data.ear

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import io.github.andrewkomkov.greenpods.core.model.MediaAction

/**
 * Presses play and pause the only way a non-privileged app can: by dispatching media
 * key events, which the system routes to whichever app currently owns the media
 * session.
 *
 * `MediaSessionManager.getActiveSessions` would give finer control, but it requires
 * notification-listener access — a permission that reads as spyware for a battery
 * widget. Media keys need nothing and work with every player.
 */
class AndroidPlaybackActuator(
    context: Context,
) : PlaybackActuator {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun isPlaying(): Boolean = audioManager?.isMusicActive == true

    /**
     * Whether audio is currently routed to a Bluetooth output.
     *
     * This is intentionally coarser than "is it *these* AirPods": the A2DP route
     * carries the accessory's classic Bluetooth address, while the advertisement gives
     * a rotating private one, so the two cannot be matched without pairing. Checking
     * for *a* Bluetooth output is enough to avoid the absurd case — pausing music
     * playing from the phone speaker because a bud moved.
     */
    override fun isBluetoothOutputActive(): Boolean {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    override fun perform(action: MediaAction) {
        val keyCode =
            when (action) {
                MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                MediaAction.RESUME -> KeyEvent.KEYCODE_MEDIA_PLAY
            }
        // Both halves of the press are required; a lone ACTION_DOWN is ignored by most
        // media sessions.
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
