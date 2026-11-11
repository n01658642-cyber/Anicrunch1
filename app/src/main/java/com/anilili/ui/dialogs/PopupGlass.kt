package com.anilili.ui.dialogs

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.glassEnabled
import com.anilili.ui.watch.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The app as it looked when a popup opened, blurred once, for the popup's panel to show through.
 *
 * A popup is its own window, which Haze cannot reach: it only blurs content inside the window it
 * draws in. Android 12's cross-window blur can, but Samsung turns it off at the window manager
 * (`mBlurEnabled=false` on an S25 with power saving off) — the most common Android phone would
 * never see it. What sits behind a popup does not move while the popup is open, so one copy of the
 * app window taken as it opens, blurred once, is indistinguishable from a live blur and costs
 * nothing per frame.
 *
 * The copy is a quarter of the window's resolution — PixelCopy scales into whatever bitmap it is
 * given — which makes the blur a few milliseconds on a background thread and the bitmap well under
 * a megabyte; scaling it back up is itself part of the softening. PixelCopy reads the activity's
 * own surface, so the popup's window and its dim are never in the picture whatever the timing.
 *
 * Null until the copy lands (a frame or two), and always null with Popups glass switched off, on
 * a television, or below Android 12 — the same gate as the rest of the app's glass.
 */
@Composable
internal fun rememberPopupBackdrop(): PopupBackdrop? {
    val wanted = glassEnabled(GlassSection.POPUPS)
    val activity = LocalContext.current.findActivity()
    var backdrop by remember { mutableStateOf<PopupBackdrop?>(null) }
    LaunchedEffect(wanted, activity) {
        if (!wanted || activity == null) {
            backdrop = null
            return@LaunchedEffect
        }
        val window = activity.window
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return@LaunchedEffect
        val copy = Bitmap.createBitmap(
            (decor.width / SCALE).coerceAtLeast(1),
            (decor.height / SCALE).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val result = suspendCancellableCoroutine { continuation ->
            PixelCopy.request(window, copy, { continuation.resume(it) }, Handler(Looper.getMainLooper()))
        }
        if (result != PixelCopy.SUCCESS) return@LaunchedEffect
        withContext(Dispatchers.Default) { boxBlur(copy, BLUR_RADIUS, passes = 3) }
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        backdrop = PopupBackdrop(
            image = copy.asImageBitmap(),
            screenOrigin = IntOffset(origin[0], origin[1]),
            screenSize = IntSize(decor.width, decor.height),
        )
    }
    return backdrop
}

/** A blurred copy of the app window and where on the screen that window sits. */
internal class PopupBackdrop(
    val image: ImageBitmap,
    val screenOrigin: IntOffset,
    val screenSize: IntSize,
)

/**
 * Paints the part of [backdrop] that lies behind this node, so a panel reads as frosted glass over
 * the app. Drawn behind the node's own content; put it after the panel's clip and before its tint.
 */
internal fun Modifier.popupGlass(backdrop: PopupBackdrop?): Modifier =
    if (backdrop == null) {
        this
    } else {
        composed {
            var position by remember { mutableStateOf(Offset.Zero) }
            onGloballyPositioned { position = it.positionOnScreen() }
                .drawBehind {
                    drawImage(
                        image = backdrop.image,
                        dstOffset = IntOffset(
                            (backdrop.screenOrigin.x - position.x).toInt(),
                            (backdrop.screenOrigin.y - position.y).toInt(),
                        ),
                        dstSize = backdrop.screenSize,
                        filterQuality = FilterQuality.Low,
                    )
                }
        }
    }

/** Copies are taken at 1/[SCALE] of the window in each direction. */
private const val SCALE = 4

/** In copy pixels: 7 at quarter scale is a ~28px blur on screen, three passes near-Gaussian. */
private const val BLUR_RADIUS = 7

/** An in-place separable box blur, repeated [passes] times; clamps at the edges. */
internal fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    val a = IntArray(w * h)
    val b = IntArray(w * h)
    bitmap.getPixels(a, 0, w, 0, 0, w, h)
    repeat(passes) {
        blurLine(a, b, w, h, radius, horizontal = true)
        blurLine(b, a, w, h, radius, horizontal = false)
    }
    bitmap.setPixels(a, 0, w, 0, 0, w, h)
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = r * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val p = at(i)
            sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until length) {
            val out = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val add = at(i + r + 1)
            val drop = at(i - r)
            sa += (add ushr 24) - (drop ushr 24)
            sr += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
            sb += (add and 0xFF) - (drop and 0xFF)
        }
    }
}
