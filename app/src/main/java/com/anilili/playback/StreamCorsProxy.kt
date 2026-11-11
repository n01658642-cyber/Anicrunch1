package com.anilili.playback

import java.util.Base64

/**
 * Rebuilds the catalogue site's own public stream proxy URLs.
 *
 * [LocalStreamProxy] solves the same problem for players on this device by lending them our
 * headers over loopback. A Chromecast receiver is not on this device: it fetches whatever URL it
 * is handed, from its own network stack, and there is no way to attach a `Referer` to that fetch.
 * Every referer-gated source therefore 403s on a receiver while playing here, and every subtitle
 * host that omits CORS is dropped by the receiver before it renders a cue — the two failures that
 * make casting look broken on sources that are perfectly healthy.
 *
 * The site solves it upstream: an edge proxy that takes the target and its referer *inside the
 * path*, so a bare GET with no headers reaches a gated origin. Both hops are XOR'd with a fixed
 * obfuscation key and base64url-encoded, then joined with `~`; `/pl.m3u8` fetches a playlist and
 * `/sub.vtt` a subtitle track. Playlists come back rewritten, so segments, keys and child variant
 * playlists route through the proxy too — lending the manifest a referer alone would only move the
 * 403 one request along, exactly as it would locally.
 *
 * Verified against the live proxy on 2026-09-07: a playlist returns 200 with
 * `Access-Control-Allow-Origin: *` and its variant URLs rewritten, a subtitle returns 200 as
 * `text/vtt`, and the referer is propagated into the rewritten children.
 *
 * This is third-party infrastructure and it is not ours to rely on: nothing here is on the path to
 * first frame locally, and a caller that cannot reach it must still be able to fall back to the
 * direct URL.
 */
internal object StreamCorsProxy {

    /**
     * The site publishes two and picks between them by hashing a seed, spreading load and keeping
     * one stream on one host for cache hits. Both answer for any target, so the choice is only
     * about parity with the site's own traffic.
     */
    private val ORIGINS = listOf(
        "https://s1.watami.win/",
        "https://s1.piltover.li/",
    )

    /** `VITE_PROXY_OBF_KEY` from the site bundle: obfuscation, not a secret, and not a signature. */
    private val KEY = byteArrayOf(
        0xa5.toByte(), 0x4d, 0x38, 0x9c.toByte(), 0x18, 0x52, 0x7d, 0x9f.toByte(),
        0xd3.toByte(), 0xe7.toByte(), 0xf0.toByte(), 0x64, 0x3e, 0x27, 0xed.toByte(), 0xbe.toByte(),
    )

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** True for a URL this object built, so an already-proxied stream is never wrapped twice. */
    fun isProxied(url: String): Boolean = ORIGINS.any { url.startsWith(it) }

    /**
     * A proxied playlist or progressive stream URL, or null when [url] cannot be routed.
     *
     * `data:` and `blob:` URLs carry their own bytes and have no origin to gate on, and a URL that
     * is already proxied is returned unchanged rather than nested inside itself.
     */
    fun playlist(url: String, referer: String?, seed: String): String? =
        build("pl.m3u8", url, referer, seed)

    /** A proxied subtitle URL. The proxy answers `text/vtt`, which is what a receiver accepts. */
    fun subtitle(url: String, referer: String?, seed: String): String? =
        build("sub.vtt", url, referer, seed)

    private fun build(suffix: String, url: String, referer: String?, seed: String): String? {
        if (url.startsWith("data:") || url.startsWith("blob:")) return null
        if (isProxied(url)) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val payload = buildString {
            append(encode(url))
            if (!referer.isNullOrBlank()) {
                append('~')
                append(encode(referer))
            }
        }
        return originFor(seed) + payload + "/" + suffix
    }

    /** Which of the two origins the site would pick for this seed; FNV-1a, as the bundle does it. */
    fun originFor(seed: String): String = ORIGINS[Math.floorMod(fnv1a(seed), ORIGINS.size)]

    private fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val masked = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }
        return encoder.encodeToString(masked)
    }

    private fun fnv1a(value: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (char in value) {
            hash = (hash xor char.code) * 16777619
        }
        return hash
    }
}
