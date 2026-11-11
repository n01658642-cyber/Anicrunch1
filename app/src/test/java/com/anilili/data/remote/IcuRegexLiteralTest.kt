package com.anilili.data.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards against a regex that compiles here and throws on every device.
 *
 * Android's `java.util.regex` is ICU-backed, and ICU rejects a `}` that does not close a `{n,m}`
 * quantifier. OpenJDK — which is what these unit tests run on — accepts the same `}` as a literal.
 * So `Regex("""\blinks\s*=\s*(\{[^{}]*})""")` passed every test in this suite and threw
 * `PatternSyntaxException: Syntax error in regexp pattern near index 24` on the TV box that
 * reported it, taking the otakuhg/VidHide embed path down on 100% of plays. It shipped in v0.1.78.
 *
 * Nothing on this JVM can compile a pattern the way a device will, so this reads the source text
 * instead. Escaping the brace costs nothing and means the same thing to both engines.
 *
 * Call sites that build their pattern some other way — a `pattern =` argument, a concatenation —
 * are not matched and so are not covered; this pins the shape that actually shipped broken.
 */
class IcuRegexLiteralTest {

    @Test
    fun `no Regex literal carries a brace ICU will reject`() {
        val sources = kotlinSources() ?: return
        val offenders = sources.flatMap { file ->
            REGEX_LITERAL.findAll(file.readText())
                // A raw literal is already the pattern; a quoted one still carries Kotlin's escapes.
                .map { match ->
                    match.groupValues[1].ifEmpty { decodeKotlinEscapes(match.groupValues[2]) }
                }
                .filter(::hasUnclosedBrace)
                .map { "${file.name}: $it" }
                .toList()
        }
        assertEquals("escape these as \\} — ICU rejects a bare }", emptyList<String>(), offenders)
    }

    private companion object {
        /** `Regex("""…""")` or `Regex("…")`, whichever a call site used. */
        val REGEX_LITERAL = Regex(
            "Regex\\(\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")",
            RegexOption.DOT_MATCHES_ALL,
        )
        val INTERPOLATION = Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}""")
        val CHAR_CLASS = Regex("""\[(?:\\.|[^\]\\])*\]""")
        val QUANTIFIER = Regex("""\{\d*(?:,\d*)?\}""")
        val ESCAPED_BRACE = Regex("""\\\{|\\\}""")

        /**
         * A quoted Kotlin literal's text reduced to the pattern the regex engine will see, so
         * `"\\{[^}]*\\}"` is analysed as `\{[^}]*\}` and its braces read as escaped.
         */
        fun decodeKotlinEscapes(literal: String): String = buildString {
            var index = 0
            while (index < literal.length) {
                val char = literal[index]
                if (char != '\\' || index == literal.lastIndex) {
                    append(char)
                    index++
                    continue
                }
                when (val escaped = literal[index + 1]) {
                    '\\', '"', '\'', '$' -> append(escaped)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    // \uXXXX and anything else cannot be a brace, so a placeholder is enough.
                    else -> append('X')
                }
                index += 2
            }
        }

        /**
         * True when a `}` survives removal of everything that may legitimately carry one: Kotlin
         * string templates, character classes (where ICU reads a brace as a literal too), escaped
         * braces, and `{n,m}` quantifiers.
         *
         * Classes are removed before escapes so that a `\]` inside one cannot be mistaken for the
         * end of it — that is what made `DiagnosticRedactor`'s URL pattern look broken.
         */
        fun hasUnclosedBrace(pattern: String): Boolean {
            var rest = INTERPOLATION.replace(pattern, "X")
            rest = CHAR_CLASS.replace(rest, "X")
            rest = ESCAPED_BRACE.replace(rest, "")
            rest = QUANTIFIER.replace(rest, "")
            return '}' in rest
        }

        fun kotlinSources(): List<File>? {
            val main = generateSequence(File(".").absoluteFile) { it.parentFile }
                .take(4)
                .map { File(it, "app/src/main/java/com/anilili") }
                .firstOrNull { it.isDirectory }
                ?: return null
            return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
