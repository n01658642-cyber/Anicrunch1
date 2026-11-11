package com.anilili.ui.settings

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    private val notes = """
        ## ✨ Liquid Glass
        - The phone's **tab bar now blurs** whatever scrolls behind it.
        - Available on **Android 12 and newer**.

        **🛠️ Fixes**
        * Fixed **MegaPlay ads**.
        Plus lots of smaller polish.
    """.trimIndent()

    @Test
    fun `headings bullets and paragraphs are told apart`() {
        assertEquals(
            listOf(
                ReleaseNoteBlock.Heading("✨ Liquid Glass"),
                ReleaseNoteBlock.Bullet("The phone's **tab bar now blurs** whatever scrolls behind it."),
                ReleaseNoteBlock.Bullet("Available on **Android 12 and newer**."),
                ReleaseNoteBlock.Heading("🛠️ Fixes"),
                ReleaseNoteBlock.Bullet("Fixed **MegaPlay ads**."),
                ReleaseNoteBlock.Paragraph("Plus lots of smaller polish."),
            ),
            parseReleaseNotes(notes),
        )
    }

    @Test
    fun `an old plain changelog is paragraphs, one per line`() {
        val old = "New: One Pace.\nFixes for subtitle pickers."
        assertEquals(
            listOf(ReleaseNoteBlock.Paragraph("New: One Pace."), ReleaseNoteBlock.Paragraph("Fixes for subtitle pickers.")),
            parseReleaseNotes(old),
        )
    }

    @Test
    fun `a line that only opens with bold stays a paragraph`() {
        assertEquals(
            listOf(ReleaseNoteBlock.Paragraph("**Zoko** and **Ani.pm** join.")),
            parseReleaseNotes("**Zoko** and **Ani.pm** join."),
        )
    }

    @Test
    fun `bold runs become spans and the marks disappear`() {
        val text = inlineBold("Fixed **MegaPlay ads** and **subtitles**.", SpanStyle(fontWeight = FontWeight.Bold))
        assertEquals("Fixed MegaPlay ads and subtitles.", text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals("MegaPlay ads", text.text.substring(text.spanStyles[0].start, text.spanStyles[0].end))
    }

    @Test
    fun `an unclosed bold mark is left as written`() {
        assertEquals("a **b", inlineBold("a **b", SpanStyle()).text)
    }

    @Test
    fun `the plain rendering is what older builds show`() {
        assertEquals(
            "✨ Liquid Glass\n• The phone's tab bar now blurs whatever scrolls behind it.\n" +
                "• Available on Android 12 and newer.\n🛠️ Fixes\n• Fixed MegaPlay ads.\nPlus lots of smaller polish.",
            plainChangelog(notes),
        )
    }
}
