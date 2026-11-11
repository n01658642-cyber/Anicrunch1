package com.anilili.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvWebRemoteFocusTest {
    @Test
    fun downAndRightWalkForward() {
        assertEquals(false, tabDirectionForRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(false, tabDirectionForRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun upAndLeftWalkBack() {
        assertEquals(true, tabDirectionForRemoteKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(true, tabDirectionForRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun selectKeysAreLeftToChromium() {
        // Translating these would break the thing the traversal exists for: Chromium activates the
        // focused element on centre/enter, which is how a Cloudflare checkbox gets ticked.
        assertNull(tabDirectionForRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(tabDirectionForRemoteKey(KeyEvent.KEYCODE_ENTER))
        assertNull(tabDirectionForRemoteKey(KeyEvent.KEYCODE_BACK))
    }
}
