package com.phairplay.airplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class AirPlayRemoteKeysTest {
    @Test fun basicTvRemoteCanToggleAndSkip() {
        assertEquals("playpause", AirPlayRemoteKeys.command(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("nextitem", AirPlayRemoteKeys.command(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("previtem", AirPlayRemoteKeys.command(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("play", AirPlayRemoteKeys.command(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals("pause", AirPlayRemoteKeys.command(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertNull(AirPlayRemoteKeys.command(KeyEvent.KEYCODE_BACK))
        assertNull(AirPlayRemoteKeys.command(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun bonjourIdentityIgnoresHexCaseAndLeadingZeroes() {
        assertTrue(DacpServiceIdentity.matches("iTunes_Ctrl_000abc", "iTunes_Ctrl_ABC"))
        assertFalse(DacpServiceIdentity.matches("iTunes_Ctrl_ABC", "iTunes_Ctrl_ABD"))
        assertFalse(DacpServiceIdentity.matches("Other_ABC", "iTunes_Ctrl_ABC"))
        assertNull(DacpServiceIdentity.normalize("../command"))
    }
}
