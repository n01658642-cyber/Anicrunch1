package com.anilili.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.anilili.data.settings.DEFAULT_CUSTOM_SEED
import com.anilili.data.settings.ThemeAccent
import com.anilili.data.settings.ThemeBackground
import com.anilili.data.settings.ThemeContrast
import com.anilili.data.settings.ThemeStyle
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/** Everything the Appearance section decides, as one value the theme is a pure function of. */
@Immutable
data class ThemeSpec(
    val background: ThemeBackground = ThemeBackground.AMOLED,
    val accent: ThemeAccent = ThemeAccent.MONOCHROME,
    val style: ThemeStyle = ThemeStyle.VIVID,
    val contrast: ThemeContrast = ThemeContrast.STANDARD,
    val playerAccent: Boolean = false,
    /** The colour behind [ThemeAccent.CUSTOM]; ignored for every other accent. */
    val customSeed: Int = DEFAULT_CUSTOM_SEED,
)

/**
 * The app's design tokens: a full Material 3 [scheme] plus the handful of roles M3 does not name
 * but the Midnight dialog parts, the TV focus treatment and the player were built on.
 *
 * Every one of the extras is derived from the same generated palettes as the scheme, so nothing in
 * the app carries a colour of its own any more — change the seed and all of it moves together.
 */
@Immutable
data class AppColors(
    val scheme: ColorScheme,
    /** The quietest legible text: captions, timestamps, the third line of a row. */
    val text3: Color,
    /** A hairline inside a container. */
    val line: Color,
    /** A hairline at a container's own edge. */
    val line2: Color,
    val radioRing: Color,
    val barTrack: Color,
    val disabledFill: Color,
    val disabledContent: Color,
    /** What a control fills with under the remote's cursor, and the copy on top of it. */
    val focusFill: Color,
    val focusContent: Color,
    /** The player's selection colour: the accent when the viewer opted in, white otherwise. */
    val playerAccent: Color,
    val onPlayerAccent: Color,
)

/** The theme in effect. Provided once, at [MiruroTheme]; the default only serves previews. */
val LocalAppColors = staticCompositionLocalOf { appColors(ThemeSpec(), wallpaperSeed = null) }

/**
 * The seed Material You extracted from the wallpaper, or null where there is none.
 *
 * Read as the system's own accent palette at tone 50 rather than re-extracted from the bitmap: it
 * is the colour the rest of the device is already themed from, which is the point of choosing it.
 * Televisions are excluded by the caller — their launchers carry no wallpaper to theme from.
 */
fun wallpaperSeed(context: Context): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getColor(android.R.color.system_accent1_500)
    } else {
        null
    }

/** Whether "From wallpaper" can mean anything on this device. */
fun wallpaperAccentAvailable(isTv: Boolean): Boolean =
    !isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The colour a picker swatch shows for [accent]. */
fun ThemeAccent.swatch(wallpaperSeed: Int?, customSeed: Int = DEFAULT_CUSTOM_SEED): Color = when {
    seed != null -> Color(seed)
    this == ThemeAccent.WALLPAPER && wallpaperSeed != null -> Color(wallpaperSeed)
    this == ThemeAccent.CUSTOM -> Color(customSeed)
    else -> Color.White
}

/** Below this tone a pinned `primary` stops reading against AMOLED black. */
private const val MIN_PRIMARY_TONE = 40.0

/**
 * Generates the whole token set from [spec] with Material 3's dynamic colour rules.
 *
 * Three deliberate departures from stock M3, each because the stock answer is wrong for this app:
 *
 *  1. **Vivid pins `primary` to the seed.** Every M3 dark scheme draws `primary` at tone 80, which is
 *     a pastel of whatever was picked — blood red arrives salmon, neon green arrives mint. Fidelity
 *     already keeps the containers true to the seed; pinning `primary` finishes the job, and
 *     `onPrimary` is chosen by contrast against it rather than assumed dark.
 *  2. **AMOLED black is real black.** M3's dark surface is tone 6. Here background, surface and the
 *     lowest container are tone 0, and the container ladder is rebuilt below it (4 / 6 / 10 / 14)
 *     so a sheet or a card still lifts off the canvas without spending a lit pixel on the canvas.
 *  3. **Hairlines stay hairlines.** The app draws `outline` as a 1dp divider on cards and rows,
 *     where M3's tone-60 outline (meant for text-field borders) reads as a drawn box. Both outline
 *     roles come from the neutral-variant palette at divider tones, rising with the contrast level.
 *
 * `surfaceVariant` is pointed at `surfaceContainerHigh`: M3 retired it in favour of the container
 * ladder, and at its stock tone 30 every card that still uses it would turn mid-grey.
 */
fun appColors(spec: ThemeSpec, wallpaperSeed: Int?): AppColors {
    val wallpaper = spec.accent == ThemeAccent.WALLPAPER && wallpaperSeed != null
    val monochrome = spec.accent == ThemeAccent.MONOCHROME ||
        (spec.accent == ThemeAccent.WALLPAPER && wallpaperSeed == null)
    val seedArgb = when {
        monochrome -> 0xFF808080.toInt()
        wallpaper -> wallpaperSeed!!
        spec.accent == ThemeAccent.CUSTOM -> spec.customSeed or 0xFF000000.toInt()
        else -> spec.accent.seed!!.toInt()
    }
    val source = Hct.fromInt(seedArgb)
    val contrast = spec.contrast.level
    val dynamic: DynamicScheme = when {
        monochrome -> SchemeMonochrome(source, true, contrast)
        else -> when (spec.style) {
            ThemeStyle.VIVID -> SchemeFidelity(source, true, contrast)
            ThemeStyle.TONAL -> SchemeTonalSpot(source, true, contrast)
            ThemeStyle.NEUTRAL -> SchemeNeutral(source, true, contrast)
            ThemeStyle.VIBRANT -> SchemeVibrant(source, true, contrast)
        }
    }
    val roles = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(dynamic))
    fun TonalPalette.at(tone: Double) = Color(tone(tone.toInt().coerceIn(0, 100)))
    val neutral = dynamic.neutralPalette
    val neutralVariant = dynamic.neutralVariantPalette
    // How far the fixed tones below move as contrast rises: 0 at standard, 1 at high.
    val lift = contrast.coerceIn(0.0, 1.0)

    var primary = roles.primary().color()
    var onPrimary = roles.onPrimary().color()
    if (!monochrome && spec.style == ThemeStyle.VIVID) {
        // The seed itself - unless it is too dark to read on a black canvas, which only a custom
        // colour can be (every preset sits above tone 40). Then its own hue, lifted to tone 50.
        primary = if (source.tone >= MIN_PRIMARY_TONE) Color(seedArgb) else Color(source.withTone(50.0).toInt())
        onPrimary = if (primary.luminance() > 0.3f) dynamic.primaryPalette.at(10.0) else Color.White
    }

    val amoled = spec.background == ThemeBackground.AMOLED
    val background = if (amoled) Color.Black else roles.background().color()
    val surface = if (amoled) Color.Black else roles.surface().color()
    val containerLowest = if (amoled) Color.Black else roles.surfaceContainerLowest().color()
    val containerLow = if (amoled) neutral.at(4.0) else roles.surfaceContainerLow().color()
    val container = if (amoled) neutral.at(6.0) else roles.surfaceContainer().color()
    val containerHigh = if (amoled) neutral.at(10.0) else roles.surfaceContainerHigh().color()
    val containerHighest = if (amoled) neutral.at(14.0) else roles.surfaceContainerHighest().color()

    val outline = neutralVariant.at(24.0 + 18.0 * lift)
    val outlineVariant = neutralVariant.at(15.0 + 12.0 * lift)

    val scheme = darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = background,
        onBackground = roles.onBackground().color(),
        surface = surface,
        onSurface = roles.onSurface().color(),
        surfaceVariant = containerHigh,
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = primary,
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = if (amoled) neutral.at(18.0) else roles.surfaceBright().color(),
        surfaceDim = if (amoled) Color.Black else roles.surfaceDim().color(),
        surfaceContainerLowest = containerLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
    )
    return AppColors(
        scheme = scheme,
        text3 = neutralVariant.at(58.0 + 14.0 * lift),
        line = outlineVariant,
        line2 = outline,
        radioRing = neutralVariant.at(40.0 + 15.0 * lift),
        barTrack = containerHighest,
        disabledFill = containerHigh,
        disabledContent = neutralVariant.at(40.0 + 10.0 * lift),
        focusFill = primary,
        focusContent = onPrimary,
        playerAccent = if (spec.playerAccent) primary else Color.White,
        onPlayerAccent = if (spec.playerAccent) onPrimary else Color.Black,
    )
}
