package com.anilili.data

import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant

/**
 * Provider classification across both streaming backends:
 * - **Miruro** pipe providers (via the on-device WebView bridge)
 * - **Anivexa** providers (via an Anivexa-API instance over HTTP)
 * A provider name is globally unique, so routing is by name.
 */
object ProviderCatalog {
    enum class Source { MIRURO, ANIVEXA }

    // Miruro providers, in the site's own `providerOrder` from its live streaming configuration
    // (`/api/config`, re-read 2026-10-03 when the catalog API replaced the pipe; labels, not keys).
    // Copying it rather than inventing an order means Miruro's own judgement of which of its
    // backends to reach for first is the one the app follows, and it moves when theirs does.
    // sun (icarus, Miruro's own vault hosts) and knob (aniwaves) arrived with the catalog API.
    private val miruroOrder = listOf(
        "bee", "sun", "pewe", "hop", "ally", "kiwi", "moo", "bonk", "knob", // native HLS
        "nun", "bun", "cog", "twin", "telli",                                // iframe embeds
    )
    private val miruroEmbed = setOf("nun", "bun", "twin", "cog", "telli")

    /**
     * Each iframe embed and the native provider it re-hosts, mirroring the `parent` /
     * `relationship: "embed"` pairs in Miruro's live streaming configuration.
     *
     * The pipe's `episodes` response only ever names native providers, so nothing here can be
     * discovered from it — that is precisely why these servers were missing from the app while
     * the site listed them. Miruro's own picker derives them: an embed child is offered whenever
     * its parent has the episode, and `sources` for the child is fetched with the *parent's*
     * episode id. Verified against Bleach ep 7 on 2026-09-06, where the site offered
     * `nunembedh`/`bunembeds`/`twinembedh`/`twinembeds` and the app offered none of them; asking
     * the pipe for `bun` and `twin` with bee's and bonk's episode ids answered 200 with streams.
     *
     * `telli` is deliberately absent. It is the one child the live map marks `visible: false`,
     * so the site itself no longer offers it and synthesising it would invent a server Miruro has
     * retired. It stays in [miruroEmbed] so an old saved favourite still routes as an embed.
     */
    private val miruroEmbedParents = mapOf(
        "nun" to "ally",
        "bun" to "bee",
        "twin" to "bonk",
        "cog" to "moo",
    )

    /** The iframe embeds Miruro derives from [provider], in picker order. */
    fun embedChildrenOf(provider: String): List<String> =
        miruroOrder.filter { miruroEmbedParents[it] == provider }

    /**
     * Iframe embeds whose page the app can read a real stream out of, over plain HTTP.
     *
     * Being `player: "iframe"` upstream describes how Miruro *offers* a provider, not what its page
     * turns out to contain. `MiruroEmbedResolver` fetches the page with one OkHttp GET and takes the
     * manifest straight out of it, so these play in the app's own player: quality ladder, subtitle
     * delay, PiP, Cast, external players and offline downloads, none of which reach into a WebView.
     *
     * Only `twin` is listed because only `twin` has been measured. Both its halves were probed live
     * on 2026-09-09 with bare curl — no Referer, no cookies, curl's own User-Agent — and both are
     * header-free by construction:
     *
     *  - `twinembedh` (hard sub) is a VidHide/EarnVids page on otakuvid.online. Page 200 in ~1.0s;
     *    master and media playlists 200; segments 200 as h264+AAC `video/MP2T` with no
     *    `#EXT-X-KEY`; `Range` answered 206; a hostile `Referer: example.com` still 200.
     *  - `twinembeds` (soft sub) is a "VibePlayer" page on bibiemb.xyz whose stream is a Cloudflare
     *    Worker answering `Access-Control-Allow-Origin: *`, and whose subtitle URL arrives in the
     *    iframe URL's own `?sub=` parameter, needing no request at all.
     *
     * `nun`, `bun` and `cog` are deliberately absent: nothing has read their pages, and listing an
     * unmeasured provider here would promote it up the picker on a guess. Adding one is a probe,
     * not an edit — and a wrong guess costs nothing at resolve time (the resolver simply fails and
     * the embed plays as before) but everything in ordering, which is what this set really drives.
     */
    private val nativelyResolvableEmbeds = setOf("twin")

    /**
     * Miruro subtitle-delivery capabilities from its public streaming configuration.
     *
     * This is not the SUB/DUB catalog bucket: `sub` here means hard-subbed video and `ssub`
     * means a soft-sub stream with selectable tracks. Providers with both must remain two
     * independent picker and failure-cooldown entries.
     */
    private val miruroSubtitleVariants: Map<String, List<SourceVariant>> = mapOf(
        // bonk lost its hard-sub category (2026-08-18) — `category=sub` answered HTTP 444 six times
        // out of six while `ssub` answered every time — and got it back (re-measured 2026-09-06).
        // The live capability map now publishes `bonk: {sub: true, ssub: true}` with
        // `variantOrder: ["ssub", "sub"]`, the site's own picker offers both (`bonkdls`/`bonkdlh`),
        // and `category=sub` returned streams on every title tried, so SOFT first then HARD below
        // matches upstream exactly.
        //
        // The reason the dead entry mattered, if it ever 444s again: bonk's first choice is
        // soft-sub, so when that stream fails the automatic fallback is bonk's *own* hard-sub, and
        // a guaranteed 444 there puts a pointless round trip in front of every bonk failure.
        "bonk" to listOf(SourceVariant.SOFT_SUB, SourceVariant.HARD_SUB),
        "kiwi" to listOf(SourceVariant.HARD_SUB),
        "hop" to listOf(SourceVariant.SOFT_SUB),
        "ally" to listOf(SourceVariant.HARD_SUB),
        "pewe" to listOf(SourceVariant.HARD_SUB),
        "bee" to listOf(SourceVariant.SOFT_SUB),
        "moo" to listOf(SourceVariant.HARD_SUB),
        "nun" to listOf(SourceVariant.HARD_SUB),
        "bun" to listOf(SourceVariant.SOFT_SUB),
        "twin" to listOf(SourceVariant.HARD_SUB, SourceVariant.SOFT_SUB),
        "cog" to listOf(SourceVariant.HARD_SUB),
        "telli" to listOf(SourceVariant.HARD_SUB),
        // From the first catalog `play` responses (2026-10-03): icarus answers on `ssub` and
        // aniwaves on `sub`, each alongside `dub`.
        "sun" to listOf(SourceVariant.SOFT_SUB),
        "knob" to listOf(SourceVariant.HARD_SUB),
    )

    /**
     * Miruro providers whose source response can include a separate download page. This mirrors
     * the live provider capability map; the current provider is also checked dynamically because
     * Miruro can change the map between app releases.
     */
    private val externalDownloadProviders = setOf("bonk", "kiwi", "ally", "moo")

    // Anivexa providers we query (reliable, self-hosted sources).
    //
    // animeshqip is gone (2026-09-04), and the measurement tables further down this file are why
    // it is still named there: 12.0s to answer for a catalogue, the second slowest of anything
    // measured, on a list whose every member is awaited. It carried Albanian subtitles and nothing
    // else, so what it bought for that was a lane and a wait on every title in the app for an
    // audience of approximately nobody. Its provider, its parser and their tests went with it.
    //
    // anibd is gone too (2026-10-03): epeng.animeapps.top and playeng.animeapps.top both serve a
    // page titled "Sorry, the website has been stopped", and the catalogue API 404s for every id.
    // It led this list on the cheapest lookup measured (89-139ms), which is why it is still named
    // in the tables below; on the day it went it was costing every title a lane and a 6s timeout.
    val anivexaProviders = listOf(
        // RareAnimes must enter the constrained-device semaphore early. At its old fifteenth
        // position, a two-lane phone could spend its whole 15s budget waiting behind dead sites
        // and cancel RareAnimes before its first HTTP request.
        //
        // animepahe is third on the same grounds and on its measured catalogue latency (1.1s, see
        // the table below — second only to anibd). Placed last it hit precisely the RareAnimes
        // failure: starved of a lane and cancelled with an InterruptedIOException before its first
        // request, which reads in the log as a provider that was never wired up at all.
        // senshi is gone (2026-09-10). It was demoted on 2026-08-03 when /episodes/<malId> started
        // answering 502, and it has since stopped answering at all: senshi.live now closes the
        // connection during the TLS handshake. Five tester bundles from 2026-09-10 carry 20 of 20
        // failed lookups, every one an SSLHandshakeException, on four devices across two
        // continents — and a probe from this machine resets the same way. It was still costing a
        // lane and a slot in every catalogue walk to fail in.
        // animeheaven is placed fifth on its measured catalogue latency (search 0.44-0.72s plus a
        // detail page at 0.58-0.88s, so ~1.0-1.6s end to end — the animepahe band) and because it
        // is the only provider here whose stream needs no headers at all, which makes it the one
        // worth reaching on a title where the gated servers are refusing. It is deliberately absent
        // from fastAnivexaProviders below; see the note there.
        // anichan is second because it is the cheapest catalogue lookup in the list: one small JSON
        // GET keyed on the AniList id, so unlike every scraper here it does no search request and
        // no title matching at all. Same shape as anibd, which leads for the same reason.
        // zoko follows it on the same grounds: keyed on the MAL id AniList already gave us, so no
        // search, and each probe is a ~2KB page answered in ~0.1s (measured from a desktop,
        // 2026-09-22, not yet on a device). A complete title is two probes per track. It joined
        // fastAnivexaProviders on 2026-09-23 on phone-emulator timings, and the TV emulator then
        // agreed: 545ms median, 1.9s worst over five catalogues, against anikoto's 1.5s and 3.2s.
        // animeonsen sits in the animepahe band rather than with anibd/anichan, which its per-call
        // latency alone would argue for. Measured 2026-09-13: search 0.29s and episodes 0.34s, but a
        // *cold* lookup also pays a landing page and a token (~1.3s, once per process) and up to
        // three 0.74s detail requests to confirm the match by MAL id — so the honest figure is ~1.0s
        // typical and ~2.5s worst case, not the 0.6s the warm path suggests. It earns the placement
        // it has on being the only provider here that parses no HTML at all, which is what a
        // television is worst at; see [[tv-catalogue-parse-cost]].
        // xfdm follows animeheaven on the same measurement, taken on a Galaxy S25 over three
        // uncached titles: a 1302ms median catalogue answer, between animepahe's 1254ms and
        // allanime's 1484ms, against anibd's 89ms and anichan's 173ms at the head. It cannot reach
        // that head — it is two sequential HTML fetches, a search and a subject page, where those
        // two are one JSON GET — so it earns this band and not the front of the list.
        //
        // This list is the catalogue fan-out order, which is about lane priority and nothing else.
        // Where it *shows* and what plays first is [leaders] below, where xfdm is pinned ahead of
        // both other defaults — so its position here is not the whole story.
        //
        // It stays out of fastAnivexaProviders: that set is the short list a constrained device
        // walks when it cannot afford everything, and a provider that declines outright on roughly
        // a third of the catalogue — it cannot run at all until the Bangumi join has placed the
        // title — is the wrong thing to spend a scarce lane on.
        "anipm", "anichan", "zoko", "animepahe", "rareanimes", "anidbapp", "animeheaven", "xfdm", "animeonsen", "anikoto", "kaa", "allanime", "animekai", "reanime", "anizone", "animegg", "anineko", "2dhive",
        "animedekho", "toonhub", "uniquestream", "animekhor",
        // kartoons (2026-10-03) is Hindi like animedekho above, so it joins that band: asked about
        // every title for the dub row, never a fast-list candidate until measured. Its catalogue is
        // JSON only — a search, the show's seasons, then the season lists (all of a whole-series
        // run's at once) — measured at ~1.2s cold on a Galaxy S25. Declared Hindi below.
        "kartoons",
        // animeav1 (2026-09-25) is Spanish, and last among the general servers for that reason:
        // it is asked about every title so that it is always in the picker, but it is never the
        // automatic choice unless the viewer asked for Spanish — see [declaredLanguage].
        "animeav1",
        // rift (2026-10-06) is the Arabic catalogue behind the Anime Rift app, and the only Arabic
        // server here. It sits with animeav1 rather than in the general band for the same reason:
        // asked about every title so it is always in the picker, never the automatic choice unless
        // the viewer asked for Arabic — see [declaredLanguage]. Its lookup is JSON throughout and
        // needs no HTML parsing, but it is three requests (a search, the episode list, and one dub
        // probe) on top of a Firebase bootstrap the first time, so it is placed late and left out of
        // the fast list until it has been measured on a device.
        "rift",
        // hstream (2026-10-03) sits with the other two adult servers, which every catalogue drops
        // while adult content is hidden. Its catalogue is the cached sitemap, so a lookup costs no
        // request once that is warm, and the stream is header-free DASH — see HStreamProvider.
        "hanime", "hentaihaven", "hstream",
    )

    /**
     * The one language a server's streams are in, for servers that are not in English.
     *
     * A resolve usually says nothing about language — AnimeAV1's Spanish subtitles are burned into
     * the video — and the picker's language row assumes English of any server that says nothing.
     * A declared language replaces that assumption, so the Spanish row finds AnimeAV1 before it has
     * been resolved and the English row never claims it.
     *
     * It also keeps the server out of every automatic choice unless the viewer's preferred audio
     * (for a dub) or subtitle language (for a sub) is this language: see
     * [MiruroRepository.resolveSources]. Picking the server by hand always plays it.
     */
    // kartoons carries one Hindi audio track and nothing else. Undeclared, an English-dub viewer's
    // automatic walk could reach it once the faster servers refused, and the dub row would list it
    // under English; declared, it plays only when picked or when the preferred audio is Hindi — and
    // then it leads, which is what a viewer who set Hindi is asking for.
    // rift publishes Arabic subtitles and, on some titles, an Arabic dub — and nothing else. Left
    // undeclared it would be reachable as an English sub, which is a stream a viewer who did not ask
    // for Arabic would be shown in Arabic; declared, it leads for a viewer who set Arabic and stays
    // out of the automatic walk for everyone else.
    private val declaredLanguages = mapOf(
        "animeav1" to "Spanish",
        "kartoons" to "Hindi",
        "rift" to "Arabic",
    )

    /**
     * Servers whose stream URLs expire soon after being minted.
     *
     * A failure on one of these is first answered by resolving the same server again, rather than
     * by moving to a different one — which would trade the audio the viewer chose for whatever the
     * next server speaks. That holds for a paused or long-queued download, whose stored playlist URL
     * has lapsed ([com.anilili.playback.EpisodeDownloads]), and for playback resumed after a long
     * pause ([com.anilili.ui.watch.WatchViewModel.onPlaybackError]).
     *
     * kartoons: each `/links` call mints new proxy ids, and two minted at 03:41 on 2026-10-03 both
     * answered 404 by 04:13 while the account token stayed good.
     */
    private val shortLivedStreamUrls = setOf("kartoons")

    fun hasShortLivedStreamUrls(provider: String): Boolean = provider.lowercase() in shortLivedStreamUrls

    fun declaredLanguage(provider: String): String? = declaredLanguages[provider.lowercase()]

    /** The server that is in [language], if one is declared; see [declaredLanguage]. */
    fun providerForLanguage(language: String?): String? =
        language?.let { wanted -> declaredLanguages.entries.firstOrNull { it.value.equals(wanted, ignoreCase = true) }?.key }

    /**
     * Providers whose catalogue only ever covers one country of origin, and the country it covers.
     *
     * Animekhor is a donghua site and nothing else: searching it for "Solo Leveling" returns "Not
     * Found" while "Battle Through the Heavens" returns nine entries. Without this gate it would
     * take a lane in the sixteen-provider fan-out on every Japanese title in the app — a
     * guaranteed miss, competing for the semaphore with servers that can actually answer.
     *
     * Keyed on AniList's `countryOfOrigin`, which is the only field that separates donghua from
     * anime; genre and studio do not.
     */
    private val countryLockedProviders = mapOf("animekhor" to "CN")

    /**
     * [providers] narrowed to those that can serve a title from [countryOfOrigin].
     *
     * An unknown country lets everything through. `countryOfOrigin` is absent from every [Media]
     * cached before the field was requested, so gating on it strictly would hide Animekhor from
     * donghua for the life of the 24h media cache; letting the unknowns through costs at most one
     * wasted search per title, which the provider's own negative cache then remembers.
     */
    fun providersForCountry(providers: List<String>, countryOfOrigin: String?): List<String> {
        if (countryOfOrigin.isNullOrBlank()) return providers
        return providers.filter { provider ->
            val required = countryLockedProviders[provider] ?: return@filter true
            required.equals(countryOfOrigin, ignoreCase = true)
        }
    }

    /**
     * Providers that only ever carry adult material. They are dropped from every catalog and from
     * the server picker while "Hide adult content" is on, so a viewer who has not opted in is
     * never offered one — even on a title that happens to match by name.
     */
    val adultProviders = setOf("hanime", "hentaihaven", "hstream")

    fun isAdultOnly(provider: String): Boolean = provider in adultProviders

    /** The providers to query for [hideAdult]; the adult-only ones drop out when it is set. */
    fun anivexaProvidersFor(hideAdult: Boolean): List<String> =
        if (hideAdult) anivexaProviders.filterNot(::isAdultOnly) else anivexaProviders

    /**
     * [providers] without the servers the viewer switched off in Settings.
     *
     * [disabled] is passed in rather than read from the settings store so this file keeps deciding
     * only what a provider *is*, and so a caller has to name the set it is filtering against —
     * the same shape as [providersForCountry] and [anivexaProvidersFor] above.
     */
    fun enabledProviders(providers: List<String>, disabled: Set<String>): List<String> =
        if (disabled.isEmpty()) providers else providers.filterNot { it.lowercase() in disabled }

    /**
     * A short, order-independent stamp of a provider list, for cache keys that are only valid for
     * the list that produced them. Names are what identifies a provider here, so renaming one is
     * meant to invalidate too; the digest keeps the key short rather than carrying all sixteen.
     */
    fun fingerprint(providers: List<String>): String =
        providers.sorted().joinToString(",").hashCode().toUInt().toString(36)

    // Providers whose resolution can require browser state rather than plain HTTP, and which
    // therefore need a browser's budget rather than a scraper's — and no speculative background
    // resolves, because the engine each needs is a single shared one.
    //
    // They land here for different reasons. ReAnime drives a hidden player, which must not compete
    // with playback for the decoder. AnimePahe never plays anything, but its whole resolver is a
    // WebView (see PaheBridge: the zone binds its clearance to the engine that earned it), and
    // leaving it out of this set was worth an 8-second scraper budget for a chain that has to fetch
    // a play page and then *navigate* to a kwik embed. Measured 2026-09-10 on a Galaxy S25: the
    // catalogue answered in 2.0s and the stream resolve was cut off at 8.0s in the same run.
    //
    // hanime is the same case as AnimePahe and was missing here for the same reason — it was only
    // ever reached through its own bridge, so nothing made the omission visible until its stream
    // resolution was fixed and started being cut off instead of failing outright. Its page has to
    // hydrate before the WASM handshake it exists to observe will even run; measured on a Galaxy
    // S25 the resolve was cut off at exactly 8.0s having loaded the page and installed its hook.
    val webViewResolverProviders = setOf("reanime", "animepahe", "hanime")

    /**
     * Servers whose one stream carries several audio tracks rather than a stream per category.
     *
     * Sub and dub are the same file here, so choosing between them is a track change the player
     * makes — see `PlayerSurface.applyCategoryAudioPreference`, which reads the category and picks
     * the matching track once the stream's tracks are published.
     */
    val multiAudioProviders = setOf("reanime", "kaa")

    /**
     * The [multiAudioProviders] whose *catalog* cannot express a dub either, so a dub request has
     * to be looked up under sub.
     *
     * This is not the same question as carrying several audio tracks, and conflating the two was
     * wrong for ReAnime. Its catalog does distinguish: `api/v1/search` reports `dubbed` separately
     * from `subbed` — Solo Leveling 12/12, Gintama 200/0 — and `api/flix` returns
     * `dataType:"dub"` rows only when that count is non-zero (verified live 2026-09-03, Gintama
     * and Bakemonogatari return sub rows only). Redirecting it therefore did two kinds of harm:
     * the dub request lost the one thing that marks a dub, the `a=1` audio-track index the site
     * appends, and a fallback could land on a title ReAnime has no dub for at all and play
     * Japanese under a DUB label — the exact outcome the redirect was written to avoid.
     *
     * KickAssAnime keeps it: that is the server the original report came from, where an English
     * track sat in the player's audio menu on an episode the catalog listed only under sub.
     */
    private val dubUncatalogedProviders = setOf("kaa")

    /**
     * Which catalog category to search for [category] on [provider].
     *
     * Everything resolves under the category asked for, except a dub request on a server that
     * cannot list one, which resolves its sub entry — the same file — and lets the player select
     * the English track.
     */
    fun dubCapableCategory(provider: String, category: Category): Category =
        if (category == Category.DUB && provider.lowercase() in dubUncatalogedProviders) Category.SUB else category

    /** Any source lookup that must create a hidden browser, regardless of returned stream type. */
    fun requiresResolverWebView(provider: String): Boolean =
        sourceOf(provider) == Source.MIRURO || provider in webViewResolverProviders

    // The consistently quick Anivexa lookups (API-backed, not full-page scrapers). Raced as an
    // early partial catalog when the Miruro pipe is down or slow, so playback never waits for
    // the 15-second stragglers; the remaining providers still merge in behind.
    //
    // senshi led this list until 2026-08-03, when /episodes/<malId> began answering 502 and it
    // failed 19 of 19 lookups; it was demoted then in the hope the endpoint would come back. It did
    // not. senshi.live now closes the connection during the TLS handshake and the provider is gone
    // entirely — see [anivexaProviders] for the measurement.
    //
    // Until 2026-09-23 every member was awaited before the early catalogue was published, so this
    // list was as slow as its slowest member — which is why most of the notes below judge
    // candidates against the others. The watch screen now takes each member's row as it answers
    // (AnivexaClient.getEpisodes' onProvider), so a slow member delays only itself. It still costs a
    // lane on every title, so it stays short, and "reliable" is still not enough on its own.
    //
    // kaa (2026-08-04, restated 2026-08-11): stays out, but no longer for the reason first
    // recorded here. It was pulled for failing two of three resolves with its one success at 2.9s;
    // re-measured over eight pinned resolves it now succeeds 8/8 at a 1.4s median warm. It is
    // healthy again — just still about three times slower than the members below, and adding it
    // would push that delay onto every viewer's first paint.
    //
    // Measured warm medians, Galaxy S25, Frieren, 2026-08-11 (pinned per-provider resolves):
    // 2dhive 123ms, anidbapp 300ms, anikoto 408ms, animekai 587ms, animeshqip 656ms (3/5),
    // kaa 1356ms, anineko 1680ms, animegg 2260ms, allanime 0/4.
    //
    // Those are *source resolve* times. Catalogue latency is what decides membership here, and
    // measuring it (Galaxy S25, eight uncached titles, 2026-08-11) inverts the ranking almost
    // completely — so nothing is promoted:
    //
    //   anibd 139ms · anikoto 886ms · anidbapp 1.7s · allanime 2.1s · kaa 2.9s · animekai 4.3s
    //   anineko 5.5s · 2dhive 7.7s · animegg 7.8s · animeshqip 12.0s · uniquestream 14.3s
    //   anizone: never returned, 7 timeouts out of 7
    //
    // 2dhive is the trap. It resolves a stream faster than anything measured (123ms) and returns
    // an episode list slower than almost everything (7.7s, best case 5.4s). Promoting it on the
    // resolve number would have added up to the 6s cap to every viewer's first paint, on every
    // title, to gain a server that is quick only once you have already picked it. anidbapp and
    // animekai fail the same way, less dramatically.
    //
    // Caveat on the numbers: they come from the full 17-provider merge, whose semaphore admits
    // 2-4 at a time and whose timeout counts the wait for a lane, so they are upper bounds. That
    // does not rescue 2dhive — anibd shared the same queue and came back in 139ms.
    //
    // animepahe placed third (2026-09-09). Catalogue medians, Galaxy S25, three uncached
    // current-season titles in one session, counting only real fetches (`catalog.provider`) so a
    // seeded or cached answer cannot flatter anyone:
    //
    //   anibd 129ms · animepahe 1.1s · anikoto 2.3s · allanime 3.7s · kaa 4.1s · reanime 4.4s
    //   animekai 4.7s · rareanimes 5.4s · anineko 7.9s · 2dhive 13.6s
    //
    // It answers faster than anikoto while running an entire WebView, which is not what anyone
    // expected of it — the engine is the price of reaching the site at all (see PaheBridge), not a
    // per-request cost, and animepahe's own API is two small JSON calls. It is also not the 2dhive
    // trap in reverse: it is quick at both ends, resolving a stream in about 1.2s.
    //
    // It is deliberately *not* added to the fast list below. Every member here is awaited before
    // first paint, and what is unmeasured is exactly what would hurt there: the WebView cold start
    // on an old TV box, where the engine has to be built before the first request can go out. The
    // phone never pays that visibly. Promoting it on phone numbers alone would repeat the mistake
    // the 2dhive note above exists to prevent, just with a different second measurement missing.
    // animeheaven stays out for the same reason animepahe does, with a different second
    // measurement missing. Its phone numbers would qualify it (~1.0-1.6s for a catalogue), but the
    // episode list comes off a 31KB `anime.php` page read with regex, which is precisely the shape
    // NativeProviderParsers' header warns about: the same parse that costs a phone ~100ms cost a
    // TV box 3.4s. Every member of this list is awaited before first paint, so promoting it on
    // phone timings alone would put that unmeasured cost in front of every viewer.
    // anipm (2026-09-23): its catalogue is one JSON GET keyed on the AniList id (~130ms from a
    // desktop, 120-280ms across six titles, 54KB at worst for One Piece) with no HTML to parse, so
    // it has none of the TV parse cost that keeps animeheaven out.
    //
    // zoko (2026-09-23): 417ms median, 1.8s worst over seven titles on the phone emulator, both
    // inside anikoto's 1.3s/2.4s. Its old exclusion was that every member here was awaited before
    // first paint; rows now reach the picker as each member answers, so a member only has to be
    // quick itself, not quicker than the rest.
    val fastAnivexaProviders = listOf("anikoto", "anipm", "zoko")

    /**
     * Fast providers that are currently unreliable. They stay selectable and keep their place in
     * the catalogue; they just lose the ⚡ badge and the speed-first sort, so nothing puts them
     * ahead of a server that works.
     *
     * Empty right now. hop was demoted 2026-08-04 — pinned resolves failed on all three titles
     * measured (Grand Blue S3, One Piece, Skeleton Knight) in 176-316ms, failing fast enough that
     * it never showed up as a timeout — and restored 2026-09-09, when pinned resolves succeeded on
     * three titles including One Piece from that same set: Daemons of the Shadow Realm ep22
     * (hls=2, 14 subtitle tracks), Attack on Titan ep1, One Piece ep1. Every one chose a native HLS
     * stream from `hls.krussdomi.com` rather than falling back to an embed, which is more than the
     * demotion asked for.
     *
     * Leaving the mechanism in place deliberately: it is how a provider gets held back without
     * being taken away from anyone who has pinned it, and Miruro's own ordering (see
     * [miruroOrder]) cannot express "ranked second but currently broken".
     *
     * zoko, 2026-09-26, on viewer reports of heavy buffering on TV and phone. Its catalogue is as
     * quick as ever; its video is not. Segments Cloudflare has cached arrive at 6-88 Mbit/s, but a
     * segment it has not (cf-cache-status MISS) comes from the origin at 0.3-1.1 Mbit/s — 0.3-0.7x
     * realtime for a stream declaring 5.3 Mbit/s. An episode's opening is usually cached because
     * everyone starts there, so it starts well and then stalls a few minutes in. Measured from a
     * desktop over six titles (Frieren 3, One Piece 1100, Cowboy Bebop 7 and 9, 6702/13-14): every
     * MISS below realtime. Fetching 8 segments at once only reached 1.05-1.85x, so parallelism is
     * not a dependable fix, and most titles publish a single 1080p rendition, so there is no lower
     * one to fall back to. Restore it when a MISS segment comes in faster than its own duration.
     */
    private val degradedFastProviders = setOf("zoko")

    // Providers that consistently resolve and start quickly: the Miruro pipe's native HLS set
    // (one pipe call away) and the API-backed Anivexa lookups. Embeds and full-page scrapers
    // are excluded. Drives the ⚡ badge and the speed-first sort below.
    // An embed the app can resolve natively is no longer paying an embed's cost, so it belongs
    // here with the rest: `twin` is one pipe call plus a single ~1.0s HTTP GET, the same shape as
    // the native Miruro set beside it, and the WebView it used to need was the whole reason embeds
    // were excluded. Its parent bonk is already in this set on exactly that reasoning.
    val fastProviders: Set<String> =
        (miruroOrder - miruroEmbed).toSet() + nativelyResolvableEmbeds + fastAnivexaProviders -
            degradedFastProviders

    fun isFast(provider: String): Boolean = provider in fastProviders
    /** Whether the provider's own website offers download links, reached by opening a browser. */
    fun supportsExternalDownloads(provider: String): Boolean = provider in externalDownloadProviders

    /**
     * Whether the app can save an episode from this provider into the offline library.
     *
     * Deliberately separate from [supportsExternalDownloads]: an embed plays inside a WebView and
     * exposes no stream the downloader can fetch, so a provider can offer download links on its
     * site while the app can do nothing with it. Conflating the two put a download glyph on Kiwi
     * and then hid the download button.
     *
     * Being an embed the app cannot read is the whole rule — [nativelyResolvableEmbeds] are the
     * exception, because the downloader gets a real manifest from them rather than a player page.
     * This remains only the *forecast* used before a server has resolved; `sourceOffersDownload`
     * prefers what the resolve actually returned, so a page that stops being readable stops
     * claiming a download without needing an edit here.
     *
     * Kiwi was excluded on top of this for months, on the belief that its CDN answered 403 to every
     * header a player could send; instrumenting the device on 2026-08-10 showed that CDN answering
     * 200 throughout, and the real fault to be Kwik's non-standard AES-128 IV, which
     * [com.anilili.playback.KwikPlaylistDataSource] now writes out. Saved episodes failed to play
     * because they were decrypted wrongly, not because they were never fetched. Downloads from Kiwi
     * were then verified end to end on a Galaxy S25.
     */
    fun supportsOfflineDownload(provider: String): Boolean =
        isNative(provider) || provider in nativelyResolvableEmbeds

    /**
     * The servers that play first when the viewer has ranked nothing, in this order — the ones
     * judged fastest right now (2026-09-27). This is an editorial ranking and deliberately outranks
     * [ProviderSpeed]'s per-device measurement: measurement only orders what this list leaves out.
     * A ranking the viewer made in Settings, or a server picked by hand, still comes before it.
     */
    val defaultPriority = listOf("bee", "anichan", "anikoto", "animepahe", "xfdm")

    /** Position in [defaultPriority], or [Int.MAX_VALUE] for a server it does not name. */
    fun defaultPriorityRank(provider: String): Int =
        defaultPriority.indexOf(provider.lowercase()).let { if (it >= 0) it else Int.MAX_VALUE }

    // Default row order: one Miruro pipe provider and one Anivexa provider lead, so the two
    // defaults are independent backends and a bad day for one is not a bad day for both.
    // After the leaders, fast providers sort before embeds and slow scrapers so the quick
    // options are always at the top of the server list. A user's saved favourite provider
    // always overrides this order.
    //
    // The Miruro leader is whatever Miruro itself ranks first, rather than a name pinned here.
    // bonk held this slot and the site ranks it *fifth*; while it was broken upstream — its
    // segments 403 with veImageX code 1004 and its Worker fallback 500s with a Backblaze
    // `account_trouble`, with the site's own `server-maintenance` message live — every first play
    // stalled on it before falling back. Deferring to `miruroOrder` means the next such outage
    // costs a reordering upstream, not a release here.
    //
    // senshi held the second slot until 2026-08-03 and is now removed from the app entirely — see
    // [anivexaProviders]. Being a leader pinned it near the top of the picker no matter how it
    // performed, which is how a dead server stayed in front of working ones for a day.
    // xfdm leads outright, which is the one place this list departs from "Miruro's own ranking
    // goes first". That rule exists because a provider pinned ahead of it is how bonk sat in front
    // of every working server for a day while failing upstream — so the exception needs its own
    // argument, and it is that none of bonk's failure modes are reachable here:
    //
    //  - It cannot be offered for a title it does not carry. An option only reaches the picker
    //    once its provider has reported episodes, and xfdm reports from a file rather than a guess.
    //  - It cannot be slow to answer. Both halves are precomputed: 0 requests to the site, a 134ms
    //    catalogue and an 85ms resolve, against 1,302ms and 589ms before.
    //  - It cannot stay broken. A URL the player refuses is retired on the spot and the next play
    //    resolves live, which is the healing bonk had no equivalent of.
    //
    // What it costs is editorial and worth stating plainly: on a title the English sources carry
    // cleanly, what plays first is now a picture with Chinese subtitles burned into it and English
    // drawn over the top.
    //
    // Superseded 2026-09-27 by [defaultPriority], which now owns the head of this list.
    private val leaders = defaultPriority
    private val order = leaders +
        (miruroOrder + anivexaProviders).filterNot { it in leaders }
            .sortedByDescending { it in fastProviders }

    /**
     * Native servers that belong to one catalogue-native title and nothing else. They resolve like
     * any Anivexa provider but stay out of [anivexaProviders], because that list is the fan-out
     * asked about every title in the app — a lane spent on each of them for a guaranteed miss.
     */
    private val titleBoundProviders = setOf("onepace")

    fun sourceOf(provider: String): Source =
        if (provider in anivexaProviders || provider in titleBoundProviders) Source.ANIVEXA else Source.MIRURO

    /** Exact source choices for one audio category, in the provider's preferred order. */
    fun sourceVariants(provider: String, category: Category): List<SourceVariant> = when {
        category == Category.DUB -> listOf(SourceVariant.DUB)
        sourceOf(provider) == Source.ANIVEXA -> listOf(SourceVariant.GENERIC_SUB)
        else -> miruroSubtitleVariants[provider.lowercase()] ?: listOf(SourceVariant.HARD_SUB)
    }

    /**
     * The subtitle shape [sourceVariants] does not publish for a one-variant Miruro server, to be
     * tried only after the published one has actually been refused.
     *
     * [miruroSubtitleVariants] is a hand-kept mirror of Miruro's capability map, and the map is a
     * per-*provider* statement while 444 is a per-*episode* one. Six servers there are hard-sub
     * only — kiwi, ally, pewe, moo, nun, cog — so when Miruro says "not this episode in that
     * variant" they have no second entry to fall through to and the picker leaves for a different
     * server entirely. Viewers read that as the server being broken: three testers on 2026-09-10
     * reported ally, nun and pewe as "only Dub works", and the resolve logs behind those reports
     * are `ally:hsub:failed`, `nun:hsub:failed` — 444, every one.
     *
     * That the other shape is worth asking for is not a guess. bonk and twin publish both, and on
     * the very episode where their hard sub was refused their own soft sub answered:
     * `bonk:hsub:failed:1127, bonk:ssub:ok:66` and `twin:hsub:failed:1323, twin:ssub:ok:981`.
     * Miruro had the episode; it did not have it burned in.
     *
     * Deliberately not folded into [miruroSubtitleVariants]. That set drives picker ordering and
     * the count of choices on screen, and promoting an unmeasured variant there would put a cell in
     * front of viewers for something that may not exist. This is a resolver-only last resort, and
     * the caller adds it for the requested server alone so an automatic walk pays nothing for it.
     */
    fun unpublishedSubtitleVariant(provider: String, category: Category): SourceVariant? {
        if (category == Category.DUB || sourceOf(provider) != Source.MIRURO) return null
        return when (sourceVariants(provider, category).singleOrNull()) {
            SourceVariant.HARD_SUB -> SourceVariant.SOFT_SUB
            SourceVariant.SOFT_SUB -> SourceVariant.HARD_SUB
            else -> null
        }
    }

    /** Only Miruro has provider-level iframe embeds; Anivexa decides embed per-stream. */
    fun isEmbed(provider: String): Boolean = provider in miruroEmbed
    fun isNative(provider: String): Boolean = !isEmbed(provider)

    fun sortKey(provider: String): Int =
        order.indexOf(provider).let { if (it >= 0) it else Int.MAX_VALUE }

    /** Every server a user can pin in Settings, in the same order the player's picker shows. */
    fun selectableProviders(hideAdult: Boolean): List<String> =
        if (hideAdult) order.filterNot(::isAdultOnly) else order

    fun label(provider: String): String = when (provider) {
        // Gone as a server (2026-10-03), kept so a download made from it still names its source.
        "anibd" -> "AniBD"
        "anidbapp" -> "AniDB App"
        "2dhive" -> "2Dhive"
        "allanime" -> "MKissa (AllAnime)"
        "animepahe" -> "AnimePahe"
        "animeheaven" -> "AnimeHeaven"
        // The only provider here whose own name is in a script most of this app's viewers cannot
        // read, and "稀饭动漫 (XFDM)" tells them nothing about what they are choosing. Named in the
        // style of the pipe servers instead — bee, hop, kiwi, nun — which reach the same fallthrough
        // below. The *id* stays `xfdm`, so diagnostics, cache keys and the catalogue log still name
        // the site being scraped.
        "xfdm" -> "Lili"
        "anichan" -> "AniChan"
        "animeav1" -> "AnimeAV1 (Español)"
        // Named in the AnimeAV1 style, because "AnimeRift" alone says nothing about the one thing
        // that makes it worth a picker row.
        "rift" -> "AnimeRift (Arabic)"
        "anipm" -> "Ani.pm"
        "animeonsen" -> "AnimeOnsen"
        "animekai" -> "AnimeKai"
        "kaa" -> "KickAssAnime"
        "animedekho" -> "AnimeDekho"
        // Named in the AnimeAV1 style: the site's own name says nothing about what makes it worth
        // a picker row, and here that is the Hindi audio every stream carries.
        "kartoons" -> "Kartoons (Hindi)"
        "animekhor" -> "AnimeKhor"
        "toonhub" -> "ToonHub4u"
        "rareanimes" -> "RareAnimes"
        "hentaihaven" -> "Hentai Haven"
        "hstream" -> "HStream"
        "onepace" -> "One Pace"
        "uniquestream" -> "UniqueStream"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
}
