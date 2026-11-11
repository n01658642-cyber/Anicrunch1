package com.anilili.ui

import android.view.KeyEvent
import android.webkit.WebView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import com.anilili.data.remote.PipeBridge
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController

/**
 * Hidden WebView that hosts the Cloudflare-cleared miruro.to session. It stays attached to the
 * window (so Chromium treats it like a real tab) but is effectively invisible. All pipe requests
 * run as same-origin fetches inside it — see [PipeBridge].
 *
 * When Cloudflare puts up a challenge that will not clear itself, [visible] turns the same tab into
 * a real, reachable page for as long as it takes to answer it. It has to be *this* WebView: the
 * clearance cookie is scoped to this app's cookie store, so one earned in the user's own browser
 * does nothing for us.
 *
 * A broken/disabled system WebView provider must not kill the whole app at first frame: the pipe
 * providers become unavailable, but AniList browsing and the native scrapers keep working.
 */
@Composable
fun PipeWebView(
    visible: Boolean = false,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    // The viewport the hidden tab reports while it is out of sight. It has to be this device's own
    // window rather than a fixed size: the layout viewport is checked against `screen`, and a tab
    // claiming to be wider than the display it is on is a thing no real phone can be. A hardcoded
    // 412x915 was measured doing exactly that on a 384x832 dp Galaxy S25 — the challenge then ran
    // (it has room to render) but never completed.
    val configuration = LocalConfiguration.current
    val hiddenWidth = configuration.screenWidthDp.dp
    val hiddenHeight = configuration.screenHeightDp.dp
    // One Box, one AndroidView, whatever the state: this composable is what holds the WebView that
    // holds the challenge, and adding or removing a wrapper around it as [visible] flips would
    // destroy that WebView and reload straight into a fresh challenge. Only the modifiers change.
    Box(modifier = if (visible) modifier else Modifier.size(1.dp).clipToBounds()) {
        AndroidView(
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("PipeWebView factory create WebView start")
                    DiagnosticsLog.webViewPackage("PipeWebView factory")
                    WebView(ctx).also {
                        // Hardware layer, hidden as well as shown, and it no longer changes with
                        // [visible]. The software layer was here to keep a *1dp* helper off
                        // Chromium's SurfaceControl overlay path, where its overlay could black out
                        // the whole window. There is no 1dp WebView any more — the tab is laid out
                        // at the device's window whether it is shown or clipped away — so that case
                        // is gone, and the software layer's cost is not: it repaints the full
                        // 1080x2340 view into a bitmap on every frame, which measured 2-3fps
                        // against ~136fps for the old 1dp tab. Cloudflare's challenge is driven by
                        // requestAnimationFrame, so starving frames is not free.
                        it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        PipeBridge.attach(it)
                        WebViewProcessController.register(it, "pipe-resolver")
                        DiagnosticsLog.event("PipeWebView factory create WebView complete")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; pipe providers disabled", e)
                    View(ctx)
                }
            },
            update = { view ->
                // Only the challenge makes this tab reachable; the rest of the time it must stay
                // out of the way of focus, touches and the screen reader.
                view.importantForAccessibility = if (visible) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                view.isFocusable = visible
                view.isClickable = visible
                val web = view as? WebView ?: return@AndroidView
                web.isFocusableInTouchMode = visible
                if (!visible) {
                    web.setOnKeyListener(null)
                    return@AndroidView
                }
                web.setOnKeyListener { _, _, event -> handleRemoteKey(web, event, onDismiss) }
                // Without this the remote's keys go to whatever Compose had focused behind the
                // scrim.
                if (!web.hasFocus()) web.requestFocus()
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                DiagnosticsLog.event(
                    "PipeWebView release url=${web.url ?: "none"} size=${web.width}x${web.height}",
                )
                PipeBridge.detach(web)
                web.stopLoading()
                web.webChromeClient = null
                web.webViewClient = android.webkit.WebViewClient()
                WebViewProcessController.release(web, "pipe-resolver")
                web.destroy()
            },
            // Hidden, the tab is still laid out at the device's whole window and merely clipped
            // away by the 1dp Box above. That is not cosmetic. Cloudflare's managed challenge
            // answers itself by rendering Turnstile, and Turnstile draws nothing in a 4x4 viewport
            // with a 1x0 body: the widget iframe is never created, so no clearance is ever issued
            // and the interstitial sits there until the bridge gives up on it. Measured on a Galaxy
            // S25, same WebView and cookie jar, only the viewport differing: 4x4 was still walled
            // after 30s having never been granted a cookie, while a phone viewport cleared in 3.2s.
            modifier = if (visible) {
                Modifier.fillMaxSize()
            } else {
                Modifier.requiredSize(hiddenWidth, hiddenHeight)
            },
        )
    }
}

/**
 * Lets a TV remote answer the challenge. The arrow-to-Tab translation and why it is needed live in
 * [sendTab]; Back closes the challenge here rather than navigating the hidden tab's history.
 */
private fun handleRemoteKey(web: WebView, event: KeyEvent, onDismiss: () -> Unit): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) {
        // Swallow the matching UP so the key does not also reach the screen behind the scrim.
        return event.keyCode in TRANSLATED_KEYS
    }
    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        onDismiss()
        return true
    }
    val shift = tabDirectionForRemoteKey(event.keyCode) ?: return false
    web.sendTab(shift)
    return true
}

private val TRANSLATED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_BACK,
)
