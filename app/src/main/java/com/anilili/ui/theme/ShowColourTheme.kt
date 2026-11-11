package com.anilili.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.ThemeAccent
import com.materialkolor.hct.Hct

/** The Appearance choices [MiruroTheme] generated the app's theme from. */
val LocalThemeSpec = staticCompositionLocalOf { ThemeSpec() }

/**
 * Below this chroma a cover colour is a grey, and a grey has no hue to theme a page with.
 *
 * AniList derives `coverImage.color` from the poster, and a monochrome or near-black poster gives
 * `#1a1a1a`-like values. Seeding from one would drain the viewer's own accent out of the page for
 * nothing, so those pages keep the app's theme.
 */
private const val MIN_SHOW_CHROMA = 16.0

/**
 * Re-themes [content] from a show's own colour: AniList's `coverImage.color`, which it picks from
 * the poster and which 92% of the catalogue carries.
 *
 * Only the seed changes. The viewer's background, colour style and contrast still apply, so the
 * page is the same kind of theme they chose, in the show's colour — Vivid pins buttons to that
 * colour exactly, Tonal softens it, AMOLED stays black. The one exception is "Accent in the player":
 * whether the player is coloured stays the viewer's choice, and a player opened from here follows it.
 *
 * A colour too grey to theme with, an unparseable one, or the Appearance switch being off all leave
 * [content] exactly as it was.
 */
@Composable
fun ShowColourTheme(colour: String?, content: @Composable () -> Unit) {
    val enabled by SettingsStore.showColourPages.collectAsState()
    val seed = remember(colour) { showSeed(colour) }
    if (!enabled || seed == null) {
        content()
        return
    }
    val spec = LocalThemeSpec.current
    val showSpec = spec.copy(accent = ThemeAccent.CUSTOM, customSeed = seed)
    val colors = remember(showSpec) { appColors(showSpec, wallpaperSeed = null) }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalThemeSpec provides showSpec,
        LocalMidnightAccent provides colors.scheme.primary,
    ) {
        MaterialTheme(colorScheme = colors.scheme, content = content)
    }
}

/** [colour] (`#RRGGBB`) as a seed worth theming with, or null. */
internal fun showSeed(colour: String?): Int? {
    val argb = colour?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }
        ?.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        ?: return null
    return argb.takeIf { Hct.fromInt(it).chroma >= MIN_SHOW_CHROMA }
}
