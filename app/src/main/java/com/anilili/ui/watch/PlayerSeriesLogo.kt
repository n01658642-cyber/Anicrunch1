package com.anilili.ui.watch

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.anilili.ui.components.SeriesLogo

/**
 * The series' logo for whatever the player is showing, or null where the tree has none.
 *
 * A composition local rather than one more parameter, because the name is drawn four layers down
 * in four different places — the phone controls, the TV controls, the embed controls and the pause
 * billing — and none of the layers in between has any use for it. Provided once, around the player
 * pane, by the screen that knows which series it is.
 */
val LocalPlayerSeriesLogo = staticCompositionLocalOf<String?> { null }

/**
 * The series' name the way streaming apps bill it over the picture: its own logo, as on the anime
 * page, with [fallback] (the title set in type) wherever there is no logo or it does not load.
 *
 * Same request as the anime page's header, so it is normally a memory-cache hit — no second
 * download and no second trim on a television that has just opened the page it came from.
 */
@Composable
internal fun PlayerSeriesName(
    title: String,
    maxHeight: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    SeriesLogo(
        url = LocalPlayerSeriesLogo.current,
        contentDescription = title,
        modifier = modifier.heightIn(max = maxHeight).widthIn(max = maxWidth),
        fallback = fallback,
    )
}
