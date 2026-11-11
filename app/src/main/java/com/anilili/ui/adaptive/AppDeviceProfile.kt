package com.anilili.ui.adaptive

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import com.anilili.data.device.TELEVISION_MIN_WIDTH_DP
import com.anilili.data.device.TelevisionSignal
import com.anilili.data.device.TelevisionSignals
import com.anilili.data.device.readTelevisionSignals
import com.anilili.data.device.televisionSignal
import com.anilili.data.settings.FormFactorPreference
import com.anilili.data.settings.SettingsStore
import kotlin.math.roundToInt

enum class AppFormFactor { PHONE, TABLET, TV }

data class AppDeviceProfile(
    val formFactor: AppFormFactor,
    val widthDp: Int,
    val isLowRamDevice: Boolean = false,
    val memoryClassMb: Int? = null,
    /**
     * The viewer's interface size, mirrored from [LocalUiScale].
     *
     * Every `Dp` on this profile scales on its own, because [AppUiScale] scales the density they
     * are resolved against. The column counts do not: television pins them to a constant, and the
     * grids that use them hand each cell a `weight(1f)` of whatever width is left — so a fixed
     * count at a larger density produces cells of exactly the same physical size as before. They
     * have to be divided by the scale by hand, which is the only reason the profile knows about it.
     */
    val uiScale: Float = 1f,
    /** Why [formFactor] is TV, or null when it is not. See [TelevisionSignal]. */
    val televisionSignal: TelevisionSignal? = null,
) {
    val isTv: Boolean get() = formFactor == AppFormFactor.TV
    val isTablet: Boolean get() = formFactor == AppFormFactor.TABLET
    val isExpanded: Boolean get() = widthDp >= 840 || isTv
    val useNavigationRail: Boolean get() = widthDp >= 600 || isTv
    val useLowCostTvEffects: Boolean
        get() = shouldUseLowCostTvEffects(isTv, isLowRamDevice, memoryClassMb)

    val pagePadding: Dp
        get() = when {
            isTv -> 48.dp
            isExpanded -> 32.dp
            isTablet -> 24.dp
            else -> 16.dp
        }

    /**
     * How wide a poster card is drawn on a rail.
     *
     * The handheld sizes are deliberately under a third of a phone's window: a rail exists to say
     * "there is more this way", and a card wide enough that only two fit shows no third edge to
     * scroll towards. Television keeps its own size — it is read from across a room, and the
     * couch layout has the width to spend.
     *
     * A phone's card follows its window, though, rather than holding 108 dp. A fixed size fits
     * three-and-a-bit across a 360–411 dp phone, but a phone with Display size turned down (or
     * simply a big one) reports 500–600 dp, and the same card left four and a half small posters on
     * a rail — reported as "too many cards". So the card is sized to leave three whole cards and a
     * third of a fourth, never below the 108 dp the common phones already had and never above the
     * television's own 150 dp.
     */
    val posterWidth: Dp
        get() = when {
            isTv -> 150.dp
            isExpanded -> 132.dp
            isTablet -> 120.dp
            else -> phoneRailPosterWidth(widthDp)
        }

    /**
     * The narrowest a Browse result card may be before the grid drops a column.
     *
     * A phone's third column needs 3 x this + two 9 dp gaps + two 16 dp margins. At 110 dp that
     * was 380 dp of window, so a 384 dp phone showed three columns while a 360 dp one — common,
     * and what any phone becomes with Display size turned up — showed two outsized cards. 100 dp
     * keeps three on anything from 350 dp.
     */
    val gridMinWidth: Dp
        get() = when {
            isTv -> 145.dp
            isExpanded -> 150.dp
            isTablet -> 132.dp
            else -> 100.dp
        }

    val episodeColumns: Int
        get() = when {
            isTv -> scaledTvColumns(TV_EPISODE_COLUMNS)
            widthDp >= 1_000 -> 8
            isExpanded -> 7
            isTablet -> 6
            else -> 5
        }

    val posterColumns: Int
        get() = when {
            isTv -> scaledTvColumns(TV_POSTER_COLUMNS)
            widthDp >= 1_000 -> 7
            isExpanded -> 6
            isTablet -> 4
            else -> 3
        }

    /**
     * [base] columns' worth of cells at the size the viewer asked for: a larger interface fits
     * fewer of them across the screen, a smaller one more. Clamped so an extreme scale can never
     * produce a grid of one enormous cell or of slivers too narrow to read an episode number in.
     */
    private fun scaledTvColumns(base: Int): Int =
        (base / uiScale).roundToInt().coerceIn(MIN_TV_COLUMNS, MAX_TV_COLUMNS)
}

/**
 * Three whole rail cards and [PHONE_RAIL_PEEK] of a fourth across a phone of [widthDp]: the window
 * less its two 16 dp margins and the three 10 dp gaps a rail puts between those cards.
 */
internal fun phoneRailPosterWidth(widthDp: Int): Dp {
    val available = widthDp - 2 * 16 - 3 * 10
    return (available / (3f + PHONE_RAIL_PEEK)).dp.coerceIn(108.dp, 150.dp)
}

private const val PHONE_RAIL_PEEK = 0.35f

private const val TV_EPISODE_COLUMNS = 8
private const val TV_POSTER_COLUMNS = 6
private const val MIN_TV_COLUMNS = 3
private const val MAX_TV_COLUMNS = 12

fun resolveAppDeviceProfile(
    uiModeType: Int,
    widthDp: Int,
    isLowRamDevice: Boolean = false,
    memoryClassMb: Int? = null,
    uiScale: Float = 1f,
    signals: TelevisionSignals = TelevisionSignals(),
    preference: FormFactorPreference = FormFactorPreference.AUTOMATIC,
): AppDeviceProfile {
    val television = televisionSignal(uiModeType, widthDp, signals, preference)
    val formFactor = when {
        television != null -> AppFormFactor.TV
        widthDp >= TELEVISION_MIN_WIDTH_DP -> AppFormFactor.TABLET
        else -> AppFormFactor.PHONE
    }
    return AppDeviceProfile(formFactor, widthDp, isLowRamDevice, memoryClassMb, uiScale, television)
}

internal fun shouldUseLowCostTvEffects(isTv: Boolean, isLowRamDevice: Boolean, memoryClassMb: Int?): Boolean =
    isTv && (isLowRamDevice || (memoryClassMb != null && memoryClassMb <= LOW_COST_TV_MEMORY_CLASS_MB))

private const val LOW_COST_TV_MEMORY_CLASS_MB = 256

val LocalAppDeviceProfile = staticCompositionLocalOf {
    AppDeviceProfile(AppFormFactor.PHONE, 360)
}

@Composable
fun rememberAppDeviceProfile(): AppDeviceProfile {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val activityManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }
    val lowRam = activityManager?.isLowRamDevice == true
    val memoryClassMb = activityManager?.memoryClass
    // The configuration's width is the device's own, unaffected by the density override the scale
    // rides on, which is what keeps the form-factor decision anchored to the real screen.
    val uiScale = LocalUiScale.current
    val signals = rememberTelevisionSignals()
    val preference by SettingsStore.formFactorPreference.collectAsState()
    return remember(uiModeType, configuration.screenWidthDp, lowRam, memoryClassMb, uiScale, signals, preference) {
        resolveAppDeviceProfile(
            uiModeType,
            configuration.screenWidthDp,
            lowRam,
            memoryClassMb,
            uiScale,
            signals,
            preference,
        )
    }
}

/**
 * [isTelevision] inside a composition, for the composables that run outside — or above —
 * [LocalAppDeviceProfile]. The theme and [AppUiScale] are both, and both have to reach the same
 * answer as the profile or a device caught by a fallback would get the couch layout wrapped in
 * handheld typography at handheld scale.
 */
@Composable
fun rememberIsTelevision(): Boolean {
    val configuration = LocalConfiguration.current
    val signals = rememberTelevisionSignals()
    val preference by SettingsStore.formFactorPreference.collectAsState()
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return remember(uiModeType, configuration.screenWidthDp, signals, preference) {
        televisionSignal(uiModeType, configuration.screenWidthDp, signals, preference) != null
    }
}

/**
 * The device's own answers, read once per process — none of them can change while it runs.
 *
 * The leanback-launcher observation is the exception and is read from settings rather than from the
 * system: it is recorded by `MainActivity` the first time the app is started from a television home
 * screen and is remembered from then on.
 */
@Composable
fun rememberTelevisionSignals(): TelevisionSignals {
    val context = LocalContext.current
    val leanbackLauncher by SettingsStore.leanbackLauncherSeen.collectAsState()
    val device = remember(context.applicationContext) {
        readTelevisionSignals(context.applicationContext)
    }
    return remember(device, leanbackLauncher) {
        device.copy(launchedFromLeanbackLauncher = leanbackLauncher)
    }
}

/**
 * Clear focus treatment for TV remotes, keyboards, and game controllers.
 *
 * **On a television it is two cues: the element lifts, and a thin white ring ([focusHalo]) stands a
 * few dp clear of it** — the ring Home's posters wear, for cards and controls alike. What follows
 * describes the glow-and-rim treatment a television had until 2026-09-29, and that phones and
 * tablets under a keyboard still get. Viewers found the television's glow too heavy: on small targets
 * it spread a grey cloud well past the control, and round a white-filled button it left the label
 * hard to read. A ring held off the element also shows round one that is itself white, which a rim
 * on its edge did not.
 *
 * Three cues, in the order a viewer reads them from the couch: the element lifts, a coloured glow
 * pools behind it, and a hairline rim separates it from whatever it is sitting on. That is the
 * shape of the treatment every large television app uses, and each layer covers a case the others
 * miss — the glow is invisible against a bright poster, the rim is invisible against a pale one,
 * and the lift is invisible when the focused thing is alone on screen.
 *
 * The rim is **white**, not the accent. A 3dp band of `MiruroAccent` around a card is a lot of
 * saturated purple at a metre's viewing distance and it reads as a drawn-on box rather than as an
 * element being picked out; against dark artwork it also has less contrast than white does, so it
 * was the loudest and the least legible option at once. The brand colour moved into the glow, where
 * it tints without outlining. This is also what keeps a focused item distinguishable from a merely
 * *selected* one — selection is drawn in the accent throughout the app.
 *
 * Both cues fade rather than snap. Focus can cross a row faster than a card can be read, and a
 * hard cut on every step makes the strip flicker.
 */
@Composable
fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = if (LocalAppDeviceProfile.current.isTv) 1.06f else 1.025f,
    /**
     * Whether to draw the rim.
     *
     * Cleared by [focusFill] for buttons and chips, which indicate focus with a solid white fill. A white rim around a white fill is a second outline
     * with a dark line between it and the thing it is outlining, which reads as a drawing mistake
     * rather than as emphasis. Everything that keeps its own colours under the cursor keeps the rim.
     */
    rim: Boolean = true,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val device = LocalAppDeviceProfile.current
    val lowCostTvEffects = device.useLowCostTvEffects
    val accent = MaterialTheme.colorScheme.primary
    if (device.isTv) {
        // The ring sits before the clip, which would otherwise shave it off; it is drawn outside
        // the element. The cheap boxes get it without the lift, as they had the rim without one.
        val progress = if (lowCostTvEffects) {
            if (focused) 1f else 0f
        } else {
            animateFloatAsState(
                targetValue = if (focused) 1f else 0f,
                animationSpec = tween(durationMillis = 140),
                label = "focus-highlight",
            ).value
        }
        return this
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .zIndex(if (focused) 1f else 0f)
            .then(if (lowCostTvEffects) Modifier else Modifier.scale(1f + (focusedScale - 1f) * progress))
            .focusHalo(focused, shape, FocusRingGap)
            .clip(shape)
    }
    if (lowCostTvEffects) {
        // The cheap boxes get the rim alone: no animation to drive, no scale to re-layout
        // neighbours around, and no elevation for a GPU without the fill rate to spare.
        return this
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .then(
                if (focused && rim) {
                    Modifier.border(FocusRimWidth, FocusRimColor, shape)
                } else {
                    Modifier
                }
            )
    }
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "focus-highlight",
    )
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        // Every layer below stays in the chain at a resting value of zero rather than being
        // swapped in and out, so gaining focus animates instead of rebuilding the modifier chain.
        .zIndex(if (focused) 1f else 0f)
        .scale(1f + (focusedScale - 1f) * progress)
        .shadow(
            elevation = FOCUS_GLOW_ELEVATION * progress,
            shape = shape,
            // The rim below already clips; clipping here as well would round the glow away.
            clip = false,
            ambientColor = accent,
            spotColor = accent,
        )
        .clip(shape)
        .border(
            FocusRimWidth,
            FocusRimColor.copy(alpha = if (rim) FocusRimColor.alpha * progress else 0f),
            shape,
        )
}

/**
 * How far a focused element lifts. Read as a glow rather than as a drop shadow: the palette is
 * near-black, so the accent-tinted spot colour is most of what actually lands on screen.
 *
 * Colour in a shadow needs API 28; below that the platform draws it black, which still reads as a
 * lift. Nothing else in the treatment depends on the version.
 */
private val FOCUS_GLOW_ELEVATION = 20.dp

/**
 * The rim [focusHighlight] draws, published for the handful of places that cannot use the modifier.
 *
 * A poster card carries its own resting hairline and swaps that same border over to the focused
 * treatment, so it needs the values rather than the whole chain. They belong in one place either
 * way: a focus rim that is white here and accent-coloured two screens over is not a convention, it
 * is two conventions.
 */
val FocusRimWidth = 2.dp
val FocusRimColor = Color.White.copy(alpha = .92f)

/**
 * The focus ring the television's artwork cards wear: a thin white line held [FocusHaloGap] clear
 * of the card instead of drawn on its edge.
 *
 * The rim hugging the artwork read as a white frame pasted onto the poster, and viewers said so.
 * Standing it off by a few dp lets the page show through between ring and card, which is what
 * makes it read as a halo around the thing picked rather than a border drawn on it — the same
 * cue Google TV uses. Its corners grow by the gap, so the ring stays concentric with the card.
 *
 * It draws *outside* the element's bounds, so it has to sit before any clip in the chain and under
 * no clipping parent narrower than the card plus the ring; a clip there shaves the ring off. The
 * fade follows [focusHighlight]'s timing, and the cheap boxes get it without the animation.
 */
@Composable
fun Modifier.focusHalo(focused: Boolean, shape: Shape, gap: Dp = FocusHaloGap): Modifier {
    val lowCostTvEffects = LocalAppDeviceProfile.current.useLowCostTvEffects
    val progress = if (lowCostTvEffects) {
        if (focused) 1f else 0f
    } else {
        animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(durationMillis = 140),
            label = "focus-halo",
        ).value
    }
    return drawWithCache {
        val stroke = FocusHaloWidth.toPx()
        val inset = gap.toPx() + stroke / 2
        val ring = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> outline.roundRect.let { card ->
                fun CornerRadius.grown() = if (x > 0f) CornerRadius(x + inset, y + inset) else this
                RoundRect(
                    left = card.left - inset,
                    top = card.top - inset,
                    right = card.right + inset,
                    bottom = card.bottom + inset,
                    topLeftCornerRadius = card.topLeftCornerRadius.grown(),
                    topRightCornerRadius = card.topRightCornerRadius.grown(),
                    bottomRightCornerRadius = card.bottomRightCornerRadius.grown(),
                    bottomLeftCornerRadius = card.bottomLeftCornerRadius.grown(),
                )
            }
            else -> RoundRect(outline.bounds.inflate(inset))
        }
        val path = Path().apply { addRoundRect(ring) }
        onDrawWithContent {
            drawContent()
            if (progress > 0f) {
                drawPath(
                    path = path,
                    color = FocusHaloColor.copy(alpha = FocusHaloColor.alpha * progress),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

val FocusHaloGap = 4.dp

/**
 * The gap for a control's ring — buttons, icons, chips, tabs, rows — as opposed to [FocusHaloGap]
 * for artwork. Held 4dp off, the ring read as floating loose around something as small as an icon
 * button; this keeps a sliver of the page between ring and control, so the ring still shows round
 * one that is itself white, while sitting close enough to belong to it.
 */
val FocusRingGap = 1.5.dp
val FocusHaloWidth = 2.dp
val FocusHaloColor = Color.White.copy(alpha = .85f)

/**
 * Highlight shape for a card whose caption sits flush against its own bottom edge.
 *
 * [focusHighlight] rounds a card by clipping it, and it clips the whole card — the strip of
 * metadata under the poster as much as the poster. At the default 12dp radius the bottom-left arc
 * still reaches about 8dp inward level with the caption's stems, which is enough to take the left
 * arm off the "T" of "TV" on every poster in the catalogue; the digits further along the line start
 * past the arc and survive, which is why the damage reads as one broken letter rather than a clip.
 *
 * Nothing is drawn in those bottom corners except the caption itself, so rounding only the top —
 * over the artwork, where the corner is the one the eye actually reads — gives up no shape that was
 * visible and hands the caption back its first letter. Cards that pad their text away from the edge
 * do not need this and should keep an evenly rounded shape.
 */
val CaptionedCardShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
