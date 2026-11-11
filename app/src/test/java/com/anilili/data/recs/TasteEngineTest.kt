package com.anilili.data.recs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEngineTest {
    private val now = 1_800_000_000_000L
    private val year = 365L * 24 * 60 * 60 * 1000

    /**
     * 1 neighbours 2 and 3 strongly, and 6 — the second season of franchise 5. 4 neighbours 3 and 5.
     * 7 is adult, 8 unreleased; 5 has no neighbours of its own.
     */
    private val graph = NeighbourGraph.parse(
        listOf(
            "#anilili-neighbours\t1\ttest",
            "1\t1\t0\t2:90,3:80,6:70,7:95,8:95",
            "2\t2\t0\t1:90",
            "3\t3\t0\t1:80,2:30",
            "4\t4\t0\t3:60,5:50",
            "5\t5\t0\t",
            "6\t5\t0\t",
            "7\t7\t1\t",
            "8\t8\t2\t",
            "9\t9\t0\t2:100",
        ).joinToString("\n").toByteArray(),
    )

    private fun rating(id: Int, status: String, score: Double = 0.0, at: Long = now) =
        ListRating(id, status, score, at)

    @Test
    fun `liked titles pull their neighbours up`() {
        val recs = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0)))
        assertEquals(listOf(2, 3, 5), recs.map { it.anilistId })
        assertTrue(recs.all { it.becauseId == 1 })
    }

    @Test
    fun `a later season is recommended as its franchise's first entry`() {
        val recs = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0)))
        assertTrue(5 in recs.map { it.anilistId })
        assertFalse(6 in recs.map { it.anilistId })
    }

    @Test
    fun `adult and unreleased titles are left out unless allowed`() {
        val safe = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0))).map { it.anilistId }
        assertFalse(7 in safe)
        assertFalse(8 in safe)
        val all = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0)), hideAdult = false).map { it.anilistId }
        assertTrue(7 in all)
        assertFalse(8 in all)
    }

    @Test
    fun `watched titles and their franchises are never recommended`() {
        val recs = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0)), exclude = setOf(6, 2))
        assertEquals(listOf(3), recs.map { it.anilistId })
    }

    @Test
    fun `a dropped title pushes its neighbours down`() {
        val liked = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0)))
        val withDrop = TasteEngine.recommend(graph, listOf(TasteSignal(1, 1.0), TasteSignal(4, -0.8)))
        val violetBefore = liked.first { it.anilistId == 3 }.score
        val violetAfter = withDrop.first { it.anilistId == 3 }.score
        assertTrue(violetAfter < violetBefore)
    }

    @Test
    fun `nothing is recommended from no signals`() {
        assertEquals(emptyList<Recommendation>(), TasteEngine.recommend(graph, emptyList()))
    }

    @Test
    fun `scores are read against the viewer's own average`() {
        // Everything rated 8 except one 10: the 10 is the standout, the 8s are ordinary.
        val list = listOf(
            rating(1, "COMPLETED", 10.0),
            rating(2, "COMPLETED", 8.0),
            rating(3, "COMPLETED", 8.0),
            rating(4, "COMPLETED", 8.0),
        )
        val weights = TasteEngine.signals(list, emptyList(), nowMs = now).associate { it.anilistId to it.weight }
        assertTrue(weights.getValue(1) > weights.getValue(2))
        assertTrue(weights.getValue(2) > 0)
    }

    @Test
    fun `low scores and drops count against`() {
        val weights = TasteEngine.signals(
            listOf(rating(1, "COMPLETED", 3.0), rating(2, "DROPPED"), rating(3, "PLANNING")),
            emptyList(),
            nowMs = now,
        ).associate { it.anilistId to it.weight }
        assertTrue(weights.getValue(1) < 0)
        assertTrue(weights.getValue(2) < 0)
        assertTrue(weights.getValue(3) in 0.0..0.5)
    }

    @Test
    fun `old watches fade but never vanish`() {
        val fresh = TasteEngine.signals(listOf(rating(1, "COMPLETED", at = now)), emptyList(), nowMs = now).single()
        val old = TasteEngine.signals(listOf(rating(1, "COMPLETED", at = now - 10 * year)), emptyList(), nowMs = now).single()
        assertTrue(old.weight < fresh.weight)
        assertTrue(old.weight > 0.3)
    }

    @Test
    fun `the list service outranks local history for the same title`() {
        val signals = TasteEngine.signals(
            listOf(rating(1, "DROPPED")),
            listOf(PlayedTitle(1, episodesWatched = 12, updatedAtMs = now), PlayedTitle(2, 4, now)),
            nowMs = now,
        ).associate { it.anilistId to it.weight }
        assertTrue(signals.getValue(1) < 0)
        assertTrue(signals.getValue(2) > 0)
    }

    @Test
    fun `dice feedback only fills gaps`() {
        val signals = TasteEngine.signals(
            listOf(rating(1, "COMPLETED")),
            emptyList(),
            feedback = mapOf(1 to TasteEngine.DICE_SKIPPED, 9 to TasteEngine.DICE_OPENED),
            nowMs = now,
        ).associate { it.anilistId to it.weight }
        assertTrue(signals.getValue(1) > 0)
        assertEquals(TasteEngine.DICE_OPENED, signals.getValue(9), 1e-9)
    }

    @Test
    fun `one favourite cannot fill the whole row`() {
        // Seed 1 has three strong neighbours; seed 9 one weaker one. On raw score 9's pick would
        // come last; diversifying lets it in straight after 1's best.
        val g = NeighbourGraph.parse(
            listOf(
                "#anilili-neighbours\t1\ttest",
                "1\t1\t0\t20:100,21:99,22:98",
                "9\t9\t0\t30:80",
            ).joinToString("\n").toByteArray(),
        )
        val recs = TasteEngine.recommend(g, listOf(TasteSignal(1, 1.0), TasteSignal(9, 1.0)))
        assertEquals(listOf(20, 30, 21, 22), recs.map { it.anilistId })
    }

    @Test
    fun `the featured seed is the most recently finished title with neighbours`() {
        val list = listOf(
            rating(1, "COMPLETED", at = now - 1000),
            rating(4, "DROPPED", at = now),
            rating(5, "COMPLETED", at = now), // no neighbours in the graph
        )
        assertEquals(1, TasteEngine.featuredSeed(graph, list, emptyList(), nowMs = now))
        assertNull(TasteEngine.featuredSeed(graph, emptyList(), emptyList(), nowMs = now))
    }

    @Test
    fun `a show still being watched never displaces a finished one`() {
        // The phone case of 2026-10-03: three Watching entries newer than the finished Frieren.
        val list = listOf(
            rating(1, "COMPLETED", at = now - 4 * 86_400_000L),
            rating(2, "CURRENT", at = now),
            rating(3, "CURRENT", at = now - 86_400_000L),
        )
        assertEquals(1, TasteEngine.featuredSeed(graph, list, emptyList(), nowMs = now))
    }

    @Test
    fun `a finished title the viewer disliked is not featured`() {
        val list = listOf(rating(1, "COMPLETED", 2.0, at = now), rating(3, "COMPLETED", at = now - 1000))
        assertEquals(3, TasteEngine.featuredSeed(graph, list, emptyList(), nowMs = now))
    }

    @Test
    fun `with nothing finished on a list, local viewing is the fallback`() {
        val played = listOf(PlayedTitle(2, episodesWatched = 1, updatedAtMs = now), PlayedTitle(3, 5, now - 1000))
        assertEquals(3, TasteEngine.featuredSeed(graph, emptyList(), played, nowMs = now))
        assertEquals(3, TasteEngine.featuredSeed(graph, listOf(rating(2, "CURRENT")), played, nowMs = now))
    }
}
