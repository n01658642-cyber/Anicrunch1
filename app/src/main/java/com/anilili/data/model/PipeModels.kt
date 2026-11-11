package com.anilili.data.model

import com.anilili.data.ProviderCatalog
import kotlinx.serialization.Serializable

enum class Category(val api: String) {
    SUB("sub"),
    DUB("dub");

    companion object {
        fun from(api: String): Category = if (api.equals("dub", true)) DUB else SUB
    }
}

/**
 * The source endpoint's playback variant, kept separate from [Category]'s audio-language axis.
 *
 * Miruro uses `sub` for video with subtitles burned into the picture and `ssub` for a clean
 * video plus selectable subtitle tracks. Older app routes only stored `sub`; [GENERIC_SUB]
 * preserves those routes and lets each provider choose its preferred subtitle variant.
 */
enum class SourceVariant(
    val api: String,
    val route: String,
    val category: Category,
    val label: String,
) {
    GENERIC_SUB("sub", "sub", Category.SUB, "SUB"),
    HARD_SUB("sub", "hsub", Category.SUB, "H-SUB"),
    SOFT_SUB("ssub", "ssub", Category.SUB, "S-SUB"),
    DUB("dub", "dub", Category.DUB, "DUB");

    val isExactSubtitleVariant: Boolean
        get() = this == HARD_SUB || this == SOFT_SUB

    companion object {
        fun fromRoute(value: String): SourceVariant = when (value.lowercase()) {
            "hsub" -> HARD_SUB
            "ssub" -> SOFT_SUB
            "dub" -> DUB
            else -> GENERIC_SUB
        }

        fun defaultFor(category: Category): SourceVariant =
            if (category == Category.DUB) DUB else GENERIC_SUB
    }
}

@Serializable
data class EpisodeItem(
    /** Raw pipe id — passed straight back to the `sources` endpoint. */
    val pipeId: String,
    val number: Double,
    val title: String?,
    val image: String?,
    val filler: Boolean,
    /**
     * When the episode catalogues say this one airs, in whatever shape they wrote it — a bare day
     * or a full timestamp. Null when no catalogue placed the episode, which is most of them.
     *
     * Carried on the row rather than looked up at draw time because it is the only thing that can
     * tell an episode nobody can watch yet from one they can. AniList's `nextAiringEpisode` bounds
     * the synthetic catalogue, but it is absent often enough — a RELEASING title with a known total
     * and no schedule — that a season then lists its whole run as though every episode were out.
     */
    val airDate: String? = null,
    /**
     * What the metadata overlay knows about the episode beyond its name and still: the synopsis,
     * the runtime in minutes, and the viewer score.
     *
     * Carried on the row rather than looked up beside it because the row is what the UI holds —
     * the ten-foot browser describes whichever episode the cursor is on, and a second structure
     * keyed by number would have to be kept in step with a list that is replaced wholesale every
     * time enrichment lands. Absent on every episode until that overlay runs, and absent
     * afterwards for anything no catalogue covered, so all three default.
     */
    val overview: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    /**
     * Whether a dub of this episode is known to exist, from the published latest-dubbed-episode
     * index (see `DubIndex.dubbedThrough`). False means unknown, not "sub only".
     */
    val dubbed: Boolean = false,
    /**
     * When the episode went out, in epoch seconds, from AniList's airing schedule — set only on an
     * episode that aired within the last [EPISODE_WATCHABLE_AFTER_SEC], because that is the one
     * question a bare catalogue date cannot answer. See [episodeIsUnaired].
     */
    val airingAt: Long? = null,
) {
    val displayNumber: String get() = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

    /**
     * The episode's own title, or null when the source only echoed the number back ("Episode 6",
     * "EP 6", "6"). Several providers fill the field that way rather than leaving it empty, and
     * such a title must not shadow a real one from the metadata overlay or get printed twice in a
     * row that already shows the number.
     */
    val distinctTitle: String? get() {
        val trimmed = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val echoesNumber = trimmed.equals("Episode $displayNumber", ignoreCase = true) ||
            trimmed.equals("EP $displayNumber", ignoreCase = true) ||
            trimmed == displayNumber
        return trimmed.takeUnless { echoesNumber }
    }
}

@Serializable
data class ProviderData(
    val name: String,
    val sub: List<EpisodeItem>,
    val dub: List<EpisodeItem>,
) {
    val isEmbed: Boolean get() = ProviderCatalog.isEmbed(name)
    fun episodes(category: Category): List<EpisodeItem> = if (category == Category.SUB) sub else dub
    fun hasCategory(category: Category): Boolean = episodes(category).isNotEmpty()
    val categories: List<Category> get() = buildList {
        if (sub.isNotEmpty()) add(Category.SUB)
        if (dub.isNotEmpty()) add(Category.DUB)
    }
}

@Serializable
data class EpisodesResult(
    val providers: List<ProviderData>,
    /**
     * Providers the fan-out budget cut off, so a caller can tell a complete catalogue from one
     * that merely looks complete. Only a count of providers cannot: a provider legitimately has
     * nothing for a title all the time, and that is indistinguishable from one that never answered.
     *
     * Deliberately `@Transient`. This describes *this fetch*, not the answer, and a row read back
     * from the cache was already judged when it was written — decoding it as empty is the correct
     * reading, not a lost field.
     */
    @kotlinx.serialization.Transient val cutOff: List<String> = emptyList(),
) {
    val providerNames: List<String> get() = providers.map { it.name }
    fun provider(name: String): ProviderData? = providers.firstOrNull { it.name == name }
    val isEmpty: Boolean get() = providers.all { it.sub.isEmpty() && it.dub.isEmpty() }

    /**
     * Whether any provider, in either audio, lists [number].
     *
     * Asked of the whole catalogue rather than of one category on purpose. A dub trailing its sub
     * is ordinary and has its own fallback; a number that *nothing* here carries means something
     * else — this catalogue was assembled before that episode existed.
     */
    fun carriesEpisode(number: Double): Boolean = providers.any { provider ->
        provider.sub.any { it.number == number } || provider.dub.any { it.number == number }
    }

    /** The furthest episode anywhere in the catalogue, or null when it lists none. */
    val lastEpisodeNumber: Double?
        get() = providers.maxOfOrNull { provider ->
            maxOf(
                provider.sub.maxOfOrNull { it.number } ?: 0.0,
                provider.dub.maxOfOrNull { it.number } ?: 0.0,
            )
        }?.takeIf { it > 0.0 }
}

data class StreamItem(
    val url: String,
    val type: String,
    val quality: String?,
    val audio: String?,
    val referer: String?,
    val isActive: Boolean,
    val width: Int?,
    val height: Int?,
    val playlistKey: String? = null,
    /**
     * Extra request headers this stream's CDN needs on every manifest and segment fetch, beyond
     * the Referer/Origin pair derived from [referer]. May carry short-lived per-playback tokens,
     * so it is resolved fresh at playback rather than cached.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Play this source over the plain HTTP transport instead of Cronet. Some CDNs trip an
     * assertion inside `CronetDataSource.read` a second into playback ("Source error"), while the
     * identical manifest plays clean on `DefaultHttpDataSource` — measured on device against Hentai
     * Haven's old CDN, and the segments themselves verify as valid fMP4 either way.
     */
    val avoidCronet: Boolean = false,
) {
    val isHls: Boolean get() = type.equals("hls", true) || url.contains(".m3u8")
    val isDash: Boolean get() = type.equals("dash", true) || type.equals("mpd", true) || url.contains(".mpd")
    val isEmbed: Boolean get() = type.equals("embed", true)

    /**
     * One file read over HTTP Range, rather than a manifest listing segments.
     *
     * Worth knowing apart because the two have opposite failure modes. A segmented stream refills
     * from a fresh short-lived request either way, so the buffer only decides how far ahead it
     * works. A progressive file is one long read that the load control *cancels* when the buffer is
     * full, and every refill after that is a new range request into the middle of the file — on
     * Lili's half-gigabyte BDRips, measured at most of a second each before a byte arrives.
     */
    val isProgressive: Boolean get() = !isHls && !isDash && !isEmbed

    /**
     * Whether this is positively a single video file rather than a page that plays one.
     *
     * Stricter than [isProgressive], which is only "not a manifest and not typed as an embed". The
     * pipe does not always type an iframe provider's page URL, so an untyped link from one of those
     * could be either. This answers yes only when something says so — a file type, or a path ending
     * in a video extension, as Cog's AnimeGG links (`/play/<id>/video.mp4?for=<token>`) do — and
     * leaves everything else to be treated as the page it may be.
     */
    val isMediaFile: Boolean get() = !isHls && !isDash && !isEmbed &&
        (type.lowercase() in MEDIA_FILE_TYPES || urlPathExtension(url) in MEDIA_FILE_TYPES)

    val label: String get() = quality ?: height?.let { "${it}p" } ?: "auto"
}

private val MEDIA_FILE_TYPES = setOf("mp4", "m4v", "webm", "mkv", "mov")

/** The lower-cased extension of [url]'s path, ignoring its query and fragment. */
private fun urlPathExtension(url: String): String =
    url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase()

/**
 * Whether this stream has to be handed to a WebView rather than the app's own player.
 *
 * Being served by a provider Miruro classes as `player: "iframe"` is not the same question, which
 * is what asking [ProviderCatalog.isEmbed] alone got wrong. That is a statement about how the
 * provider is *offered*; `MiruroEmbedResolver` can read an actual manifest out of some of those
 * pages, and a manifest belongs in the native player whatever the provider is called. Deciding on
 * the media is also the only reading that keeps working when the resolver fails, since the embed
 * page it falls back to is still the non-media URL this returns true for.
 *
 * A video file belongs in the native player for the same reason, and handing one to the WebView
 * does not merely cost the app's controls. Cog serves AnimeGG's MP4s, which answer with a redirect
 * to vidcache.net; the embed player refuses any top-frame navigation off the page's own host, so it
 * cancelled that redirect, sat on a blank page for twenty seconds and reported `PLAY-06` — on every
 * one of Cog's four qualities in turn.
 */
fun StreamItem.playsInWebView(provider: String): Boolean =
    !isHls && !isDash && !isMediaFile && (isEmbed || ProviderCatalog.isEmbed(provider))

/**
 * Serializable because a borrowed track list is cached: the provider that lends subtitles to XFDM
 * charges a session POST and a servers GET for them, and that is the last thing left on the path
 * between pressing play and a picture.
 */
@Serializable
data class SubtitleItem(
    val url: String,
    val label: String,
    val language: String,
    /** Explicit for content:// imports, whose URI often has no usable file extension. */
    val mimeType: String? = null,
    /** Some subtitle CDNs require a profile different from the video manifest's host. */
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class SkipTimes(
    val introStart: Double?,
    val introEnd: Double?,
    val outroStart: Double?,
    val outroEnd: Double?,
    /**
     * The "previously on" block. AniSkip carries these; no streaming provider we read does, which is
     * why it defaults to absent rather than being part of every provider's construction.
     */
    val recapStart: Double? = null,
    val recapEnd: Double? = null,
    /**
     * AniSkip's per-segment ids, present only for windows that came from AniSkip. They are what its
     * vote endpoint is keyed by, so without them a viewer can see a wrong timing but not report it.
     */
    val introSkipId: String? = null,
    val outroSkipId: String? = null,
    val recapSkipId: String? = null,
)

/**
 * Whether these timestamps can produce at least one action in the player.
 *
 * Some provider payloads contain an empty `skipTimes: {}` object. Treating that object as real
 * metadata prevents the AniSkip fallback from running even though neither player can render a
 * button from it.
 */
fun SkipTimes.hasUsableWindow(): Boolean {
    val introUsable = introEnd?.let { end -> end > (introStart ?: 0.0) } == true
    val outroUsable = outroStart?.let { start -> outroEnd?.let { end -> end > start } } == true
    val recapUsable = recapStart?.let { start -> recapEnd?.let { end -> end > start } } == true
    return introUsable || outroUsable || recapUsable
}

/** Every AniSkip segment behind these timings, as (id, label) pairs for reporting them. */
fun SkipTimes.aniSkipSegmentIds(): List<Pair<String, String>> = listOfNotNull(
    introSkipId?.let { it to "Intro" },
    outroSkipId?.let { it to "Outro" },
    recapSkipId?.let { it to "Recap" },
)

data class SourcesResult(
    val streams: List<StreamItem>,
    val subtitles: List<SubtitleItem>,
    val skip: SkipTimes?,
    val download: String?,
    /**
     * How far these subtitles need shifting to line up with the stream, in milliseconds. Non-zero
     * only where a provider was caught handing out a file cut for a different encode of the same
     * episode; the player seeds its subtitle delay from it.
     */
    val subtitleOffsetMs: Long = 0L,
) {
    val hlsStreams: List<StreamItem> get() = streams.filter { it.isHls }
    val embedStreams: List<StreamItem> get() = streams.filter { it.isEmbed }
}
