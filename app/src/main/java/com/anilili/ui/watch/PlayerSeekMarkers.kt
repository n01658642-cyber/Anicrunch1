package com.anilili.ui.watch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.anilili.data.model.SkipTimes

/** One named stretch of an episode the timeline draws: the recap, the opening, or the credits. */
internal data class PlayerSeekMarker(
    val label: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * The skip windows worth drawing on a timeline of [durationMs].
 *
 * The same timings both players already act on — the auto-skip and the "Skip Intro" button — read
 * as a shape on the bar rather than only as a button that appears once the viewer is already inside
 * the window. Until now the only way to find the opening was to scrub for it.
 *
 * The readings match the players' own so the drawn band and the button cannot disagree: a window
 * with no start begins at the top of the file (see PlayerSurface's `introStart ?: 0`), and a window
 * whose end is missing or lands at or before its start is not a window at all. The one exception is
 * the outro, where providers routinely publish the moment the credits begin and nothing else; that
 * still describes a real stretch, because credits run to the end of the file. An opening does not
 * get the same treatment — an intro with no end is a broken row, not an episode that is all intro.
 *
 * Everything is clamped to the duration: provider timings are frequently cut for a different encode
 * of the same episode, and an outro at 23:40 on a 22-minute file would otherwise paint off the end
 * of the bar.
 */
internal fun playerSeekMarkers(skip: SkipTimes?, durationMs: Long): List<PlayerSeekMarker> {
    if (skip == null || durationMs <= 0L) return emptyList()
    fun ms(seconds: Double?): Long? = seconds?.let { (it * 1000.0).toLong() }
    fun marker(label: String, startMs: Long?, endMs: Long?): PlayerSeekMarker? {
        val start = (startMs ?: 0L).coerceIn(0L, durationMs)
        val end = (endMs ?: return null).coerceIn(0L, durationMs)
        return if (end > start) PlayerSeekMarker(label, start, end) else null
    }
    val outro = ms(skip.outroStart)
        ?.let { start -> marker("Outro", start, ms(skip.outroEnd) ?: durationMs) }
        // Nothing else believes an outro that stops short of the end of the file, so the bar must
        // not draw one either — a band nothing acts on would be the last place the app still
        // claimed the fifth minute was the ending. See [outroWindowIsCredits].
        ?.takeIf { outroWindowIsCredits(it.startMs, it.endMs, durationMs) }
    return listOfNotNull(
        marker("Recap", ms(skip.recapStart), ms(skip.recapEnd)),
        marker("Intro", ms(skip.introStart), ms(skip.introEnd)),
        outro,
    ).sortedBy { it.startMs }
}

/**
 * Where each marker sits along the bar, as start..end fractions.
 *
 * Split out from the drawing so the arithmetic can be tested without a Canvas, and so the two bars —
 * the phone's [PlayerSeekBar] and the TV's [BufferedSeekTrack], which measure their tracks
 * differently — place a window at the same spot.
 */
internal fun playerSeekMarkerFractions(
    markers: List<PlayerSeekMarker>,
    durationMs: Long,
): List<ClosedFloatingPointRange<Float>> {
    if (durationMs <= 0L) return emptyList()
    return markers.mapNotNull { marker ->
        val start = (marker.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val end = (marker.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
        if (end > start) start..end else null
    }
}

/**
 * The colour every timeline marker is drawn in.
 *
 * Deliberately not the theme accent: the played band already carries that, and a marker painted in
 * the same colour reads as progress rather than as a stretch of the episode. Amber holds against
 * both the accent underneath it and the grey of the unplayed track, whichever accent a viewer has
 * picked.
 */
internal val PlayerSeekMarkerColor = Color(0xFFFFC14D)

/**
 * Paints [fractions] onto a track running from [left] for [span] pixels.
 *
 * Drawn over every band including the played one, so a marker stays where it is as the episode
 * plays rather than being eaten by progress. That matters most right after an auto-skip: the
 * playhead jumps, and the band it jumped over is the only thing on screen that explains why.
 *
 * Butt caps, unlike the rest of the bar: a rounded end spills half the stroke past the window at
 * both ends, which on a short recap is most of the marker.
 */
internal fun DrawScope.drawPlayerSeekMarkers(
    fractions: List<ClosedFloatingPointRange<Float>>,
    left: Float,
    span: Float,
    centreY: Float,
    strokePx: Float,
    color: Color = PlayerSeekMarkerColor,
) {
    if (span <= 0f || strokePx <= 0f) return
    val right = left + span
    fractions.forEach { fraction ->
        val startX = left + span * fraction.start
        // A window of a few seconds is a hairline at this scale, and a hairline reads as a
        // rendering fault rather than as a marker, so every one is drawn at least as wide as the
        // track is tall.
        val endX = maxOf(left + span * fraction.endInclusive, startX + strokePx).coerceAtMost(right)
        if (endX <= startX) return@forEach
        drawLine(
            color = color,
            start = Offset(startX, centreY),
            end = Offset(endX, centreY),
            strokeWidth = strokePx,
            cap = StrokeCap.Butt,
        )
    }
}
