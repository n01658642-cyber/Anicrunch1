package com.anilili.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.rememberIsTelevision

/**
 * The app's one theme, generated from the Appearance settings with Material 3's colour system.
 *
 * Dark only: the site is dark-first and so is every surface here. What the viewer chooses is the
 * canvas, the seed, the scheme style and the contrast — see [appColors] — and because this is the
 * single composition root, a change reaches every screen, dialog and popup at once.
 */
@Composable
fun MiruroTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The same question the device profile asks, rather than the ui mode alone: a box detected as a
    // television by one of the fallback signals would otherwise draw the couch layout in typography
    // sized for something held in a hand.
    val isTv = rememberIsTelevision()
    val context = LocalContext.current
    val background by SettingsStore.themeBackground.collectAsState()
    val accent by SettingsStore.themeAccent.collectAsState()
    val style by SettingsStore.themeStyle.collectAsState()
    val contrast by SettingsStore.themeContrast.collectAsState()
    val playerAccent by SettingsStore.playerAccent.collectAsState()
    val customSeed by SettingsStore.themeCustomSeed.collectAsState()
    val spec = ThemeSpec(background, accent, style, contrast, playerAccent, customSeed)
    val seed = remember(isTv) { if (wallpaperAccentAvailable(isTv)) wallpaperSeed(context) else null }
    val colors = remember(spec, seed) { appColors(spec, seed) }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalThemeSpec provides spec,
        LocalMidnightAccent provides colors.scheme.primary,
    ) {
        MaterialTheme(
            colorScheme = colors.scheme,
            typography = if (isTv) MiruroTvTypography else MiruroTypography,
            content = { ProvideAppBackdrop(content) },
        )
    }
}
