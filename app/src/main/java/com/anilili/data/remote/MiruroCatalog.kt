package com.anilili.data.remote

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.model.hasUsableWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The shapes of Miruro's catalog API ([MiruroCatalogApi]) and how they become the app's catalogue
 * and sources. Pure functions over decoded JSON, so every rule here is pinned by a unit test.
 */
internal object MiruroCatalog {

    /** One entry of `config.streaming`: a provider key and what the site's picker does with it. */
    data class ProviderConfig(
        val key: String,
        /** The name the site shows, and the name this app has always used ("bee", "kiwi"…). */
        val label: String,
        val visible: Boolean,
        /** For an iframe embed, the provider key whose servers carry its embed pages. */
        val parent: String?,
        val isEmbed: Boolean,
        val downloadWorker: String?,
    )

    data class Episode(
        val number: Double,
        val title: String?,
        val thumbnail: String?,
        val airedOn: String?,
        val canonType: String?,
        val skip: SkipTimes?,
    )

    data class Title(val id: String, val subCount: Int?, val dubCount: Int?)

    fun parseConfig(json: Json, text: String): List<ProviderConfig> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val streaming = root.obj("streaming") ?: return emptyList()
        val order = root.arr("providerOrder")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val entries = streaming.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            ProviderConfig(
                key = key,
                label = o.str("label")?.takeIf(String::isNotBlank) ?: key,
                visible = o.bool("visible") ?: true,
                parent = o.str("parent"),
                isEmbed = o.str("relationship") == "embed",
                downloadWorker = o.str("downloadWorker")?.takeIf(String::isNotBlank),
            )
        }
        // The site's own `providerOrder` is its judgement of which backend to reach for first.
        return entries.sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** The first title a `v1/anime?anilist_id_in=…` lookup returned, or null when it knows none. */
    fun parseTitle(json: Json, text: String): Title? {
        val first = (json.parseToJsonElement(text) as? JsonObject)?.arr("data")?.firstOrNull() as? JsonObject
            ?: return null
        val id = first.str("id")?.takeIf(String::isNotBlank) ?: return null
        val counts = first.obj("episode_counts")
        return Title(id, counts?.int("sub"), counts?.int("dub"))
    }

    fun parseEpisodes(json: Json, text: String): Pair<List<Episode>, String?> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList<Episode>() to null
        val episodes = root.arr("data").orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            // The server stopped taking `kind=regular` (2026-10-12: 400 on every filter tried), so
            // the list arrives with specials mixed in, numbered from 20001 ("Magic Episode 1" on
            // Frieren). Kept, they would show up as twenty-five phantom episodes after the finale.
            // A row without a `kind` is read as regular, which is what every row used to be.
            if (o.str("kind")?.equals("regular", ignoreCase = true) == false) return@mapNotNull null
            val number = o.num("episode_number") ?: return@mapNotNull null
            Episode(
                number = number,
                title = o.str("title"),
                thumbnail = o.str("thumbnail_url"),
                airedOn = o.str("aired_on"),
                canonType = o.str("canon_type"),
                skip = skipTimes(o.arr("skip_times")),
            )
        }
        val next = root.str("next_cursor")?.takeIf { root.bool("has_more") == true }
        return episodes to next
    }

    /**
     * `skip_times` as the player wants it. Kinds seen: `op`, `ed`, `mixed_op`, `mixed_ed`; the plain
     * one wins when both are present, because a "mixed" window overlaps the episode's own content.
     */
    private fun skipTimes(windows: JsonArray?): SkipTimes? {
        val parsed = windows.orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            Triple(o.str("kind").orEmpty(), o.num("start_seconds"), o.num("end_seconds"))
        }
        fun pick(kind: String) = parsed.firstOrNull { it.first == kind }
            ?: parsed.firstOrNull { it.first == "mixed_$kind" }
        val intro = pick("op")
        val outro = pick("ed")
        return SkipTimes(intro?.second, intro?.third, outro?.second, outro?.third).takeIf(SkipTimes::hasUsableWindow)
    }

    /** The episode id the app round-trips through [com.anilili.data.model.EpisodeItem.pipeId]. */
    fun episodeId(titleId: String, number: Double): String = "$EPISODE_ID_PREFIX$titleId:${numberText(number)}"

    /** [episodeId] taken apart, or null for an id from the old pipe (a cached catalogue). */
    fun parseEpisodeId(id: String): Pair<String, String>? {
        if (!id.startsWith(EPISODE_ID_PREFIX)) return null
        val rest = id.removePrefix(EPISODE_ID_PREFIX)
        val split = rest.lastIndexOf(':').takeIf { it > 0 } ?: return null
        return rest.substring(0, split) to rest.substring(split + 1)
    }

    /** An episode number as the API's paths write it: `1`, not `1.0`. */
    fun numberText(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    /**
     * Which provider keys a `play` response carries, per audio.
     *
     * A sub here is either of the site's two subtitle tracks — `sub` (burned in) and `ssub`
     * (selectable) — exactly as the pipe's `sub`/`ssub` categories were.
     */
    fun providersIn(play: JsonObject): Pair<Set<String>, Set<String>> {
        val sub = mutableSetOf<String>()
        val dub = mutableSetOf<String>()
        play.arr("tracks").orEmpty().forEach { element ->
            val track = element as? JsonObject ?: return@forEach
            val target = when (track.str("track")) {
                "sub", "ssub" -> sub
                "dub" -> dub
                else -> return@forEach
            }
            track.arr("providers").orEmpty().forEach { p ->
                val provider = p as? JsonObject ?: return@forEach
                val usable = provider.arr("servers").orEmpty().any { s ->
                    val server = s as? JsonObject ?: return@any false
                    server.embedUrl() != null || server.arr("streams").orEmpty().isNotEmpty()
                }
                if (usable) provider.str("provider")?.let(target::add)
            }
        }
        return sub to dub
    }

    /**
     * The catalogue for one title: one [ProviderData] per server the site would offer.
     *
     * The API lists a title's episodes once, not per provider, so a provider's list is the title's
     * list bounded by the title's sub and dub counts. Which providers exist at all is read off one
     * `play` response ([sample]) — the first episode's — because nothing else names them. A provider
     * missing from that one episode but present on another is the cost; without a sample (the play
     * call failed) every visible provider is offered and the resolver's fallback absorbs the misses.
     *
     * An iframe embed is offered whenever its parent is, as the site's picker does, and only if the
     * site still shows it.
     */
    fun providers(
        config: List<ProviderConfig>,
        title: Title,
        episodes: List<Episode>,
        sample: JsonObject?,
    ): List<ProviderData> {
        val items = episodes.map { episode ->
            EpisodeItem(
                pipeId = episodeId(title.id, episode.number),
                number = episode.number,
                title = episode.title,
                image = episode.thumbnail,
                filler = isFillerEpisode(null, episode.canonType),
                airDate = episode.airedOn,
            )
        }
        val subItems = title.subCount?.let { count -> items.filter { it.number <= count } } ?: items
        val dubItems = title.dubCount?.let { count -> items.filter { it.number <= count } } ?: emptyList()
        val (sampleSub, sampleDub) = sample?.let(::providersIn) ?: (null to null)
        val byKey = config.associateBy(ProviderConfig::key)
        return config.filter { it.visible }.mapNotNull { entry ->
            val source = when {
                !entry.isEmbed -> entry
                else -> entry.parent?.let(byKey::get)?.takeIf { it.visible } ?: return@mapNotNull null
            }
            val sub = if (sampleSub == null || source.key in sampleSub) subItems else emptyList()
            val dub = if (sampleDub == null || source.key in sampleDub) dubItems else emptyList()
            if (sub.isEmpty() && dub.isEmpty()) null else ProviderData(entry.label, sub, dub)
        }
    }

    /**
     * The streams one server name ([label]) offers in one `play` response, for [variant].
     *
     * A native provider plays each server's stream URLs. An embed child plays its parent's embed
     * pages instead — a server's own `embed`, or a stream's. The server's `headers` are what its CDN
     * wants: the Referer goes where the app has always carried it, the rest (an `Origin`, say) ride
     * along as extra request headers.
     *
     * A generic sub asks for the burned-in track first and settles for the selectable one, which is
     * the site's own fallback order for "sub".
     */
    fun sources(
        config: List<ProviderConfig>,
        label: String,
        variant: SourceVariant,
        play: JsonObject,
        skip: SkipTimes?,
    ): SourcesResult {
        val entry = config.firstOrNull { it.label == label } ?: return SourcesResult(emptyList(), emptyList(), skip, null)
        val key = if (entry.isEmbed) entry.parent ?: entry.key else entry.key
        val tracks = when (variant) {
            SourceVariant.GENERIC_SUB -> listOf("sub", "ssub")
            else -> listOf(variant.api)
        }
        val provider = tracks.firstNotNullOfOrNull { wanted ->
            play.arr("tracks").orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("track") == wanted }
                ?.arr("providers").orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("provider") == key }
        } ?: return SourcesResult(emptyList(), emptyList(), skip, null)

        val servers = provider.arr("servers").orEmpty().mapNotNull { it as? JsonObject }
        fun streamsOf(asEmbeds: Boolean) = servers.flatMap { server ->
            val name = server.str("server").orEmpty()
            val headers = server.obj("headers").orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }.toMap()
            val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            val extra = headers.filterKeys { !it.equals("Referer", true) }
            val raw = server.arr("streams").orEmpty().mapNotNull { it as? JsonObject }
            if (asEmbeds) {
                (listOfNotNull(server.embedUrl()) + raw.mapNotNull { it.embedUrl() }).distinct().map { url ->
                    StreamItem(url, "embed", "${entry.label} $name".trim(), null, referer, false, null, null, headers = extra)
                }
            } else {
                raw.mapNotNull { stream ->
                    val url = stream.str("url")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val (width, height) = resolution(stream["resolution"])
                    StreamItem(
                        url = url,
                        type = stream.str("format") ?: if (".m3u8" in url) "hls" else "mp4",
                        quality = stream.str("quality")?.takeIf(String::isNotBlank) ?: name.ifBlank { null },
                        audio = null,
                        referer = referer,
                        isActive = false,
                        width = width,
                        height = height,
                        headers = extra,
                    )
                }
            }
        }
        // A native provider whose servers name no stream URL still has its embed pages. Miruro
        // fills in kiwi's Kwik playlist for only some tracks of an episode and leaves the rest as
        // bare `embed` pages: on 2026-10-22 The Fragrant Flower Blooms With Dignity (181444) ep 1
        // had a playlist for its dub and none for any of its three sub servers, and Frieren the
        // reverse. Reading streams alone made those tracks "no source" and the player fell over to
        // another server, which a viewer reads as "kiwi only works for dub". Only when nothing
        // direct is left, because pickProviderStream already prefers direct HLS over embeds.
        val offered = if (entry.isEmbed) {
            streamsOf(asEmbeds = true)
        } else {
            streamsOf(asEmbeds = false).ifEmpty { streamsOf(asEmbeds = true) }
        }
        val streams = offered.distinctBy(StreamItem::url).mapIndexed { index, stream -> stream.copy(isActive = index == 0) }

        val subtitleReferer = servers.firstNotNullOfOrNull { server ->
            server.obj("headers")?.entries?.firstOrNull { it.key.equals("Referer", true) }
                ?.let { (it.value as? JsonPrimitive)?.contentOrNull }
        }
        val subtitles = provider.arr("subtitles").orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val url = o.str("file") ?: o.str("url") ?: return@mapNotNull null
            SubtitleItem(
                url = url,
                label = o.str("label") ?: o.str("language") ?: "Subtitle",
                language = o.str("language") ?: "en",
                // The vault hosts serve WebVTT under image extensions, so the URL cannot say what
                // it is; the response's own `format` can.
                mimeType = if (o.str("format").equals("vtt", true)) "text/vtt" else null,
                referer = subtitleReferer,
            )
        }

        val download = provider.arr("downloads").orEmpty()
            .firstNotNullOfOrNull { (it as? JsonObject)?.str("url")?.takeIf(String::isNotBlank) }
            ?.let { downloadUrl(it, entry.downloadWorker ?: config.firstOrNull { c -> c.key == key }?.downloadWorker) }
        return SourcesResult(streams, subtitles, skip, download)
    }

    /**
     * A download page, rewritten the way the site rewrites AnimePahe's: a bare `/<id>` path is moved
     * onto the provider's `downloadWorker`, because the original host is retired.
     */
    internal fun downloadUrl(url: String, worker: String?): String {
        if (worker != null) {
            val id = Regex("""^https?://[^/]+/([A-Za-z0-9_-]+)/?$""").find(url)?.groupValues?.get(1)
            if (id != null) return worker.trimEnd('/') + "/" + id
        }
        return normalizePipeDownloadUrl(url)
    }

    private fun resolution(element: JsonElement?): Pair<Int?, Int?> = when (element) {
        is JsonObject -> element.int("width") to element.int("height")
        is JsonPrimitive -> element.contentOrNull?.split('x', 'X')?.takeIf { it.size == 2 }
            ?.let { it[0].trim().toIntOrNull() to it[1].trim().toIntOrNull() } ?: (null to null)
        else -> null to null
    }

    /** A server's or stream's iframe page: `"embed": {"url": …}`, or a bare string should it ever be one. */
    private fun JsonObject.embedUrl(): String? = when (val embed = this["embed"]) {
        is JsonObject -> embed.str("url")
        is JsonPrimitive -> embed.contentOrNull
        else -> null
    }?.takeIf(String::isNotBlank)

    private const val EPISODE_ID_PREFIX = "miruro:"

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = prim(key)?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.int(key: String): Int? = num(key)?.toInt()
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}
