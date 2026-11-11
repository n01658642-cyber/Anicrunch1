package com.anilili.data.settings

import com.anilili.ui.watch.applyCaptionStyleJs
import com.anilili.ui.watch.asJsStringLiteral
import com.anilili.ui.watch.captionCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStyleTest {

    @Test
    fun `default background is translucent, not media3's opaque black`() {
        val alpha = (CaptionStyle().backgroundArgb ushr 24) and 0xFF
        assertTrue("default background must let the video through", alpha in 1..254)
    }

    @Test
    fun `background opacity maps onto the alpha channel`() {
        val style = CaptionStyle(backgroundColor = CaptionBackgroundColor.BLACK)
        assertEquals(0x00000000, style.copy(backgroundOpacityPercent = 0).backgroundArgb)
        assertEquals(0xFF000000.toInt(), style.copy(backgroundOpacityPercent = 100).backgroundArgb)
        // 50% of 255 truncates to 127, not 128.
        assertEquals(0x7F000000, style.copy(backgroundOpacityPercent = 50).backgroundArgb)
        assertEquals(0x99000000.toInt(), style.copy(backgroundOpacityPercent = 60).backgroundArgb)
    }

    @Test
    fun `background opacity preserves the chosen colour's rgb`() {
        val style = CaptionStyle(
            backgroundColor = CaptionBackgroundColor.NAVY,
            backgroundOpacityPercent = 40,
        )
        assertEquals(CaptionBackgroundColor.NAVY.rgb, style.backgroundArgb and 0xFFFFFF)
    }

    @Test
    fun `text colour is opaque until the viewer says otherwise`() {
        CaptionTextColor.entries.forEach { color ->
            val argb = CaptionStyle(textColor = color).textArgb
            assertEquals("alpha for $color", 0xFF, (argb ushr 24) and 0xFF)
            assertEquals("rgb for $color", color.rgb, argb and 0xFFFFFF)
        }
    }

    /**
     * Fully transparent text was asked for and is reachable: the floor of the offered steps is
     * zero, and zero really means no ink rather than the lowest visible setting.
     */
    @Test
    fun `text opacity reaches nothing at all, keeping its colour on the way down`() {
        assertEquals(0, CaptionStyle.TEXT_OPACITY_STEPS.first())
        assertEquals(100, CaptionStyle.TEXT_OPACITY_STEPS.last())

        val style = CaptionStyle(textColor = CaptionTextColor.YELLOW)
        assertEquals(0x00000000, style.copy(textOpacityPercent = 0).textArgb and 0xFF000000.toInt())
        assertEquals(0x7F, (style.copy(textOpacityPercent = 50).textArgb ushr 24) and 0xFF)
        assertEquals(
            "the colour survives the fade",
            CaptionTextColor.YELLOW.rgb,
            style.copy(textOpacityPercent = 25).textArgb and 0xFFFFFF,
        )
        assertEquals("rgba(255, 235, 59, 0.25)", style.copy(textOpacityPercent = 25).textCssRgba())
    }

    /**
     * Media3 takes the edge colour separately from the text colour. Left opaque while the text
     * faded, an outline turns into a hollow black tracing of every letter — a different caption
     * rather than a fainter one.
     */
    @Test
    fun `the outline fades with the text it draws around`() {
        val style = CaptionStyle(textOpacityPercent = 30)
        assertEquals((style.textArgb ushr 24) and 0xFF, (style.edgeArgb ushr 24) and 0xFF)
        assertEquals(0x000000, style.edgeArgb and 0xFFFFFF)
        assertEquals(0xFF, (CaptionStyle().edgeArgb ushr 24) and 0xFF)
    }

    @Test
    fun `css rgba uses a dot decimal regardless of locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale would otherwise emit rgba(..., 0,60) and void the rule.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val css = CaptionStyle(
                backgroundColor = CaptionBackgroundColor.BLACK,
                backgroundOpacityPercent = 60,
            ).backgroundCssRgba()
            assertEquals("rgba(0, 0, 0, 0.60)", css)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `text css carries the colour's channels`() {
        assertEquals(
            "rgba(255, 255, 255, 1.00)",
            CaptionStyle(textColor = CaptionTextColor.WHITE).textCssRgba(),
        )
        assertEquals(
            "rgba(77, 208, 225, 1.00)",
            CaptionStyle(textColor = CaptionTextColor.CYAN).textCssRgba(),
        )
    }

    @Test
    fun `stored values round-trip and unknown values fall back to the default`() {
        CaptionTextColor.entries.forEach {
            assertEquals(it, CaptionTextColor.fromStored(it.storedValue))
        }
        CaptionBackgroundColor.entries.forEach {
            assertEquals(it, CaptionBackgroundColor.fromStored(it.storedValue))
        }
        CaptionEdgeStyle.entries.forEach {
            assertEquals(it, CaptionEdgeStyle.fromStored(it.storedValue))
        }
        assertEquals(CaptionTextColor.WHITE, CaptionTextColor.fromStored("chartreuse"))
        assertEquals(CaptionBackgroundColor.BLACK, CaptionBackgroundColor.fromStored(null))
        assertEquals(CaptionEdgeStyle.NONE, CaptionEdgeStyle.fromStored(""))
    }

    @Test
    fun `fonts round-trip through their stored value and an unknown one reads as the default`() {
        CaptionFont.entries.forEach { assertEquals(it, CaptionFont.fromStored(it.storedValue)) }
        assertEquals(CaptionFont.SANS, CaptionFont.fromStored(null))
        assertEquals(CaptionFont.SANS, CaptionFont.fromStored("comic"))
        assertEquals(CaptionFont.SANS, CaptionStyle().font)
    }

    @Test
    fun `defaults are offered as selectable steps`() {
        assertTrue(
            CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT in CaptionStyle.BACKGROUND_OPACITY_STEPS,
        )
        assertTrue(CaptionStyle.DEFAULT_TEXT_OPACITY_PERCENT in CaptionStyle.TEXT_OPACITY_STEPS)
        assertTrue(CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT in CaptionStyle.TEXT_SCALE_STEPS)
        assertTrue(CaptionStyle().textSizeValue in CaptionStyle.TEXT_SIZE_STEPS)
        assertTrue(CaptionStyle.DEFAULT_EDGE_MARGIN_PERCENT in CaptionStyle.EDGE_MARGIN_STEPS)
        assertTrue(
            CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT in CaptionStyle.HORIZONTAL_ANCHOR_STEPS,
        )
    }

    @Test
    fun `captions default to bold, to the bottom, and above media3's usual padding`() {
        val style = CaptionStyle()

        assertTrue(style.boldText)
        assertEquals(CaptionPosition.BOTTOM, style.position)
        assertEquals(0.12f, style.edgePaddingFraction)
        assertTrue(style.edgePaddingFraction > 0.08f)
    }

    @Test
    fun `edge margin percent becomes a fraction`() {
        assertEquals(0.25f, CaptionStyle(edgeMarginPercent = 25).edgePaddingFraction, 0.001f)
    }

    /**
     * Every value that reaches the players is clamped to the offered steps, so a zero gap is only
     * reachable if zero is one of them — otherwise the setting is rounded straight back up to 4%.
     */
    @Test
    fun `no margin at all is an offered step and survives the clamp`() {
        assertEquals(0, CaptionStyle.MIN_EDGE_MARGIN_PERCENT)
        assertTrue(0 in CaptionStyle.EDGE_MARGIN_STEPS)

        val flush = CaptionStyle(edgeMarginPercent = 0)
        assertEquals(0f, flush.edgePaddingFraction, 0.001f)
        assertTrue(applyCaptionStyleJs(flush).contains("bottom: 0% !important"))
    }

    /**
     * The other end of the same setting: a margin may take the whole picture, which walks the
     * captions off the edge they are anchored to and out of sight. Media3 stops drawing once the
     * padding leaves no canvas, so nothing is left half-clipped on the way out.
     */
    @Test
    fun `the margin reaches the whole picture, so captions can be sent off it`() {
        assertEquals(100, CaptionStyle.MAX_EDGE_MARGIN_PERCENT)

        val gone = CaptionStyle(edgeMarginPercent = CaptionStyle.MAX_EDGE_MARGIN_PERCENT)
        assertEquals(1f, gone.edgePaddingFraction, 0.001f)
        assertTrue(applyCaptionStyleJs(gone).contains("bottom: 100% !important"))
    }

    /**
     * Across the picture, a margin *pair* rather than a `left`: the player-drawn layers these
     * rules land on are laid out full width with the line centred inside, so an offsetting pair
     * slides the whole box without changing how wide it is — and the line keeps wrapping where it
     * did. A `left` would either over-constrain the box against the player's own `right` or shrink
     * it to fit, and both rewrap every caption.
     */
    @Test
    fun `the horizontal anchor slides the caption box without resizing it`() {
        val left = applyCaptionStyleJs(CaptionStyle(horizontalAnchorPercent = 20))
        assertTrue(left.contains("margin-left: -30% !important"))
        assertTrue(left.contains("margin-right: 30% !important"))

        val right = applyCaptionStyleJs(CaptionStyle(horizontalAnchorPercent = 75))
        assertTrue(right.contains("margin-left: 25% !important"))
        assertTrue(right.contains("margin-right: -25% !important"))

        assertFalse("a slide is not a resize", left.contains("width:"))
    }

    /**
     * The browser's own captions take the same offset as a translation instead. A margin on the
     * cue container is laid out around it and moves nothing inside — measured, a 75% anchor left
     * the box at the same `72-1192` it started at — and the margin pair that did work landed on
     * the per-cue box, where it flattened the cues onto each other all by itself: both boxes came
     * back at `top=671` with no vertical rule in play at all. Translating the container moves the
     * painted result and leaves the layout, so the box keeps its width (measured `352-1472`,
     * exactly 25% across and the same 1120 wide) and the cues keep their spacing.
     */
    @Test
    fun `the browser's caption container is slid, not margined`() {
        val right = applyCaptionStyleJs(CaptionStyle(horizontalAnchorPercent = 75))
        val native = right.substringAfter("media-text-track-container").substringBefore("}")

        assertTrue("the container is translated", native.contains("transform: translateX(25%) !important"))
        assertFalse("a margin on the container moves nothing inside it", native.contains("margin-left"))
    }

    /**
     * Centred is where every embed player already puts its captions, so the default must emit no
     * horizontal rule at all — a redundant one would re-place boxes that were fine and undo
     * whatever offset the page's own player had chosen.
     */
    @Test
    fun `a centred caption is left exactly where the embed put it`() {
        val centred = applyCaptionStyleJs(CaptionStyle())

        assertTrue(CaptionStyle().horizontallyCentred)
        assertFalse(centred.contains("margin-left"))
        assertFalse(centred.contains("margin-right"))
        assertFalse(centred.contains("translateX"))
    }

    /**
     * The size control reads 0–100 rather than a font percentage: "100%" meant *unchanged*, which
     * read as the top of the range and made the two larger steps look like a mistake. The ends of
     * the shown scale are the ends of the real one, and the middle is the size that changes
     * nothing — which is the whole reason the mapping has two segments rather than one.
     */
    @Test
    fun `the shown size scale spans the whole range, with normal in the middle`() {
        assertEquals(0, CaptionStyle.MIN_TEXT_SIZE)
        assertEquals(100, CaptionStyle.MAX_TEXT_SIZE)

        assertEquals(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.textScaleForSize(0))
        assertEquals(CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT, CaptionStyle.textScaleForSize(50))
        assertEquals(CaptionStyle.MAX_TEXT_SCALE_PERCENT, CaptionStyle.textScaleForSize(100))

        // Out-of-range values are clamped rather than extrapolated into an unreadable size.
        assertEquals(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.textScaleForSize(-40))
        assertEquals(CaptionStyle.MAX_TEXT_SCALE_PERCENT, CaptionStyle.textScaleForSize(400))
    }

    /** Half size is the floor: the smallest offered step, and where anything smaller clamps to. */
    @Test
    fun `the bottom of the scale is half size`() {
        assertEquals(50, CaptionStyle.MIN_TEXT_SCALE_PERCENT)
        assertEquals(50, CaptionStyle.TEXT_SCALE_STEPS.first())
        assertEquals(0, CaptionStyle.TEXT_SIZE_STEPS.first())
        assertEquals(0, CaptionStyle(textScalePercent = 50).textSizeValue)
    }

    /**
     * The editor shows the scale and stores the font percentage, so a value that cannot survive the
     * trip in both directions would drift every time the dialog was opened.
     */
    @Test
    fun `every shown size round-trips through the stored font scale`() {
        for (shown in CaptionStyle.MIN_TEXT_SIZE..CaptionStyle.MAX_TEXT_SIZE) {
            val scale = CaptionStyle.textScaleForSize(shown)
            assertEquals("$shown did not survive the round trip", shown, CaptionStyle.textSizeForScale(scale))
            assertTrue(
                "$shown mapped outside the font range",
                scale in CaptionStyle.MIN_TEXT_SCALE_PERCENT..CaptionStyle.MAX_TEXT_SCALE_PERCENT,
            )
        }
    }

    /**
     * The fine-tune nudge has to land on the offered sizes rather than sliding past them forever,
     * which it only does while every rung is a whole number of nudges from the floor.
     */
    @Test
    fun `every offered text size sits on the fine-tune grid`() {
        CaptionStyle.TEXT_SIZE_STEPS.forEach { step ->
            assertEquals(
                "$step is not reachable by nudging",
                0,
                (step - CaptionStyle.MIN_TEXT_SIZE) % CaptionStyle.TEXT_SIZE_FINE_STEP,
            )
        }
    }

    /**
     * The selector drawing these is a fixed row of equal-weight cells with nowhere to scroll, so the
     * ladder cannot grow: a new size costs an old one. The two ladders are the same rungs in
     * different units, and the players read the second one, so they must not drift apart.
     */
    @Test
    fun `text sizes stay seven wide and ascend`() {
        assertEquals(7, CaptionStyle.TEXT_SIZE_STEPS.size)
        assertEquals(CaptionStyle.TEXT_SIZE_STEPS.sorted(), CaptionStyle.TEXT_SIZE_STEPS)
        assertEquals(CaptionStyle.MAX_TEXT_SIZE, CaptionStyle.TEXT_SIZE_STEPS.max())

        assertEquals(
            CaptionStyle.TEXT_SIZE_STEPS.map { CaptionStyle.textScaleForSize(it) },
            CaptionStyle.TEXT_SCALE_STEPS,
        )
        assertEquals(CaptionStyle.MAX_TEXT_SCALE_PERCENT, CaptionStyle.TEXT_SCALE_STEPS.max())
    }

    @Test
    fun `an unreadable stored position falls back to the bottom`() {
        assertEquals(CaptionPosition.BOTTOM, CaptionPosition.fromStored(null))
        assertEquals(CaptionPosition.BOTTOM, CaptionPosition.fromStored("middle"))
        assertEquals(CaptionPosition.TOP, CaptionPosition.fromStored("top"))
    }

    @Test
    fun `injected css carries the margin and lifts further when controls are up`() {
        val style = CaptionStyle(edgeMarginPercent = 25)
        assertTrue(applyCaptionStyleJs(style, controlsVisible = false).contains("bottom: 25% !important"))
        assertTrue(applyCaptionStyleJs(style, controlsVisible = true).contains("bottom: 35% !important"))
    }

    /**
     * Both edges move the box the browser lays its cues out in. `::cue` cannot place anything —
     * the WebVTT property allow-list has no `transform`, and Blink drops it — so a `translateY`
     * there left the bottom margin doing nothing at all on a natively-rendered embed.
     *
     * The box is the *container*, never the per-cue display box: Blink makes one display box per
     * cue and spaces them from the WebVTT layout, so an `!important` edge on that box is an edge
     * every simultaneous cue shares, and they are painted through one another. Measured with two
     * cues live at once, a 630px video and an 8% margin: `top=584`/`627` unstyled, `580`/`580`
     * with the old per-cue rule, `534`/`577` with the container.
     */
    @Test
    fun `the margin moves the cue container, never the per-cue box`() {
        listOf(CaptionPosition.BOTTOM, CaptionPosition.TOP).forEach { position ->
            val js = applyCaptionStyleJs(CaptionStyle(edgeMarginPercent = 20, position = position))

            assertTrue("$position places the container", js.contains("media-text-track-container"))
            assertFalse(
                "$position must not place the per-cue box, which flattens simultaneous cues",
                js.contains("media-text-track-display"),
            )
            assertFalse("::cue honours no transform, so nothing may depend on one", js.contains("translateY"))
        }
    }

    /**
     * The container's cues stay anchored to its bottom edge, so a `top` on it slides the whole
     * block downwards and off the picture — measured at `top=710`/`753` on a video ending at 670.
     * Both edges are therefore the same property, the top one counted from the other side and
     * less a line so the first line lands on the margin rather than one line above it: at 20%,
     * `bottom: 80%` alone measured 13.2% down the picture and 20.6% with the allowance.
     */
    @Test
    fun `the top edge is the same property, counted from the other side`() {
        val top = applyCaptionStyleJs(CaptionStyle(edgeMarginPercent = 20, position = CaptionPosition.TOP))
        val rule = top.substringAfter("media-text-track-container").substringBefore("}")

        assertTrue("counted from the bottom", rule.contains("bottom: calc(80% - 1.5em) !important"))
        assertTrue("and the other edge released", rule.contains("top: auto !important"))
        assertFalse(
            "a top offset would push the block off the bottom of the picture",
            rule.contains("top: 20%"),
        )

        val bottom = applyCaptionStyleJs(CaptionStyle(edgeMarginPercent = 20))
        val bottomRule = bottom.substringAfter("media-text-track-container").substringBefore("}")
        assertTrue(bottomRule.contains("bottom: 20% !important"))
        assertTrue("the bottom edge releases the top", bottomRule.contains("top: auto !important"))
    }

    /**
     * JW Player hangs the line it paints (`.jw-text-track-display`, already offset for the control
     * bar) inside a full-picture layer (`.jw-captions`). Moving the layer moved the line *and* kept
     * JW's own offset, so an 8% margin measured 19.4% on MegaPlay's own bundle; placing the display
     * box gives 10.7%, and 0% reaches the edge instead of stopping at 10.8%.
     */
    @Test
    fun `the placement names the box the line is painted in, not the layer around it`() {
        val js = applyCaptionStyleJs(CaptionStyle(edgeMarginPercent = 20))

        assertTrue("the display box is what carries the line", js.contains(".jw-text-track-display"))
        assertFalse("the full-picture layer must not be moved as well", js.contains(".jw-captions,"))

        val containerRule = js.substringAfter(".vjs-text-track-display").substringBefore("}")
        assertTrue(containerRule.contains("bottom: 20% !important"))
        assertFalse("containers must not also set margin-bottom", containerRule.contains("margin-bottom"))
    }

    /**
     * The two stylesheets an embed page gets are installed into the same document, so a selector
     * that appears in both with a `bottom` applies the margin twice. Looks live in one, placement
     * in the other.
     */
    @Test
    fun `the looks stylesheet places nothing`() {
        val css = captionCss(CaptionStyle(edgeMarginPercent = 20))

        assertFalse("placement belongs to applyCaptionStyleJs alone", css.contains("bottom:"))
        assertFalse("placement belongs to applyCaptionStyleJs alone", css.contains("top:"))
        assertTrue("but it still styles the cue", css.contains("font-size: 100% !important"))
    }

    /**
     * ArtPlayer, which is what ReAnime's embed runs, writes its subtitle *state* onto the player
     * root — `art-video-player art-subtitle-show …` — and that root is `position: relative` and
     * holds the picture. An unguarded `[class*="subtitle"]` matched it, so `bottom` moved the whole
     * episode up the screen instead of placing a caption, by a further ten percent of the height
     * every time the controls came up.
     *
     * A caption box the guesses would move on top of the one we already name is the same fault a
     * layer further out: JW's `.jw-captions` wraps `.jw-text-track-display` and carries the word
     * "caption" itself, and an attribute selector out-specifies a class, so both moved.
     */
    @Test
    fun `the guessed caption selectors never move a box that holds the video or a caption`() {
        val js = applyCaptionStyleJs(CaptionStyle(edgeMarginPercent = 20))
        val guesses = Regex("""\[class\*=\\"[a-z]+\\"\](:not\([^)]*\)*\))*""").findAll(js)
            .map { it.value.trim() }
            .filter { it.contains(":not(") }
            .toList()

        assertEquals(2, guesses.size)
        guesses.forEach { selector ->
            assertTrue(
                "$selector would also match a player root that holds the video",
                selector.contains(":not(:has(video))"),
            )
            assertTrue(
                "$selector would also match the layer around a caption box we already place",
                selector.contains("""track-display"""),
            )
        }
        // The named players keep a rule of their own: `:has()` is Chrome 105 and later, and a
        // WebView too old to parse it drops the whole rule the selector appears in.
        val named = js.substringAfter(".vjs-text-track-display").substringBefore("}")
        assertFalse("the players we can name must not depend on :has()", named.contains(":has("))
    }

    @Test
    fun `the css is embedded as a valid javascript string literal`() {
        // The stylesheet's attribute selectors contain double quotes. Interpolating them raw made
        // the whole injected script a SyntaxError, and evaluateJavascript reports that nowhere —
        // captions silently kept the page's own styling on every embed player.
        val quote = '"'
        val backslash = '\\'
        val js = applyCaptionStyleJs(CaptionStyle())
        // The CSS is flattened onto one line and is full of semicolons, so take the whole
        // assignment line rather than cutting at the first one.
        val literal = js.lineSequence().first { it.contains("style.textContent") }
            .trim().removePrefix("style.textContent = ").removeSuffix(";")
        assertEquals("literal must open with a quote", quote, literal.first())
        assertEquals("literal must close with a quote", quote, literal.last())
        // Every quote inside the literal has to be escaped, or it ends the string early.
        val body = literal.drop(1).dropLast(1)
        body.forEachIndexed { index, ch ->
            if (ch == quote) {
                assertTrue("unescaped quote at $index", index > 0 && body[index - 1] == backslash)
            }
        }
        // ...and the selector still has to be there once escaped.
        assertTrue("the attribute selector must survive", body.contains("[class*=" + backslash + quote))
    }

    @Test
    fun `js string literal escapes everything that could end it early`() {
        val quote = '"'
        val backslash = '\\'

        // A bare quote comes back as backslash + quote, wrapped in quotes.
        val quoted = quote.toString().asJsStringLiteral()
        assertEquals(listOf(quote, backslash, quote, quote), quoted.toList())

        // A backslash doubles, so it cannot escape the closing quote.
        val slashed = backslash.toString().asJsStringLiteral()
        assertEquals(listOf(quote, backslash, backslash, quote), slashed.toList())

        // Nothing that terminates a JS string literal may survive raw — including the line and
        // paragraph separators, which are not newlines but end a literal all the same.
        listOf('\n', '\r', ' ', ' ').forEach { ch ->
            assertFalse("$ch must not appear raw", ch in ch.toString().asJsStringLiteral())
        }
        // '<' is escaped so the payload stays inert inside an inline script block.
        assertFalse("<".asJsStringLiteral().contains('<'))

        // Ordinary characters, spaces included, pass through untouched.
        assertEquals(quote + "a b" + quote, "a b".asJsStringLiteral())
    }
}
