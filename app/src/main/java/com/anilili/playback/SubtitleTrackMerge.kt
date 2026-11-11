package com.anilili.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import com.anilili.data.model.SubtitleItem
import com.anilili.util.cached
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/*
 * Signs and dialogue on screen at once.
 *
 * Dub releases split their English text in two: a full "English CC (Dub)" track that follows the
 * dub's own script, and an "English (Signs & Songs)" track carrying only the typesetting — on-screen
 * text, song lyrics, episode titles. ReAnime and ani.pm both hand them over as separate sidecars.
 * Media3 draws one text track at a time (DefaultTrackSelector gives each track type to a single
 * renderer), so picking either one loses the other: the signs track has no dialogue, and the sub
 * track's dialogue is the Japanese script, which does not match what the dub actors say.
 *
 * So the two files are merged into a third, attached to the episode the way an imported subtitle
 * file is, and the player shows that one track. Every cue still passes through SubtitleCueLayout,
 * and a sign placed with `\pos` keeps its place because the signs file's canvas is the merged one's.
 */

/** A signs-only track and the dialogue track it is shown with. */
internal data class SignsPairing(val signs: SubtitleItem, val dialogue: SubtitleItem) {
    /** The merged track's label, which is also how the player's track list finds it again. */
    val mergedLabel: String get() = "${dialogue.label}$SIGNS_MERGE_SUFFIX"
}

/** The dialogue track a merged track was built from, read back off its label; null for any other. */
internal fun signsMergeDialogueLabel(label: String?): String? =
    label?.takeIf { it.endsWith(SIGNS_MERGE_SUFFIX) }?.removeSuffix(SIGNS_MERGE_SUFFIX)

private const val SIGNS_MERGE_SUFFIX = " + signs"

/** Whether a track carries only typesetting (signs, songs, forced lines) rather than dialogue. */
internal fun isSignsSubtitle(label: String): Boolean {
    val lower = label.lowercase(Locale.ROOT)
    return "signs" in lower || "songs" in lower || "forced" in lower
}

/**
 * The signs and dialogue tracks to show together, or null when this episode has no such pair.
 *
 * [selectedLabel] is the track on screen. A dialogue track the viewer picked is the one the signs
 * join; with the signs track itself picked, or nothing, the dialogue is chosen for them — on a dub
 * ([preferDub]) the track labelled as the dub's script, since that is the one that matches the
 * voices. Both halves must be in the same language: Spanish dialogue under English signs is two
 * translations talking over each other.
 */
internal fun signsPairing(
    subtitles: List<SubtitleItem>,
    selectedLabel: String?,
    preferDub: Boolean,
): SignsPairing? {
    val sidecars = subtitles.filter(::isMergeableSidecar)
    val signsTracks = sidecars.filter { isSignsSubtitle(it.label) }
    if (signsTracks.isEmpty()) return null
    val selected = sidecars.firstOrNull { it.label == selectedLabel }
    val dialogue = selected?.takeUnless { isSignsSubtitle(it.label) } ?: run {
        val candidates = sidecars.filter { track ->
            !isSignsSubtitle(track.label) &&
                (selected == null || sameLanguage(track.language, selected.language)) &&
                signsTracks.any { sameLanguage(it.language, track.language) }
        }
        candidates.firstOrNull { preferDub && "dub" in it.label.lowercase(Locale.ROOT) }
            ?: candidates.firstOrNull()
    } ?: return null
    val signs = selected?.takeIf { isSignsSubtitle(it.label) && sameLanguage(it.language, dialogue.language) }
        ?: signsTracks.firstOrNull { sameLanguage(it.language, dialogue.language) }
        ?: return null
    return SignsPairing(signs = signs, dialogue = dialogue)
}

/** Sidecar text this file can merge; TTML is the one format the player takes that it cannot. */
private fun isMergeableSidecar(subtitle: SubtitleItem): Boolean {
    if (subtitle.url.isBlank()) return false
    val mime = subtitleMimeTypeFor(subtitle.url, subtitle.mimeType) ?: return true
    return mime != MimeTypes.APPLICATION_TTML
}

/** Language codes compared by their three-letter form; an unknown language matches anything. */
private fun sameLanguage(first: String, second: String): Boolean {
    val a = languageKey(first) ?: return true
    val b = languageKey(second) ?: return true
    return a == b
}

private fun languageKey(raw: String): String? {
    val value = raw.trim().lowercase(Locale.ROOT)
    if (value.isEmpty() || value == "und") return null
    if (value.length != 2) return value
    return runCatching { Locale.forLanguageTag(value).isO3Language }.getOrNull()?.ifEmpty { null } ?: value
}

/**
 * Fetches both halves of [pairing], merges them and stores the result beside imported subtitle
 * files, as a track the player can attach. The two downloads run side by side.
 */
internal suspend fun buildSignsMerge(context: Context, pairing: SignsPairing): SubtitleItem = withContext(Dispatchers.IO) {
    // All of it off the main thread: a typeset signs file runs to megabytes, and merging and
    // writing it there was long enough for StrictMode to flag.
    val signs = async { loadSubtitleText(context, pairing.signs) }
    val dialogue = async { loadSubtitleText(context, pairing.dialogue) }
    val signsText = signs.await() ?: throw IOException("The signs track has no address.")
    val dialogueText = dialogue.await() ?: throw IOException("The dialogue track has no address.")
    val merged = mergeSubtitleTexts(base = signsText, overlay = dialogueText)
    val target = context.subtitleCacheFile("signs-merge", merged.mimeType)
    target.writeText(merged.text)
    SubtitleItem(
        url = Uri.fromFile(target).toString(),
        label = pairing.mergedLabel,
        language = pairing.dialogue.language,
        mimeType = merged.mimeType,
    )
}

/** A merged subtitle file and the format it came out in. */
internal data class MergedSubtitle(val text: String, val mimeType: String)

/**
 * Two subtitle files as one, in whichever of ASS, WebVTT or SubRip keeps the most of both.
 *
 * Any ASS input makes the result ASS, since only ASS carries a sign's position, and a WebVTT or
 * SubRip side is converted into it. Two SubRip files stay SubRip; any other text pair becomes
 * WebVTT. In ASS, [base]'s canvas (`PlayResX`/`PlayResY`) is the merged file's, and [overlay]'s
 * coordinates are rescaled onto it when the two differ — so the file whose positions matter, the
 * signs, goes in as [base].
 */
internal fun mergeSubtitleTexts(base: String, overlay: String): MergedSubtitle {
    val first = parseSubtitleDocument(base)
    val second = parseSubtitleDocument(overlay)
    return when {
        first is AssDocument || second is AssDocument ->
            MergedSubtitle(mergeAsAss(first, second), MimeTypes.TEXT_SSA)
        first is TextCueDocument && second is TextCueDocument && first.isSubRip && second.isSubRip ->
            MergedSubtitle(writeSubRip(first.cues + second.cues), MimeTypes.APPLICATION_SUBRIP)
        else -> MergedSubtitle(
            writeWebVtt(listOf(first, second).filterIsInstance<TextCueDocument>()),
            MimeTypes.TEXT_VTT,
        )
    }
}

private sealed interface SubtitleDocument

private class AssDocument(
    val scriptInfo: List<Pair<String, String>>,
    val playRes: Pair<Int, Int>?,
    /** Each style as its fields, keyed by the lower-case V4+ field name. */
    val styles: List<Map<String, String>>,
    /** Each `Dialogue:` line as its fields, keyed by the lower-case field name. */
    val events: List<Map<String, String>>,
) : SubtitleDocument

private class TextCueDocument(
    val cues: List<TextCue>,
    /** WebVTT `STYLE` and `REGION` blocks, which must come before the first cue. */
    val headerBlocks: List<String>,
    val isSubRip: Boolean,
) : SubtitleDocument

private data class TextCue(
    val startMs: Long,
    val endMs: Long,
    /** WebVTT cue settings after the end time (`line:0 align:start`), empty for SubRip. */
    val settings: String,
    val text: String,
    val isSubRip: Boolean,
)

private fun parseSubtitleDocument(raw: String): SubtitleDocument {
    val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val isAss = text.lineSequence().any { line ->
        val trimmed = line.trim()
        trimmed.equals("[Events]", ignoreCase = true) || trimmed.equals("[Script Info]", ignoreCase = true)
    }
    if (isAss) return parseAss(text)
    return parseTextCues(text, isSubRip = !text.trimStart().startsWith("WEBVTT"))
}

private fun parseAss(text: String): AssDocument {
    val info = ArrayList<Pair<String, String>>()
    val styles = ArrayList<Map<String, String>>()
    val events = ArrayList<Map<String, String>>()
    var section = ""
    var styleFormat = ASS_STYLE_FIELDS.map { it.lowercase(Locale.ROOT) }
    var eventFormat = ASS_EVENT_FIELDS.map { it.lowercase(Locale.ROOT) }
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith(";")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.lowercase(Locale.ROOT)
            continue
        }
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val key = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trimStart()
        when (section) {
            "[script info]" -> info += key to value.trim()
            "[v4+ styles]", "[v4 styles]" -> when (key.lowercase(Locale.ROOT)) {
                "format" -> styleFormat = formatFields(value)
                "style" -> styles += assRecord(styleFormat, value)
                    .let { if (section == "[v4 styles]") legacyStyleToV4Plus(it) else it }
            }
            "[events]" -> when (key.lowercase(Locale.ROOT)) {
                "format" -> eventFormat = formatFields(value)
                "dialogue" -> assRecord(eventFormat, value)
                    .takeIf { "start" in it && "end" in it && "text" in it }
                    ?.let(events::add)
            }
        }
    }
    return AssDocument(info, assPlayRes(info), styles, events)
}

private fun formatFields(value: String): List<String> =
    value.split(',').map { it.trim().lowercase(Locale.ROOT) }

/** One `Style:` or `Dialogue:` line; the last field (the text) may itself contain commas. */
private fun assRecord(format: List<String>, value: String): Map<String, String> =
    format.zip(value.split(',', limit = format.size)).associate { (field, part) ->
        field to if (field == "text") part else part.trim()
    }

/**
 * An SSA v4 style in V4+ terms. The outline colour was called TertiaryColour, and alignment used
 * the old numbering — 1-3 bottom, 5-7 top, 9-11 middle — where V4+ uses the numeric keypad.
 */
private fun legacyStyleToV4Plus(style: Map<String, String>): Map<String, String> {
    val converted = style.toMutableMap()
    style["tertiarycolour"]?.let { converted["outlinecolour"] = it }
    style["alignment"]?.toIntOrNull()?.let { legacy ->
        converted["alignment"] = when (legacy) {
            in 5..7 -> legacy + 2
            in 9..11 -> legacy - 5
            else -> legacy
        }.toString()
    }
    return converted
}

/** The script's canvas; one side missing is derived the way libass derives it. */
private fun assPlayRes(info: List<Pair<String, String>>): Pair<Int, Int>? {
    fun value(name: String) = info.firstOrNull { it.first.equals(name, ignoreCase = true) }
        ?.second?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    val x = value("PlayResX")
    val y = value("PlayResY")
    return when {
        x != null && y != null -> x to y
        x != null -> x to if (x == 1280) 1024 else x * 3 / 4
        y != null -> (if (y == 1024) 1280 else y * 4 / 3) to y
        else -> null
    }
}

private fun parseTextCues(text: String, isSubRip: Boolean): TextCueDocument {
    val cues = ArrayList<TextCue>()
    val headerBlocks = ArrayList<String>()
    for (block in text.split(BLANK_LINE)) {
        val lines = block.lines().map(String::trimEnd).dropWhile(String::isBlank)
        val first = lines.firstOrNull() ?: continue
        if (!isSubRip) {
            when {
                first.startsWith("WEBVTT") || first.startsWith("NOTE") -> continue
                first.startsWith("STYLE") || first.startsWith("REGION") -> {
                    headerBlocks += lines.joinToString("\n")
                    continue
                }
            }
        }
        val timing = lines.indexOfFirst { "-->" in it }
        if (timing < 0) continue
        val (left, right) = lines[timing].split("-->", limit = 2)
        val startMs = parseTimestampMs(left) ?: continue
        val rightParts = right.trim().split(WHITESPACE, limit = 2)
        val endMs = parseTimestampMs(rightParts[0]) ?: continue
        val payload = lines.drop(timing + 1).joinToString("\n").trim('\n')
        if (payload.isBlank()) continue
        cues += TextCue(
            startMs = startMs,
            endMs = endMs,
            settings = if (isSubRip) "" else rightParts.getOrNull(1).orEmpty().trim(),
            text = payload,
            isSubRip = isSubRip,
        )
    }
    return TextCueDocument(cues, headerBlocks, isSubRip)
}

private fun mergeAsAss(first: SubtitleDocument, second: SubtitleDocument): String {
    val canvasDocument = listOf(first, second).filterIsInstance<AssDocument>().first()
    val canvas = canvasDocument.playRes
    val styles = ArrayList<String>()
    val events = ArrayList<String>()
    listOf(first to "A_", second to "B_").forEach { (document, prefix) ->
        when (document) {
            is AssDocument -> {
                // Scaled only when the canvas has a size. Without one media3 ignores `\pos`
                // altogether, so there is nothing to keep consistent.
                val scale = if (canvas != null && document !== canvasDocument) {
                    val own = document.playRes ?: (ASS_DEFAULT_PLAY_RES_X to ASS_DEFAULT_PLAY_RES_Y)
                    (canvas.first.toDouble() / own.first to canvas.second.toDouble() / own.second)
                        .takeUnless { it.first == 1.0 && it.second == 1.0 }
                } else {
                    null
                }
                val names = document.styles.mapNotNull { it["name"] }.toSet()
                document.styles.forEach { styles += assStyleLine(it, prefix, scale) }
                document.events.forEach { events += assDialogueLine(it, prefix, names, scale) }
            }
            is TextCueDocument -> {
                val height = canvas?.second ?: ASS_DEFAULT_PLAY_RES_Y
                styles += assStyleLine(
                    mapOf(
                        "name" to TEXT_CUE_STYLE,
                        "fontsize" to formatNumber(height * 0.055),
                        "marginv" to formatNumber(height * 0.04),
                    ),
                    prefix,
                    scale = null,
                )
                document.cues.sortedBy(TextCue::startMs).forEach { cue ->
                    events += "Dialogue: 0,${assTime(cue.startMs)},${assTime(cue.endMs)}," +
                        "$prefix$TEXT_CUE_STYLE,,0,0,0,,${textCueToAss(cue)}"
                }
            }
        }
    }
    return buildString {
        append("[Script Info]\n")
        append("ScriptType: v4.00+\n")
        canvasDocument.scriptInfo
            .filterNot { (key, _) -> key.lowercase(Locale.ROOT) in REWRITTEN_SCRIPT_INFO }
            .forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        canvas?.let { (x, y) -> append("PlayResX: $x\nPlayResY: $y\n") }
        append("\n[V4+ Styles]\n")
        append("Format: ").append(ASS_STYLE_FIELDS.joinToString(", ")).append('\n')
        styles.forEach { append(it).append('\n') }
        append("\n[Events]\n")
        append("Format: ").append(ASS_EVENT_FIELDS.joinToString(", ")).append('\n')
        events.forEach { append(it).append('\n') }
    }
}

private fun assStyleLine(style: Map<String, String>, prefix: String, scale: Pair<Double, Double>?): String {
    val values = ASS_STYLE_FIELDS.map { field ->
        val key = field.lowercase(Locale.ROOT)
        val value = style[key]?.takeIf { it.isNotEmpty() } ?: ASS_STYLE_DEFAULTS.getValue(key)
        when {
            key == "name" -> prefix + value
            scale == null -> value
            key == "fontsize" || key == "marginv" -> scaleNumber(value, scale.second)
            key == "marginl" || key == "marginr" -> scaleNumber(value, scale.first)
            else -> value
        }
    }
    return "Style: " + values.joinToString(",")
}

private fun assDialogueLine(
    event: Map<String, String>,
    prefix: String,
    styleNames: Set<String>,
    scale: Pair<Double, Double>?,
): String {
    val values = ASS_EVENT_FIELDS.map { field ->
        val key = field.lowercase(Locale.ROOT)
        val value = event[key].orEmpty()
        when (key) {
            "layer" -> value.ifEmpty { "0" }
            "style" -> prefix + value.ifEmpty { "Default" }.removePrefix("*")
            "marginl", "marginr" -> scale?.let { scaleNumber(value.ifEmpty { "0" }, it.first) } ?: value.ifEmpty { "0" }
            "marginv" -> scale?.let { scaleNumber(value.ifEmpty { "0" }, it.second) } ?: value.ifEmpty { "0" }
            "text" -> renameStyleResets(value, prefix, styleNames).let { text ->
                scale?.let { scaleOverrideTags(text, it) } ?: text
            }
            else -> value
        }
    }
    return "Dialogue: " + values.joinToString(",")
}

/** `\rName` switches to another style by name, so it follows the rename. */
private fun renameStyleResets(text: String, prefix: String, styleNames: Set<String>): String =
    STYLE_RESET.replace(text) { match ->
        val name = match.groupValues[1]
        if (name in styleNames) "\\r$prefix$name" else match.value
    }

/** `\pos`, `\move` and `\org` in the overlay's own canvas, carried onto the merged one. */
private fun scaleOverrideTags(text: String, scale: Pair<Double, Double>): String =
    POSITION_TAG.replace(text) { match ->
        val numbers = match.groupValues[2].split(',').map(String::trim)
        val scaled = numbers.mapIndexed { index, number ->
            // \move(x1,y1,x2,y2[,t1,t2]): only the first four are coordinates.
            if (index >= 4) number else scaleNumber(number, if (index % 2 == 0) scale.first else scale.second)
        }
        "\\${match.groupValues[1]}(${scaled.joinToString(",")})"
    }

private fun scaleNumber(value: String, factor: Double): String =
    value.trim().toDoubleOrNull()?.let { formatNumber(it * factor) } ?: value

private fun formatNumber(value: Double): String {
    val rounded = (value * 100).roundToLong() / 100.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

/** `H:MM:SS.cc`, the only time format ASS reads. */
private fun assTime(ms: Long): String {
    val centis = ms.coerceAtLeast(0) / 10
    return String.format(
        Locale.ROOT,
        "%d:%02d:%02d.%02d",
        centis / 360_000,
        centis / 6_000 % 60,
        centis / 100 % 60,
        centis % 100,
    )
}

/**
 * A WebVTT or SubRip cue's text as an ASS line: italics, bold and underline carried over, every
 * other tag dropped, and a cue the file lifted to the top of the picture kept there.
 */
private fun textCueToAss(cue: TextCue): String {
    var text = cue.text
    if (!cue.isSubRip) text = text.replace("{", "(").replace("}", ")")
    text = SIMPLE_TAG.replace(text) { match ->
        val closing = match.groupValues[1].isNotEmpty()
        "{\\${match.groupValues[2].lowercase(Locale.ROOT)}${if (closing) 0 else 1}}"
    }
    text = decodeEntities(ANY_TAG.replace(text, ""))
    val lifted = !cue.isSubRip && isTopLine(cue.settings)
    return (if (lifted) "{\\an8}" else "") + text.replace("\n", "\\N")
}

/** WebVTT `line:` placing the cue in the upper half: a low line number, or under 50%. */
private fun isTopLine(settings: String): Boolean {
    val line = settings.split(WHITESPACE)
        .firstOrNull { it.startsWith("line:") }
        ?.removePrefix("line:")
        ?.substringBefore(',')
        ?: return false
    return if (line.endsWith('%')) {
        (line.removeSuffix("%").toDoubleOrNull() ?: return false) < 50.0
    } else {
        (line.toIntOrNull() ?: return false) in 0..3
    }
}

private fun writeWebVtt(documents: List<TextCueDocument>): String = buildString {
    append("WEBVTT\n\n")
    documents.flatMap(TextCueDocument::headerBlocks).forEach { append(it).append("\n\n") }
    documents.flatMap(TextCueDocument::cues).sortedBy(TextCue::startMs).forEach { cue ->
        var text = cue.text
        var settings = cue.settings
        if (cue.isSubRip) {
            // SubRip borrows ASS's `{\an8}` to lift a line; WebVTT would print it.
            if (SUBRIP_TOP_ALIGN.containsMatchIn(text)) settings = "line:0"
            text = ASS_OVERRIDE_BLOCK.replace(text, "")
        }
        append(vttTime(cue.startMs)).append(" --> ").append(vttTime(cue.endMs))
        if (settings.isNotEmpty()) append(' ').append(settings)
        append('\n').append(text).append("\n\n")
    }
}

private fun writeSubRip(cues: List<TextCue>): String = buildString {
    cues.sortedBy(TextCue::startMs).forEachIndexed { index, cue ->
        append(index + 1).append('\n')
        append(vttTime(cue.startMs).replace('.', ',')).append(" --> ")
            .append(vttTime(cue.endMs).replace('.', ',')).append('\n')
        append(cue.text).append("\n\n")
    }
}

private fun vttTime(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d.%03d",
        value / 3_600_000,
        value / 60_000 % 60,
        value / 1_000 % 60,
        value % 1_000,
    )
}

private fun decodeEntities(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", "\u00A0")
    .replace("&lrm;", "\u200E")
    .replace("&rlm;", "\u200F")
    .replace("&amp;", "&")

private val ASS_STYLE_FIELDS = listOf(
    "Name", "Fontname", "Fontsize", "PrimaryColour", "SecondaryColour", "OutlineColour",
    "BackColour", "Bold", "Italic", "Underline", "StrikeOut", "ScaleX", "ScaleY", "Spacing",
    "Angle", "BorderStyle", "Outline", "Shadow", "Alignment", "MarginL", "MarginR", "MarginV",
    "Encoding",
)

private val ASS_STYLE_DEFAULTS = mapOf(
    "name" to "Default",
    "fontname" to "Arial",
    "fontsize" to "20",
    "primarycolour" to "&H00FFFFFF",
    "secondarycolour" to "&H000000FF",
    "outlinecolour" to "&H00000000",
    "backcolour" to "&H00000000",
    "bold" to "0",
    "italic" to "0",
    "underline" to "0",
    "strikeout" to "0",
    "scalex" to "100",
    "scaley" to "100",
    "spacing" to "0",
    "angle" to "0",
    "borderstyle" to "1",
    "outline" to "2",
    "shadow" to "0",
    "alignment" to "2",
    "marginl" to "10",
    "marginr" to "10",
    "marginv" to "10",
    "encoding" to "1",
)

private val ASS_EVENT_FIELDS = listOf(
    "Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text",
)

/** Script Info keys the merged header writes itself rather than copying from the base file. */
private val REWRITTEN_SCRIPT_INFO = setOf("scripttype", "playresx", "playresy")

/** The canvas ASS assumes when a script names none. */
private const val ASS_DEFAULT_PLAY_RES_X = 384
private const val ASS_DEFAULT_PLAY_RES_Y = 288

private const val TEXT_CUE_STYLE = "Text"

private val BLANK_LINE = Regex.cached("""\n[ \t]*\n""")
private val WHITESPACE = Regex.cached("""\s+""")
private val STYLE_RESET = Regex.cached("""\\r([^\\}]+)""")
private val POSITION_TAG = Regex.cached("""\\(pos|move|org)\(([^)]*)\)""")
private val SIMPLE_TAG = Regex.cached("""<(/?)([ibu])>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex.cached("""<[^>]*>""")
private val ASS_OVERRIDE_BLOCK = Regex.cached("""\{\\[^}]*\}""")
private val SUBRIP_TOP_ALIGN = Regex.cached("""\{\\an[789]\}""")
