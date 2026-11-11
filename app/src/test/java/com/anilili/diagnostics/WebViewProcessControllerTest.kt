package com.anilili.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewProcessControllerTest {
    @Test
    fun `only constrained TVs terminate an idle renderer`() {
        assertTrue(shouldTerminateRenderer(isTv = true, isLowRamDevice = true, memoryClassMb = 512))
        assertTrue(shouldTerminateRenderer(isTv = true, isLowRamDevice = false, memoryClassMb = 256))
        assertFalse(shouldTerminateRenderer(isTv = true, isLowRamDevice = false, memoryClassMb = 512))
        assertFalse(shouldTerminateRenderer(isTv = false, isLowRamDevice = true, memoryClassMb = 128))
    }

    @Test
    fun `renderer crashes are survivable only from the release that reports them`() {
        // onRenderProcessGone arrived in API 26; below it a dead renderer is a dead process.
        assertTrue(rendererCrashIsFatal(sdkInt = 24))
        assertTrue(rendererCrashIsFatal(sdkInt = 25))
        assertFalse(rendererCrashIsFatal(sdkInt = 26))
        assertFalse(rendererCrashIsFatal(sdkInt = 34))
    }

    @Test
    fun `only an abandoned pre-O WebView cannot be restarted`() {
        // The box that died: MTK 32G20, Android 7.0, the stock image's Chromium 51 from 2016.
        assertTrue(chromiumRestartIsFatal(24, "com.android.webview", 51))
        // Same package, same era, older still — the amlogic stick on API 22.
        assertTrue(chromiumRestartIsFatal(22, "com.android.webview", 39))

        // A Fire TV stick is equally unable to survive a renderer crash at API 25, but its WebView
        // is maintained and it restarted Chromium 23 times without losing the process. Holding a
        // browser through every episode there would cost memory and buy nothing.
        assertFalse(chromiumRestartIsFatal(25, "com.amazon.webview.chromium", 118))
        // Anything that reports the renderer's death can recover without a restart at all.
        assertFalse(chromiumRestartIsFatal(28, "com.android.webview", 51))
        assertFalse(chromiumRestartIsFatal(36, "com.google.android.webview", 151))
    }

    @Test
    fun `an unreadable provider leaves current behaviour alone`() {
        // A missing package or version must not talk a healthy device into holding a WebView it
        // does not need; only the stock package with no version at all is assumed abandoned.
        assertFalse(chromiumRestartIsFatal(24, null, null))
        assertFalse(chromiumRestartIsFatal(24, "com.google.android.webview", null))
        assertTrue(chromiumRestartIsFatal(24, "com.android.webview", null))
    }

    @Test
    fun `the Chromium milestone is the leading integer of every shipped version name`() {
        fun major(version: String?) = SystemWebViewProvider("p", version).majorVersion
        assertEquals(51, major("51.0.2704.91"))
        assertEquals(151, major("151.0.7922.199"))
        assertEquals(39, major("39 (20181207-arm)"))
        assertEquals(118, major("118.amazon-webview-v118-5993-tv.5993.155.160"))
        assertNull(major(null))
        assertNull(major("none"))
    }
}
