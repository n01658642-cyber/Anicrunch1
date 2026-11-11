package com.anilili.util

/**
 * Extra headers for [android.webkit.WebView.loadUrl], in a map it is allowed to modify.
 *
 * Chromium pulls the referer out of the map it is handed rather than copying it first, so the
 * caller's map has `remove` called on it. Newer WebViews copy defensively; the ones shipped on old
 * TV boxes do not, and a Kotlin `mapOf("Referer" to x)` is a `Collections.singletonMap` whose
 * `remove` throws. That crashed the app outright on Android 7.1.1 Realtek boxes, from two separate
 * diagnostics bundles on 0.1.69:
 *
 * ```
 * java.lang.UnsupportedOperationException
 *   at java.util.Collections$1.remove(Collections.java:3880)
 *   at java.util.AbstractMap.remove(AbstractMap.java:247)
 *   at org.chromium.android_webview.AwContents.loadUrl(AwContents.java:1574)
 * ```
 *
 * So every map handed to `loadUrl` has to be one we do not mind losing entries from.
 */
internal fun webViewRefererHeaders(referer: String?): MutableMap<String, String> {
    val headers = HashMap<String, String>(2)
    referer?.takeIf(String::isNotBlank)?.let { headers["Referer"] = it }
    return headers
}

/** As above, for headers assembled elsewhere: hands [android.webkit.WebView] its own copy. */
internal fun webViewHeaders(headers: Map<String, String>): MutableMap<String, String> =
    HashMap(headers)
