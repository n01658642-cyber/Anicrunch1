package com.anilili.playback

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import com.anilili.data.settings.CaptionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two identical cues alive at the same moment are what "double subtitles" looks like: the view
 * stacks them half a line apart. Only exact repeats collapse — everything else is a real second
 * line and must survive.
 */
@UnstableApi
class SimultaneousCueDedupeTest {
    private fun cue(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun repeatedLineIsShownOnce() {
        val cues = listOf(cue("Just the seven of you."), cue("Just the seven of you."))
        assertEquals(listOf("Just the seven of you."), dedupeSimultaneousCues(cues).map { it.text })
    }

    @Test
    fun repeatWithSurroundingWhitespaceStillCollapses() {
        val cues = listOf(cue("Line"), cue("  Line \n"))
        assertEquals(1, dedupeSimultaneousCues(cues).size)
    }

    @Test
    fun genuinelyDifferentLinesBothSurvive() {
        val cues = listOf(cue("Top line"), cue("Bottom line"))
        assertEquals(cues.size, dedupeSimultaneousCues(cues).size)
    }

    @Test
    fun untouchedListKeepsItsIdentitySoNoCueGroupIsAllocated() {
        val cues = listOf(cue("Only line"), cue("Other line"))
        assertSame(cues, dedupeSimultaneousCues(cues))
    }

    /**
     * Textless cues — bitmap subtitles, which a JVM test cannot build — take the same branch as
     * these blank ones: no text to compare, so they are never treated as repeats of each other.
     */
    @Test
    fun cuesWithNoTextToCompareAllSurvive() {
        val cues = listOf(cue(""), cue("   "))
        assertEquals(cues.size, dedupeSimultaneousCues(cues).size)
    }
}

/**
 * An ASS drawing reaches the player as the text of its path; ReAnime's sign tracks painted
 * "m 0 0 l 200 0 200 65 0 65" into the middle of a sign.
 */
@UnstableApi
class VectorDrawingCueTest {
    private fun cue(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun drawingPathsAreRecognised() {
        assertTrue(isVectorDrawing("m 0 0 l 200 0 200 65 0 65"))
        assertTrue(isVectorDrawing(" m -12.5 4 b 10 10 20 20 30 30 l 0 0 "))
        assertTrue(isVectorDrawing("m 0 0 s 100 0 100 100 0 100 c"))
    }

    @Test
    fun dialogueIsNeverADrawing() {
        listOf("m", "m 0", "mm 0 0", "Clear Reward: Title", "m 0 0 lol", "I'm 20 0 years old", "M 0 0 l 1 1")
            .forEach { assertEquals(it, false, isVectorDrawing(it)) }
    }

    @Test
    fun aCueThatIsOnlyADrawingIsDropped() {
        val cues = listOf(cue("m 0 0 l 200 0 200 65 0 65"), cue("Dude! That reward is insane!"))
        assertEquals(listOf("Dude! That reward is insane!"), dropVectorDrawings(cues).map { it.text.toString() })
    }

    @Test
    fun aDrawingLineInsideASignIsRemovedAndTheRestKept() {
        val cues = listOf(cue("For the Villagers of Winston (A)\nm 0 0 l 200 0 200 65 0 65\nYou have abandoned"))
        assertEquals(
            listOf("For the Villagers of Winston (A)\nYou have abandoned"),
            dropVectorDrawings(cues).map { it.text.toString() },
        )
    }

    @Test
    fun cuesWithoutDrawingsKeepTheirIdentity() {
        val cues = listOf(cue("Only line"), cue("Other line"))
        assertSame(cues, dropVectorDrawings(cues))
    }
}

/**
 * Cues that ask for the same box are painted over each other, so they have to be joined into one
 * block before the view sees them — and cues the file placed apart have to be left alone, because
 * joining those would move a line away from the thing it is captioning.
 */
@UnstableApi
class CollidingCueMergeTest {
    /** What media3's SSA reader produces for a bottom-centred line that carries no `\pos`. */
    private fun bottomCentred(text: String): Cue = Cue.Builder()
        .setText(text)
        .setLine(0.95f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    private fun unpositioned(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun linesSharingOneAnchorBecomeOneBlockInFileOrder() {
        val cues = listOf(bottomCentred("Zhu Qianning"), bottomCentred("This year's competition"))
        val laidOut = mergeCollidingCues(cues)
        assertEquals(1, laidOut.size)
        assertEquals("Zhu Qianning\nThis year's competition", laidOut.single().text.toString())
    }

    @Test
    fun theBlockIsDrawnWhereTheFirstCueAskedToBe() {
        val cues = listOf(bottomCentred("First"), bottomCentred("Second"))
        val block = mergeCollidingCues(cues).single()
        assertEquals(0.95f, block.line, 0f)
        assertEquals(Cue.LINE_TYPE_FRACTION, block.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, block.lineAnchor)
        assertEquals(0.5f, block.position, 0f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, block.positionAnchor)
    }

    /** WebVTT and SubRip lines carry no position at all, which is the same collision. */
    @Test
    fun unpositionedLinesCollideToo() {
        val cues = listOf(unpositioned("Top"), unpositioned("Bottom"))
        assertEquals("Top\nBottom", mergeCollidingCues(cues).single().text.toString())
    }

    @Test
    fun aSignThatCarriesItsOwnPositionIsLeftWhereItIs() {
        val sign = bottomCentred("Maiden Peak").buildUpon().setLine(0.2f, Cue.LINE_TYPE_FRACTION).build()
        val cues = listOf(sign, bottomCentred("Dialogue"))
        assertEquals(cues.size, mergeCollidingCues(cues).size)
    }

    @Test
    fun linesAtTheSameHeightButOppositeSidesStayApart() {
        val left = bottomCentred("Xinxin").buildUpon()
            .setPosition(0.1f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .build()
        val cues = listOf(left, bottomCentred("Zhu Qianning"))
        assertEquals(cues.size, mergeCollidingCues(cues).size)
    }

    @Test
    fun untouchedListKeepsItsIdentitySoNoCueGroupIsAllocated() {
        val cues = listOf(bottomCentred("Only line"))
        assertSame(cues, mergeCollidingCues(cues))
        val apart = listOf(bottomCentred("Down here"), unpositioned("Somewhere else"))
        assertSame(apart, mergeCollidingCues(apart))
    }

    @Test
    fun surroundingBlankLinesDoNotOpenGapsInTheBlock() {
        val cues = listOf(bottomCentred("First\n"), bottomCentred("  Second  "))
        assertEquals("First\nSecond", mergeCollidingCues(cues).single().text.toString())
    }

    /**
     * A blank cue is nothing to read, so it is neither folded into the block nor allowed to push a
     * line off the bottom of it.
     */
    @Test
    fun blankCuesArePassedThroughRatherThanJoined() {
        val cues = listOf(bottomCentred("First"), bottomCentred("   "), bottomCentred("Second"))
        val laidOut = mergeCollidingCues(cues)
        assertEquals(listOf("First\nSecond", "   "), laidOut.map { it.text.toString() })
    }

    /** Repeats are dropped before the rest are joined, so a doubled line is not doubled in place. */
    @Test
    fun theRepeatIsDroppedBeforeTheBlockIsBuilt() {
        val cues = listOf(bottomCentred("Same line"), bottomCentred("Same line"), bottomCentred("Other"))
        val laidOut = layOutSimultaneousCues(cues, CaptionPosition.BOTTOM)
        assertEquals("Same line\nOther", laidOut.single().text.toString())
    }
}

/**
 * Sending the captions to the top edge is for the encodes that burn their own subtitles across the
 * bottom of the frame. It moves the lines resting on the default bottom anchor and nothing else: a
 * sign the file placed itself belongs with the thing it labels, wherever that is.
 */
@UnstableApi
class CaptionsAtTheTopTest {
    private fun bottomAnchored(text: String, line: Float): Cue = Cue.Builder()
        .setText(text)
        .setLine(line, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .build()

    private fun raise(vararg cues: Cue) = raiseCuesToTop(cues.toList(), CaptionPosition.TOP)

    /** WebVTT and SubRip lines, which media3 pins to the bottom edge for want of a line. */
    @Test
    fun aLineWithNoPositionOfItsOwnGoesToTheTop() {
        val raised = raise(Cue.Builder().setText("Line").build()).single()
        assertEquals(0f, raised.line, 0f)
        assertEquals(Cue.LINE_TYPE_FRACTION, raised.lineType)
        assertEquals(Cue.ANCHOR_TYPE_START, raised.lineAnchor)
    }

    /** The default media3's SSA reader gives every ASS line written without a `\pos`. */
    @Test
    fun theSsaDefaultBottomAnchorGoesToTheTop() {
        assertEquals(0f, raise(bottomAnchored("Dialogue", 0.95f)).single().line, 0f)
    }

    @Test
    fun aSignAlreadyPlacedInTheUpperHalfIsNotMoved() {
        val sign = bottomAnchored("Maiden Peak", 0.2f)
        assertSame(sign, raise(sign).single())
    }

    /** CEA-608 roll-up rows carry their own line layout; a fraction would throw it away. */
    @Test
    fun numberedLinesKeepTheirOwnLayout() {
        val row = Cue.Builder().setText("Row").setLine(-2f, Cue.LINE_TYPE_NUMBER).build()
        assertSame(row, raise(row).single())
    }

    @Test
    fun theBottomPositionChangesNothingAtAll() {
        val cues = listOf(bottomAnchored("Line", 0.95f), Cue.Builder().setText("Other").build())
        assertSame(cues, raiseCuesToTop(cues, CaptionPosition.BOTTOM))
    }

    /**
     * Lines that sat at slightly different bottom anchors all arrive at the top on the same one, so
     * the move has to happen before the merge or they are painted over each other up there.
     */
    @Test
    fun linesGatheredAtTheTopAreJoinedRatherThanStacked() {
        val cues = listOf(bottomAnchored("First", 0.95f), bottomAnchored("Second", 0.9f))
        val laidOut = layOutSimultaneousCues(cues, CaptionPosition.TOP)
        assertEquals("First\nSecond", laidOut.single().text.toString())
        assertEquals(0f, laidOut.single().line, 0f)
    }
}

/**
 * Media3 implements the WebVTT rule for cues with no line of their own: everything alive at that
 * moment is sorted by start time and handed rows -1, -2, … which count *up* from the bottom. The
 * cue that started first therefore lands at the bottom and later ones stack above it — so a
 * sentence split across two overlapping cues reads bottom-to-top.
 */
@UnstableApi
class AutoStackedCueTest {
    private fun stacked(text: String, row: Int) = Cue.Builder()
        .setText(text)
        .setLine(-row.toFloat(), Cue.LINE_TYPE_NUMBER)
        .build()

    @Test
    fun aSplitSentenceReadsTopToBottomAgain() {
        // Reported on Vampire Knight: "all the proper channels" was drawn above the half of the
        // sentence that comes before it.
        val cues = listOf(
            stacked("Maria Kurenai went through", 1),
            stacked("all the proper channels", 2),
        )

        val block = flattenAutoStackedCues(cues).single()

        assertEquals("Maria Kurenai went through\nall the proper channels", block.text.toString())
    }

    @Test
    fun theOrderComesFromTheRowNotTheListOrder() {
        val cues = listOf(
            stacked("with a weak constitution...", 2),
            stacked("Other than that, she was born", 1),
        )

        assertEquals(
            "Other than that, she was born\nwith a weak constitution...",
            flattenAutoStackedCues(cues).single().text.toString(),
        )
    }

    /**
     * The block has to come back unpositioned, or the Top caption setting keeps ignoring it —
     * [raiseCuesToTop] only moves cues that rest on the bottom, and a numbered row never did.
     */
    @Test
    fun theBlockIsUnpositionedSoTopPositioningCanStillMoveIt() {
        val block = flattenAutoStackedCues(
            listOf(stacked("First", 1), stacked("Second", 2)),
        ).single()

        assertEquals(Cue.DIMEN_UNSET, block.line, 0f)
        assertEquals(Cue.TYPE_UNSET, block.lineType)

        val raised = raiseCuesToTop(listOf(block), CaptionPosition.TOP).single()
        assertEquals(0f, raised.line, 0f)
        assertEquals(Cue.ANCHOR_TYPE_START, raised.lineAnchor)
    }

    /** Media3 numbers a lone unpositioned cue too, which is why Top did nothing for any WebVTT. */
    @Test
    fun aSingleNumberedCueIsUnpositionedAsWell() {
        val block = flattenAutoStackedCues(listOf(stacked("Only line", 1))).single()

        assertEquals("Only line", block.text.toString())
        assertEquals(Cue.DIMEN_UNSET, block.line, 0f)
    }

    @Test
    fun rowsThatDoNotStartAtTheBottomAreLeftWhereTheFilePutThem() {
        // No row -1, so this is not the shape media3 produces; the file positioned these itself.
        val cues = listOf(stacked("Upper sign", 2), stacked("Higher sign", 3))
        assertSame(cues, flattenAutoStackedCues(cues))
    }

    @Test
    fun aGapInTheRowsIsLeftAlone() {
        val cues = listOf(stacked("Bottom", 1), stacked("Well above", 3))
        assertSame(cues, flattenAutoStackedCues(cues))
    }

    @Test
    fun cuesOnOppositeSidesAreNotOneStack() {
        val left = Cue.Builder()
            .setText("Xinxin")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setPosition(0.1f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .build()
        val cues = listOf(left, stacked("Zhu Qianning", 1))

        assertSame(cues, flattenAutoStackedCues(cues))
    }

    @Test
    fun aPositionedSignKeepsItsPlaceBesideTheBlock() {
        val sign = Cue.Builder()
            .setText("Maiden Peak")
            .setLine(0.2f, Cue.LINE_TYPE_FRACTION)
            .build()
        val laidOut = flattenAutoStackedCues(
            listOf(sign, stacked("Dialogue one", 1), stacked("Dialogue two", 2)),
        )

        assertEquals(2, laidOut.size)
        assertEquals("Maiden Peak", laidOut.first().text.toString())
        assertEquals("Dialogue one\nDialogue two", laidOut.last().text.toString())
    }

    @Test
    fun aListWithNothingStackedKeepsItsIdentity() {
        val cues = listOf(Cue.Builder().setText("Plain SubRip line").build())
        assertSame(cues, flattenAutoStackedCues(cues))
    }
}

/**
 * Sideways placement has to be written onto the cue rather than taken out of the view: padding the
 * subtitle canvas narrows the box the line is centred in, which drags the wrap point along with it
 * and turns a caption an eighth of the way over into one word a line.
 */
@UnstableApi
class CaptionsAcrossThePictureTest {
    private fun line(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun anUnplacedLineTakesTheChosenAnchor() {
        val moved = shiftCuesAcross(listOf(line("Over here")), 25).single()

        assertEquals(0.25f, moved.position, 0.001f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, moved.positionAnchor)
    }

    /**
     * No [Cue.size] alongside the anchor: media3 widens any cue carrying one out to its full
     * allotted width, so a two-word line would arrive on a background bar half the screen across.
     */
    @Test
    fun theLineKeepsTheWidthItWrapsAt() {
        assertEquals(Cue.DIMEN_UNSET, shiftCuesAcross(listOf(line("Over here")), 90).single().size)
    }

    /** A sign the file placed itself stays beside the thing it labels, wherever the viewer is. */
    @Test
    fun aLineThatPlacedItselfIsLeftAlone() {
        val sign = Cue.Builder()
            .setText("Maiden Peak")
            .setPosition(0.8f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .build()
        val cues = listOf(sign)

        assertSame(cues, shiftCuesAcross(cues, 0))
    }

    @Test
    fun centredCuesAreNotRewrittenAtAll() {
        val cues = listOf(line("Middle of the picture"))
        assertSame(cues, shiftCuesAcross(cues, 50))
    }

    @Test
    fun anAnchorOutsideThePictureIsClampedToItsEdge() {
        assertEquals(0f, shiftCuesAcross(listOf(line("Left")), -30).single().position, 0.001f)
        assertEquals(1f, shiftCuesAcross(listOf(line("Right")), 400).single().position, 0.001f)
    }

    @Test
    fun theWholePassCarriesTheAnchorThrough() {
        val laidOut = layOutSimultaneousCues(listOf(line("A"), line("B")), CaptionPosition.BOTTOM, 10)
        assertTrue(laidOut.all { it.position == 0.1f })
    }
}
