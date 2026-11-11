package com.anilili.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Lets a TV remote move focus through web content.
 *
 * A D-pad cannot drive a page: Chromium takes web content as one opaque focus block and the arrow
 * keys die there — verified on the TV emulator when the player's own chrome was tried this way.
 * **Tab** does traverse it, with real focus rings, and it crosses into cross-origin frames that
 * injected script cannot see into. No remote sends Tab, so the arrows are translated into it; that
 * is what makes a Cloudflare checkbox reachable without a touchscreen.
 *
 * Centre/Enter is deliberately not translated: Chromium already activates the focused element with
 * it.
 */
internal fun WebView.sendTab(shift: Boolean) {
    val meta = if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
    // Posted rather than dispatched inline: this is called from the WebView's own key listener, and
    // Chromium drops a key fed back into it while it is still delivering one — the emulator showed
    // focus sitting on the Turnstile box for thirteen presses in a row. The event also has to carry
    // a real keyboard source and device; a bare KeyEvent(...) is SOURCE_UNKNOWN from device 0.
    post {
        val now = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            dispatchKeyEvent(
                KeyEvent(
                    now,
                    now,
                    action,
                    KeyEvent.KEYCODE_TAB,
                    0,
                    meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    0,
                    InputDevice.SOURCE_KEYBOARD,
                ),
            )
        }
    }
}

/**
 * Which way [sendTab] should move for a remote key, or null when the key is not a direction and
 * belongs to the page as it is.
 */
internal fun tabDirectionForRemoteKey(keyCode: Int): Boolean? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> false
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> true
    else -> null
}
