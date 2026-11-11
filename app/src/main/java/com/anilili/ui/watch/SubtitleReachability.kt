package com.anilili.ui.watch

import com.anilili.data.model.SubtitleItem
import com.anilili.playback.NATIVE_PLAYBACK_USER_AGENT
import com.anilili.playback.mediaRequestHeaders
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds the subtitle tracks a provider advertises but its host does not actually have.
 *
 * Providers list tracks from their own metadata rather than from the files, so a `.vtt` that was
 * never uploaded — or has since been swept — still arrives in the source response and is offered
 * to the viewer. Selecting it renders nothing, and if it happens to be the track enabled by
 * default the episode simply has no subtitles, which reads as the app losing them rather than the
 * server never having had them. Media3 does not give up quietly either: it re-requests a failing
 * text track many times before it stops.
 *
 * **Only an answer that means "there is no file at this address" counts.** A HEAD is not the
 * request the player will make, and a host may refuse it (405), rate-limit it, gate it on headers
 * this probe does not reproduce, or simply be slow — none of which says anything about whether the
 * track plays. Dropping on those would take away working subtitles to avoid a broken one, so 404
 * and 410 are the whole rule and every other outcome keeps the track. 403 is deliberately not on
 * the list: a referer this probe got wrong looks exactly like a file that is genuinely forbidden.
 *
 * The result is applied after playback has already started, so nothing here is in front of the
 * first frame; see `WatchViewModel.launchSubtitleReachability`.
 */
internal object SubtitleReachability {

    /**
     * How long the whole sweep may take before it gives up and keeps every track.
     *
     * Short on purpose. Removing a track re-prepares the media item, and while that swap is
     * seamless it still costs a rebuffer — harmless during the second or two an episode spends
     * opening, noticeable once someone is watching. Answering inside that window is also not much
     * to ask of a host that has a file to serve, and one that cannot is exactly the host this must
     * not draw conclusions about.
     */
    const val PROBE_BUDGET_MS = 2_500L

    /** True for the two answers that mean the file is not there, whatever headers were sent. */
    fun isMissing(code: Int): Boolean = code == 404 || code == 410

    /** Only what can be probed: a local import is already on disk, and a data URL carries its own bytes. */
    fun probable(tracks: List<SubtitleItem>): List<SubtitleItem> = tracks
        .filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }
        .distinctBy(SubtitleItem::url)

    /**
     * The URLs among [tracks] whose host reports them missing, empty when nothing can be proven.
     *
     * [streamReferer] is the stream's own, which is what the player sends for subtitle loads too;
     * a track that names its own takes precedence, as the download path already assumes.
     */
    suspend fun missingTracks(
        client: OkHttpClient,
        tracks: List<SubtitleItem>,
        streamReferer: String?,
        budgetMs: Long = PROBE_BUDGET_MS,
    ): Set<String> {
        val probes = probable(tracks)
        if (probes.isEmpty()) return emptySet()
        val probeClient = client.newBuilder()
            .callTimeout(budgetMs, TimeUnit.MILLISECONDS)
            .build()
        return withTimeoutOrNull(budgetMs) {
            coroutineScope {
                probes.map { track ->
                    async(Dispatchers.IO) {
                        val headers = mediaRequestHeaders(
                            referer = track.referer ?: streamReferer,
                            userAgent = NATIVE_PLAYBACK_USER_AGENT,
                            extraHeaders = track.headers,
                        )
                        val request = Request.Builder().url(track.url).head()
                            .header("User-Agent", NATIVE_PLAYBACK_USER_AGENT)
                            .apply { headers.forEach { (name, value) -> header(name, value) } }
                            .build()
                        val code = runCatching {
                            probeClient.newCall(request).execute().use { it.code }
                        }.getOrNull()
                        track.url.takeIf { code != null && isMissing(code) }
                    }
                }.awaitAll().filterNotNull().toSet()
            }
        }.orEmpty()
    }
}
