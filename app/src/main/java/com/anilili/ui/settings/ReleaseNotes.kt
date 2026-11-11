package com.anilili.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.Midnight

/**
 * One block of release notes: a section heading, a bullet, or a plain paragraph.
 *
 * Release notes are written as light markdown — `## Heading`, `- item`, `**bold**` — the same shape
 * as the Discord changelog, so one text serves both. Installs from before this renderer strip the
 * same marks down to plain lines and "•" bullets (see [plainChangelog]), so a formatted changelog
 * reads cleanly on every version still in use.
 */
internal sealed interface ReleaseNoteBlock {
    data class Heading(val text: String) : ReleaseNoteBlock
    data class Bullet(val text: String) : ReleaseNoteBlock
    data class Paragraph(val text: String) : ReleaseNoteBlock
}

private val HEADING = Regex("""^#{1,6}\s+(.+)$""")
private val BOLD_LINE = Regex("""^\*\*(.+)\*\*:?$""")
private val BULLET = Regex("""^[-*•]\s+(.+)$""")

/** [body] as blocks. A line wholly in bold is a heading too, which is how the Discord fields read. */
internal fun parseReleaseNotes(body: String): List<ReleaseNoteBlock> =
    body.lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line ->
            HEADING.matchEntire(line)?.let { return@map ReleaseNoteBlock.Heading(it.groupValues[1].trim().stripBold()) }
            BULLET.matchEntire(line)?.let { return@map ReleaseNoteBlock.Bullet(it.groupValues[1].trim()) }
            BOLD_LINE.matchEntire(line)
                ?.takeIf { "**" !in it.groupValues[1] }
                ?.let { return@map ReleaseNoteBlock.Heading(it.groupValues[1].trim()) }
            ReleaseNoteBlock.Paragraph(line)
        }

private fun String.stripBold() = replace("**", "")

/** [text] with its `**bold**` runs set in [bold]; unmatched marks are left as written. */
internal fun inlineBold(text: String, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val open = rest.indexOf("**")
        val close = if (open >= 0) rest.indexOf("**", open + 2) else -1
        if (open < 0 || close < 0) {
            append(rest.replace("`", ""))
            break
        }
        append(rest.substring(0, open).replace("`", ""))
        withStyle(bold) { append(rest.substring(open + 2, close)) }
        rest = rest.substring(close + 2)
    }
}

/** The plain-text rendering older builds used, kept for anything that cannot draw [ReleaseNotes]. */
internal fun plainChangelog(body: String): String =
    parseReleaseNotes(body).joinToString("\n") { block ->
        when (block) {
            is ReleaseNoteBlock.Heading -> block.text
            is ReleaseNoteBlock.Bullet -> "• " + block.text.stripBold()
            is ReleaseNoteBlock.Paragraph -> block.text.stripBold()
        }
    }

/**
 * Release notes as sections: headings in the accent colour, bullets on a hanging indent with the
 * highlighted words in bold white, paragraphs plain. [maxBlocks] cuts a long release down to its
 * opening lines for the update prompt; the full notes are one press away.
 */
@Composable
internal fun ReleaseNotes(
    blocks: List<ReleaseNoteBlock>,
    modifier: Modifier = Modifier,
    maxBlocks: Int = Int.MAX_VALUE,
    compact: Boolean = false,
) {
    val body: TextStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)) {
        blocks.take(maxBlocks).forEachIndexed { index, block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> Text(
                    text = block.text,
                    style = (if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall)
                        .copy(fontWeight = FontWeight.Bold),
                    color = accent,
                    // A section starts with a little air above it, except at the very top.
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else if (compact) 6.dp else 10.dp),
                )
                is ReleaseNoteBlock.Bullet -> Row {
                    Text("•", style = body, color = accent, fontWeight = FontWeight.Bold)
                    Text(
                        text = inlineBold(block.text, bold),
                        style = body,
                        color = Midnight.Text2,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                }
                is ReleaseNoteBlock.Paragraph -> Text(
                    text = inlineBold(block.text, bold),
                    style = body,
                    color = Midnight.Text2,
                )
            }
        }
    }
}

/** How many blocks the update prompt shows before "Read more". */
internal const val COLLAPSED_RELEASE_NOTE_BLOCKS = 4
