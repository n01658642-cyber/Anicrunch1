package com.anilili.data.remote

import kotlin.math.pow

/** Apache-2.0 adaptation of yuzono/anime-extensions' Dean Edwards P.A.C.K.E.R. unpacker. */
internal object AllAnimeJsUnpacker {
    private val packed = Regex(
        """eval\(function\(p,a,c,k,e,[rd]?""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val extract = Regex(
        """\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val word = Regex("""\b\w+\b""")

    fun detect(script: String): Boolean = packed.containsMatchIn(script)

    fun unpackAndCombine(script: String): String? = extract.findAll(script).mapNotNull { match ->
        val payload = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val count = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
        val symbols = match.groupValues[4].split('|')
        if (symbols.size != count) return@mapNotNull null
        val unbaser = Unbaser(radix)
        unescape(
            payload.replace(word) { token ->
                symbols.getOrNull(unbaser.decode(token.value)).orEmpty().ifEmpty(token::value)
            },
        )
    }.joinToString(" ").takeIf(String::isNotBlank)

    /**
     * Resolves backslash escapes in a single left-to-right pass.
     *
     * Chained `replace` calls cannot do this correctly whatever order they run in, because each
     * pass reads output the previous one produced. Unescaping `\\` last meant the literal sequence
     * `\` `\` `n` had its trailing `\n` consumed as a newline first, leaving a stray backslash;
     * unescaping it first would corrupt the other direction. One pass, consuming both characters
     * of each escape at once, has no such ordering to get wrong.
     */
    internal fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                append(char)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '\\', '\'', '"' -> append(escaped)
                // Not an escape the packer emits; keep both characters as they were.
                else -> append(char).append(escaped)
            }
            index += 2
        }
    }

    private class Unbaser(private val base: Int) {
        private val alphabet = when {
            base > 62 -> ALPHABET_95
            base > 54 -> ALPHABET_62
            base > 52 -> ALPHABET_54
            else -> ALPHABET_52
        }
        private val lookup = alphabet.indices.associateBy(alphabet::get)

        fun decode(value: String): Int {
            if (base in 2..36) return value.toIntOrNull(base) ?: 0
            // Double, not Float: a 24-bit mantissa stops representing powers of 62 exactly at
            // 62^5, so a longer token would decode to a neighbouring symbol rather than its own.
            return value.reversed().foldIndexed(0) { index, total, character ->
                total + (base.toDouble().pow(index) * (lookup[character] ?: 0)).toInt()
            }
        }
    }

    private const val ALPHABET_52 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP"
    private const val ALPHABET_54 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR"
    private const val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    /**
     * Printable ASCII, space through tilde, in order — exactly 95 characters.
     *
     * Written out rather than generated because it is the packer's own table and reads as such,
     * but note the one escape that matters: the backslash belongs between `[` and `]`, where ASCII
     * puts it, and nowhere else. Porting this from a single-quoted Python/JS source, where the
     * table is written `&\'()`, previously turned an escaped quote into a literal backslash *and*
     * quote — 96 characters, every symbol from index 7 on shifted by one, and any script packed at
     * radix 63 or above silently decoding to nonsense.
     */
    /** Exposed so a test can hold the table to the standard rather than to itself. */
    internal val printableAsciiAlphabet: String get() = ALPHABET_95

    private const val ALPHABET_95 =
        " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
}
