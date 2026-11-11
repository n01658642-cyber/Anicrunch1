package com.anilili.data.remote

import com.anilili.data.model.StreamItem
import com.anilili.util.Base64Compat
import com.anilili.util.cached
import java.net.URI
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Resolves the comparatively stable MKissa external hosts without involving the visible player
 * WebView. The algorithms are adapted under Apache-2.0 from yuzono/anime-extensions; hosts with
 * large, fast-changing browser challenges (currently Filemoon and StreamWish) deliberately keep
 * the existing embed fallback instead of duplicating a second browser implementation here.
 */
internal class AllAnimeExternalExtractor(
    private val client: OkHttpClient,
    private val json: Json,
    private val streamlareApiOrigin: String = "https://slwatch.co",
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val randomToken: () -> String = ::secureToken,
) {
    fun supports(url: String): Boolean {
        val normalized = url.lowercase()
        return GOGO_HOST_MARKERS.any(normalized::contains) ||
            "dood" in normalized ||
            "ok.ru" in normalized ||
            "okru" in normalized ||
            "mp4upload.com" in normalized ||
            "streamlare.com" in normalized
    }

    fun resolve(url: String, sourceName: String, audio: String): List<StreamItem> = runCatching {
        val normalized = url.lowercase()
        when {
            GOGO_HOST_MARKERS.any(normalized::contains) -> resolveGogo(url, sourceName, audio)
            "dood" in normalized -> listOfNotNull(resolveDood(url, sourceName, audio))
            "ok.ru" in normalized || "okru" in normalized -> resolveOkru(url, sourceName, audio)
            "mp4upload.com" in normalized -> resolveMp4Upload(url, sourceName, audio)
            "streamlare.com" in normalized -> resolveStreamlare(url, sourceName, audio)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun resolveGogo(url: String, sourceName: String, audio: String): List<StreamItem> {
        val html = get(url)
        val iv = html.classValue("wrapper").digitsAfter("container-")
        val secretKey = html.classValue("body").digitsAfter("container-")
        val decryptionKey = html.classValue("videocontent").digitsAfter("videocontent-")
        if (iv.isEmpty() || secretKey.isEmpty() || decryptionKey.isEmpty()) return emptyList()

        val scriptTag = Regex.cached("""<script\b[^>]*\bdata-value\s*=\s*([\"']).*?\1[^>]*>""", HTML_OPTIONS)
            .find(html)?.value ?: return emptyList()
        val encryptedParams = NativeProviderParsers.attr(scriptTag, "data-value")
        val ajaxParams = aesCbc(encryptedParams, iv, secretKey, encrypt = false).substringAfter('&')
        val parsed = url.toHttpUrl()
        val id = parsed.queryParameter("id") ?: return emptyList()
        val encryptedId = aesCbc(id, iv, secretKey, encrypt = true)
        val host = "${parsed.scheme}://${parsed.host}"
        val response = get(
            "$host/encrypt-ajax.php?id=$encryptedId&$ajaxParams&alias=$id",
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val encryptedData = (json.parseToJsonElement(response) as? JsonObject)
            ?.primitive("data") ?: return emptyList()
        val decrypted = aesCbc(encryptedData, iv, decryptionKey, encrypt = false)
        val sources = ((json.parseToJsonElement(decrypted) as? JsonObject)?.get("source") as? JsonArray).orEmpty()

        return sources.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val streamUrl = item.primitive("file") ?: return@mapNotNull null
            val type = when {
                item.primitive("type").equals("hls", true) || streamUrl.contains(".m3u8", true) -> "hls"
                streamUrl.contains(".mpd", true) -> "dash"
                else -> "mp4"
            }
            stream(
                url = streamUrl,
                type = type,
                label = label(sourceName, item.primitive("label")),
                audio = audio,
                referer = url,
            )
        }
    }

    private fun resolveDood(url: String, sourceName: String, audio: String): StreamItem? {
        val page = execute(Request.Builder().url(url).get().build())
        val finalUrl = page.finalUrl
        val path = Regex.cached("""/pass_md5/[^'\"\s<]+""").find(page.body)?.value ?: return null
        val base = URI(finalUrl).let { "${it.scheme}://${it.rawAuthority}" }
        val passUrl = NativeProviderParsers.absoluteUrl(base, path)
        val token = passUrl.substringAfterLast('/')
        val prefix = get(passUrl, mapOf("Referer" to finalUrl)).trim()
        if (!prefix.startsWith("http://") && !prefix.startsWith("https://")) return null
        val quality = Regex.cached("""\b\d{3,4}p\b""").find(
            page.body.substringAfter("<title>", "").substringBefore("</title>", ""),
        )?.value
        val videoUrl = "$prefix${randomToken()}?token=$token&expiry=${nowMs()}"
        return stream(
            url = videoUrl,
            type = "mp4",
            label = label(sourceName, quality ?: "Doodstream"),
            audio = audio,
            referer = "$base/",
            headers = mapOf("User-Agent" to ALLANIME_USER_AGENT, "Referer" to "$base/"),
        )
    }

    private fun resolveOkru(url: String, sourceName: String, audio: String): List<StreamItem> {
        val html = get(url)
        val tag = Regex.cached("""<div\b[^>]*\bdata-options\s*=\s*([\"']).*?\1[^>]*>""", HTML_OPTIONS)
            .find(html)?.value ?: return emptyList()
        val options = NativeProviderParsers.attr(tag, "data-options")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        findEscapedJsonValue(options, "ondemandHls")?.let { playlist ->
            return listOf(stream(playlist, "hls", label(sourceName, "Ok.ru HLS"), audio, url))
        }
        findEscapedJsonValue(options, "ondemandDash")?.let { playlist ->
            return listOf(stream(playlist, "dash", label(sourceName, "Ok.ru DASH"), audio, url))
        }

        val videos = Regex.cached(
            """(?:\\?\"name\\?\"\s*:\s*\\?\"([^\"]+)\\?\")[\s\S]*?(?:\\?\"url\\?\"\s*:\s*\\?\"([^\"]+)\\?\")""",
            RegexOption.IGNORE_CASE,
        ).findAll(options).mapNotNull { match ->
            val streamUrl = match.groupValues[2].replace("\\u0026", "&").replace("\\/", "/")
            if (!streamUrl.startsWith("https://") && !streamUrl.startsWith("http://")) return@mapNotNull null
            stream(
                streamUrl,
                if (streamUrl.contains(".m3u8", true)) "hls" else "mp4",
                label(sourceName, "Ok.ru ${okruQuality(match.groupValues[1])}"),
                audio,
                url,
            )
        }.toList()
        return videos.distinctBy(StreamItem::url)
    }

    private fun resolveMp4Upload(url: String, sourceName: String, audio: String): List<StreamItem> {
        val referer = "https://mp4upload.com/"
        val html = get(url, mapOf("Referer" to referer))
        val scripts = Regex.cached("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        val script = scripts.firstNotNullOfOrNull { candidate ->
            when {
                AllAnimeJsUnpacker.detect(candidate) -> AllAnimeJsUnpacker.unpackAndCombine(candidate)
                "player.src" in candidate || ".src(" in candidate -> candidate
                else -> null
            }
        } ?: return emptyList()
        val videoUrl = listOf(
            Regex.cached("""\.src\(\s*\{?[\s\S]*?src\s*:\s*[\"']([^\"']+)"""),
            Regex.cached("""\.src\(\s*[\"']([^\"']+)"""),
            Regex.cached("""\bfile\s*:\s*[\"']([^\"']+)"""),
        ).firstNotNullOfOrNull { it.find(script)?.groupValues?.get(1) }?.replace("\\/", "/")
            ?: return emptyList()
        val resolution = Regex.cached("""\bHEIGHT\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(script)?.groupValues?.get(1)?.let { "${it}p" }
        return listOf(
            stream(
                videoUrl,
                if (videoUrl.contains(".m3u8", true)) "hls" else "mp4",
                label(sourceName, "MP4Upload ${resolution ?: "auto"}"),
                audio,
                referer,
                headers = mapOf("Referer" to referer),
            ),
        )
    }

    private fun resolveStreamlare(url: String, sourceName: String, audio: String): List<StreamItem> {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return emptyList()
        val body = "{\"id\":\"$id\"}".toRequestBody(JSON_MEDIA_TYPE)
        val response = execute(
            Request.Builder()
                .url("$streamlareApiOrigin/api/video/stream/get")
                .post(body)
                .header("Content-Type", "application/json")
                .build(),
        ).body
        val root = json.parseToJsonElement(response) as? JsonObject ?: return emptyList()
        val type = root.findPrimitive("type")
        val file = root.findPrimitive("file")?.replace("\\/", "/")
        if (!file.isNullOrBlank() && (type.equals("hls", true) || file.contains(".m3u8", true))) {
            return listOf(stream(file, "hls", label(sourceName, "Streamlare HLS"), audio, url))
        }
        if (!file.isNullOrBlank() && (file.startsWith("https://") || file.startsWith("http://"))) {
            return listOf(stream(file, "mp4", label(sourceName, "Streamlare"), audio, url))
        }
        return emptyList()
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get().header("User-Agent", ALLANIME_USER_AGENT)
        headers.forEach(builder::header)
        return execute(builder.build()).body
    }

    private fun execute(request: Request): PageResponse = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("External host returned HTTP ${response.code}")
        PageResponse(response.request.url.toString(), response.body?.string().orEmpty())
    }

    private fun stream(
        url: String,
        type: String,
        label: String,
        audio: String,
        referer: String,
        headers: Map<String, String> = emptyMap(),
    ) = StreamItem(url, type, label, audio, referer, false, null, null, headers = headers)

    private fun label(sourceName: String, detail: String?): String = listOfNotNull(
        "MKissa",
        detail?.takeIf(String::isNotBlank),
        sourceName.takeIf(String::isNotBlank),
    ).distinct().joinToString(" ")

    private fun String.classValue(requiredClass: String): String = Regex.cached(
        """<(?:body|div)\b[^>]*\bclass\s*=\s*([\"'])(.*?)\1[^>]*>""",
        HTML_OPTIONS,
    ).findAll(this).firstOrNull { match ->
        requiredClass == "body" && match.value.startsWith("<body", true) ||
            match.groupValues[2].split(Regex.cached("\\s+")).any { it.equals(requiredClass, true) }
    }?.groupValues?.get(2).orEmpty()

    private fun String.digitsAfter(marker: String): ByteArray = substringAfter(marker, "")
        .takeWhile(Char::isDigit)
        .toByteArray()

    private fun aesCbc(value: String, iv: ByteArray, key: ByteArray, encrypt: Boolean): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        return if (encrypt) {
            Base64Compat.encode(cipher.doFinal(value.toByteArray()))
        } else {
            String(cipher.doFinal(Base64Compat.decode(value)))
        }
    }

    private fun findEscapedJsonValue(value: String, name: String): String? {
        val patterns = listOf(
            Regex("""\"${Regex.escape(name)}\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE),
            Regex("""\\\"${Regex.escape(name)}\\\"\s*:\s*\\\"([^\"]+)\\\"""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(value)?.groupValues?.get(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
        }
    }

    private fun okruQuality(value: String): String = when (value.lowercase()) {
        "ultra" -> "2160p"
        "quad" -> "1440p"
        "full" -> "1080p"
        "hd" -> "720p"
        "sd" -> "480p"
        "low" -> "360p"
        "lowest" -> "240p"
        "mobile" -> "144p"
        else -> value
    }

    private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.findPrimitive(name: String): String? {
        primitive(name)?.let { return it }
        values.forEach { value ->
            when (value) {
                is JsonObject -> value.findPrimitive(name)?.let { return it }
                is JsonArray -> value.forEach { child ->
                    (child as? JsonObject)?.findPrimitive(name)?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    private data class PageResponse(val finalUrl: String, val body: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val HTML_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val GOGO_HOST_MARKERS = listOf("vidstreaming", "gogo", "playgo1.cc", "playtaku", "vidcloud")

        private fun secureToken(): String {
            val random = SecureRandom()
            return buildString(10) {
                repeat(10) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
            }
        }

        private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
