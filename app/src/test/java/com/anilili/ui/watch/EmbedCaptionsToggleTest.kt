package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Captions sheet on an embed used to offer nothing but an explanation, because the track list
 * belongs to the server. Whether to draw a caption at all does not, and this is what carries that
 * choice into the page.
 */
class EmbedCaptionsToggleTest {

    @Test
    fun `off reaches the player's own switch, the tracks, and the layer`() {
        val off = setEmbedCaptionsJs(enabled = false)

        // JW Player, which MegaPlay (AniKoto) runs, keeps redrawing its caption layer from its own
        // state, so hiding the layer without telling the player would leave it repainting.
        assertTrue("the player's own switch", off.contains("setCurrentCaptions(0)"))
        assertTrue("the browser-rendered tracks", off.contains("'disabled'"))
        assertTrue("and whatever neither reached", off.contains("display: none !important"))
    }

    @Test
    fun `on restores the track that was showing rather than choosing one`() {
        val on = setEmbedCaptionsJs(enabled = true)

        assertTrue(on.contains("__aniliWasShowing"))
        assertTrue("and the player's switch goes back too", on.contains("win.__aniliCaptionIndex"))
    }

    /**
     * This script runs on every finished page load, not only after a toggle, so "nothing
     * remembered" is the ordinary case rather than the rare one — and a page that has just loaded
     * has already chosen the track its server flagged as default. MegaPlay lists Arabic first and
     * flags English, so forcing the first track is how an English episode came back in Arabic.
     */
    @Test
    fun `on leaves a page that is already showing a track alone`() {
        val on = setEmbedCaptionsJs(enabled = true)

        assertFalse("the player's own selection is not overridden", on.contains("__aniliCaptionIndex || 1"))
        assertTrue("JW only gets a track when it is showing none", on.contains("jw.getCurrentCaptions() === 0"))
        assertTrue(
            "and the browser-rendered tracks are only chosen for when none is showing",
            on.contains("!restored && !showing && first"),
        )
    }

    /**
     * `display` is not in the WebVTT allow-list for `::cue` — the same allow-list that silently
     * dropped the `transform` the bottom margin used to lean on. `visibility` is on it.
     */
    @Test
    fun `native cues are hidden with a property cue rules actually honour`() {
        val off = setEmbedCaptionsJs(enabled = false)
        val cueRule = off.substringAfter("::cue {").substringBefore("}")

        assertTrue(cueRule.contains("visibility: hidden"))
        assertFalse(cueRule.contains("display:"))
    }

    @Test
    fun `the guessed selectors never hide a player that holds the video`() {
        val off = setEmbedCaptionsJs(enabled = false)

        Regex("""\[class\*=\\"[a-z]+\\"\][^,{]*""").findAll(off).forEach { match ->
            assertTrue(
                "${match.value} would also hide a player root that holds the video",
                match.value.trim().endsWith(":not(:has(video))"),
            )
        }
    }
}
