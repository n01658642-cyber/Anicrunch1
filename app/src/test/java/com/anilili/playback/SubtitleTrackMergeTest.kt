package com.anilili.playback

import androidx.media3.common.MimeTypes
import com.anilili.data.model.SubtitleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTrackMergeTest {
    private val signs = SubtitleItem(
        url = "https://cdn.example/ep1_eng_1.ass",
        label = "English (Signs & Songs)",
        language = "en",
    )
    private val dubCc = SubtitleItem(
        url = "https://cdn.example/ep1_eng_2.ass",
        label = "English CC (Dub)",
        language = "en",
    )
    private val fullSubs = SubtitleItem(
        url = "https://cdn.example/ep1_eng_3.ass",
        label = "English (Full)",
        language = "en",
    )
    private val spanish = SubtitleItem(
        url = "https://cdn.example/ep1_spa_4.vtt",
        label = "Spanish",
        language = "es",
    )

    @Test
    fun recognisesSignsTracksByLabel() {
        assertTrue(isSignsSubtitle("English (Signs & Songs [ParanDark modified])"))
        assertTrue(isSignsSubtitle("English (Forced)"))
        assertFalse(isSignsSubtitle("English CC (Dub)"))
        assertFalse(isSignsSubtitle("English (Full)"))
    }

    @Test
    fun aDubPairsTheSignsWithTheDubScript() {
        // ReAnime ranks the signs track first on a dub, so it is what plays when nothing was chosen.
        val pairing = signsPairing(listOf(signs, fullSubs, dubCc), selectedLabel = signs.label, preferDub = true)

        assertEquals(SignsPairing(signs = signs, dialogue = dubCc), pairing)
        assertEquals("English CC (Dub) + signs", pairing?.mergedLabel)
    }

    @Test
    fun aDialogueTrackTheViewerPickedIsTheOneTheSignsJoin() {
        val pairing = signsPairing(listOf(signs, dubCc, fullSubs), selectedLabel = fullSubs.label, preferDub = true)

        assertEquals(fullSubs, pairing?.dialogue)
    }

    @Test
    fun withNothingSelectedASubPrefersTheFirstDialogueTrack() {
        val pairing = signsPairing(listOf(fullSubs, signs, dubCc), selectedLabel = null, preferDub = false)

        assertEquals(SignsPairing(signs = signs, dialogue = fullSubs), pairing)
    }

    @Test
    fun noPairWithoutASignsTrack() {
        assertNull(signsPairing(listOf(dubCc, fullSubs), selectedLabel = null, preferDub = true))
    }

    @Test
    fun noPairWithoutADialogueTrack() {
        assertNull(signsPairing(listOf(signs), selectedLabel = signs.label, preferDub = true))
    }

    @Test
    fun signsDoNotJoinDialogueInAnotherLanguage() {
        assertNull(signsPairing(listOf(signs, spanish), selectedLabel = spanish.label, preferDub = false))
        // And with nothing chosen, the Spanish track is not offered as the English signs' partner.
        assertNull(signsPairing(listOf(signs, spanish), selectedLabel = null, preferDub = false))
    }

    @Test
    fun ttmlTracksCannotBeMerged() {
        val ttml = dubCc.copy(url = "https://cdn.example/ep1.ttml")

        assertNull(signsPairing(listOf(signs, ttml), selectedLabel = null, preferDub = true))
    }

    @Test
    fun twoAssFilesKeepBothStyleSetsUnderPrefixedNames() {
        val merged = mergeSubtitleTexts(base = SIGNS_ASS, overlay = DIALOGUE_ASS)

        assertEquals(MimeTypes.TEXT_SSA, merged.mimeType)
        val lines = merged.text.lines()
        // Both files define "Default"; prefixing keeps the two from overwriting each other.
        assertTrue(lines.any { it.startsWith("Style: A_Default,Arial,40,") })
        assertTrue(lines.any { it.startsWith("Style: A_Sign,") })
        assertTrue(lines.any { it.startsWith("Style: B_Default,Verdana,") })
        assertEquals(1, lines.count { it.startsWith("[Events]") })
        assertEquals(1, lines.count { it.startsWith("[V4+ Styles]") })
        assertTrue(lines.contains("Dialogue: 0,0:00:05.00,0:00:07.00,A_Sign,,0,0,0,,{\\pos(960,200)}CITY HALL"))
        assertTrue(lines.contains("Dialogue: 0,0:00:06.00,0:00:08.00,B_Default,,0,0,0,,Where are we, exactly?"))
    }

    @Test
    fun theSignsCanvasIsKeptAndTheDialogueIsScaledOntoIt() {
        val merged = mergeSubtitleTexts(base = SIGNS_ASS, overlay = DIALOGUE_ASS).text.lines()

        assertTrue(merged.contains("PlayResX: 1920"))
        assertTrue(merged.contains("PlayResY: 1080"))
        assertEquals(1, merged.count { it.startsWith("PlayResX") })
        // The dialogue file's 640x360 canvas is a third of the signs file's: positions and sizes
        // written against it grow by three.
        assertTrue(merged.contains("Dialogue: 0,0:00:09.00,0:00:10.00,B_Default,,0,0,0,,{\\pos(300,330)}Over here!"))
        assertTrue(merged.any { it.startsWith("Style: B_Default,Verdana,60,") })
        assertTrue(
            merged.contains(
                "Dialogue: 0,0:00:11.00,0:00:12.00,B_Default,,0,0,0,,{\\move(30,30,60,60,0,500)}Moving",
            ),
        )
    }

    @Test
    fun differingFieldOrdersAreReadByTheirFormatLine() {
        val reordered = """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontsize, Fontname, Alignment
            Style: Top,30,Georgia,8

            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 1,0:00:01.00,0:00:02.00,Top,Hello, there
        """.trimIndent()

        val merged = mergeSubtitleTexts(base = SIGNS_ASS, overlay = reordered).text.lines()

        assertTrue(merged.any { it.startsWith("Style: B_Top,Georgia,30,") && it.contains(",8,10,10,10,1") })
        assertTrue(merged.contains("Dialogue: 1,0:00:01.00,0:00:02.00,B_Top,,0,0,0,,Hello, there"))
    }

    @Test
    fun styleResetsFollowTheRename() {
        val merged = mergeSubtitleTexts(base = SIGNS_ASS, overlay = DIALOGUE_ASS).text

        assertFalse(merged.contains("{\\rDefault}"))
        assertTrue(merged.contains("Back to {\\rA_Default}normal"))
    }

    @Test
    fun webVttDialogueJoinsAssSignsAsAssLines() {
        val merged = mergeSubtitleTexts(base = SIGNS_ASS, overlay = DIALOGUE_VTT)

        assertEquals(MimeTypes.TEXT_SSA, merged.mimeType)
        val lines = merged.text.lines()
        assertTrue(lines.any { it.startsWith("Style: B_Text,") })
        assertTrue(
            lines.contains(
                "Dialogue: 0,0:00:06.50,0:00:08.25,B_Text,,0,0,0,,{\\i1}Where{\\i0} are we?\\NTom & Jerry",
            ),
        )
        // A cue WebVTT lifted to the top stays at the top.
        assertTrue(lines.contains("Dialogue: 0,0:01:02.00,0:01:03.00,B_Text,,0,0,0,,{\\an8}Up here"))
        // The signs still come first and keep their canvas.
        assertTrue(lines.contains("PlayResX: 1920"))
        assertTrue(lines.contains("Dialogue: 0,0:00:05.00,0:00:07.00,A_Sign,,0,0,0,,{\\pos(960,200)}CITY HALL"))
    }

    @Test
    fun webVttSignsUnderAssDialogueUseTheDialogueCanvas() {
        val merged = mergeSubtitleTexts(base = DIALOGUE_VTT, overlay = DIALOGUE_ASS).text.lines()

        assertTrue(merged.contains("PlayResX: 640"))
        // Unscaled: the ASS file is the canvas.
        assertTrue(merged.contains("Dialogue: 0,0:00:09.00,0:00:10.00,B_Default,,0,0,0,,{\\pos(100,110)}Over here!"))
        assertTrue(merged.any { it.startsWith("Dialogue: 0,0:00:06.50,0:00:08.25,A_Text,") })
    }

    @Test
    fun twoWebVttFilesBecomeOneSortedWebVtt() {
        val signsVtt = """
            WEBVTT

            STYLE
            ::cue { color: yellow }

            NOTE a comment

            sign-1
            00:00:07.000 --> 00:00:09.000 line:10%
            CITY HALL
        """.trimIndent()

        val merged = mergeSubtitleTexts(base = signsVtt, overlay = DIALOGUE_VTT)

        assertEquals(MimeTypes.TEXT_VTT, merged.mimeType)
        assertEquals(
            """
            WEBVTT

            STYLE
            ::cue { color: yellow }

            00:00:06.500 --> 00:00:08.250
            <i>Where</i> are we?
            Tom &amp; Jerry

            00:00:07.000 --> 00:00:09.000 line:10%
            CITY HALL

            00:01:02.000 --> 00:01:03.000 line:0
            Up here

            """.trimIndent() + "\n",
            merged.text,
        )
    }

    @Test
    fun subRipAndWebVttBecomeWebVttWithoutAssTags() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\n{\\an8}TOP SIGN\r\n"

        val merged = mergeSubtitleTexts(base = srt, overlay = DIALOGUE_VTT)

        assertEquals(MimeTypes.TEXT_VTT, merged.mimeType)
        assertTrue(merged.text.contains("00:00:01.000 --> 00:00:02.500 line:0\nTOP SIGN\n"))
    }

    @Test
    fun twoSubRipFilesStaySubRipRenumbered() {
        val first = "1\n00:00:05,000 --> 00:00:06,000\nSecond\n\n2\n00:00:09,000 --> 00:00:10,000\nFourth\n"
        val second = "﻿1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n2\n00:00:07,000 --> 00:00:08,000\nThird\n"

        val merged = mergeSubtitleTexts(base = first, overlay = second)

        assertEquals(MimeTypes.APPLICATION_SUBRIP, merged.mimeType)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,000\nFirst\n\n" +
                "2\n00:00:05,000 --> 00:00:06,000\nSecond\n\n" +
                "3\n00:00:07,000 --> 00:00:08,000\nThird\n\n" +
                "4\n00:00:09,000 --> 00:00:10,000\nFourth\n\n",
            merged.text,
        )
    }

    @Test
    fun legacySsaStylesAreConvertedToV4Plus() {
        val ssa = """
            [Script Info]
            ScriptType: v4.00
            PlayResX: 1920
            PlayResY: 1080

            [V4 Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
            Style: Old,Arial,40,&H00FFFFFF,&H00FFFFFF,&H00112233,&H00000000,0,0,1,2,0,6,10,10,10,0,1

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:01.00,0:00:02.00,Old,,0,0,0,,Top centre
        """.trimIndent()

        val merged = mergeSubtitleTexts(base = ssa, overlay = DIALOGUE_VTT).text.lines()

        assertTrue(merged.contains("ScriptType: v4.00+"))
        // SSA's 6 (top centre) is the keypad's 8; its TertiaryColour is the outline.
        assertTrue(merged.contains("Style: A_Old,Arial,40,&H00FFFFFF,&H00FFFFFF,&H00112233,&H00000000,0,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1"))
        assertTrue(merged.contains("Dialogue: 0,0:00:01.00,0:00:02.00,A_Old,,0,0,0,,Top centre"))
    }

    @Test
    fun commentsAndAttachmentsAreLeftBehind() {
        val withExtras = SIGNS_ASS + "\nComment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,note to self\n" +
            "\n[Fonts]\nfontname: custom.ttf\nM0!@#\n"

        val merged = mergeSubtitleTexts(base = withExtras, overlay = DIALOGUE_ASS).text

        assertFalse(merged.contains("note to self"))
        assertFalse(merged.contains("[Fonts]"))
        assertFalse(merged.contains("custom.ttf"))
    }

    private companion object {
        val SIGNS_ASS = """
            [Script Info]
            ; Script generated by Aegisub
            Title: Episode 1 signs
            ScriptType: v4.00+
            WrapStyle: 0
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,20,20,40,1
            Style: Sign,Times New Roman,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.00,0:00:07.00,Sign,,0,0,0,,{\pos(960,200)}CITY HALL
            Dialogue: 0,0:00:13.00,0:00:14.00,Sign,,0,0,0,,{\b1}Bold{\rDefault}Back to {\rDefault}normal
        """.trimIndent()

        val DIALOGUE_ASS = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Verdana,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,15,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:06.00,0:00:08.00,Default,,0,0,0,,Where are we, exactly?
            Dialogue: 0,0:00:09.00,0:00:10.00,Default,,0,0,0,,{\pos(100,110)}Over here!
            Dialogue: 0,0:00:11.00,0:00:12.00,Default,,0,0,0,,{\move(10,10,20,20,0,500)}Moving
        """.trimIndent()

        val DIALOGUE_VTT = """
            WEBVTT

            1
            00:00:06.500 --> 00:00:08.250
            <i>Where</i> are we?
            Tom &amp; Jerry

            2
            01:02.000 --> 01:03.000 line:0
            Up here
        """.trimIndent()
    }
}
