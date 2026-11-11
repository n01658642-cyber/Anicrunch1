package com.anilili.data.remote

import com.anilili.util.cached
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A playable stream read straight out of a Miruro iframe embed's own page.
 *
 * The point of reaching this is that the app stops needing a browser to play the provider: the
 * streams behind both of Miruro's `twin` embeds are plain HLS that no header gates, so a WebView
 * buys nothing and costs the app its own controls, subtitle delay, PiP, Cast and downloads.
 */
internal data class MiruroEmbedStream(
    val url: String,
    val subtitleUrl: String? = null,
    /** BCP-47-ish code read off the subtitle's own filename, `null` when it does not name one. */
    val subtitleLanguage: String? = null,
)

// ---- VidHide / EarnVids (twin's hard-sub host, e.g. otakuvid.online) ----

/**
 * The `var links = {…}` map a VidHide embed declares inside its packed player script.
 *
 * The page is Dean Edwards packed, so this runs [AllAnimeJsUnpacker] first — the same unpacker the
 * AllAnime external extractor uses, which handles this payload unchanged (radix 36, 530 symbols).
 * The values are a plain JSON object once unpacked; the URLs contain no braces, which is what lets
 * the object be cut out by matching to the first `}`.
 */
internal fun vidHideLinks(html: String, json: Json): Map<String, String> {
    if (!AllAnimeJsUnpacker.detect(html)) return emptyMap()
    val script = AllAnimeJsUnpacker.unpackAndCombine(html) ?: return emptyMap()
    val literal = Regex("""\blinks\s*=\s*(\{[^{}]*\})""").find(script)?.groupValues?.get(1)
        ?: return emptyMap()
    val root = runCatching { json.parseToJsonElement(literal) as? JsonObject }.getOrNull()
        ?: return emptyMap()
    return root.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { key to it }
    }.toMap()
}

/**
 * How much a VidHide link looks like the one that will actually serve.
 *
 * The page's own preference cannot be copied. JWPlayer is configured `links.hls4||links.hls3||
 * links.hls2`, so it reaches for `hls3` first — and on 2026-09-09 `hls3` was a `master.txt` that
 * answered **404**, with playback only arriving at the working `hls2` through the page's `error`
 * handler (codes 232000-233999 / 202000-202999, or a `fragLoadError`). Following that order here
 * would mean every play started with a guaranteed failed request.
 *
 * So links are ranked by what distinguishes the live one rather than by key name: it carries a
 * signature (`?t=…&s=…&e=…`) and it ends `.m3u8`. Ranking beats hardcoding `hls2` because the keys
 * are the host's to rename, while an unsigned `.txt` alternate staying dead is the observed shape.
 * Ties keep the page's own order, [JsonObject] being insertion-ordered.
 */
private fun vidHideLinkRank(url: String): Int {
    val signed = url.substringAfter('?', "").isNotEmpty()
    val manifest = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    return (if (signed) 2 else 0) + (if (manifest) 1 else 0)
}

/** The VidHide link most likely to play, or `null` when the page declares none. */
internal fun vidHideStreamUrl(html: String, json: Json): String? =
    vidHideLinks(html, json).entries
        .sortedByDescending { vidHideLinkRank(it.value) }
        .firstOrNull()
        ?.value

// ---- VibePlayer (twin's soft-sub host, e.g. bibiemb.xyz) ----

/**
 * The stream a VibePlayer embed declares.
 *
 * Nothing here is obfuscated — the page is a plain `const src = "…/master.m3u8"` beside a JWPlayer
 * setup. Matching on the `.m3u8` rather than on `const src` keeps this working through a rename and
 * stops it colliding with the page's several `<script src="…">` tags.
 */
internal fun vibePlayerStreamUrl(html: String): String? =
    Regex.cached("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").find(html)?.groupValues?.get(1)

/**
 * The caption track for a VibePlayer embed, which is **not** in the page.
 *
 * The page only echoes its own `?sub=` query parameter into a JW `tracks` entry, so the subtitle URL
 * is already present in the iframe URL the pipe hands over — verified by fetching the same embed
 * without the parameter, which yields `const subtitle = ""`. Reading it from [embedUrl] therefore
 * costs no request and works even when the page fetch is the thing that failed.
 *
 * The value arrives unencoded (a bare `https://…`), so it is only percent-decoded when it actually
 * looks encoded; decoding unconditionally would turn a legitimate `+` in a path into a space.
 */
internal fun vibePlayerSubtitleUrl(embedUrl: String, html: String? = null): String? {
    val raw = Regex("""[?&]sub=([^&]+)""").find(embedUrl)?.groupValues?.get(1)
        ?: html?.let { Regex.cached("""subtitle\s*=\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
    val value = raw?.takeIf(String::isNotBlank) ?: return null
    return if (value.contains('%')) {
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    } else {
        value
    }
}

/**
 * The language a subtitle names in its own filename, e.g. `…_136739_sub_eng-0.vtt` → `eng`.
 *
 * Returns `null` rather than guessing when the filename says nothing. Defaulting to English would
 * be a lie the player then acts on, auto-selecting a track for a viewer whose preferred language it
 * has no evidence about.
 */
internal fun subtitleLanguageFromFilename(url: String): String? =
    Regex.cached("""_(?:sub|dub)_([a-z]{2,3})(?:[-_.]|$)""", RegexOption.IGNORE_CASE)
        .find(url.substringBefore('?').substringAfterLast('/'))
        ?.groupValues
        ?.get(1)
        ?.lowercase()

// ---- dispatch ----

/**
 * The stream an embed page declares, whichever of Miruro's two embed players served it.
 *
 * Both are tried against the same fetched page rather than the host being routed on, because
 * `twin` alone spans two unrelated hosts (`otakuvid.online` for hard sub, `bibiemb.xyz` for soft)
 * and either could be moved without notice. What the page *is* survives a domain change; a hostname
 * table does not.
 */
internal fun miruroEmbedStream(embedUrl: String, html: String, json: Json): MiruroEmbedStream? {
    val url = vidHideStreamUrl(html, json) ?: vibePlayerStreamUrl(html) ?: return null
    val subtitle = vibePlayerSubtitleUrl(embedUrl, html)
    return MiruroEmbedStream(
        url = url,
        subtitleUrl = subtitle,
        subtitleLanguage = subtitle?.let(::subtitleLanguageFromFilename),
    )
}
