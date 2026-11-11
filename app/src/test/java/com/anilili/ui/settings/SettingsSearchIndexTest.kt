package com.anilili.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    private val entries = settingsSearchEntries(isTv = true)

    @Test
    fun `a query finds the setting by its own name`() {
        val hit = searchSettings("clear cache", entries).first()
        assertEquals("Clear cache", hit.title)
        assertEquals("data", hit.sectionId)
    }

    @Test
    fun `the word a viewer actually types finds the setting the title does not name`() {
        // Nobody searches "Prefer dubbed audio"; they search "dub".
        assertTrue(searchSettings("dub", entries).any { it.title == "Prefer dubbed audio" })
        // "Blur episode images" is the title; "spoilers" is what it is for.
        assertTrue(searchSettings("spoiler", entries).any { it.title == "Blur episode images" })
        assertTrue(searchSettings("nsfw", entries).any { it.title == "Hide adult content" })
        assertTrue(searchSettings("cc", entries).any { it.sectionId == "subtitles" })
        assertTrue(searchSettings("long press", entries).any { it.title == "Hold to change speed" })
        assertEquals(
            "Open episodes in external player",
            searchSettings("vlc", entries).first().title,
        )
        // The row is named for the window; a viewer looks for it by what they want to stop.
        assertTrue(
            searchSettings("background", entries).any { it.title == "Keep playing in a mini player" },
        )
        assertTrue(searchSettings("pip", entries).any { it.title == "Keep playing in a mini player" })
    }

    @Test
    fun `a name match outranks a keyword match`() {
        // "Skip button" and "Skip button length" are named for it; auto-skip only mentions it.
        val titles = searchSettings("skip button", entries).map { it.title }
        assertEquals("Skip button", titles.first())
    }

    @Test
    fun `every word has to land somewhere`() {
        // Both words are in "Skip button length", so it survives.
        assertTrue(searchSettings("skip length", entries).any { it.title == "Skip button length" })
        // The second word matches nothing at all, so the first word cannot carry the result.
        assertTrue(searchSettings("skip zzzzz", entries).isEmpty())
    }

    @Test
    fun `a blank query offers nothing rather than everything`() {
        assertTrue(searchSettings("", entries).isEmpty())
        assertTrue(searchSettings("   ", entries).isEmpty())
    }

    @Test
    fun `results are capped so the index stays visible underneath`() {
        // "a" is in almost every title; the field must not push the category grid off screen.
        assertTrue(searchSettings("a", entries, limit = 8).size <= 8)
    }

    @Test
    fun `television-only rows are indexed only on a television, exactly as the screen draws them`() {
        val phone = settingsSearchEntries(isTv = false)
        assertTrue(entries.any { it.title == "Interface size" })
        assertTrue(entries.any { it.title == "Overscan" })
        assertTrue(phone.none { it.title == "Interface size" || it.title == "Overscan" || it.title == "Spoken feedback" })
        // Drawn on both since the television's top menu learned to fade out.
        assertTrue(phone.any { it.title == "Keep the bars on screen" })
        assertTrue(entries.any { it.title == "Keep the bars on screen" })
    }

    @Test
    fun `the pinned menu is found by what a television viewer calls it`() {
        assertTrue(searchSettings("auto hide menu", entries).any { it.title == "Keep the bars on screen" })
    }

    @Test
    fun `the theme is found by the colour a viewer wants`() {
        assertEquals("Accent colour", searchSettings("neon green", entries).first().title)
        assertTrue(searchSettings("amoled", entries).any { it.title == "Background" })
        assertTrue(searchSettings("theme", entries).all { it.sectionId == "appearance" })
    }

    /**
     * The index is hand-written against a screen it cannot see, so this reads the screen back and
     * fails if a section it can open has been renamed or removed. A section that no longer exists
     * would strand every search that points at it.
     */
    @Test
    fun `every indexed section is a section the screen can actually open`() {
        val declared = Regex("""settingsSection\("([a-z]+)"""")
            .findAll(screenSource())
            .map { it.groupValues[1] }
            .toSet()
        val indexed = entries.map(SettingsSearchEntry::sectionId).toSet()
        assertEquals(emptySet<String>(), indexed - declared)
    }

    /**
     * A search result marks the row it named once its section opens, and the only name the two ends
     * share is the title — so a row renamed on the screen and not in the index would open the right
     * section and mark nothing, silently, which is exactly the failure a viewer cannot report.
     *
     * Reading the source back is the only way to check it: the rows are Compose calls, and there is
     * nothing to enumerate at runtime. Titles are attributed to the section they are written under,
     * so a row that moves between sections fails this too.
     */
    @Test
    fun `every indexed row is a row its section actually has`() {
        val source = screenSource()
        // Everything from the first section onwards: before it are the pickers, whose titles repeat
        // the row titles and would answer for rows that no longer exist.
        val body = source.substring(source.indexOf("""settingsSection(""""))
        var section = ""
        val rows = mutableSetOf<Pair<String, String>>()
        Regex("""settingsSection\("([a-z]+)"|title = "([^"]+)"""").findAll(body).forEach { match ->
            val (sectionId, title) = match.destructured
            if (sectionId.isNotEmpty()) section = sectionId else rows += section to title
        }
        val indexed = entries.map { it.sectionId to it.title }.toSet()
        assertEquals(emptySet<Pair<String, String>>(), indexed - rows)
    }

    @Test
    fun `every indexed row has a scroll key in its own section`() {
        val source = screenSource()
        val body = source.substring(source.indexOf("""settingsSection(""""))
        var section = ""
        val keyedRows = mutableSetOf<Pair<String, String>>()
        Regex("""settingsSection\("([a-z]+)"|item\(key = "([^"]+)"""")
            .findAll(body).forEach { match ->
                val (sectionId, key) = match.destructured
                if (sectionId.isNotEmpty()) section = sectionId else keyedRows += section to key
            }
        val indexed = entries.map { it.sectionId to it.title }.toSet()
        assertEquals(emptySet<Pair<String, String>>(), indexed - keyedRows)
    }

    private fun screenSource(): String {
        val source = File("src/main/java/com/anilili/ui/settings/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt not found at ${source.absolutePath}", source.isFile)
        return source.readText()
    }
}
