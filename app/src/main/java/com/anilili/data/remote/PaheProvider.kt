package com.anilili.data.remote

import com.anilili.data.AppGraph
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsing for AnimePahe, kept free of Android and OkHttp so every shape below is a unit test.
 *
 * The site is keyed by opaque `session` strings rather than ids — one for the series, one for each
 * episode — and they rotate, so nothing here may be cached beyond a run.
 */
internal object PaheParser {
    /** `let url = "https://kwik.cx/e/XXXX"` on the play page; the host moves, the shape does not. */
    private val KWIK_URL = Regex("""let\s+url\s*=\s*["']([^"']+)["']""")

    /**
     * Any playlist in the unpacked player.
     *
     * Deliberately not the reference client's `source='...'`: that stopped matching, because the
     * current player assigns the playlist somewhere else. Matching the URL itself survives the
     * assignment being rewritten, which it evidently is.
     */
    private val M3U8 = Regex("""https?://[^\s'"\\]+\.m3u8[^\s'"\\]*""")

    private val ANILIST_ID = Regex("""anilist\.co/anime/(\d+)""")
    private val MAL_ID = Regex("""myanimelist\.net/anime/(\d+)""")

    /**
     * The same ids as a `<meta>` tag, which is the only form the *play* page carries.
     *
     * Both forms are read because the site publishes both and neither is promised: the series page
     * links out to the databases, the play page states them as metadata. Accepting either means a
     * page that changes which it uses cannot silently cost this provider its id confirmation — and
     * that confirmation is the whole reason [PaheProvider.session] can match exactly rather than
     * guess from a title.
     */
    private val ANILIST_META = Regex(
        """<meta\b[^>]*\bname=["']anilist["'][^>]*\bcontent=["'](\d+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val MAL_META = Regex(
        """<meta\b[^>]*\bname=["']mal["'][^>]*\bcontent=["'](\d+)["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A button in the play page's `#resolutionMenu`, matched by the one attribute only it carries.
     *
     * Only the opening tag is captured. Everything worth knowing is an attribute on it, so the
     * label between the tags — `SubsPlease &middot; 1080p BD eng` — is never parsed. The reference
     * client reads exactly that text and re-derives the language from the words in it; doing the
     * same here would mean depending on a human-facing string when the machine-facing one is
     * sitting in the same tag.
     */
    private val RESOLUTION_BUTTON = Regex(
        """<button\b([^>]*\bdata-src\s*=[^>]*)>""",
        RegexOption.IGNORE_CASE,
    )

    /** Japanese audio: a subtitled release, and what the site states when it states nothing else. */
    const val AUDIO_JAPANESE = "jpn"

    /** English audio: a dubbed release. The site's own vocabulary, not a guess from a label. */
    const val AUDIO_ENGLISH = "eng"

    /** One series in a search response. */
    data class Candidate(val session: String, val title: String, val episodes: Int?)

    /**
     * One page of `m=release`: how many pages exist, the episode-to-session map on this one, and
     * which of those episodes a dub exists for.
     */
    data class ReleasePage(
        val total: Int,
        val perPage: Int,
        val lastPage: Int,
        val episodes: Map<Int, String>,
        val episodeOffset: Int = 0,
        /**
         * Episodes on this page whose release states a non-Japanese audio track.
         *
         * The row says `eng` when a dubbed release exists *beside* the subtitled one, not instead
         * of it — verified on 2026-09-13 against Jujutsu Kaisen, whose rows all read `eng` while
         * every episode's menu still offers three `jpn` buttons. So this names the episodes that
         * have a dub, and never the ones that lack a sub.
         */
        val dubbed: Set<Int> = emptySet(),
    )

    /**
     * One release the play page offers: a fansub's encode at one resolution, in one audio language.
     *
     * AnimePahe publishes the same episode several times over — three resolutions per fansub, and
     * separate entries again for each dub — and each is its own Kwik embed rather than a variant
     * inside one playlist. So choosing between them is this provider's job and cannot be left to
     * the player's usual adaptive selection.
     */
    data class Variant(
        val kwikUrl: String,
        val fansub: String,
        val resolution: Int?,
        /** `jpn`, `eng`, `kor`, … exactly as the site states it. */
        val audio: String,
        val av1: Boolean,
    ) {
        /** What the picker shows: who encoded it, how big it is, and whether it is the awkward codec. */
        val label: String
            get() = buildString {
                append(fansub.ifBlank { "Kwik" })
                resolution?.let { append(" ${it}p") }
                if (av1) append(" AV1")
            }
    }

    fun parseSearch(element: JsonElement): List<Candidate> =
        (element as? JsonObject)?.get("data").asArray().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val session = row.string("session")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Candidate(session, row.string("title").orEmpty(), row.int("episodes"))
        }

    fun parseRelease(element: JsonElement, episodeOffset: Int? = null): ReleasePage {
        val root = element as? JsonObject ?: return ReleasePage(0, 0, 0, emptyMap())
        val rows = root["data"].asArray().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val number = row.int("episode") ?: return@mapNotNull null
            val session = row.string("session")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Triple(number, session, row.string("audio").orEmpty().lowercase())
        }
        val numbered = rows.associate { (number, session, _) -> number to session }
        // A confirmed season can retain the series' absolute numbering: Bleach's fourth
        // cour starts at 41. Derive its offset from the first ascending page and reuse it
        // on later pages; deriving it per page would turn episode 31 back into episode 1.
        val offset = episodeOffset ?: ((numbered.keys.minOrNull() ?: 1) - 1).coerceAtLeast(0)
        val episodes = numbered.mapKeys { (number, _) -> number - offset }
        // A blank `audio` is read as Japanese rather than as a dub: the field is only ever absent
        // on older rows, and claiming a dub that is not there costs a picker entry that can never
        // resolve — which is the exact failure the old blanket `dub = emptySet()` was avoiding.
        val dubbed = rows.asSequence()
            .filter { (_, _, audio) -> audio.isNotBlank() && audio != AUDIO_JAPANESE }
            .map { (number, _, _) -> number - offset }
            .toSet()
        return ReleasePage(
            total = root.int("total") ?: episodes.size,
            perPage = root.int("per_page") ?: episodes.size,
            lastPage = root.int("last_page") ?: 1,
            episodes = episodes,
            episodeOffset = offset,
            dubbed = dubbed,
        )
    }

    /** The AniList and MyAnimeList ids the page states, however it states them, in that order. */
    fun parseIds(html: String): Pair<Int?, Int?> {
        fun id(link: Regex, meta: Regex): Int? =
            (link.find(html) ?: meta.find(html))?.groupValues?.get(1)?.toIntOrNull()
        return id(ANILIST_ID, ANILIST_META) to id(MAL_ID, MAL_META)
    }

    fun kwikUrl(playHtml: String): String? =
        KWIK_URL.find(playHtml)?.groupValues?.get(1)?.takeIf { it.contains("kwik", ignoreCase = true) }

    /**
     * Every release the play page offers, in the order the site lists them.
     *
     * Verified against a live page on 2026-09-13: each button states `data-fansub`,
     * `data-resolution`, `data-audio` and `data-av1` beside its `data-src`, so nothing here is
     * inferred. A button missing a Kwik link is dropped rather than guessed at.
     */
    fun variants(playHtml: String): List<Variant> =
        RESOLUTION_BUTTON.findAll(playHtml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val kwik = NativeProviderParsers.attr(attrs, "data-src")
                .takeIf { it.contains("kwik", ignoreCase = true) }
                ?: return@mapNotNull null
            Variant(
                kwikUrl = kwik,
                fansub = NativeProviderParsers.attr(attrs, "data-fansub"),
                resolution = NativeProviderParsers.attr(attrs, "data-resolution").toIntOrNull(),
                audio = NativeProviderParsers.attr(attrs, "data-audio").lowercase()
                    .ifBlank { AUDIO_JAPANESE },
                av1 = NativeProviderParsers.attr(attrs, "data-av1") == "1",
            )
        }.toList()

    /**
     * The one variant worth spending a Kwik resolve on, or null when [audio] is not published.
     *
     * Exactly one, because resolving costs a WebView navigation through [PaheBridge] and a nine-way
     * menu — Solo Leveling publishes three resolutions each of `jpn`, `eng` and `kor` — would spend
     * the whole budget offering choices nobody asked for.
     *
     * The order is deliberate. English wins among dubs because that is what "dub" means to the
     * picker that asked; a `kor` release is offered only when it is the sole dub. AV1 is then
     * avoided ahead of resolution rather than after it, because a 720p h264 file plays on a TV box
     * that would drop every frame of a 1080p AV1 one — the codec decides whether the episode plays
     * at all, the resolution only how it looks.
     */
    fun variantFor(variants: List<Variant>, audio: String, avoidAv1: Boolean): Variant? {
        val wantsDub = audio.equals("dub", ignoreCase = true)
        return variants
            .filter { (it.audio != AUDIO_JAPANESE) == wantsDub }
            .maxWithOrNull(
                compareBy<Variant> { if (wantsDub && it.audio == AUDIO_ENGLISH) 1 else 0 }
                    .thenBy { if (avoidAv1 && it.av1) 0 else 1 }
                    .thenBy { it.resolution ?: 0 },
            )
    }

    /**
     * The playlist inside a Kwik embed, unpacking the player first when it is packed.
     *
     * Kwik ships its player through Dean Edwards' P.A.C.K.E.R., the same packer AllAnime uses, so
     * [AllAnimeJsUnpacker] does the work rather than a second copy of it. An unpacked page is read
     * directly, because the packer is a choice Kwik makes per deploy and not a promise.
     */
    fun playlistFrom(embedHtml: String): String? {
        val unpacked = if (AllAnimeJsUnpacker.detect(embedHtml)) {
            AllAnimeJsUnpacker.unpackAndCombine(embedHtml)
        } else {
            null
        }
        return M3U8.find(unpacked ?: embedHtml)?.value
    }

    private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}

/**
 * AnimePahe (animepahe.pw) — the site behind Miruro's `kiwi`, reached directly.
 *
 * Reaching it natively began as a way around the pipe's engine: `kiwi` is only available through
 * the Miruro pipe, the pipe needed GeckoView, and GeckoView needs API 26, so on an older TV box
 * AnimePahe was unreachable however well it worked elsewhere. The pipe runs on OkHttp now and
 * reaches those devices too, so this is no longer the only route to the site — but it is still the
 * one that does not depend on Miruro carrying the episode.
 *
 * Every request goes through [PaheBridge] and none through OkHttp. That is not a style choice: the
 * zone binds `cf_clearance` to the engine that solved its challenge, and a clearance this app's
 * WebView earns was measured refused for OkHttp in five separate combinations. The protocol below
 * is ordinary HTTP; only the client is unusual.
 *
 * The site publishes one episode many times over, and says so plainly. Each entry in the play
 * page's `#resolutionMenu` carries `data-fansub`, `data-resolution`, `data-audio` and `data-av1`
 * beside its Kwik link, and `m=release` states an `audio` per episode — so both the dub this
 * provider once claimed did not exist and the codec a TV box cannot decode are facts to be read
 * rather than inferred. Each entry is a separate embed with a single rendition inside it (the Kwik
 * player's own quality menu reads `undefined`), so picking between them is work no player can do
 * for us, and [PaheParser.variantFor] does it once instead of resolving a menu's worth of them.
 */
internal class PaheProvider(private val json: Json) {
    /** AniList id to series session. Sessions rotate, so this dies with the process on purpose. */
    private val sessions = ConcurrentHashMap<Int, String>()

    suspend fun episodeAvailability(media: Media): EpisodeAvailability = PaheBridge.session {
        val page = release(session(media), page = 1)
        if (page.total <= 0) error("AnimePahe returned no episodes")
        // The catalogue is one request: `total` is enough to say which episodes exist, and asking
        // for all forty pages of a long-running series to learn the same thing would cost the
        // constrained-device budget the whole provider.
        val sub = (1..page.total).toSet()
        EpisodeAvailability(sub = sub, dub = dubbed(page))
    }

    /**
     * Which episodes carry a dub, inferred from the single release page the budget pays for.
     *
     * `m=release` states an `audio` per episode, so this costs nothing extra. What it cannot do is
     * see past page one, and One Piece has forty of them.
     *
     * Measured on 2026-09-13: the value is uniform across a series. One Piece reads `jpn` for all
     * thirty rows on page one, Bleach reads `eng` on page one *and* on page thirteen, Jujutsu
     * Kaisen reads `eng` for all twenty-four. So a page that is wholly dubbed is read as a series
     * that is, and anything mixed is reported exactly as seen rather than extrapolated from.
     *
     * Being optimistic in the uniform case is deliberately cheap: [sources] reads the menu on the
     * page for the episode actually asked for, so a badge that over-promises costs one failed
     * resolve and never a wrong audio track.
     */
    private fun dubbed(page: PaheParser.ReleasePage): Set<Int> {
        val seen = page.episodes.keys
        if (page.dubbed.isEmpty() || seen.isEmpty()) return page.dubbed
        return if (page.dubbed.containsAll(seen)) (1..page.total).toSet() else page.dubbed
    }

    suspend fun sources(media: Media, audio: String, episode: Int): SourcesResult = PaheBridge.session {
        val seriesSession = session(media)
        val episodeSession = episodeSession(seriesSession, episode)
            ?: error("AnimePahe has no episode $episode")

        val playHtml = get("/play/$seriesSession/$episodeSession")
        // AV1 is avoided on a television and accepted on a phone: the boxes this app still supports
        // have no AV1 block in hardware, and software-decoding 1080p beside everything else they
        // are already doing is the difference between an episode and a slideshow.
        val chosen = PaheParser.variantFor(
            PaheParser.variants(playHtml),
            audio = audio,
            avoidAv1 = AppGraph.isTv,
        )
        if (chosen == null && audio == "dub") error("AnimePahe has no dub for episode $episode")

        // Falling back to the player script's own `let url` only when the menu could not be read.
        // It is not simply "the best sub": play.js writes `res`/`aud`/`av1` cookies on every
        // selection and the site marks a button `active` from them, so on an engine whose cookie
        // jar has seen a dub this default *is* the dub. Which is why it is a shape-change fallback
        // for the subtitled path and never an answer for the dubbed one.
        val embed = chosen?.kwikUrl
            ?: PaheParser.kwikUrl(playHtml)
            ?: error("AnimePahe play page named no embed")
        // Kwik answers for animepahe's referer, not its own.
        val embedHtml = PaheBridge.document(embed, referer = "${PaheBridge.ORIGIN}/")
            ?: error("Kwik embed did not load")
        val playlist = PaheParser.playlistFrom(embedHtml) ?: error("Kwik embed carried no playlist")

        val language = chosen?.audio?.let(::languageName)
        SourcesResult(
            streams = listOf(
                StreamItem(
                    url = playlist,
                    type = "hls",
                    quality = listOfNotNull(chosen?.label, language).joinToString(" ")
                        .ifBlank { "Kwik" },
                    audio = language,
                    // The playlist is refused outright without this: measured 200 with it and 403
                    // without, on the same URL seconds apart.
                    referer = kwikOrigin(embed),
                    isActive = true,
                    width = null,
                    height = chosen?.resolution,
                ),
            ),
            subtitles = emptyList(),
            skip = null,
            download = null,
        )
    }

    /**
     * The site's audio code as something a viewer reads, or null for the subtitled default.
     *
     * Japanese is deliberately unnamed: it is what every release is unless stated otherwise, and
     * labelling the ordinary case only adds noise to a picker row that already says the fansub and
     * the resolution.
     */
    private fun languageName(code: String): String? = when (code) {
        PaheParser.AUDIO_ENGLISH -> "English"
        "kor" -> "Korean"
        "chi" -> "Chinese"
        PaheParser.AUDIO_JAPANESE -> null
        else -> code.takeIf { it.isNotBlank() }
    }

    /**
     * The series session for [media], found by title and confirmed by id.
     *
     * Searching is the only way in — there is no id lookup — but the series page links out to
     * AniList and MyAnimeList, so a candidate can be *confirmed* rather than trusted. That check is
     * what separates this from the usual fuzzy title match, and it is cheap: one page for the
     * candidate the search already ranked first.
     */
    private suspend fun session(media: Media): String {
        sessions[media.id]?.let { return it }
        val titles = listOfNotNull(
            media.title.english,
            media.title.romaji,
            media.title.userPreferred,
        )
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        for (title in titles) {
            val candidates = PaheParser.parseSearch(
                json.parseToJsonElement(get("/api?m=search&l=8&q=${enc(title)}")),
            )
            // Confirmation costs a page fetch each, and this runs inside the catalogue's budget
            // behind a semaphore — walking all eight search hits spends the whole budget before the
            // first useful request. The search is relevance-ordered, so a real match is at the top
            // or is not there.
            val confirmed = candidates.take(CONFIRM_LIMIT).firstOrNull { candidate ->
                val (anilistId, malId) = runCatching {
                    PaheParser.parseIds(get("/anime/${candidate.session}"))
                }.getOrDefault(null to null)
                (anilistId != null && anilistId == media.id) ||
                    (malId != null && media.idMal != null && malId == media.idMal)
            } ?: continue
            sessions[media.id] = confirmed.session
            return confirmed.session
        }
        error("AnimePahe has no confirmed match for this title")
    }

    /**
     * The session for one episode, asking only for the page it should be on.
     *
     * Episodes are sorted ascending and numbered from one, so the page follows from the number. The
     * result is still checked rather than assumed — specials and gaps do move episodes off their
     * arithmetic page — and a miss falls back to walking the pages.
     */
    private suspend fun episodeSession(seriesSession: String, episode: Int): String? {
        val first = release(seriesSession, page = 1)
        first.episodes[episode]?.let { return it }
        val perPage = first.perPage.takeIf { it > 0 } ?: return null
        val guess = ((episode - 1) / perPage) + 1
        if (guess in 2..first.lastPage) {
            release(seriesSession, guess, first.episodeOffset).episodes[episode]?.let { return it }
        }
        // A plain loop rather than a sequence: each page is a suspending request, and a sequence
        // cannot carry one.
        for (page in 2..first.lastPage) {
            if (page == guess) continue
            release(seriesSession, page, first.episodeOffset).episodes[episode]?.let { return it }
        }
        return null
    }

    private suspend fun release(seriesSession: String, page: Int, episodeOffset: Int? = null): PaheParser.ReleasePage = PaheParser.parseRelease(
        json.parseToJsonElement(
            get("/api?m=release&id=$seriesSession&sort=episode_asc&page=$page"),
        ),
        episodeOffset = episodeOffset,
    )

    private fun kwikOrigin(embed: String): String =
        runCatching { java.net.URI(embed).let { "${it.scheme}://${it.authority}/" } }.getOrNull()
            ?: "https://kwik.cx/"

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * One request, issued by the engine that holds the clearance.
     *
     * There is no OkHttp path here on purpose — see [PaheBridge] for the five measurements that
     * ruled one out.
     */
    private suspend fun get(path: String): String =
        PaheBridge.fetchText(path) ?: error("AnimePahe did not answer $path")

    companion object {
        /** How many search hits are worth a confirming page fetch. See [PaheProvider.session]. */
        private const val CONFIRM_LIMIT = 3
    }
}
