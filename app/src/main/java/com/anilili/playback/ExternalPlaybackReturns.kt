package com.anilili.playback

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.anilili.data.library.LibraryStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.watch.shouldSyncAniListProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The episode an external player was handed, kept until that player reports back. */
@Serializable
data class ExternalPlaybackSession(
    val anilistId: Int,
    val episodeNumber: Double,
    /** The length the in-app player had measured, for players that report a position alone. */
    val durationMsHint: Long = 0L,
    val totalEpisodes: Int? = null,
    val nextEpisode: Double? = null,
    val nextEpisodeTitle: String? = null,
)

/** What the other player said when it closed, in the terms the watched threshold needs. */
internal data class ExternalPlaybackOutcome(
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
)

/**
 * Where an external player left the episode, and whether that counts as watching it.
 *
 * MX Player and Just Player say `end_by=playback_completion` when the episode ran out, and may send
 * no position with it. Everything else is judged by the same threshold as the in-app player, so an
 * episode is no more or less "watched" for having been played somewhere else. Null when there is
 * nothing to judge — no position, or no length from either side.
 */
internal fun externalPlaybackOutcome(
    session: ExternalPlaybackSession,
    reportedPositionMs: Long?,
    reportedDurationMs: Long?,
    completed: Boolean,
    thresholdPercent: Int? = null,
): ExternalPlaybackOutcome? {
    val durationMs = reportedDurationMs?.takeIf { it > 0 } ?: session.durationMsHint.takeIf { it > 0 }
    if (completed) {
        val length = durationMs ?: return ExternalPlaybackOutcome(0L, 0L, watched = true)
        return ExternalPlaybackOutcome(length, length, watched = true)
    }
    val positionMs = reportedPositionMs?.takeIf { it > 0 } ?: return null
    durationMs ?: return null
    val watched = if (thresholdPercent == null) {
        shouldSyncAniListProgress(session.episodeNumber, positionMs, durationMs)
    } else {
        shouldSyncAniListProgress(session.episodeNumber, positionMs, durationMs, thresholdPercent)
    }
    return ExternalPlaybackOutcome(positionMs, durationMs, watched)
}

/**
 * Carries an episode finished in another player back into history and the list services.
 *
 * Handing an episode to VLC or MX used to be the end of the app's interest in it: the request asked
 * for a result and nothing ever read one, so an episode watched there — streamed or downloaded —
 * never moved Continue Watching and never reached AniList or MAL. The players that report back
 * ([externalPlayerResultPositionMs]) now feed the same path the in-app player uses, including the
 * queued, network-constrained list push.
 *
 * The launcher belongs to the activity, not to the screen that started the hand-off: the automatic
 * hand-off closes its route as soon as the other player opens, and a television often kills this
 * process while the other player runs. The session is in preferences for the same reason, so a
 * result delivered to a recreated activity still knows which episode it is about.
 */
internal object ExternalPlaybackReturns {
    private const val PREFS = "external_playback"
    private const val KEY_SESSION = "session"
    private val json = Json { ignoreUnknownKeys = true }

    private var launcher: ActivityResultLauncher<Intent>? = null
    private lateinit var appContext: Context

    /** Registers the result callback; call from the activity's onCreate, before it starts. */
    fun register(activity: ComponentActivity) {
        appContext = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result.resultCode, result.data)
        }
    }

    /**
     * Starts [intent] for a result about [session]. False when no activity is registered to take
     * the result, and the caller launches it the ordinary way instead.
     *
     * [intent] must not carry `FLAG_ACTIVITY_NEW_TASK`: Android answers a for-result launch into a
     * new task with an immediate cancel, before the other player has even opened.
     */
    fun launch(intent: Intent, session: ExternalPlaybackSession): Boolean {
        val target = launcher ?: return false
        save(session)
        return try {
            target.launch(intent)
            true
        } catch (error: Exception) {
            clear()
            throw error
        }
    }

    private fun onResult(resultCode: Int, data: Intent?) {
        val session = load() ?: return
        clear()
        val position = externalPlayerResultPositionMs(data)
        val duration = externalPlayerResultDurationMs(data)
        val completed = externalPlayerResultCompleted(data)
        DiagnosticsLog.event(
            "External player returned id=${session.anilistId} episode=${session.episodeNumber} " +
                "result=${if (resultCode == Activity.RESULT_OK) "ok" else resultCode} " +
                "positionMs=${position ?: "none"} durationMs=${duration ?: "none"} completed=$completed",
        )
        val outcome = externalPlaybackOutcome(session, position, duration, completed) ?: return
        if (outcome.durationMs > 0) {
            LibraryStore.updateProgress(session.anilistId, session.episodeNumber, outcome.positionMs, outcome.durationMs)
        }
        if (!outcome.watched) return
        val total = session.totalEpisodes
        LibraryStore.markEpisodeWatched(
            anilistId = session.anilistId,
            watchedEpisode = session.episodeNumber,
            nextEpisode = session.nextEpisode,
            nextEpisodeTitle = session.nextEpisodeTitle,
            seriesCompleted = total != null && session.episodeNumber.toInt() >= total,
            totalEpisodes = total,
        )
        val episode = session.episodeNumber.toInt()
        if (episode >= 1 && session.episodeNumber % 1.0 == 0.0 && LibraryStore.canSyncProgress(session.anilistId)) {
            LibraryStore.enqueueProgressSync(session.anilistId, episode, total)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(session: ExternalPlaybackSession) {
        prefs().edit().putString(KEY_SESSION, json.encodeToString(ExternalPlaybackSession.serializer(), session)).apply()
    }

    private fun load(): ExternalPlaybackSession? = prefs().getString(KEY_SESSION, null)?.let {
        runCatching { json.decodeFromString(ExternalPlaybackSession.serializer(), it) }.getOrNull()
    }

    private fun clear() {
        prefs().edit().remove(KEY_SESSION).apply()
    }
}
