package com.anilili.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import coil.transform.Transformation
import com.anilili.data.settings.SettingsStore

/**
 * A series' own logo — the transparent wordmark the metadata tree publishes as `images.logo` —
 * with [fallback] drawn instead wherever there is none or it will not load.
 *
 * Two shapes come back from TVDB, and only one of them fits a header as published. Its v4
 * `clearlogo` art is a wordmark cropped to its own edges; its older `icons/` art (a third of what the
 * tree carries, and most of the long-running series — Attack on Titan, One Piece) is the same
 * wordmark centred in a 1024x1024 transparent square, measured at about 1000x300 of ink. Fitted
 * into a wide, short header box the square sets the height, so the name came out a quarter of the
 * box's height and a fifth of its width. [TrimTransparentEdges] cuts the empty margin away before
 * layout sees the image, so both shapes are laid out by their ink.
 *
 * The decode size is pinned rather than taken from the box. Coil otherwise decodes to fit the
 * layout bounds, which for the square means the whole 1024px canvas shrunk to the box's height
 * *before* the trim — leaving a wordmark a few dozen pixels tall to be stretched back up, blurred.
 * Precision is inexact so a small logo is decoded at its own size, never upscaled to the pin.
 *
 * Settings ▸ Appearance ▸ "Series logos" turns every one of these into its [fallback] at once;
 * this is the one place a logo is drawn, so it is the one place that has to ask.
 */
@Composable
fun SeriesLogo(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    fallback: @Composable () -> Unit,
) {
    // Keyed on the url so a season change that brings a different logo gets its own attempt rather
    // than inheriting the last one's failure.
    var failed by remember(url) { mutableStateOf(false) }
    val enabled by SettingsStore.showSeriesLogos.collectAsState()
    if (!enabled || url.isNullOrBlank() || failed) {
        fallback()
        return
    }
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(LOGO_DECODE_PX)
            .precision(Precision.INEXACT)
            .transformations(TrimTransparentEdges)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        // Fit, never crop: cropping a wordmark beheads it.
        contentScale = ContentScale.Fit,
        alignment = alignment,
        // A logo that 404s or fails to decode must not leave the header without a name.
        onError = { failed = true },
        modifier = modifier,
    )
}

/** Wide enough for a trimmed square's ~1000px of ink to stay sharp in a TV header at 2x density. */
private const val LOGO_DECODE_PX = 1024

/**
 * Crops a bitmap to the bounding box of its visible pixels.
 *
 * "Visible" is alpha above a small floor rather than above zero: TVDB's squares carry faint noise
 * in the margin that would otherwise hold the box open. Two pixels are left around the ink so an
 * anti-aliased edge below that floor is not shaved off. A bitmap that is already tight, opaque, or
 * entirely transparent comes back untouched.
 */
internal object TrimTransparentEdges : Transformation {
    private const val ALPHA_FLOOR = 16
    private const val MARGIN_PX = 2

    override val cacheKey: String = "trim-transparent-edges:v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!input.hasAlpha()) return input
        val bounds = visibleBounds(input.width, input.height) { y, row ->
            input.getPixels(row, 0, input.width, 0, y, input.width, 1)
        } ?: return input
        val left = (bounds[0] - MARGIN_PX).coerceAtLeast(0)
        val top = (bounds[1] - MARGIN_PX).coerceAtLeast(0)
        val right = (bounds[2] + MARGIN_PX).coerceAtMost(input.width - 1)
        val bottom = (bounds[3] + MARGIN_PX).coerceAtMost(input.height - 1)
        if (left == 0 && top == 0 && right == input.width - 1 && bottom == input.height - 1) return input
        return Bitmap.createBitmap(input, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * `[left, top, right, bottom]` of the pixels above [ALPHA_FLOOR], inclusive; null if none.
     *
     * Reads one row at a time through [readRow] (ARGB ints) rather than the whole bitmap, so a
     * 1024x1024 logo costs one 4KB row buffer instead of a 4MB copy — and so it can be tested
     * without an Android Bitmap.
     */
    internal fun visibleBounds(width: Int, height: Int, readRow: (y: Int, row: IntArray) -> Unit): IntArray? {
        val row = IntArray(width)
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            readRow(y, row)
            var first = -1
            for (x in 0 until width) {
                if (row[x] ushr 24 > ALPHA_FLOOR) { first = x; break }
            }
            if (first < 0) continue
            var last = first
            for (x in width - 1 downTo first) {
                if (row[x] ushr 24 > ALPHA_FLOOR) { last = x; break }
            }
            if (top < 0) top = y
            bottom = y
            if (first < left) left = first
            if (last > right) right = last
        }
        return if (top < 0) null else intArrayOf(left, top, right, bottom)
    }
}
