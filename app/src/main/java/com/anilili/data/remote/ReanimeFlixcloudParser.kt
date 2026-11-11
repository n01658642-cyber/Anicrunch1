package com.anilili.data.remote

import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SubtitleItem
import com.anilili.util.cached

internal object ReanimeFlixcloudParser {
    fun subtitles(html: String, audio: String): List<SubtitleItem> {
        val array = jsArray(html, "subtitles") ?: return emptyList()

        return jsObjects(array)
            .mapNotNull { block ->
                val url = jsField(block, "url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = jsField(block, "language").ifBlank { "Subtitle" }
                FlixSubtitle(
                    item = SubtitleItem(
                        url = NativeProviderParsers.decodeEntities(url),
                        label = NativeProviderParsers.decodeEntities(label),
                        language = language(label, url),
                    ),
                    isDefault = jsBoolean(block, "default"),
                )
            }
            .distinctBy { it.item.url }
            .sortedWith(compareBy<FlixSubtitle> { subtitleRank(it, audio) }.thenBy { it.item.label })
            .map { it.item }
            .toList()
    }

    fun skip(html: String): SkipTimes? {
        val intro = chapter(html, "intro_chapter") ?: chapterByTitle(html, "Intro")
        val outro = chapter(html, "outro_chapter")
            ?: chapterByTitle(html, "Outro")
            ?: chapterByTitle(html, "Credits")
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.first, intro?.second, outro?.first, outro?.second)
    }

    fun defaultAudioTrack(html: String): Int? =
        Regex.cached("""default_audio_track\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun chapter(html: String, field: String): Pair<Double, Double>? =
        Regex(
            """${Regex.escape(field)}\s*:\s*\{\s*start\s*:\s*([0-9.]+)\s*,\s*end\s*:\s*([0-9.]+)""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.let { match ->
            val start = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val end = match.groupValues[2].toDoubleOrNull() ?: return@let null
            start to end
        }

    private fun chapterByTitle(html: String, title: String): Pair<Double, Double>? =
        Regex.cached(
            "\\{\\s*start\\s*:\\s*([0-9.]+)\\s*,\\s*end\\s*:\\s*([0-9.]+)\\s*,\\s*title\\s*:\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        ).findAll(html).firstOrNull { it.groupValues[3].equals(title, ignoreCase = true) }?.let { match ->
            val start = match.groupValues[1].toDoubleOrNull() ?: return@let null
            val end = match.groupValues[2].toDoubleOrNull() ?: return@let null
            start to end
        }

    /**
     * The body of the JavaScript array literal assigned to [name], brackets excluded.
     *
     * Scanned rather than matched with a regex, because the values inside carry brackets of their
     * own. `subtitles\s*:\s*\[([\s\S]*?)]` looks right until a track is labelled
     * `English (Signs & Songs [ParanDark modified])`: the lazy capture stops at the `]` closing
     * `[ParanDark modified`, four characters into the first entry, and hands back a fragment with an
     * unclosed `{` in it. Every entry is then lost, so an episode with a perfectly good subtitle
     * list reports none at all — which is what Bleach and Monster were doing, on every server and in
     * both audio modes, while the chapter markers on the same page kept parsing fine because they
     * are read by a different expression. Groups that credit themselves in brackets are common
     * enough on dub releases that this looked like a dub-only fault.
     */
    private fun jsArray(source: String, name: String): String? {
        val opening = Regex("""\b${Regex.escape(name)}\s*:\s*\[""", RegexOption.IGNORE_CASE)
            .find(source)?.range?.last ?: return null
        val closing = matchingBracket(source, opening) ?: return null
        return source.substring(opening + 1, closing)
    }

    /** The bodies of the object literals directly inside [array], braces excluded. */
    private fun jsObjects(array: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = 0
        var quote: Char? = null
        while (index < array.length) {
            val char = array[index]
            when {
                quote != null -> if (char == '\\') index++ else if (char == quote) quote = null
                char == '"' || char == '\'' || char == '`' -> quote = char
                char == '{' -> {
                    val end = matchingBracket(array, index) ?: return blocks
                    blocks += array.substring(index + 1, end)
                    index = end
                }
            }
            index++
        }
        return blocks
    }

    /**
     * The index of the bracket closing the one at [openIndex], or null if it is never closed.
     *
     * Skips anything inside a string literal, honouring backslash escapes, so a delimiter that is
     * part of a label cannot end the structure early. That is the whole point of the scan — see
     * [jsArray].
     */
    private fun matchingBracket(source: String, openIndex: Int): Int? {
        val opener = source[openIndex]
        val closer = when (opener) {
            '[' -> ']'
            '{' -> '}'
            else -> return null
        }
        var depth = 0
        var quote: Char? = null
        var index = openIndex
        while (index < source.length) {
            val char = source[index]
            when {
                quote != null -> if (char == '\\') index++ else if (char == quote) quote = null
                char == '"' || char == '\'' || char == '`' -> quote = char
                char == opener -> depth++
                char == closer -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun jsField(block: String, name: String): String =
        Regex("${Regex.escape(name)}\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.let(::decodeJsString)
            .orEmpty()

    private fun jsBoolean(block: String, name: String): Boolean =
        Regex("""${Regex.escape(name)}\s*:\s*true\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)

    private fun decodeJsString(value: String): String {
        val unicode = Regex.cached("""\\u([0-9a-fA-F]{4})""").replace(value) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return unicode
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun language(label: String, url: String): String {
        Regex.cached("""_([a-z]{3})_\d+\.(?:ass|vtt|srt)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?.let { code -> threeLetterLanguage[code]?.let { return it } }
        val normalized = label.substringBefore('(').trim().lowercase()
        return languageNames[normalized] ?: "und"
    }

    private fun subtitleRank(subtitle: FlixSubtitle, audio: String): Int {
        val label = subtitle.item.label.lowercase()
        val englishPenalty = if (subtitle.item.language == "en" || label.startsWith("english")) 0 else 100
        val defaultBonus = if (subtitle.isDefault) -5 else 0
        val isForced = label.contains("forced") || label.contains("signs") ||
            label.contains("songs") || label.contains("episode name")
        val isFull = label.contains("full") || label.contains("dialogue") ||
            label.contains("cr") || label.contains("track")
        val kind = if (audio == "dub") {
            when {
                isForced -> -10
                isFull -> 10
                else -> 0
            }
        } else {
            when {
                isFull -> -10
                isForced -> 20
                else -> 0
            }
        }
        return englishPenalty + defaultBonus + kind
    }

    private data class FlixSubtitle(val item: SubtitleItem, val isDefault: Boolean)

    private val threeLetterLanguage = mapOf(
        "ara" to "ar",
        "chi" to "zh",
        "zho" to "zh",
        "eng" to "en",
        "fre" to "fr",
        "fra" to "fr",
        "ger" to "de",
        "deu" to "de",
        "ind" to "id",
        "ita" to "it",
        "may" to "ms",
        "msa" to "ms",
        "por" to "pt",
        "rus" to "ru",
        "spa" to "es",
        "tha" to "th",
        "vie" to "vi",
        "jpn" to "ja",
    )

    private val languageNames = mapOf(
        "arabic" to "ar",
        "chinese" to "zh",
        "english" to "en",
        "french" to "fr",
        "german" to "de",
        "indonesian" to "id",
        "italian" to "it",
        "malay" to "ms",
        "portuguese" to "pt",
        "russian" to "ru",
        "spanish" to "es",
        "thai" to "th",
        "vietnamese" to "vi",
        "japanese" to "ja",
    )
}
