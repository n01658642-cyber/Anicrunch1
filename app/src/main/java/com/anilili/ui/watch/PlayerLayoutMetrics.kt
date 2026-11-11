package com.anilili.ui.watch

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How large the player draws itself for the space it has been given.
 *
 * The chrome used to size itself from two booleans — narrower than 500dp, shorter than 300dp — which
 * is enough to tell a phone from a tablet and nothing else. Everything between those two answers got
 * the same numbers, so the controls that suit a phone in landscape were also what a 10" tablet and a
 * television got: a play button lost in the middle of the screen and a title set far too small to
 * read from across a room.
 *
 * The tiers are chosen by width because that is what the player is laid out along; the height only
 * decides whether there is room for the parts that can be dropped ([compactHeight]).
 */
internal data class PlayerLayoutMetrics(
    val horizontalPadding: Dp,
    val barTopPadding: Dp,
    val centerGap: Dp,
    val playButtonSize: Dp,
    val playGlyphSize: Dp,
    val seekTrackHeight: Dp,
    val seekTouchHeight: Dp,
    val timeTextSize: TextUnit,
    val titleTextSize: TextUnit,
    val edgeInset: Dp,
    /** Whether the bottom row has room to write a word beside each glyph. */
    val showPillLabels: Boolean,
    /** No room for the title block and the time row both; the title is what goes. */
    val compactHeight: Boolean,
)

/**
 * The tier boundaries, in dp of available width.
 *
 * 500 is where a phone in landscape stops being cramped, 900 is about a small tablet, and 1400 is a
 * television or a desktop-sized window. They are deliberately the same shape as the two thresholds
 * the player used before, so nothing that was already sized correctly moves.
 */
internal object PlayerWidthTier {
    const val COMPACT_DP = 500
    const val MEDIUM_DP = 900
    const val LARGE_DP = 1400
}

internal fun playerLayoutMetrics(widthDp: Int, heightDp: Int): PlayerLayoutMetrics {
    val compactHeight = heightDp < 300
    return when {
        widthDp >= PlayerWidthTier.LARGE_DP -> PlayerLayoutMetrics(
            horizontalPadding = 28.dp,
            barTopPadding = 26.dp,
            centerGap = 34.dp,
            playButtonSize = 74.dp,
            playGlyphSize = 36.dp,
            seekTrackHeight = 6.dp,
            seekTouchHeight = 34.dp,
            timeTextSize = 15.sp,
            titleTextSize = 24.sp,
            edgeInset = 32.dp,
            showPillLabels = true,
            compactHeight = compactHeight,
        )
        widthDp >= PlayerWidthTier.MEDIUM_DP -> PlayerLayoutMetrics(
            horizontalPadding = 22.dp,
            barTopPadding = 24.dp,
            centerGap = 26.dp,
            playButtonSize = 64.dp,
            playGlyphSize = 31.dp,
            seekTrackHeight = 5.dp,
            seekTouchHeight = 30.dp,
            timeTextSize = 14.sp,
            titleTextSize = 21.sp,
            edgeInset = 28.dp,
            showPillLabels = true,
            compactHeight = compactHeight,
        )
        widthDp >= PlayerWidthTier.COMPACT_DP -> PlayerLayoutMetrics(
            horizontalPadding = 16.dp,
            barTopPadding = if (compactHeight) 8.dp else 24.dp,
            centerGap = 16.dp,
            playButtonSize = 54.dp,
            playGlyphSize = 26.dp,
            seekTrackHeight = 4.dp,
            seekTouchHeight = 28.dp,
            timeTextSize = 13.sp,
            titleTextSize = 18.sp,
            edgeInset = 24.dp,
            showPillLabels = false,
            compactHeight = compactHeight,
        )
        else -> PlayerLayoutMetrics(
            horizontalPadding = 8.dp,
            barTopPadding = if (compactHeight) 8.dp else 20.dp,
            centerGap = 8.dp,
            playButtonSize = 48.dp,
            playGlyphSize = 23.dp,
            seekTrackHeight = 4.dp,
            seekTouchHeight = 26.dp,
            timeTextSize = 12.sp,
            titleTextSize = 16.sp,
            edgeInset = 16.dp,
            showPillLabels = false,
            compactHeight = compactHeight,
        )
    }
}

/**
 * The metrics the player chrome is currently drawing at.
 *
 * Provided once by the scaffold and read by the leaves, so a pill or a pill's label does not have to
 * be handed the size of the screen through every slot it happens to be composed in.
 */
internal val LocalPlayerLayoutMetrics = staticCompositionLocalOf {
    playerLayoutMetrics(widthDp = PlayerWidthTier.COMPACT_DP, heightDp = 400)
}
