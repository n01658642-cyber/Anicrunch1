package com.anilili.ui.adaptive

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner radii for the television browse surfaces.
 *
 * A radius in dp that looks right on a phone does not look like anything on a television. The card
 * is several times larger, it is read from across a room, and a TV box commonly runs at density 1,
 * where 12.dp is twelve actual pixels on a card five hundred wide — a corner that measurably exists
 * and visibly does not. These are sized for that distance instead, and being shared keeps the rows
 * on Home, Detail and Search from drifting apart from each other.
 */
val TvCardCornerShape = RoundedCornerShape(22.dp)

/** Smaller artwork — related titles, episode strips — kept proportionate to [TvCardCornerShape]. */
val TvSmallCardCornerShape = RoundedCornerShape(16.dp)

/**
 * [TvSmallCardCornerShape] for a card that captions its artwork, where the bottom corners would
 * clip the caption's first and last letters. See [CaptionedCardShape]; a television's larger radius
 * takes a correspondingly larger bite.
 */
val TvSmallCaptionedCardShape = TvSmallCardCornerShape.copy(
    bottomStart = CornerSize(0.dp),
    bottomEnd = CornerSize(0.dp),
)

/** Panels and dialogs, which are large enough to carry a softer corner still. */
val TvPanelCornerShape = RoundedCornerShape(24.dp)
