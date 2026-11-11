package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored value is a preference a viewer set once; it has to survive a restart unchanged. */
class PreferredSubtitleLanguageTest {
    @Test
    fun everyChoiceSurvivesBeingStoredAndReadBack() {
        PreferredSubtitleLanguage.entries.forEach { language ->
            assertEquals(language, PreferredSubtitleLanguage.fromStored(language.storedValue))
        }
    }

    @Test
    fun anUnsetPreferenceIsAutomatic() {
        // A fresh install has nothing stored, and nobody's subtitles should change until they ask.
        assertEquals(PreferredSubtitleLanguage.AUTOMATIC, PreferredSubtitleLanguage.fromStored(null))
        assertEquals(PreferredSubtitleLanguage.AUTOMATIC, PreferredSubtitleLanguage.fromStored(""))
    }

    @Test
    fun aValueFromANewerBuildFallsBackRatherThanCrashing() {
        assertEquals(PreferredSubtitleLanguage.AUTOMATIC, PreferredSubtitleLanguage.fromStored("klingon"))
    }

    @Test
    fun automaticIsTheOnlyBlankCodeSoItAloneClearsThePreference() {
        val blank = PreferredSubtitleLanguage.entries.filter { it.storedValue.isBlank() }
        assertEquals(listOf(PreferredSubtitleLanguage.AUTOMATIC), blank)
    }

    @Test
    fun everyCodeIsDistinct() {
        val codes = PreferredSubtitleLanguage.entries.map { it.storedValue }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun theCodesAreTheThreeLetterOnesMediaTracksDeclare() {
        // Two-letter codes are the OpenSubtitles API's vocabulary and belong to
        // SubtitleSearchLanguage; mixing the two here is the mistake this guards against.
        PreferredSubtitleLanguage.entries
            .filter { it != PreferredSubtitleLanguage.AUTOMATIC }
            .forEach { language ->
                assertTrue(
                    "${language.name} is not a three-letter code",
                    language.storedValue.matches(Regex("[a-z]{3}")),
                )
            }
    }

    @Test
    fun theLanguagesSubtitleTracksActuallyArriveInAreOffered() {
        val codes = PreferredSubtitleLanguage.entries.map { it.storedValue }
        listOf("eng", "spa", "por", "ara", "jpn").forEach {
            assertTrue("missing $it", it in codes)
        }
    }
}
