package com.anilili.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** How long the looping loader takes to rise from empty to full. */
const val WaterFillDurationMillis = 2_800

/** The looping loader's beat on a full mark, its drain back down, and its pause while empty. */
private const val LoopHoldMillis = 450
private const val LoopDrainMillis = 1_000
private const val LoopRestMillis = 250

/**
 * The most one frame may advance the fill.
 *
 * The fill runs on its own frame-counted clock rather than on wall time, and this cap is why. A
 * time-based animation that misses frames catches up by jumping, and the frames it misses are the
 * ones at cold start, when the app behind the splash is composing for the first time. The launch
 * splash also used to end on a plain `delay` that started counting before the first frame was
 * drawn, so on a slow start the viewer saw the mark appear half full and fade out before it
 * finished — "it shows half of it and then disappears". Capped, a stall pauses the water instead
 * of skipping it, and the splash waits for the fill to actually finish (see [AppLaunchSplash]).
 * 50ms is 20fps, below what any television here renders at, so an ordinary frame is never slowed.
 */
private const val MaxFrameStepMillis = 50f

/** Pours briskly, then slows as it tops out, the way a vessel fills. */
private val PourEasing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
private val DrainEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

private data class BubbleSpec(
    /** Horizontal position across the mark, 0..1. */
    val x: Float,
    /** How long one rise takes. */
    val periodMillis: Float,
    /** Start offset within the period, 0..1, so they do not rise in step. */
    val offset: Float,
    /** Radius as a share of the mark's shorter side. */
    val radius: Float,
)

private val BubbleSpecs = listOf(
    BubbleSpec(0.28f, 2_100f, 0.00f, 0.014f),
    BubbleSpec(0.46f, 1_700f, 0.41f, 0.010f),
    BubbleSpec(0.63f, 2_400f, 0.18f, 0.017f),
    BubbleSpec(0.37f, 1_900f, 0.72f, 0.008f),
    BubbleSpec(0.71f, 2_000f, 0.57f, 0.011f),
    BubbleSpec(0.54f, 2_600f, 0.86f, 0.013f),
)

/**
 * The shared app loader: the Anilili silhouette filling with liquid.
 *
 * [playOnce] is the launch splash: one pour to full, then [onFilled]. Everywhere else it loops —
 * pour, a beat full, drain, a beat empty — so whatever moment it is dismissed at, it was mid-motion
 * rather than frozen part way.
 *
 * Cheap televisions get [PlainWaterFillLogo] instead, because this loader is the one thing on
 * screen for the whole of a resolve and a resolve is not always short. A TCL UnionTV (Android 9,
 * 128 MB memory class, in-process Chromium) spent 39 minutes on the "finding source" overlay while
 * every Miruro mirror sat behind a Cloudflare interstitial, and the main-thread watchdog sampled
 * the block *inside this Canvas*, when it still opened an offscreen layer every frame to mask the
 * water with the logo bitmap. The detailed variant no longer needs a layer at all — the mask is the
 * water's shader — but it still traces two wave paths and a few dozen `sin` calls per frame, and
 * at 60fps for tens of minutes that is not free on that class of hardware.
 */
@Composable
fun WaterFillLogoIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    playOnce: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    fillDurationMillis: Int = WaterFillDurationMillis,
    vesselColor: Color = color.copy(alpha = 0.16f),
    onFilled: () -> Unit = {},
) {
    val elapsed = rememberLiquidClock(playOnce, fillDurationMillis, onFilled)
    val entranceMillis = (fillDurationMillis / 4).coerceIn(200, 500).toFloat()
    val layered = modifier
        .size(size)
        .semantics { contentDescription = "Loading" }
        .graphicsLayer {
            // The launch splash takes over from the system's splash icon, which is already this
            // empty vessel at this size, so it must not fade or grow in: that read as the logo
            // vanishing and a second one arriving.
            if (playOnce) return@graphicsLayer
            // The mark fades and settles in while the liquid is already rising, so this is a
            // share of the fill rather than a fixed time.
            val entrance = FastOutSlowInEasing.transform((elapsed.floatValue / entranceMillis).coerceAtMost(1f))
            alpha = entrance
            val scale = 0.92f + entrance * 0.08f
            scaleX = scale
            scaleY = scale
        }

    if (LocalAppDeviceProfile.current.useLowCostTvEffects) {
        PlainWaterFillLogo(layered, elapsed, playOnce, color, vesselColor, fillDurationMillis)
    } else {
        DetailedWaterFillLogo(layered, elapsed, playOnce, color, vesselColor, fillDurationMillis)
    }
}

/**
 * Milliseconds of fill the viewer has actually been shown, advanced once per frame and capped at
 * [MaxFrameStepMillis] per frame. Read only from draw and layer blocks, so a tick redraws the mark
 * and never recomposes anything.
 *
 * With system animations switched off it jumps straight to a full mark and stays there, which is
 * what every other Compose animation does under that setting.
 */
@Composable
private fun rememberLiquidClock(
    playOnce: Boolean,
    fillDurationMillis: Int,
    onFilled: () -> Unit,
): FloatState {
    val elapsed = remember { mutableFloatStateOf(if (playOnce) 0f else LoopHandover.resume()) }
    val latestOnFilled by rememberUpdatedState(onFilled)
    LaunchedEffect(playOnce, fillDurationMillis) {
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) {
            elapsed.floatValue = fillDurationMillis.toFloat()
            if (playOnce) latestOnFilled()
            return@LaunchedEffect
        }
        var reported = false
        cappedFrameSteps { step ->
            elapsed.floatValue += step / durationScale
            if (!playOnce) LoopHandover.record(elapsed.floatValue)
            // Keeps ticking once full: the bubbles are still rising through the hold and the fade,
            // and stopping the clock froze them mid-rise.
            if (playOnce && !reported && elapsed.floatValue >= fillDurationMillis) {
                reported = true
                latestOnFilled()
            }
            true
        }
    }
    return elapsed
}

/**
 * Lets a looping loader pick up where the previous one left off.
 *
 * Opening an episode shows two loaders back to back — the watch screen's "Finding a source"
 * page, then the player's overlay once a source is picked — and each used to start from an empty
 * mark. The first was usually dismissed part way, so what the viewer saw was the liquid rising
 * half way, vanishing, and starting again. A loader that appears within [WindowNanos] of another
 * one's last frame now continues its fill instead, and skips its fade-in because it is already
 * mid-motion. Main thread only, like everything else that touches it.
 */
private object LoopHandover {
    private const val WindowNanos = 1_000_000_000L
    private var elapsedMillis = 0f
    private var atNanos = Long.MIN_VALUE

    fun record(elapsed: Float) {
        elapsedMillis = elapsed
        atNanos = System.nanoTime()
    }

    fun resume(): Float =
        if (atNanos != Long.MIN_VALUE && System.nanoTime() - atNanos < WindowNanos) elapsedMillis else 0f
}

/**
 * Calls [onStep] once per frame with that frame's length in milliseconds, capped at
 * [MaxFrameStepMillis], until it returns false. Anything that has to stay in step with the fill
 * through stalled frames runs on this rather than on a wall-clock animation.
 */
internal suspend fun cappedFrameSteps(onStep: (Float) -> Boolean) {
    var lastFrame = -1L
    var running = true
    while (running) {
        withFrameNanos { now ->
            if (lastFrame >= 0) {
                running = onStep(((now - lastFrame) / 1_000_000f).coerceAtMost(MaxFrameStepMillis))
            }
            lastFrame = now
        }
    }
}

/** How full the mark is, 0..1, [elapsedMillis] into the animation. */
private fun liquidLevel(elapsedMillis: Float, playOnce: Boolean, fillDurationMillis: Int): Float {
    val rise = fillDurationMillis.toFloat()
    if (playOnce) return PourEasing.transform((elapsedMillis / rise).coerceIn(0f, 1f))
    val t = elapsedMillis % (rise + LoopHoldMillis + LoopDrainMillis + LoopRestMillis)
    return when {
        t < rise -> PourEasing.transform(t / rise)
        t < rise + LoopHoldMillis -> 1f
        t < rise + LoopHoldMillis + LoopDrainMillis ->
            1f - DrainEasing.transform((t - rise - LoopHoldMillis) / LoopDrainMillis)
        else -> 0f
    }
}

/**
 * The loader as designed: two wave layers drifting against each other, a lit meniscus on the
 * front one, and bubbles rising through it, all confined to the silhouette.
 *
 * The confinement is the notable part. Rather than draw the water and then mask it with the logo
 * — which needs an offscreen layer per frame — the rasterised silhouette *is* the brush: every
 * water shape is painted with the mask as its shader and tinted, so outside the mark there is
 * simply nothing to paint.
 */
@Composable
private fun DetailedWaterFillLogo(
    modifier: Modifier,
    elapsed: FloatState,
    playOnce: Boolean,
    color: Color,
    vesselColor: Color,
    fillDurationMillis: Int,
) {
    val front = remember { Path() }
    val back = remember { Path() }
    val surface = remember { Path() }
    Spacer(
        modifier.drawWithCache {
            val mask = silhouetteMask(size)
            val frame = silhouetteFrame(size)
            val brush = ShaderBrush(ImageShader(mask, TileMode.Clamp, TileMode.Clamp))
            val minDimension = size.minDimension
            val vessel = ColorFilter.tint(vesselColor)
            val deep = ColorFilter.tint(color, BlendMode.SrcIn)
            val shallow = ColorFilter.tint(color.copy(alpha = 0.5f), BlendMode.SrcIn)
            val lit = ColorFilter.tint(lerp(color, Color.White, 0.55f), BlendMode.SrcIn)
            val bubbleTint = ColorFilter.tint(lerp(color, Color.White, 0.4f).copy(alpha = 0.7f), BlendMode.SrcIn)
            val meniscus = Stroke(width = max(1f, minDimension * 0.012f))
            // The tallest the surface can swing from its mean; the travel overshoots the mark by
            // this much at both ends so "empty" and "full" have no crest poking in or dip left.
            val swing = minDimension * 0.05f
            val samples = 36

            onDrawBehind {
                val t = elapsed.floatValue
                val level = liquidLevel(t, playOnce, fillDurationMillis)
                // Calm at the bottom and near the top, liveliest mid-pour — a still surface at the
                // brim is what reads as "full" rather than "cut off".
                val agitation = 0.35f + 0.65f * sin(PI * level).toFloat()
                val mean = frame.bottom + swing - level * (frame.height + swing * 2f)
                val tilt = minDimension * 0.018f * agitation * sin(t / 1_550f * 2f * PI).toFloat()

                fun frontY(x: Float): Float {
                    val u = (x - frame.left) / frame.width
                    val a = sin((u * 1.25f - t / 1_900f) * 2f * PI).toFloat()
                    val b = sin((u * 2.6f + t / 1_300f + 0.3f) * 2f * PI).toFloat()
                    return mean + tilt * (u - 0.5f) * 2f + minDimension * agitation * (0.022f * a + 0.008f * b)
                }

                fun backY(x: Float): Float {
                    val u = (x - frame.left) / frame.width
                    val a = sin((u * 1.05f + t / 2_300f + 0.5f) * 2f * PI).toFloat()
                    val b = sin((u * 2.2f - t / 1_600f) * 2f * PI).toFloat()
                    return mean - minDimension * 0.014f - tilt * (u - 0.5f) * 1.4f +
                        minDimension * agitation * (0.02f * a + 0.007f * b)
                }

                fun Path.trace(y: (Float) -> Float, closed: Boolean) {
                    rewind()
                    for (sample in 0..samples) {
                        val x = frame.left + frame.width * sample / samples
                        if (sample == 0) moveTo(x, y(x)) else lineTo(x, y(x))
                    }
                    if (closed) {
                        lineTo(frame.right, size.height)
                        lineTo(frame.left, size.height)
                        close()
                    }
                }

                // The empty vessel, so the whole mark is legible before any liquid reaches it.
                drawImage(mask, colorFilter = vessel)
                if (level <= 0f) return@onDrawBehind

                back.trace(::backY, closed = true)
                drawPath(back, brush, colorFilter = shallow)
                front.trace(::frontY, closed = true)
                drawPath(front, brush, colorFilter = deep)

                if (level > 0.06f) {
                    BubbleSpecs.forEach { bubble ->
                        val life = ((t / bubble.periodMillis) + bubble.offset) % 1f
                        val x = frame.left + frame.width * bubble.x +
                            minDimension * 0.012f * sin((life * 2.2f + bubble.offset) * 2f * PI).toFloat()
                        val floor = frame.bottom
                        val top = frontY(x)
                        val radius = minDimension * bubble.radius * (0.7f + 0.5f * life)
                        val y = floor - (floor - top) * life
                        if (y - radius > top) {
                            drawCircle(
                                brush = brush,
                                radius = radius,
                                center = Offset(x, y),
                                alpha = sin(PI * life).toFloat().coerceIn(0f, 1f),
                                colorFilter = bubbleTint,
                            )
                        }
                    }
                }

                surface.trace(::frontY, closed = false)
                drawPath(surface, brush, alpha = 0.85f, style = meniscus, colorFilter = lit)
            }
        },
    )
}

/**
 * The same mark filling at the same pace, drawn with nothing a weak GPU has to think about.
 *
 * Two bitmap draws and a clip per frame: no offscreen layer, no per-frame path, no trigonometry
 * and no blend modes beyond a tint. The waterline is flat and the bubbles are gone — both are
 * surface detail on a 72dp mark, and neither is worth what they cost where the loader can be up
 * for many minutes. It keeps the vector silhouette and the eased pour, which cost nothing per
 * frame, so it reads as the same loader rather than a cheaper one.
 */
@Composable
private fun PlainWaterFillLogo(
    modifier: Modifier,
    elapsed: FloatState,
    playOnce: Boolean,
    color: Color,
    vesselColor: Color,
    fillDurationMillis: Int,
) {
    Spacer(
        modifier.drawWithCache {
            val mask = silhouetteMask(size)
            val frame = silhouetteFrame(size)
            val tint = ColorFilter.tint(color)
            val vessel = ColorFilter.tint(vesselColor)
            onDrawBehind {
                val level = liquidLevel(elapsed.floatValue, playOnce, fillDurationMillis)
                drawImage(mask, colorFilter = vessel)
                clipRect(top = frame.bottom - level * frame.height) {
                    drawImage(mask, colorFilter = tint)
                }
            }
        },
    )
}
