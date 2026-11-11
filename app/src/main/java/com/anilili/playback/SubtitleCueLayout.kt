package com.anilili.playback

import android.text.Layout
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.SettingsStore
import kotlin.math.floor

/**
 * Arranges the cues that are alive at one moment so they can all be read.
 *
 * Media3 draws each cue on its own, in the box the cue asks for, and never looks at what it already
 * drew: two cues asking for the same box are painted one on top of the other. Nothing in the player
 * resolves that collision — [androidx.media3.ui.SubtitleView] hands every cue straight to a painter
 * — so it has to be resolved before the cues reach the view.
 *
 * The move to the top edge happens before the merge, not after: it collapses lines that sat at
 * slightly different bottom anchors onto one, and cues that end up sharing an anchor have to be
 * joined rather than painted over each other.
 */
internal fun layOutSimultaneousCues(
    cues: List<Cue>,
    position: CaptionPosition,
    horizontalAnchorPercent: Int = CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT,
): List<Cue> =
    shiftCuesAcross(
        mergeCollidingCues(
            raiseCuesToTop(
                flattenAutoStackedCues(dedupeSimultaneousCues(dropVectorDrawings(cues))),
                position,
            ),
        ),
        horizontalAnchorPercent,
    )

/**
 * Removes ASS vector drawings, which media3 hands over as text.
 *
 * An ASS line in drawing mode (`{\p1}m 0 0 l 200 0 200 65 0 65{\p0}`) is not words but a shape:
 * typesetters use it for the box behind a sign or a mask over on-screen text. Media3's SSA reader
 * strips the `{…}` override blocks and keeps everything else, so the shape's path arrives as a
 * caption and is painted as "m 0 0 l 200 0 200 65 0 65" — reported on ReAnime's sign tracks,
 * whose quest-window signs each carry one, and where [mergeCollidingCues] then joined the path into
 * the middle of the sign it was meant to sit behind.
 *
 * Nothing here can draw the shape, so it is dropped: the line in the cue that is nothing but a
 * drawing path, and the whole cue when that was all it held. A path always opens with `m`
 * followed by coordinates and carries only the drawing commands and numbers after that, which no
 * line of dialogue does.
 */
internal fun dropVectorDrawings(cues: List<Cue>): List<Cue> {
    if (cues.none { it.text?.lines()?.any(::isVectorDrawing) == true }) return cues
    return cues.mapNotNull { cue ->
        val lines = cue.text?.lines() ?: return@mapNotNull cue
        if (lines.none(::isVectorDrawing)) return@mapNotNull cue
        val kept = lines.filterNot(::isVectorDrawing).filter { it.isNotBlank() }
        if (kept.isEmpty()) null else cue.buildUpon().setText(kept.joinToString("\n")).build()
    }
}

private val VECTOR_DRAWING = Regex("""\s*m\s+-?\d+(?:\.\d+)?\s+-?\d+(?:\.\d+)?(?:\s+(?:[mnlbspc]|-?\d+(?:\.\d+)?))*\s*""")

internal fun isVectorDrawing(line: String): Boolean = VECTOR_DRAWING.matches(line)

/**
 * Moves the captions across the picture, so they can be parked in a corner or out of the way of
 * whatever the frame is doing in the middle.
 *
 * A cue's horizontal position, unlike its vertical one, cannot be set from the view: padding
 * narrows the canvas the line is centred in, which drags the wrap point in with it and turns a
 * caption an eighth of the way over into one word a line. So the anchor is written onto the cue
 * instead — `ANCHOR_TYPE_MIDDLE` at a fraction of the width, which is what `SubtitlePainter` reads
 * to place the box, and which leaves the width it wraps at alone.
 *
 * Deliberately *not* paired with a [Cue.size]: media3 widens any cue that carries one out to its
 * full allotted width (`if (cueSize != DIMEN_UNSET && textWidth < availableWidth)`), so a two-word
 * line would be drawn on a background bar stretching half the screen.
 *
 * Only lines that carry no horizontal position of their own are moved — the same rule
 * [raiseCuesToTop] follows, and for the same reason: a sign the file placed beside the thing it
 * labels must stay there. The painter clamps the box into the picture, so a line too wide to move
 * simply stays where it was rather than running off the edge.
 */
internal fun shiftCuesAcross(cues: List<Cue>, anchorPercent: Int): List<Cue> {
    val anchor = anchorPercent.coerceIn(
        CaptionStyle.MIN_HORIZONTAL_ANCHOR_PERCENT,
        CaptionStyle.MAX_HORIZONTAL_ANCHOR_PERCENT,
    )
    if (anchor == CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT) return cues
    var moved = false
    val shifted = cues.map { cue ->
        if (cue.position != Cue.DIMEN_UNSET) {
            cue
        } else {
            moved = true
            cue.buildUpon()
                .setPosition(anchor / 100f)
                .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                .build()
        }
    }
    return if (moved) shifted else cues
}

/**
 * Holds the captions against the top edge instead of the bottom, for the encodes that burn their
 * own subtitles across the bottom of the frame.
 *
 * Only lines resting on the default bottom anchor are moved — cues carrying no line of their own
 * (WebVTT, SubRip), and the `line 0.95 / ANCHOR_TYPE_END` that media3's SSA reader hands every ASS
 * line written without a `\pos`. A sign the file placed itself, and a caption already sitting in
 * the upper half, stay where they were put: moving those would tear them off the thing they label.
 * Numbered lines (CEA-608 roll-up rows) carry their own layout and are left alone too.
 *
 * The gap from the edge is not applied here. It is padding on the subtitle view, so it holds
 * whichever edge the captions were anchored to and keeps the text size — which media3 derives from
 * the padded height — the same either way.
 */
internal fun raiseCuesToTop(cues: List<Cue>, position: CaptionPosition): List<Cue> {
    if (position != CaptionPosition.TOP) return cues
    var moved = false
    val raised = cues.map { cue ->
        if (!cue.restsOnTheBottom()) {
            cue
        } else {
            moved = true
            cue.buildUpon().setLine(0f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build()
        }
    }
    return if (moved) raised else cues
}

private fun Cue.restsOnTheBottom(): Boolean = when {
    line == Cue.DIMEN_UNSET -> true
    lineType != Cue.LINE_TYPE_FRACTION -> false
    else -> lineAnchor == Cue.ANCHOR_TYPE_END && line >= 0.5f
}

/**
 * Undoes the upward stack media3 builds for WebVTT lines that carry no position of their own.
 *
 * `WebvttSubtitle.getCues` implements the WebVTT rule for unpositioned cues: it sorts everything
 * alive at that moment by start time and hands out `line -1`, `-2`, … as `LINE_TYPE_NUMBER`.
 * Negative rows count up from the bottom, so the cue that started *first* is placed at the bottom
 * and each later one is stacked above it.
 *
 * That is right for two people talking over each other. It is backwards for the far commoner case
 * of one sentence split across two cues whose times overlap by a moment: the second half of the
 * sentence is drawn above the first, and the caption reads bottom-to-top. Reported on Vampire
 * Knight, on every episode and from every source, because the ordering never depended on the file.
 *
 * It is also invisible to [mergeCollidingCues], which is the code meant to handle exactly this:
 * media3 has already given the cues *different* lines, so they no longer share a box and are never
 * joined. And because a stacked cue's [Cue.lineType] is no longer `LINE_TYPE_FRACTION`, none of
 * them counted as resting on the bottom either — so [raiseCuesToTop] skipped them and the Top
 * caption position did nothing at all for WebVTT, single line or not.
 *
 * A run is only treated as media3's own if its rows are exactly -1 … -n with nothing missing and
 * everything else about the box identical. A file that positions its own lines does not produce
 * that shape, so signs and placed captions are left alone. The joined block is returned
 * unpositioned, which is where an unpositioned cue belongs and what lets the rest of this file
 * treat it like any SubRip line.
 */
internal fun flattenAutoStackedCues(cues: List<Cue>): List<Cue> {
    if (cues.isEmpty()) return cues
    val stack = cues.filter { it.autoStackedRow() != null }
    if (stack.isEmpty()) return cues
    // Media3 numbers every unpositioned cue alive at that moment in one sequence, so a genuine
    // stack is rows -1 … -n with nothing missing or repeated. Anything else — a file that placed
    // its own numbered lines, or a row that survived beside them — is left exactly as it is.
    if (stack.mapNotNull { it.autoStackedRow() }.toSet() != (1..stack.size).toSet()) return cues
    // Those rows say nothing about the horizontal placement, so cues the file pushed to opposite
    // sides can share the sequence. Joining those would drag a line off what it was placed beside.
    if (stack.distinctBy { it.stackBox() }.size != 1) return cues

    // Sorted by row so the earliest cue — media3's row -1, the bottom one — reads first.
    val ordered = stack.sortedByDescending { it.line }
    val block = ordered.first().buildUpon()
        .setText(ordered.joinToString("\n") { it.text.toString().trim() })
        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setLineAnchor(Cue.TYPE_UNSET)
        .build()

    val result = ArrayList<Cue>(cues.size - stack.size + 1)
    for (cue in cues) {
        when {
            cue.autoStackedRow() == null -> result += cue
            // Emitted once, in the slot the first of its cues occupied, so unrelated cues keep
            // their order relative to the block.
            stack.first() === cue -> result += block
        }
    }
    return result
}

/** The row media3 assigned, as a positive depth from the bottom, or null if it did not assign one. */
private fun Cue.autoStackedRow(): Int? {
    if (lineType != Cue.LINE_TYPE_NUMBER || text.isNullOrBlank()) return null
    if (line >= 0f || line != floor(line)) return null
    return -line.toInt()
}

/** Exactly rows -1 … -n, each once: the shape media3 produces and a file is unlikely to. */
private fun List<Cue>.isAutoStack(): Boolean {
    val rows = mapNotNull { it.autoStackedRow() }
    return rows.size == size && rows.toSet() == (1..size).toSet()
}

/** A cue's box with the row left out, so the members of one stack group together. */
private data class StackBox(
    val lineAnchor: Int,
    val position: Float,
    val positionAnchor: Int,
    val size: Float,
    val verticalType: Int,
    val textSize: Float,
    val textSizeType: Int,
    val textAlignment: Layout.Alignment?,
    val multiRowAlignment: Layout.Alignment?,
)

private fun Cue.stackBox() = StackBox(
    lineAnchor = lineAnchor,
    position = position,
    positionAnchor = positionAnchor,
    size = size,
    verticalType = verticalType,
    textSize = textSize,
    textSizeType = textSizeType,
    textAlignment = textAlignment,
    multiRowAlignment = multiRowAlignment,
)

/**
 * Drops cues that repeat a line already on screen at the same moment.
 *
 * Reported as "double subtitles": the same sentence drawn twice, half a line apart, because two
 * identical cues were active together and neither carries an explicit line position, so the view
 * stacks them. It comes from the subtitle file, not from track selection — one text renderer is
 * showing one track. A cue spanning an HLS segment boundary can be emitted by the segment on each
 * side of it, and ASS-to-VTT conversions routinely duplicate dialogue that carried a second style.
 *
 * Identical text shown twice at once is never intentional, so keeping the first is always right.
 * Bitmap cues (no [Cue.text]) are never compared and always pass through.
 */
internal fun dedupeSimultaneousCues(cues: List<Cue>): List<Cue> {
    if (cues.size < 2) return cues
    val seen = HashSet<String>(cues.size)
    val kept = ArrayList<Cue>(cues.size)
    for (cue in cues) {
        val text = cue.text?.toString()?.trim()
        if (text.isNullOrEmpty() || seen.add(text)) kept += cue
    }
    return if (kept.size == cues.size) cues else kept
}

/**
 * Joins cues that ask for the same box into one block, so they read as consecutive lines instead of
 * being painted over each other.
 *
 * A file with several dialogue lines running at once is normal — the two halves of an exchange, a
 * translated sign next to speech, a karaoke line under a title — and it is what "multi-layer
 * subtitles overlap" looks like. Media3's SSA reader gives every line that carries no `\pos` the
 * default position for its alignment, so a whole scene's worth of bottom-centred lines share one
 * anchor; WebVTT and SubRip lines carry no position at all, which anchors them to the same bottom
 * edge. Either way the painter puts them in the same place, and a taller cue reads as the shorter
 * one's letters showing through the gaps.
 *
 * Only cues that would genuinely land on top of each other are joined: the whole geometry the
 * painter uses — line, position, both anchors, size, and text metrics — has to match. Lines placed
 * apart, whether by the file or by different alignments, are left exactly where the file put them.
 *
 * Spans are dropped when joining because the player paints every cue in the viewer's chosen caption
 * style (`setApplyEmbeddedStyles(false)`), so a cue's own colours and weights never reach the
 * screen anyway. Blank and bitmap cues never take part.
 */
internal fun mergeCollidingCues(cues: List<Cue>): List<Cue> {
    if (cues.size < 2) return cues
    val boxes = LinkedHashMap<CueBox, MutableList<Cue>>()
    var collides = false
    for (cue in cues) {
        if (cue.text.isNullOrBlank()) continue
        val sharing = boxes.getOrPut(cue.box()) { ArrayList(2) }
        sharing += cue
        if (sharing.size > 1) collides = true
    }
    if (!collides) return cues

    val merged = ArrayList<Cue>(boxes.size)
    for (cue in cues) {
        val sharing = if (cue.text.isNullOrBlank()) null else boxes[cue.box()]
        when {
            // A bitmap or blank cue has no box of its own to share; it keeps its place in the list.
            sharing == null -> merged += cue
            // Every cue of a box is drawn where the first one asked to be, so that is where the
            // joined block goes; the rest have already been folded into it.
            sharing.first() !== cue -> Unit
            sharing.size == 1 -> merged += cue
            else -> merged += cue.buildUpon()
                .setText(sharing.joinToString("\n") { it.text.toString().trim() })
                .build()
        }
    }
    return merged
}

/**
 * Everything [androidx.media3.ui.SubtitlePainter] reads to decide where a cue goes and how big its
 * text is. Two cues with the same box are drawn in the same pixels.
 */
private data class CueBox(
    val line: Float,
    val lineType: Int,
    val lineAnchor: Int,
    val position: Float,
    val positionAnchor: Int,
    val size: Float,
    val verticalType: Int,
    val textSize: Float,
    val textSizeType: Int,
    val textAlignment: Layout.Alignment?,
    val multiRowAlignment: Layout.Alignment?,
)

private fun Cue.box() = CueBox(
    line = line,
    lineType = lineType,
    lineAnchor = lineAnchor,
    position = position,
    positionAnchor = positionAnchor,
    size = size,
    verticalType = verticalType,
    textSize = textSize,
    textSizeType = textSizeType,
    textAlignment = textAlignment,
    multiRowAlignment = multiRowAlignment,
)

/**
 * Lays the cues out on their way from the text renderer to the view.
 *
 * The caption position is read per pass rather than captured at construction, so switching it from
 * the player settings needs no reload. The renderer only emits when the cues change, so the line
 * already on screen keeps the edge it was placed against until the next one replaces it.
 */
@UnstableApi
internal class CueLayoutTextOutput(private val delegate: TextOutput) : TextOutput {
    private val position: CaptionPosition get() = SettingsStore.captionStyle.value.position
    private val horizontalAnchor: Int get() = SettingsStore.captionStyle.value.horizontalAnchorPercent

    override fun onCues(cueGroup: CueGroup) {
        val cues = layOutSimultaneousCues(cueGroup.cues, position, horizontalAnchor)
        delegate.onCues(
            if (cues === cueGroup.cues) cueGroup else CueGroup(cues, cueGroup.presentationTimeUs),
        )
    }

    @Deprecated("Media3 keeps calling this alongside onCues(CueGroup); both reach the view.")
    override fun onCues(cues: List<Cue>) {
        @Suppress("DEPRECATION")
        delegate.onCues(layOutSimultaneousCues(cues, position, horizontalAnchor))
    }
}
