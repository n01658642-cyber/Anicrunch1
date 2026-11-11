package com.anilili.data.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-way migration that gives rows saved before [HistoryEntry.watchedEpisodes] existed the
 * history the old "everything below the current episode" inference implied.
 *
 * The case that matters is the one that shipped broken: a viewer who starts a series at episode 7
 * has an empty set for the same reason a pre-v0.1.56 row does, and marking 1–6 watched for them is
 * exactly the guess the field was added to replace. The two are only distinguishable in the stored
 * JSON, so every test here goes through it rather than constructing entries directly.
 */
class LegacyWatchedEpisodesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun entry(
        episodeNumber: Double,
        watched: Set<Double> = emptySet(),
    ) = HistoryEntry(
        anilistId = 185874,
        title = "BLEACH: Thousand-Year Blood War - The Calamity",
        cover = null,
        episodeNumber = episodeNumber,
        provider = "bonk",
        category = "sub",
        watchedEpisodes = watched,
    )

    /** What the store does on load: decode the stored string, then seed against that same string. */
    private fun load(raw: String): List<HistoryEntry> = seedLegacyWatchedEpisodes(
        json.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw),
        raw,
    )

    /** A row as an older build wrote it: every field except the one that did not exist yet. */
    private fun withoutWatchedEpisodes(entries: List<HistoryEntry>): String =
        json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries)
            .replace(Regex(""","watchedEpisodes":\[[^\]]*\]"""), "")

    @Test
    fun `a row written before the field existed keeps the old inference`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 7.0)))

        assertEquals(setOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `starting a series at episode 7 does not mark the six below it`() {
        val raw = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 7.0)),
        )

        assertEquals(emptySet<Double>(), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a row that recorded some episodes is left exactly as it is`() {
        val raw = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 9.0, watched = setOf(1.0, 4.0))),
        )

        assertEquals(setOf(1.0, 4.0), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a legacy row on episode 1 has nothing below it to seed`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 1.0)))

        assertEquals(emptySet<Double>(), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a mix seeds only the rows that predate the field`() {
        val legacy = withoutWatchedEpisodes(listOf(entry(episodeNumber = 4.0)))
            .removeSurrounding("[", "]")
        val current = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 7.0)),
        ).removeSurrounding("[", "]")
        val loaded = load("[$legacy,$current]")

        assertEquals(setOf(1.0, 2.0, 3.0), loaded[0].watchedEpisodes)
        assertEquals(emptySet<Double>(), loaded[1].watchedEpisodes)
    }

    /** Nothing to compare the rows against is not a licence to invent watched episodes. */
    @Test
    fun `entries with no stored json behind them are left alone`() {
        val entries = listOf(entry(episodeNumber = 7.0))

        assertEquals(entries, seedLegacyWatchedEpisodes(entries, null))
        assertEquals(entries, seedLegacyWatchedEpisodes(entries, "not json"))
        assertEquals(entries, seedLegacyWatchedEpisodes(entries, "[]"))
    }
}
