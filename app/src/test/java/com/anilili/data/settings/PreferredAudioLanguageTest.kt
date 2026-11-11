package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored value is a preference a viewer set once; it has to survive a restart unchanged. */
class PreferredAudioLanguageTest {
    @Test
    fun everyChoiceSurvivesBeingStoredAndReadBack() {
        PreferredAudioLanguage.entries.forEach { language ->
            assertEquals(language, PreferredAudioLanguage.fromStored(language.storedValue))
        }
    }

    @Test
    fun anUnsetPreferenceIsAutomatic() {
        // A fresh install has nothing stored, and nobody's playback should change until they ask.
        assertEquals(PreferredAudioLanguage.AUTOMATIC, PreferredAudioLanguage.fromStored(null))
        assertEquals(PreferredAudioLanguage.AUTOMATIC, PreferredAudioLanguage.fromStored(""))
    }

    @Test
    fun aValueFromANewerBuildFallsBackRatherThanCrashing() {
        assertEquals(PreferredAudioLanguage.AUTOMATIC, PreferredAudioLanguage.fromStored("klingon"))
    }

    @Test
    fun automaticIsTheOnlyBlankCodeSoItAloneClearsThePreference() {
        val blank = PreferredAudioLanguage.entries.filter { it.storedValue.isBlank() }
        assertEquals(listOf(PreferredAudioLanguage.AUTOMATIC), blank)
    }

    @Test
    fun theLanguagesThisWasBuiltForAreOffered() {
        // The multi-audio sources that prompted this declare these among their renditions.
        val codes = PreferredAudioLanguage.entries.map { it.storedValue }
        listOf("hin", "tam", "tel", "jpn", "eng").forEach {
            assertTrue("missing $it", it in codes)
        }
    }

    @Test
    fun everyCodeIsDistinct() {
        val codes = PreferredAudioLanguage.entries.map { it.storedValue }
        assertEquals(codes.size, codes.toSet().size)
    }
}
