package com.anilili.data.settings

import com.anilili.data.remote.normalizeSubtitleLanguages
import com.anilili.ui.watch.defaultOpenSubtitlesLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The subtitle search language is stored as free text so the player's own field keeps working, and
 * read back through an enum so Settings can offer the common cases. Those two have to agree.
 */
class SubtitleSearchLanguageTest {
    @Test
    fun `an unset language still follows the device`() {
        assertEquals(SubtitleSearchLanguage.FOLLOW_DEVICE, SubtitleSearchLanguage.fromStored(""))
        assertEquals(SubtitleSearchLanguage.FOLLOW_DEVICE, SubtitleSearchLanguage.fromStored(null))
        assertEquals("Follow device", SubtitleSearchLanguage.describe(""))
        // And "follow the device" is what it was before any of this existed.
        assertEquals("ar,en", defaultOpenSubtitlesLanguages("ar"))
        assertEquals("en", defaultOpenSubtitlesLanguages("en"))
    }

    @Test
    fun `arabic can be chosen on a phone that is not arabic`() {
        val chosen = SubtitleSearchLanguage.ARABIC

        assertEquals("ar", chosen.storedValue)
        assertEquals(chosen, SubtitleSearchLanguage.fromStored("ar"))
        assertEquals("Arabic", SubtitleSearchLanguage.describe("ar"))
    }

    @Test
    fun `a value typed in the player is shown as itself, not forced into the list`() {
        // The panel accepts several codes at once, which no single enum entry can represent.
        assertNull(SubtitleSearchLanguage.fromStored("ar,en"))
        assertEquals("AR,EN", SubtitleSearchLanguage.describe("ar,en"))
    }

    @Test
    fun `every offered code survives the normalising the search applies`() {
        // A code the picker offers must reach the API unchanged, or choosing it would silently
        // search for something else.
        SubtitleSearchLanguage.entries
            .filter { it != SubtitleSearchLanguage.FOLLOW_DEVICE }
            .forEach { language ->
                assertEquals(
                    language.name,
                    language.storedValue,
                    normalizeSubtitleLanguages(language.storedValue),
                )
            }
    }

    @Test
    fun `the codes are the two-letter kind the API takes`() {
        // Deliberately not the three-letter codes PreferredAudioLanguage uses for media tracks:
        // same idea, different vocabulary, and mixing them returns nothing.
        SubtitleSearchLanguage.entries
            .filter { it != SubtitleSearchLanguage.FOLLOW_DEVICE }
            .forEach { assertEquals(it.name, 2, it.storedValue.length) }
        assertEquals("ara", PreferredAudioLanguage.ARABIC.storedValue)
    }
}
