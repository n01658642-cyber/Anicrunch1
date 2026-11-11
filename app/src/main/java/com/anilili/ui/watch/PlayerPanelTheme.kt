package com.anilili.ui.watch

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.LocalAppColors
import com.anilili.ui.theme.LocalMidnightAccent

/**
 * The palette every panel the player opens is drawn in: black, white and the greys between.
 *
 * Over a picture there is no room for a second accent. The brand purple that marks a selection
 * everywhere else in the app competes with whatever is on screen behind the panel, and against a
 * frame that happens to be violet it stops marking anything at all — so inside the player,
 * selection is told the same way focus is: by how bright the fill is. White is chosen, a fifth of
 * white is selected-but-not-pointed-at, and everything else is a wash over the video.
 *
 * Applied as a theme rather than widget by widget so that a slider, a chip, a switch and a
 * progress spinner all arrive monochrome without each one being told: Material reads its colours
 * from here.
 *
 * The one exception is the viewer's own: Appearance ▸ "Accent in the player" swaps the white for
 * the theme's accent. Off by default, for the reasons above; it is a choice, not a regression.
 */
@Composable
fun PlayerPanelTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val colors = LocalAppColors.current
    CompositionLocalProvider(LocalMidnightAccent provides colors.playerAccent) {
    MaterialTheme(
        colorScheme = base.copy(
            primary = colors.playerAccent,
            onPrimary = colors.onPlayerAccent,
            primaryContainer = PlayerPanelSelected,
            onPrimaryContainer = Color.White,
            secondary = Color.White,
            onSecondary = Color.Black,
            // What Material fills a *selected* FilterChip with.
            secondaryContainer = PlayerPanelSelected,
            onSecondaryContainer = Color.White,
            tertiary = Color.White,
            surface = PlayerPanelSurface,
            onSurface = Color.White,
            surfaceVariant = PlayerPanelRaised,
            onSurfaceVariant = PlayerPanelMutedText,
            outline = PlayerPanelOutline,
            outlineVariant = PlayerPanelOutline,
        ),
        typography = MaterialTheme.typography,
        content = content,
    )
    }
}

/** The panel itself: near-black rather than black, so its edge reads against the video behind it. */
val PlayerPanelSurface = Color(0xFF0C0C0C)

/**
 * The frame every picker the player opens shares: episodes, captions, settings and servers all come
 * in from the right edge at full height with these corners on the side facing the picture. They
 * had grown four looks between them — a navy drawer, a panel with a white strip down its edge, and
 * a card floating in the middle of the screen — for what is one kind of thing.
 */
val PlayerPanelShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

/** A row or card inside a panel, one step up from it. */
val PlayerPanelRaised = Color(0xFF17171A)

/** Chosen, but not where the cursor is — the fifth of white the anime page's tabs use. */
val PlayerPanelSelected = Color.White.copy(alpha = 0.2f)

/** Labels, captions and the second line of a row. */
val PlayerPanelMutedText = Color.White.copy(alpha = 0.64f)

val PlayerPanelOutline = Color.White.copy(alpha = 0.18f)

/**
 * The player pane's accent: white, unless the viewer turned on Appearance ▸ "Accent in the player".
 *
 * [PlayerPanelTheme] covers the panels the player opens, but the controls themselves read the
 * app's accent directly - the TV focus fill on Play, a skip button's outline, the dialog accent -
 * so with the switch off a blood-red theme still lit the Play button red over the picture. This
 * restates all three for everything inside the pane, which is what "the player stays monochrome"
 * has to mean to hold.
 */
@Composable
fun PlayerAccentScope(content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    val playerColors = remember(colors) {
        colors.copy(focusFill = colors.playerAccent, focusContent = colors.onPlayerAccent)
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, colors) {
        base.copy(primary = colors.playerAccent, onPrimary = colors.onPlayerAccent)
    }
    CompositionLocalProvider(
        LocalAppColors provides playerColors,
        LocalMidnightAccent provides colors.playerAccent,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
