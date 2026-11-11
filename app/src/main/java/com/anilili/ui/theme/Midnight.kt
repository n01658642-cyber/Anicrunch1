package com.anilili.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The "Midnight Anime" token set — the palette the dialog system is built on.
 *
 * Spec: design/midnight-anime/design.md §3. These used to be fixed colours; they are now names for
 * Material 3 roles of the theme the viewer chose in Appearance (see [appColors]), so a dialog is
 * drawn in the same scheme as the screen underneath it. Only the semantic colours — red, green,
 * amber, where colour itself means "failed", "connected", "careful" — stay fixed.
 */
object Midnight {
    private val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current

    /** Canvas. */
    val Bg: Color @Composable @ReadOnlyComposable get() = colors.scheme.background
    val Bg2: Color @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerLowest

    /** Elevated planes: sheets, dialogs, TV cards. */
    val Panel: Color @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerLow
    val Panel2: Color @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainer
    val Hover: Color @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerHigh

    /** Hairlines. [Line] inside a container, [Line2] at a container's own boundary. */
    val Line: Color @Composable @ReadOnlyComposable get() = colors.line
    val Line2: Color @Composable @ReadOnlyComposable get() = colors.line2

    val Text: Color @Composable @ReadOnlyComposable get() = colors.scheme.onSurface
    val Text2: Color @Composable @ReadOnlyComposable get() = colors.scheme.onSurfaceVariant
    val Text3: Color @Composable @ReadOnlyComposable get() = colors.text3

    val Red = Color(0xFFFF5263)
    val RedSoft = Color(0x1AFF5263)
    val Green = Color(0xFF4DD49A)
    val Amber = Color(0xFFF0BD5C)

    /** The phone sheet body. */
    val SheetSurface: Color
        @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerLowest.copy(alpha = 0.98f)

    /** The TV card body. */
    val TvSurface: Color
        @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerLowest.copy(alpha = 0.94f)

    /** The centered confirmation modal body. */
    val ModalSurface: Color
        @Composable @ReadOnlyComposable get() = colors.scheme.surfaceContainerLow.copy(alpha = 0.97f)

    /** The unselected radio ring. */
    val RadioRing: Color @Composable @ReadOnlyComposable get() = colors.radioRing

    /** The storage bar track. */
    val BarTrack: Color @Composable @ReadOnlyComposable get() = colors.barTrack

    /** Text on the filled primary action. */
    val OnAccent: Color @Composable @ReadOnlyComposable get() = colors.scheme.onPrimary

    /** Disabled primary action. */
    val DisabledFill: Color @Composable @ReadOnlyComposable get() = colors.disabledFill
    val DisabledContent: Color @Composable @ReadOnlyComposable get() = colors.disabledContent

    /** Softer red for error body text (kept legible on near-black). */
    val ErrorBody = Color(0xFFFF8A97)
}

/**
 * The accent the Midnight dialog parts draw a *selection* in.
 *
 * The theme's `primary` — white in the default monochrome theme — provided at [MiruroTheme]. It
 * stays a composition local so that a surface can still restate what "chosen" looks like without
 * every widget inside it being told: a focused row inverts it, and the player can hold it white.
 */
val LocalMidnightAccent = androidx.compose.runtime.compositionLocalOf { Color.White }
