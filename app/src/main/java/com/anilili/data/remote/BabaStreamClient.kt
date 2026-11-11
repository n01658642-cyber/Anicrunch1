package com.anilili.data.remote

import android.util.Base64
import com.anilili.util.cached
import java.io.IOException
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What BabaStream hands back for one episode. */
data class BabaStreamSource(val url: String, val type: String, val origin: String)

/**
 * The per-page-load session the embed's own scripts talk to its API with.
 *
 * [pk] stays base64 text rather than decoded bytes so that reading it off a page is pure parsing:
 * `android.util.Base64` is a stub on the unit-test classpath, and decoding here would put the whole
 * of [babaStreamConfig] out of reach of a test.
 */
internal data class BabaStreamConfig(val sid: String, val pk: String, val capBase: String?)

/** The API paths the embed's own scripts call, read back out of the page. */
internal data class BabaStreamRoutes(val resolve: String?, val verify: String?)

/** The AES-GCM shape the page's `crypto.subtle` calls use. */
internal data class BabaStreamCrypto(val ivLength: Int, val tagBits: Int)

/**
 * BabaStream's session config, taken from the `var CFG = {…}` its embed page declares.
 *
 * The page declares several `var x = {…}` objects and only one of them is this: the others are
 * JavaScript object literals with unquoted keys that will not parse as JSON, plus request bodies
 * built from `CFG` itself. Carrying `sid` and `pk` is what identifies the right one, rather than
 * the variable being called `CFG`, so a minifier renaming it costs nothing.
 */
internal fun babaStreamConfig(html: String, json: Json): BabaStreamConfig? =
    Regex.cached("""\b(?:var|let|const)\s+\w+\s*=\s*(\{[^;]+\})\s*;""").findAll(html).firstNotNullOfOrNull { match ->
        val root = runCatching { json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
        val sid = (root?.get("sid") as? JsonPrimitive)?.contentOrNull
        val pk = (root?.get("pk") as? JsonPrimitive)?.contentOrNull
        if (sid.isNullOrBlank() || pk.isNullOrBlank()) return@firstNotNullOfOrNull null
        BabaStreamConfig(sid, pk, (root["cap"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank))
    }

/**
 * The resolve and verification endpoints, matched on what they do rather than on their spelling.
 *
 * The page also calls `/api/download-token`, so taking the first `fetch` on the page would be
 * wrong; picking by keyword survives the paths being renamed around their purpose.
 */
internal fun babaStreamRoutes(html: String): BabaStreamRoutes {
    val routes = Regex("""fetch\(\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
    return BabaStreamRoutes(
        resolve = routes.firstOrNull { it.contains("resolve", ignoreCase = true) },
        verify = routes.firstOrNull { it.contains("verify", ignoreCase = true) },
    )
}

/**
 * The AES-GCM parameters the page uses, read from its own `crypto.subtle` calls.
 *
 * `tagLength` is left out of a Web Crypto call when it is the 128-bit default, which is the case
 * here, so its absence is the answer rather than a parse failure. Anything other than AES-GCM is
 * refused outright: the wire format below — IV first, tag last — is GCM's, and quietly treating a
 * CBC page as GCM would fail after the round trip instead of before it.
 */
internal fun babaStreamCrypto(html: String): BabaStreamCrypto? {
    val algorithm = Regex("""name\s*:\s*["'](AES-[A-Z]+)["']""").find(html)?.groupValues?.get(1)
    if (!algorithm.equals("AES-GCM", ignoreCase = true)) return null
    val ivLength = Regex("""getRandomValues\(new Uint8Array\((\d+)\)\)""").find(html)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..16 } ?: return null
    val tagBits = Regex("""tagLength\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 128
    return BabaStreamCrypto(ivLength, tagBits)
}

/**
 * How to play what BabaStream returned.
 *
 * The URL decides, not the `t` field: `direct` covers both the signed progressive MP4 the service
 * usually answers with and an HLS playlist, and mislabelling a playlist as a file would hand the
 * player a manifest to decode as video.
 */
internal fun babaStreamMediaType(url: String, responseType: String?): String = when {
    Regex.cached("""\.m3u8(?:$|[?&])""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "hls"
    responseType.equals("embed", ignoreCase = true) -> "embed"
    else -> "mp4"
}

/**
 * Resolves a BabaStream embed to the file behind it.
 *
 * Worth the machinery because of what comes out: a signed progressive MP4, valid around 30 hours,
 * that serves with no `Referer` at all. That is the one stream shape this app is otherwise short of
 * — Chromecast fetches URLs itself and external players send no headers, so both fail on the
 * referer-gated HLS most providers hand over, and a download needs no transmux to become a file.
 *
 * The price is [CapProofOfWork]. Every resolve is refused with `{"t":"error","m":"verify"}` until a
 * Cap challenge is solved, the `sid` is fresh per page load, and a redeemed token is single-use —
 * measured 2026-09-09: a second use of the same token answers `{"t":"error"}`. So the work lands on
 * every episode, and [BabaStreamTokenPool] exists to pay it before anybody is waiting.
 */
class BabaStreamClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val tokens: BabaStreamTokenPool,
) {
    suspend fun resolve(embedUrl: String, referer: String): BabaStreamSource? {
        val page = runCatching { get(embedUrl, referer) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return null
        }
        val config = babaStreamConfig(page, json) ?: return null
        // An AES key of any other length is not this scheme, and `Cipher` would only say so once a
        // round trip had already been spent on it.
        val key = runCatching { Base64.decode(config.pk, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.size == 16 || it.size == 24 || it.size == 32 }
            ?: return null
        val crypto = babaStreamCrypto(page) ?: return null
        val routes = babaStreamRoutes(page)
        val resolveRoute = routes.resolve ?: return null
        val origin = origin(embedUrl)

        suspend fun call(route: String, body: JsonObject): JsonObject? {
            val payload = buildJsonObject {
                put("s", config.sid)
                put("d", encrypt(json.encodeToString(JsonObject.serializer(), body), key, crypto))
            }
            val answer = runCatching {
                postJson("$origin$route", json.encodeToString(JsonObject.serializer(), payload), embedUrl)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                return null
            }
            val sealed = (answer["d"] as? JsonPrimitive)?.contentOrNull ?: return null
            val plain = runCatching { decrypt(sealed, key, crypto) }.getOrNull() ?: return null
            return runCatching { json.parseToJsonElement(plain) as? JsonObject }.getOrNull()
        }

        fun stamp() = buildJsonObject { put("ts", System.currentTimeMillis()) }

        var resolved = call(resolveRoute, stamp())
        if (resolved.needsVerification() && routes.verify != null && config.capBase != null) {
            val token = tokens.take(config.capBase) ?: return null
            val verified = call(
                routes.verify,
                buildJsonObject {
                    put("ts", System.currentTimeMillis())
                    put("token", token)
                    put("mode", "invisible")
                },
            )
            if ((verified?.get("t") as? JsonPrimitive)?.contentOrNull != "ok") return null
            resolved = call(resolveRoute, stamp())
        }

        val url = (resolved?.get("u") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val type = (resolved["t"] as? JsonPrimitive)?.contentOrNull
        return BabaStreamSource(url, babaStreamMediaType(url, type), origin)
    }

    private fun JsonObject?.needsVerification(): Boolean {
        if (this == null) return true
        if ((this["t"] as? JsonPrimitive)?.contentOrNull != "error") return false
        return (this["m"] as? JsonPrimitive)?.contentOrNull?.contains("verify", ignoreCase = true) == true
    }

    private fun encrypt(plain: String, key: ByteArray, crypto: BabaStreamCrypto): String {
        val iv = ByteArray(crypto.ivLength).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(crypto.tagBits, iv))
        // Web Crypto appends the tag to the ciphertext, which is what Cipher.doFinal returns too.
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String, key: ByteArray, crypto: BabaStreamCrypto): String {
        val raw = Base64.decode(sealed, Base64.DEFAULT)
        require(raw.size > crypto.ivLength + crypto.tagBits / 8) { "BabaStream payload is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(crypto.tagBits, raw, 0, crypto.ivLength),
        )
        return String(
            cipher.doFinal(raw, crypto.ivLength, raw.size - crypto.ivLength),
            Charsets.UTF_8,
        )
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault("")

    private suspend fun get(url: String, referer: String): String = execute(
        Request.Builder().url(url)
            .header("User-Agent", BABASTREAM_USER_AGENT)
            .header("Accept", "text/html,*/*")
            .header("Referer", referer)
            .get()
            .build(),
    )

    private suspend fun postJson(url: String, body: String, referer: String): JsonObject {
        val text = execute(
            Request.Builder().url(url)
                .header("User-Agent", BABASTREAM_USER_AGENT)
                .header("Accept", "application/json,*/*")
                .header("Referer", referer)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        return json.parseToJsonElement(text) as? JsonObject ?: error("BabaStream returned no object")
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code} fetching ${request.url}")
                        it.body?.string().orEmpty()
                    }
                    continuation.resumeWith(Result.success(body))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        const val BABASTREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
