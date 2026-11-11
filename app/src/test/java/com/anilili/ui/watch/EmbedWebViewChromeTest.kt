package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedWebViewChromeTest {
    @Test
    fun `provider chrome suppression covers supported player families`() {
        listOf(
            ".plyr__controls",
            ".plyr__control--overlaid",
            ".vjs-control-bar",
            ".jw-controlbar",
            ".art-controls",
            ".art-bottom",
            ".art-progress",
            ".media-control",
            ".fluid_controls_container",
            ".mejs__controls",
            ".shaka-controls-container",
            ".dplayer-controller",
            ".dplayer-controller-mask",
            ".jw-controls-backdrop",
            ".vds-controls",
            ".xgplayer-controls",
            ".fp-controls",
            ".playkit-control-bar",
            ".op-controls",
            "[data-media-controls]",
            "video::-webkit-media-controls",
        ).forEach { selector ->
            assertTrue("missing selector $selector", HIDE_PROVIDER_CHROME_JS.contains(selector))
        }
    }

    @Test
    fun `megaplay skip overlay is hidden so only the app's own skip button shows`() {
        // Built by megaplay.buzz's handle-bridge as
        // '<div id="overlay-control-handle"><div class="botright"><a id="skip-intro-handle">'.
        // Anchors, not buttons, so the video-document button rules never reached them.
        listOf(
            "#overlay-control-handle",
            "#overlay-control-megaplay",
        ).forEach { selector ->
            assertTrue("missing selector $selector", HIDE_PROVIDER_CHROME_JS.contains(selector))
        }
    }

    @Test
    fun `provider chrome suppression survives dynamic player rerenders`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("MutationObserver"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("attributeFilter: ['controls']"))
    }

    @Test
    fun `abyss source overlay is removed as a full click blocking layer`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("document.querySelector('#overlay > #playback')"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("overlay.id !== 'overlay'"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("overlay.style.setProperty('display', 'none', 'important')"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("overlay.style.setProperty('pointer-events', 'none', 'important')"))
    }

    @Test
    fun `abyss player uses an iframe so its top-level homepage redirect cannot fire`() {
        assertTrue("https://abyssplayer.com/video-1".requiresAbyssIframeShell())
        assertTrue("https://edge.abyssplayer.com/video-1".requiresAbyssIframeShell())
        assertFalse("https://notabyssplayer.com/video-1".requiresAbyssIframeShell())
        assertFalse("https://abyss.to/video-1".requiresAbyssIframeShell())

        val shell = providerIframeShell("https://abyssplayer.com/video-1?token=a&part=1")
        assertTrue(shell.contains("<iframe"))
        assertTrue(shell.contains("src=\"https://abyssplayer.com/video-1?token=a&amp;part=1\""))
        assertTrue(shell.contains("allow=\"autoplay; fullscreen; encrypted-media; picture-in-picture\""))
    }

    @Test
    fun `document start suppression is allowed in nested cross origin frames`() {
        assertEquals(setOf("*"), PROVIDER_CHROME_ORIGIN_RULES)
    }

    @Test
    fun `cross origin frames expose progress and receive native player commands`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onVideoAvailable()"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onTick("))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("window.__aniliDispatchPlayerCommand"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("contentWindow.postMessage(data, '*')"))
    }

    @Test
    fun `a JW error card is reported so the watch screen can fail over`() {
        // Without this the viewer sat on "Error Code: 232011" while another server would have played.
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onPlayerError(failure)"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("player.on('error', remember)"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("player.on('setupError', remember)"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("document.querySelector('.jw-state-error')"))
    }

    @Test
    fun `native player commands use the injected all frame dispatcher`() {
        val command = webPlayerCommandJs("seek", 42.5)

        assertTrue(command.contains("__aniliDispatchPlayerCommand('seek',42.5)"))
        assertTrue(command.contains("return false"))
    }

    /**
     * The dispatcher answering true come what may made every fallbackScript unreachable, this one
     * included — dispatchWebPlayerCommand only reaches for the fallback on a non-"true" answer.
     */
    @Test
    fun `dispatcher reports whether the command was actually carried out`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("var handled = applyCommand(data);"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("return handled;"))
    }

    /**
     * The same script that hides the page's control bar has to hand its quality menu back, or the
     * suppression does not move the choice into the app's sheet, it destroys it.
     */
    @Test
    fun `the hidden control bar's quality ladder is read back out`() {
        listOf("jwEngine", "videoJsEngine", "plyrEngine", "hlsJsEngine").forEach { builder ->
            assertTrue("missing engine $builder", HIDE_PROVIDER_CHROME_JS.contains("function $builder("))
        }
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("AniliProgress.onQualityLevels(report)"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("case 'quality':"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("return setQuality(Math.round(value));"))
    }

    /** JW Player wraps its own hls.js; writing the level underneath it is undone by JW. */
    @Test
    fun `the wrapping player is asked before the engine it wraps`() {
        val order = HIDE_PROVIDER_CHROME_JS.substringAfter("var builders = [").substringBefore("]")

        assertEquals("jwEngine, videoJsEngine, plyrEngine, hlsJsEngine", order)
    }

    /** A frame with no video is the outer shell or an ad slot; letting it answer overwrites the real report. */
    @Test
    fun `only the frame holding the video reports a ladder`() {
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("if (!findVideo()) return '';"))
        assertTrue(HIDE_PROVIDER_CHROME_JS.contains("if (report !== window.__aniliQualityReport)"))
    }

    /** A rejected play() leaves the video untouched, so the caller's retry needs a repeatable script. */
    @Test
    fun `start playback is repeatable rather than latched to one attempt per page`() {
        assertFalse(START_PLAYBACK_JS.contains("__aniliAutoStarted"))
        assertTrue(START_PLAYBACK_JS.contains("if (!video || !video.paused)"))
    }
}
