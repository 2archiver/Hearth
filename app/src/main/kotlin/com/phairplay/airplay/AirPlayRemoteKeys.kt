package com.phairplay.airplay

import android.view.KeyEvent

/** Shared by the audio card and URL-video player. Arrow keys never alter system volume. */
internal object AirPlayRemoteKeys {
    fun command(key: Int): String? = when (key) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> DacpClient.CMD_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> DacpClient.CMD_PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> DacpClient.CMD_PAUSE
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> DacpClient.CMD_NEXT
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> DacpClient.CMD_PREV
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> DacpClient.CMD_FF
        KeyEvent.KEYCODE_MEDIA_REWIND -> DacpClient.CMD_REW
        else -> null
    }
}
