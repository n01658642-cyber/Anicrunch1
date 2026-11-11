package com.anilili.ui.watch

/**
 * The fixed-length skip is a fallback for shows whose intro timings are missing or wrong, so it is
 * offered for the whole episode: openings do not start at a predictable moment, and a window around
 * the ends left the button absent exactly when a viewer reached an opening at 1:30.
 *
 * It does not sit over the picture regardless — both players only draw it while the controls are
 * up, so it rides with the timeline it acts on rather than floating over a scene.
 *
 * All this rules out is a timeline the button cannot act on sensibly: an unknown duration (live, or
 * a source still being probed) and a position outside it.
 */
internal fun shouldShowManualSkipButton(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs in 0L..durationMs
