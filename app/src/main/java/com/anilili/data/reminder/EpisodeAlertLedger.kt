package com.anilili.data.reminder

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog

/**
 * One notification per aired episode, whichever path noticed it first.
 *
 * Three things can announce the same episode — the local release alarm, the AniList feed poll and
 * the bell's "airs soon" reminder — and they used to post under three unrelated ids in three
 * channels, so a single episode could leave two or three rows in the shade. Two ways it happened in
 * practice: a logged-in user's local alarm stayed armed whenever the AniList list lookup failed
 * (the filter that is meant to hand those shows to AniList fell back to "none"), and an alarm that
 * cold-started the process was re-armed by `AutomaticReleaseManager.init` a second before its own
 * receiver removed it, so it fired twice.
 *
 * Every path now posts under [TAG] and [notificationId], so any late duplicate replaces the row
 * rather than adding one, and the "is out" paths [claim] the episode first so the loser stays
 * silent instead of sounding a second time.
 */
object EpisodeAlertLedger {
    /** Shared by every per-episode notification; a tag keeps these ids out of other features' way. */
    const val TAG = "episode"

    private const val PREFS = "anilili_episode_alerts"
    private const val KEY = "announced"
    private const val KEEP_SECONDS = 21L * 24 * 60 * 60
    private const val MAX_ENTRIES = 500

    private val lock = Any()

    fun notificationId(mediaId: Int, episode: Int): Int = 31 * mediaId + episode

    /**
     * True for the first caller for this episode, false for every later one. Committed
     * synchronously: the AniList poll can run as the periodic and the immediate job at once.
     */
    fun claim(context: Context, mediaId: Int, episode: Int, source: String): Boolean {
        if (mediaId <= 0 || episode <= 0) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000L
        val key = key(mediaId, episode)
        synchronized(lock) {
            val entries = decode(prefs.getString(KEY, null))
            if (key in entries) {
                DiagnosticsLog.event(
                    "episode_alert",
                    "duplicate.skipped",
                    mapOf("mediaId" to mediaId, "episode" to episode, "source" to source),
                )
                return false
            }
            prefs.edit().putString(KEY, encode(prune(entries + (key to now), now))).commit()
        }
        return true
    }

    internal fun key(mediaId: Int, episode: Int) = "$mediaId:$episode"

    internal fun prune(entries: Map<String, Long>, nowSeconds: Long): Map<String, Long> =
        entries.filterValues { nowSeconds - it < KEEP_SECONDS }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }

    internal fun encode(entries: Map<String, Long>): String =
        entries.entries.joinToString(",") { "${it.key}=${it.value}" }

    internal fun decode(raw: String?): Map<String, Long> =
        raw.orEmpty().split(',').mapNotNull { part ->
            val (key, at) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            at.toLongOrNull()?.let { key to it }
        }.toMap()
}
