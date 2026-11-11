package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSubtitlesPanelPolicyTest {
    @Test
    fun currentWholeEpisodeNumberPrefillsAutomaticSearch() {
        assertEquals("7", openSubtitlesEpisodeField("7"))
        assertEquals("12", openSubtitlesEpisodeField("12.0"))
    }

    @Test
    fun specialsDoNotBecomeTheWrongEpisodeNumber() {
        assertEquals("", openSubtitlesEpisodeField("12.5"))
        assertEquals("", openSubtitlesEpisodeField("Special"))
    }

    @Test
    fun automaticSearchIncludesEnglishAsFallbackLanguage() {
        assertEquals("es,en", defaultOpenSubtitlesLanguages("ES"))
        assertEquals("en", defaultOpenSubtitlesLanguages("en"))
        assertEquals("en", defaultOpenSubtitlesLanguages(""))
    }
}
