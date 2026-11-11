package com.anilili.errors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog's own guard rails.
 *
 * A code is worth exactly as much as its stability. The moment `EXP-04` means two things, or means
 * something different than it did last release, every bug report quoting it becomes misleading — so
 * uniqueness is asserted rather than trusted, and the rules that keep a code readable in a minified
 * release build are asserted too.
 */
class ErrorCatalogTest {

    @Test
    fun `every code is unique`() {
        val duplicates = AnililiError.entries
            .groupBy { it.code }
            .filterValues { it.size > 1 }
        assertTrue(
            "Codes must identify exactly one failure, but these are shared: $duplicates",
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `numbers are unique within a domain`() {
        val duplicates = AnililiError.entries
            .groupBy { it.domain to it.number }
            .filterValues { it.size > 1 }
        assertTrue("Reused numbers: $duplicates", duplicates.isEmpty())
    }

    /**
     * R8 renames enum constants in release builds. Anything derived from [Enum.name] would reach us
     * as `a.b.c` and need deobfuscating against the mapping archive before it could be read, which
     * defeats the point of a code someone can quote over chat.
     */
    @Test
    fun `codes survive minification because they are built from data only`() {
        AnililiError.entries.forEach { error ->
            assertEquals(
                "${error.name} must derive its code from its domain and number",
                "${error.domain.prefix}-${error.number.toString().padStart(2, '0')}",
                error.code,
            )
            // Five letters for DLINK, the desktop app's prefix: a link code reads the same in both apps.
            assertTrue("${error.code} is not in PREFIX-NN form", error.code.matches(Regex("[A-Z]{2,5}-\\d{2,}")))
        }
    }

    @Test
    fun `domain prefixes are unique`() {
        val duplicates = ErrorDomain.entries.groupBy { it.prefix }.filterValues { it.size > 1 }
        assertTrue("Prefixes must be unique: $duplicates", duplicates.isEmpty())
    }

    /**
     * One line, plain, and short enough to sit in a dense list row without pushing the row to five
     * lines — which is the failure this catalog exists to fix.
     */
    @Test
    fun `user messages stay to one short line`() {
        AnililiError.entries.forEach { error ->
            val message = error.userMessage
            assertTrue("${error.code} has an empty message", message.isNotBlank())
            assertTrue(
                "${error.code} is ${message.length} chars; keep it under 80 and move the rest to the detail",
                message.length <= 80,
            )
            assertTrue("${error.code} should not contain newlines", !message.contains('\n'))
            assertTrue(
                "${error.code} should read as a sentence: \"$message\"",
                message.first().isUpperCase() && message.last() in setOf('.', '?', '!'),
            )
        }
    }

    /**
     * More than one placeholder means positional arguments at the call site, which is where
     * formatting bugs live. One is enough for the things a viewer needs inline — a destination, a
     * service name — and everything else belongs in the detail.
     */
    @Test
    fun `messages carry at most one placeholder`() {
        AnililiError.entries.forEach { error ->
            val placeholders = Regex("%[sd]").findAll(error.userMessage).count()
            assertTrue("${error.code} has $placeholders placeholders; at most one is allowed", placeholders <= 1)
        }
    }

    @Test
    fun `a quoted code resolves back to its entry`() {
        assertEquals(AnililiError.EXPORT_NO_VIDEO_CONFIG, AnililiError.byCode("EXP-04"))
        assertEquals(AnililiError.EXPORT_NO_VIDEO_CONFIG, AnililiError.byCode(" exp-04 "))
        assertEquals(null, AnililiError.byCode("EXP-9999"))
    }

    @Test
    fun `a platform code narrows the app code without replacing it`() {
        val failure = AnililiError.PLAYBACK_SOURCE_UNREACHABLE.asFailure(platformCode = "h403")
        assertEquals("PLAY-02/h403", failure.code)
        assertEquals("PLAY-02", AnililiError.PLAYBACK_SOURCE_UNREACHABLE.asFailure().code)
    }

    /**
     * Regenerates `docs/ERROR_CODES.md` from the enum, so the published table can never disagree
     * with the app. Written rather than asserted because there is no second source to check against
     * — the enum *is* the source, and a doc that drifts is worse than no doc.
     */
    @Test
    fun `the published code table is generated from the catalog`() {
        val docs = generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(4)
            .map { File(it, "docs") }
            .firstOrNull { it.isDirectory }
            ?: return
        File(docs, "ERROR_CODES.md").writeText(renderErrorCodeTable())
    }
}

internal fun renderErrorCodeTable(): String = buildString {
    appendLine("# Error codes")
    appendLine()
    appendLine("Generated from `AnililiError` by `ErrorCatalogTest`. Do not edit by hand.")
    appendLine()
    appendLine("Every failure the app can show carries a code. Quote it in a bug report: it is the only")
    appendLine("thing that survives a minified release build and can be matched against a diagnostics")
    appendLine("bundle, where the same code is the event name.")
    appendLine()
    appendLine("A code may carry a second half — `PLAY-02/h403`. That is the underlying library's own")
    appendLine("error number, kept because it is more precise than anything this app invents:")
    appendLine()
    appendLine("| Prefix | Source |")
    appendLine("| --- | --- |")
    appendLine("| `h` | HTTP response status |")
    appendLine("| `m` | Media3 `PlaybackException.errorCode` |")
    appendLine("| `x` | Media3 `ExportException.errorCode` |")
    appendLine("| `c` | `MediaCodec.CodecException.errorCode` |")
    ErrorDomain.entries.forEach { domain ->
        val entries = AnililiError.entries.filter { it.domain == domain }.sortedBy { it.number }
        if (entries.isEmpty()) return@forEach
        appendLine()
        appendLine("## ${domain.label} — `${domain.prefix}`")
        appendLine()
        appendLine("| Code | What it means | Next step |")
        appendLine("| --- | --- | --- |")
        entries.forEach { error ->
            val message = error.userMessage.replace("|", "\\|")
            appendLine("| `${error.code}` | $message | ${error.action?.label ?: "—"} |")
        }
    }
}
