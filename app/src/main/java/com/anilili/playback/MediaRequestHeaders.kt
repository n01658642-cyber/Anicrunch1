package com.anilili.playback

import java.net.URI
import com.anilili.util.cached

/** Builds the browser-shaped request profile shared by playback and offline downloads. */
internal fun mediaRequestHeaders(
    referer: String?,
    userAgent: String,
    extraHeaders: Map<String, String> = emptyMap(),
    defaultReferer: String? = null,
): Map<String, String> {
    val safeReferer = referer?.takeIf(String::isNotBlank)
        ?: defaultReferer?.takeIf(String::isNotBlank)
        ?: return extraHeaders
    val refererUri = runCatching { URI(safeReferer) }.getOrNull()
    val origin = if (refererUri?.scheme != null && refererUri.host != null) {
        "${refererUri.scheme}://${refererUri.host}"
    } else {
        safeReferer
    }
    return buildMap {
        put("Referer", safeReferer)
        put("Origin", origin)
        if (refererUri?.host.orEmpty().endsWith("flixcloud.cc", ignoreCase = true)) {
            val chromiumMajor = Regex.cached("(?:Chrome|Chromium)/(\\d+)")
                .find(userAgent)?.groupValues?.get(1) ?: "137"
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9")
            put(
                "Sec-CH-UA",
                "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$chromiumMajor\", " +
                    "\"Android WebView\";v=\"$chromiumMajor\"",
            )
            put("Sec-CH-UA-Mobile", "?1")
            put("Sec-CH-UA-Platform", "\"Android\"")
            put("Sec-Fetch-Dest", "empty")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Site", "same-site")
            put("X-Requested-With", "com.miruronative")
        }
        // Provider-supplied values win over defaults derived from the referer.
        putAll(extraHeaders)
    }
}
