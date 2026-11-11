package com.anilili.ui.watch

import com.anilili.data.settings.CaptionBackgroundColor
import com.anilili.data.settings.CaptionEdgeStyle
import com.anilili.data.settings.CaptionFont
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.CaptionTextColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCssTest {

    @Test
    fun `styles native cues and the common embed players' dom captions`() {
        val css = captionCss(CaptionStyle())
        assertTrue("native WebVTT cues", css.contains("::cue {"))
        listOf(".plyr__caption", ".vjs-text-track-cue > div", ".jw-text-track-cue", ".art-subtitle")
            .forEach { assertTrue("selector $it", css.contains(it)) }
    }

    @Test
    fun `every declaration is important so the player's own rules cannot win`() {
        val css = captionCss(
            CaptionStyle(
                backgroundOpacityPercent = 40,
                textColor = CaptionTextColor.YELLOW,
                textScalePercent = 130,
            ),
        )
        // Each declaration ends in `!important;`, so counting them catches an unmarked one.
        val declarations = css.count { it == ';' }
        assertTrue("expected declarations, found $declarations", declarations > 0)
        assertTrue(
            "unmarked declaration in: $css",
            Regex(""":[^;{}]+;""").findAll(css).all { it.value.contains("!important") },
        )
    }

    @Test
    fun `background shorthand precedes background-color so it cannot reset it`() {
        val css = captionCss(CaptionStyle(backgroundColor = CaptionBackgroundColor.NAVY))
        val shorthand = css.indexOf("background:")
        val longhand = css.indexOf("background-color:")
        assertTrue("both present", shorthand >= 0 && longhand >= 0)
        assertTrue("shorthand must come first", shorthand < longhand)
    }

    @Test
    fun `edge style drives text-shadow`() {
        assertTrue(captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.NONE)).contains("text-shadow: none"))
        assertTrue(
            captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.OUTLINE)).contains("-1px -1px 1px #000"),
        )
        assertTrue(
            captionCss(CaptionStyle(edgeStyle = CaptionEdgeStyle.DROP_SHADOW)).contains("2px 2px 3px"),
        )
    }

    @Test
    fun `weight is emitted for web captions`() {
        val bold = captionCss(CaptionStyle(boldText = true))
        val regular = captionCss(CaptionStyle(boldText = false))

        assertTrue(bold.contains("font-weight: 700 !important"))
        assertTrue(regular.contains("font-weight: 400 !important"))
    }

    @Test
    fun `a chosen font reaches both embed stylesheets, and the default leaves the player's own`() {
        val serif = CaptionStyle(font = CaptionFont.SERIF)
        assertTrue(captionCss(serif).contains("font-family: serif !important"))
        assertTrue(applyCaptionStyleJs(serif).contains("font-family: serif !important"))

        val default = CaptionStyle()
        assertFalse(captionCss(default).contains("font-family"))
        assertFalse(applyCaptionStyleJs(default).contains("font-family"))
    }

    /**
     * This stylesheet and [applyCaptionStyleJs]'s are installed into the same document, and both
     * named a caption element: this one the line, the other the layer holding it. Two `bottom`s on
     * one line applied the viewer's margin twice, which is what put an 8% setting at 19.4% on
     * AniKoto's JW-based embed. Placement belongs to the other sheet now, whichever edge it is.
     */
    @Test
    fun `neither edge is placed here`() {
        listOf(CaptionPosition.BOTTOM, CaptionPosition.TOP).forEach { position ->
            val css = captionCss(CaptionStyle(edgeMarginPercent = 16, position = position))

            assertFalse("$position must not be placed here", css.contains("bottom:"))
            assertFalse("$position must not be placed here", css.contains("top:"))
            assertFalse(
                "nor may the browser's own cue box be moved from here",
                css.contains("media-text-track-display"),
            )
        }
    }

    @Test
    fun `css carries no quote or backslash that would break the js string literal`() {
        CaptionTextColor.entries.forEach { text ->
            CaptionBackgroundColor.entries.forEach { background ->
                val css = captionCss(CaptionStyle(textColor = text, backgroundColor = background))
                assertTrue("quote in $css", !css.contains('\'') && !css.contains('\\'))
            }
        }
    }
}
