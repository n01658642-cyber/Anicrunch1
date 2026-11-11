package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAudioPolicyTest {
    @Test
    fun categoryAudioRankingPrefersEnglishForDubAndJapaneseForSub() {
        assertTrue(categoryAudioRank("English eng", wantsDub = true) < categoryAudioRank("Japanese jpn", wantsDub = true))
        assertTrue(categoryAudioRank("Japanese jpn", wantsDub = false) < categoryAudioRank("English eng", wantsDub = false))
    }

    /**
     * The tracks ReAnime's dual-audio streams actually publish, read off a Galaxy S25: the Japanese
     * rendition is named "Native", not "Japanese", so the language code is what identifies it.
     */
    private val reanimeTracks = listOf("Native ja", "English en")

    @Test
    fun `a dub takes the english track of a dual-audio stream`() {
        assertEquals(
            CategoryAudioDecision.Select(1),
            categoryAudioDecision(reanimeTracks, selectedIndex = 0, wantsDub = true),
        )
    }

    @Test
    fun `a sub leaves the native track alone`() {
        assertEquals(
            CategoryAudioDecision.Settled,
            categoryAudioDecision(reanimeTracks, selectedIndex = 0, wantsDub = false),
        )
    }

    @Test
    fun `an empty track list is not an answer`() {
        // Media3 reports no tracks at all for the moment between preparing an item and reading its
        // manifest. Calling that settled spent the one selection the caller makes per item, so
        // switching a dual-audio server to Dub mid-episode never saw the English track arrive.
        assertEquals(CategoryAudioDecision.NotReady, categoryAudioDecision(emptyList(), null, wantsDub = true))
        assertEquals(
            CategoryAudioDecision.NotReady,
            categoryAudioDecision(listOf("Native ja"), selectedIndex = 0, wantsDub = true),
        )
    }

    @Test
    fun `a stream with no track for the category is left as it is`() {
        // Every track is a dub here, so the first is as good an answer as the one already playing.
        assertEquals(
            CategoryAudioDecision.Settled,
            categoryAudioDecision(listOf("Hindi hi", "Tamil ta"), selectedIndex = 0, wantsDub = true),
        )
        // And a sub has nothing to move to when the original audio is not among them.
        assertEquals(
            CategoryAudioDecision.Settled,
            categoryAudioDecision(listOf("Hindi hi", "Tamil ta"), selectedIndex = 0, wantsDub = false),
        )
    }

    @Test
    fun `a preferred audio language does not turn a dub back into the original audio`() {
        // The reported bug: ReAnime played Japanese under a Dub selection for anyone whose
        // standing preference was Japanese, because that preference used to answer the whole
        // question. It ranks dubs now, and Japanese is not one.
        assertEquals(
            CategoryAudioDecision.Select(1),
            categoryAudioDecision(
                reanimeTracks,
                selectedIndex = 0,
                wantsDub = true,
                preferredLanguages = listOf("ja", "jpn"),
            ),
        )
    }

    @Test
    fun `a preferred audio language picks between the dubs a stream carries`() {
        val tracks = listOf("Native ja", "English en", "Spanish es")
        assertEquals(
            CategoryAudioDecision.Select(2),
            categoryAudioDecision(
                tracks,
                selectedIndex = 0,
                wantsDub = true,
                preferredLanguages = listOf("es", "spa"),
            ),
        )
        // With no preference the English dub stays the default.
        assertEquals(
            CategoryAudioDecision.Select(1),
            categoryAudioDecision(tracks, selectedIndex = 0, wantsDub = true),
        )
    }

    @Test
    fun `a sub keeps the original audio whatever the language preference says`() {
        assertEquals(
            CategoryAudioDecision.Settled,
            categoryAudioDecision(
                reanimeTracks,
                selectedIndex = 0,
                wantsDub = false,
                preferredLanguages = listOf("en", "eng"),
            ),
        )
    }

    @Test
    fun `a dub reaches for another language rather than play the original audio`() {
        assertEquals(
            CategoryAudioDecision.Select(1),
            categoryAudioDecision(listOf("Native ja", "Hindi hi"), selectedIndex = 0, wantsDub = true),
        )
    }
}
