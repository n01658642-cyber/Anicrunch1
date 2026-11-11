package com.anilili.ui.watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.playback.layOutSimultaneousCues
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The captions have to stay inside a television's safe area.
 *
 * Media3 gives a caption line almost no side margin of its own — `SubtitlePainter` wraps text at
 * `parentWidth - 2 * (0.125 * textSize)`, seven pixels an edge on a 1080p frame — so a line is free
 * to run into the part of the picture a set crops. This paints a real [PlayerView] at 1080p, feeds
 * it a line long enough to fill the width, and reads back where the ink actually landed.
 */
@RunWith(AndroidJUnit4::class)
class CaptionSafeAreaTest {

    @Test
    fun televisionCaptionsStayInsideTheSafeArea() {
        for (scalePercent in CaptionStyle.TEXT_SCALE_STEPS) {
            val ink = inkBounds(unboxed(textScalePercent = scalePercent), isTv = true)
                ?: error("no caption drawn at $scalePercent%")
            val safeLeft = WIDTH * TV_CAPTION_SIDE_SAFE_PERCENT / 100
            val safeRight = WIDTH - safeLeft
            assertTrue(
                "caption ink at $scalePercent% started at ${ink.first}, inside the cropped edge",
                ink.first >= safeLeft,
            )
            assertTrue(
                "caption ink at $scalePercent% ran to ${ink.second}, past the safe edge $safeRight",
                ink.second <= safeRight,
            )
        }
    }

    /**
     * The guard is worth having: without it the same line reaches the very edge of the picture,
     * which is what the safe area exists to prevent. A phone keeps that behaviour — it has no
     * cropped edge — so this also pins the fix to televisions.
     */
    @Test
    fun withoutTheSafeAreaTheSameLineReachesTheEdge() {
        val ink = inkBounds(unboxed(textScalePercent = percentThatFillsTheWidth), isTv = false)
            ?: error("no caption drawn")
        val safeLeft = WIDTH * TV_CAPTION_SIDE_SAFE_PERCENT / 100
        assertTrue(
            "expected the unguarded caption to reach past $safeLeft, but it stopped at ${ink.first}",
            ink.first < safeLeft,
        )
    }

    /**
     * The horizontal anchor has to be written onto the cue, because the view cannot express it:
     * padding the canvas would narrow the box the line is centred in and drag the wrap point with
     * it. This paints a short line at three anchors and reads back where the ink landed — the only
     * way to know `SubtitlePainter` honoured the anchor rather than quietly centring anyway.
     */
    @Test
    fun theHorizontalAnchorMovesTheDrawnLine() {
        val centred = inkCentre(SHORT_LINE, anchorPercent = 50) ?: error("nothing drawn centred")
        val left = inkCentre(SHORT_LINE, anchorPercent = 20) ?: error("nothing drawn at 20%")
        val right = inkCentre(SHORT_LINE, anchorPercent = 80) ?: error("nothing drawn at 80%")

        assertTrue("centred line sat at $centred, not near the middle", centred in (WIDTH * 45 / 100)..(WIDTH * 55 / 100))
        assertTrue("20% anchor sat at $left, not left of centre", left < centred - WIDTH / 10)
        assertTrue("80% anchor sat at $right, not right of centre", right > centred + WIDTH / 10)
    }

    /**
     * The far end of the margin range is the captions leaving the picture, which is the point of
     * letting it reach 100: `CanvasSubtitleOutput` returns before drawing once the padding has
     * taken the whole canvas.
     */
    @Test
    fun aFullMarginTakesTheCaptionOffThePicture() {
        assertNull(
            inkBounds(unboxed(edgeMarginPercent = CaptionStyle.MAX_EDGE_MARGIN_PERCENT), isTv = false),
        )
        assertNotNull(inkBounds(unboxed(edgeMarginPercent = 60), isTv = false))
    }

    /** No background behind the text, so every lit pixel is a letter rather than its box. */
    private fun unboxed(
        textScalePercent: Int = CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT,
        edgeMarginPercent: Int = CaptionStyle.DEFAULT_EDGE_MARGIN_PERCENT,
    ) = CaptionStyle(
        backgroundOpacityPercent = 0,
        textScalePercent = textScalePercent,
        edgeMarginPercent = edgeMarginPercent,
    )

    /** Midpoint of the drawn ink, through the same layout pass the player's text renderer uses. */
    @UnstableApi
    private fun inkCentre(text: String, anchorPercent: Int): Int? {
        val cues = layOutSimultaneousCues(
            listOf(Cue.Builder().setText(text).build()),
            CaptionPosition.BOTTOM,
            anchorPercent,
        )
        val ink = inkBounds(unboxed(), isTv = false, cues = cues) ?: return null
        return (ink.first + ink.second) / 2
    }

    /** Leftmost and rightmost lit pixel of the drawn captions, or null if nothing was drawn. */
    @UnstableApi
    private fun inkBounds(
        style: CaptionStyle,
        isTv: Boolean,
        cues: List<Cue> = listOf(Cue.Builder().setText(LONG_LINE).build()),
    ): Pair<Int, Int>? {
        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                android.R.style.Theme_Material,
            )
            val playerView = PlayerView(context)
            playerView.useController = false
            playerView.layoutAt(WIDTH, HEIGHT)

            playerView.applyCaptionStyle(style, controlsVisible = false, isTv = isTv)
            // The inset is applied from the measured height, and applying it asks for a new
            // layout; run one so the padding is in place before anything is drawn.
            playerView.layoutAt(WIDTH, HEIGHT)

            playerView.subtitleView?.setCues(cues)

            bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            playerView.draw(Canvas(bitmap!!))
        }
        val bounds = bitmap!!.horizontalInkBounds()
        // The numbers are the whole point of the test; a failure is far easier to read with the
        // measurement beside it than with the assertion alone.
        Log.i(
            "CaptionSafeArea",
            "scale=${style.textScalePercent} margin=${style.edgeMarginPercent} isTv=$isTv ink=$bounds",
        )
        return bounds
    }

    private fun View.layoutAt(width: Int, height: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    private fun Bitmap.horizontalInkBounds(): Pair<Int, Int>? {
        val row = IntArray(width)
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                // The player draws its shutter black behind everything; white caption text is the
                // only thing that lights a pixel up.
                val lit = ((pixel shr 16) and 0xFF) > INK_THRESHOLD
                if (!lit) continue
                if (x < left) left = x
                if (x > right) right = x
            }
        }
        return if (left <= right) left to right else null
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val INK_THRESHOLD = 96

        /** Long enough that every step in the picker wraps and fills the width it is given. */
        val LONG_LINE = List(12) { "the quick brown fox jumps over the lazy dog" }.joinToString(" ")

        /**
         * Short enough to have somewhere to go: media3 pins a cue box inside the picture, so a
         * line already filling the width cannot move sideways however it is anchored.
         */
        const val SHORT_LINE = "Over here"

        /** The smallest step is enough; a wrapped line fills its width at any size. */
        val percentThatFillsTheWidth = CaptionStyle.TEXT_SCALE_STEPS.first()
    }
}
