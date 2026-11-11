package com.anilili.ui.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PreferredLanguageCodesTest {

    @Test
    fun theFirstChoiceLeadsAndTheSecondFollows() {
        assertArrayEquals(arrayOf("jpn", "eng"), preferredLanguageCodes("jpn", "eng"))
    }

    /** No opinion at all is an empty list, which is how media3 is told to choose for itself. */
    @Test
    fun automaticOnBothSidesAsksForNothing() {
        assertArrayEquals(emptyArray<String>(), preferredLanguageCodes("", ""))
    }

    @Test
    fun aSecondChoiceAloneStillCounts() {
        assertArrayEquals(arrayOf("eng"), preferredLanguageCodes("", "eng"))
    }

    @Test
    fun aFirstChoiceAloneIsUnchangedFromBefore() {
        assertArrayEquals(arrayOf("jpn"), preferredLanguageCodes("jpn", ""))
    }

    /**
     * Naming one language twice is not a fallback, and it is an easy thing to leave in the menu
     * after changing the primary to match the secondary.
     */
    @Test
    fun theSameLanguageTwiceIsListedOnce() {
        assertArrayEquals(arrayOf("jpn"), preferredLanguageCodes("jpn", "jpn"))
    }

    @Test
    fun surroundingSpaceIsNotALanguage() {
        assertArrayEquals(arrayOf("jpn"), preferredLanguageCodes(" jpn ", "   "))
    }
}
