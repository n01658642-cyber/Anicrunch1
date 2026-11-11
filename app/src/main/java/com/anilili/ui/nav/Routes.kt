package com.anilili.ui.nav

import android.net.Uri

/** Central route table + typed builders so call sites don't hand-format paths. */
object Routes {
    const val EXTRA_ROUTE = "com.miruronative.extra.ROUTE"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "$SEARCH?studioId={studioId}&studioName={studioName}&genre={genre}&query={query}"
    const val SCHEDULE = "schedule"
    const val MORE = "more"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    /** Top-level destinations that show the bottom navigation bar. */
    val tabRoutes = setOf(HOME, SEARCH, SCHEDULE, MORE, DOWNLOADS, SETTINGS)

    const val NOTIFICATIONS = "notifications"
    const val DOWNLOAD = "download/{downloadId}"
    fun download(downloadId: String) = "download/${Uri.encode(downloadId)}"

    const val DETAIL = "detail/{id}"

    /**
     * The same destination declaring the episode the player's Back should land on.
     *
     * Kept apart from [DETAIL] because that constant is what every comparison in the host reads:
     * the back stack reports a destination's route with its optional arguments stripped by
     * [tabRoute], so a pattern with a query in it would never match.
     */
    const val DETAIL_DESTINATION = "$DETAIL?episode={episode}"

    fun detail(id: Int, episode: String? = null) =
        if (episode.isNullOrBlank()) "detail/$id"
        else "detail/$id?episode=${Uri.encode(episode)}"

    fun studioSearch(studioId: Int, studioName: String) =
        "$SEARCH?studioId=$studioId&studioName=${Uri.encode(studioName)}"

    /** Home genre pills open Search with one genre pre-applied. */
    fun genreSearch(genre: String) = "$SEARCH?genre=${Uri.encode(genre)}"

    /** Search with a term already typed, as an `anilili://search` link asks. */
    fun querySearch(query: String) = "$SEARCH?query=${Uri.encode(query)}"

    /** Maps optional-argument destinations back to their top-level tab route. */
    fun tabRoute(destinationRoute: String?): String? = destinationRoute?.substringBefore('?')

    /** The route pattern a tab's page is registered under — Search's carries optional filters. */
    fun tabDestinationPattern(route: String): String = if (route == SEARCH) SEARCH_DESTINATION else route

    /**
     * Destinations that put a video player on screen, whether it is streaming or a local file.
     *
     * Callers use this to mean "the user is watching", which is deliberately coarser than asking
     * the player whether frames are moving right now: a seek or an episode change stops playback
     * for a second or two without the user having left the player.
     */
    fun hostsPlayer(route: String?): Boolean =
        route != null && (route == tabRoute(WATCH) || route == tabRoute(DOWNLOAD))

    /**
     * Home is the graph start destination. Restoring state while returning to it can resurrect a
     * child route (such as a studio-filtered Search) that was saved above Home instead.
     */
    fun shouldRestoreTabState(route: String): Boolean = route != HOME

    // Watch is addressed by anilistId + provider + category + episode number; the episode list
    // (and each episode's raw pipe id) is pulled from the repository's cache on arrival.
    const val WATCH = "watch/{id}/{provider}/{category}/{episode}" +
        "?showEpisodes={showEpisodes}&autoAdvanced={autoAdvanced}&fullscreen={fullscreen}&startMs={startMs}"
    fun watch(id: Int, provider: String, category: String, episode: String) =
        "watch/$id/$provider/$category/${Uri.encode(episode)}"

    /** Season selection opens the Watch destination with its episode grid visible on TV. */
    fun episodes(id: Int, provider: String, category: String, episode: String) =
        withEpisodeList(watch(id, provider, category, episode))

    internal fun withEpisodeList(watchRoute: String): String = "$watchRoute?showEpisodes=true"

    /**
     * An episode played from [startMs] rather than from the saved position — an
     * `anilili://watch/…?t=` link. A null start is the plain [watch] route.
     */
    fun watchFrom(id: Int, provider: String, category: String, episode: String, startMs: Long?) =
        watchFromStart(watch(id, provider, category, episode), startMs)

    internal fun watchFromStart(watchRoute: String, startMs: Long?): String =
        if (startMs == null || startMs <= 0) watchRoute else "$watchRoute?startMs=$startMs"

    /**
     * The player moving on from a season's last episode to the next season's first, carrying
     * whether auto-play took it and whether the outgoing player was fullscreen.
     */
    fun nextSeason(id: Int, provider: String, category: String, autoAdvanced: Boolean, fullscreen: Boolean) =
        "${watch(id, provider, category, "1")}?autoAdvanced=$autoAdvanced&fullscreen=$fullscreen"

    object Arg {
        const val ID = "id"
        const val STUDIO_ID = "studioId"
        const val STUDIO_NAME = "studioName"
        const val GENRE = "genre"
        const val QUERY = "query"
        const val PROVIDER = "provider"
        const val CATEGORY = "category"
        const val EPISODE = "episode"
        const val SHOW_EPISODES = "showEpisodes"
        const val AUTO_ADVANCED = "autoAdvanced"
        const val FULLSCREEN = "fullscreen"
        const val START_MS = "startMs"
        const val DOWNLOAD_ID = "downloadId"
    }
}
