package com.anilili.ui.watch

import com.anilili.data.model.SubtitleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncableSubtitleTest {

    private val english = SubtitleItem(url = "https://cdn/en.vtt", label = "English", language = "eng")
    private val spanish = SubtitleItem(url = "https://cdn/es.vtt", label = "Spanish", language = "spa")
    private val attached = SubtitleItem(url = "file:///tmp/mine.srt", label = "Mine", language = "eng")

    @Test
    fun anAttachedFileIsChosenByItsUrl() {
        val all = listOf(english, spanish, attached)
        assertEquals(attached, syncableSubtitle(all, attached.url, selectedLanguage = "spa"))
    }

    @Test
    fun otherwiseTheSelectedTracksLanguageDecides() {
        val all = listOf(english, spanish)
        assertEquals(spanish, syncableSubtitle(all, selectedAttachedUrl = null, selectedLanguage = "spa"))
    }

    /** Tracks declare two- or three-letter codes interchangeably; both name the same language. */
    @Test
    fun aTwoLetterCodeStillMatchesAThreeLetterTrack() {
        val all = listOf(english, spanish)
        assertEquals(spanish, syncableSubtitle(all, selectedAttachedUrl = null, selectedLanguage = "es"))
    }

    /** With one sidecar there is nothing to confuse it with, so it needs no language to be found. */
    @Test
    fun aLoneSidecarIsAssumed() {
        assertEquals(english, syncableSubtitle(listOf(english), null, selectedLanguage = null))
    }

    /**
     * Several files and nothing naming which is playing: reading the wrong one would derive a shift
     * from lines that were never on screen, so it declines rather than guesses.
     */
    @Test
    fun severalFilesAndNoSelectionIsRefused() {
        assertNull(syncableSubtitle(listOf(english, spanish), null, selectedLanguage = null))
    }

    @Test
    fun aLanguageThatMatchesNothingFallsBackToRefusing() {
        assertNull(syncableSubtitle(listOf(english, spanish), null, selectedLanguage = "jpn"))
    }

    @Test
    fun noSubtitlesAtAllIsRefused() {
        assertNull(syncableSubtitle(emptyList(), null, selectedLanguage = "eng"))
    }
}
