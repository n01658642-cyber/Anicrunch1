package com.anilili.data.recs

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How much one title says about the viewer's taste: positive for something they liked, negative for
 * something they gave up on. Roughly -1..2; a plain "completed, no score" is 1.
 */
data class TasteSignal(val anilistId: Int, val weight: Double)

/**
 * One recommended title. [anilistId] is always a franchise's first entry — nobody wants season 3
 * recommended — and [becauseId] is the watched title that contributed most, for "Because you
 * watched …".
 */
data class Recommendation(val anilistId: Int, val score: Double, val becauseId: Int)

/** One title on the viewer's list service, as the taste model reads it. */
data class ListRating(
    val anilistId: Int,
    val status: String,
    /** 0..10, 0 meaning unscored. */
    val score: Double,
    /** Epoch millis of the entry's last change; 0 when unknown. */
    val updatedAtMs: Long,
)

/** One title this device has played. */
data class PlayedTitle(
    val anilistId: Int,
    val episodesWatched: Int,
    val updatedAtMs: Long,
)

/**
 * Turns what the viewer has watched into recommendations, against a [NeighbourGraph].
 *
 * Item-to-item, the way most production recommenders started: a title is recommended in proportion
 * to how strongly it neighbours the titles the viewer liked, minus how strongly it neighbours the
 * ones they dropped. It needs nothing but the viewer's own list and the published graph, so it
 * works signed out, offline, and on the first launch after an update.
 */
object TasteEngine {
    /**
     * The weight each title on the viewer's list carries.
     *
     * Scores are read against the viewer's own habits rather than as absolutes, because people use
     * the scale very differently: someone who gives everything an 8 means "fine" by it, someone
     * whose average is 6 means "loved". Below [LOW_SCORE] is a dislike whatever the average.
     *
     * The list service wins over this device's history for a title on both: "Completed, 9/10"
     * says more than "played six episodes here".
     */
    fun signals(
        list: Collection<ListRating>,
        played: Collection<PlayedTitle>,
        feedback: Map<Int, Double> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<TasteSignal> {
        val scored = list.map { it.score }.filter { it > 0 }
        val mean = if (scored.isEmpty()) 0.0 else scored.average()
        val spread = if (scored.size < 2) 1.0 else
            sqrt(scored.sumOf { (it - mean).pow(2) } / scored.size).coerceAtLeast(1.0)

        val weights = linkedMapOf<Int, Double>()
        list.forEach { entry ->
            val base = statusWeight(entry, mean, spread) ?: return@forEach
            weights[entry.anilistId] = base * recency(entry.updatedAtMs, nowMs)
        }
        played.forEach { title ->
            if (title.anilistId in weights) return@forEach
            val base = when {
                title.episodesWatched >= 3 -> 0.9
                title.episodesWatched >= 1 -> 0.45
                // Opened and played into, but never past the watched threshold.
                else -> 0.15
            }
            weights[title.anilistId] = base * recency(title.updatedAtMs, nowMs)
        }
        // Dice swipes and the like: light, and only where nothing stronger is known.
        feedback.forEach { (id, weight) -> if (id !in weights) weights[id] = weight }
        return weights.map { (id, weight) -> TasteSignal(id, weight) }.filter { it.weight != 0.0 }
    }

    private fun statusWeight(entry: ListRating, mean: Double, spread: Double): Double? {
        val score = entry.score
        val relative = if (score > 0) ((score - mean) / spread).coerceIn(-2.0, 2.0) else 0.0
        return when (entry.status.uppercase()) {
            "COMPLETED" -> when {
                score <= 0 -> 1.0
                score < LOW_SCORE -> -0.6
                else -> (1.0 + 0.45 * relative).coerceIn(0.2, 2.0)
            }
            "REPEATING" -> 1.6
            "CURRENT" -> if (score > 0 && score < LOW_SCORE) -0.3 else 0.8 + 0.2 * relative.coerceAtLeast(0.0)
            "PAUSED" -> 0.25
            "DROPPED" -> if (score >= LOW_SCORE + 2) 0.0 else -0.8
            // Wanting to watch something says a little about taste; it has not been tried yet.
            "PLANNING" -> 0.25
            else -> null
        }
    }

    /**
     * Older watches count for less, but never for nothing — a favourite from five years ago still
     * describes the viewer. Unknown dates count in full.
     */
    private fun recency(updatedAtMs: Long, nowMs: Long): Double {
        if (updatedAtMs <= 0) return 1.0
        val years = (nowMs - updatedAtMs).coerceAtLeast(0) / YEAR_MS
        return 0.5.pow(years).coerceAtLeast(MIN_RECENCY)
    }

    /**
     * The best titles to recommend, best first.
     *
     * Titles in [exclude] — and every other entry of their franchises — are never recommended, and
     * neither are the signals' own titles. Candidates are scored per franchise, so a series with
     * five seasons cannot outvote a film on headcount, and each watched franchise counts once
     * however many of its seasons are on the list.
     *
     * The ranking is then spread out: each further pick credited to the same watched title is worth
     * a little less, so one huge favourite cannot fill the whole row with its neighbours.
     */
    fun recommend(
        graph: NeighbourGraph,
        signals: List<TasteSignal>,
        exclude: Set<Int> = emptySet(),
        hideAdult: Boolean = true,
        limit: Int = 40,
    ): List<Recommendation> {
        if (signals.isEmpty()) return emptyList()
        val excludedRoots = HashSet<Int>()
        exclude.forEach { excludedRoots += graph.rootOf(it) }
        signals.forEach { excludedRoots += graph.rootOf(it.anilistId) }

        // Each watched franchise speaks once, with its strongest member's voice.
        val seeds = signals.groupBy { graph.rootOf(it.anilistId) }.map { (root, members) ->
            val strongest = members.maxBy { abs(it.weight) }
            Seed(root = root, weight = strongest.weight, members = members.map { it.anilistId }, displayId = strongest.anilistId)
        }

        val totals = HashMap<Int, Double>()
        val bestContribution = HashMap<Int, Double>()
        val because = HashMap<Int, Int>()
        val local = HashMap<Int, Double>()
        seeds.forEach { seed ->
            local.clear()
            seed.members.forEach { member ->
                graph.forEachNeighbour(member) { neighbour, weight ->
                    val root = graph.rootOf(neighbour)
                    if (root in excludedRoots) return@forEachNeighbour
                    if (graph.isUnreleased(neighbour)) return@forEachNeighbour
                    if (hideAdult && (graph.isAdult(neighbour) || graph.isAdult(root))) return@forEachNeighbour
                    val strength = weight / 100.0
                    if (strength > (local[root] ?: 0.0)) local[root] = strength
                }
            }
            local.forEach { (root, strength) ->
                val contribution = seed.weight * strength
                totals[root] = (totals[root] ?: 0.0) + contribution
                if (contribution > (bestContribution[root] ?: 0.0)) {
                    bestContribution[root] = contribution
                    because[root] = seed.displayId
                }
            }
        }

        val ranked = totals.entries
            .filter { it.value > 0 && it.key in because }
            .sortedByDescending { it.value }
            .take(limit * CANDIDATE_POOL_FACTOR)
            .map { Recommendation(it.key, it.value, because.getValue(it.key)) }
            .toMutableList()
        return diversify(ranked, limit)
    }

    /**
     * The neighbours of one title, for a "Because you watched X" row: [recommend] with a single
     * seed, so the same exclusions and franchise collapsing apply.
     */
    fun because(
        graph: NeighbourGraph,
        seedId: Int,
        exclude: Set<Int> = emptySet(),
        hideAdult: Boolean = true,
        limit: Int = 30,
    ): List<Recommendation> =
        recommend(graph, listOf(TasteSignal(seedId, 1.0)), exclude, hideAdult, limit)

    /**
     * The seed worth a "Because you watched" row: the most recently **finished** title the viewer
     * did not dislike, that the graph has neighbours for.
     *
     * Finished only, so the row changes when the viewer completes something rather than drifting
     * with whatever they are partway through. It used to be "the latest title whose weight cleared
     * 0.8", which a two-day-old unscored Watching entry missed by 0.003 — so the choice between a
     * show in progress and a finished one hung on its age in days.
     *
     * A viewer with nothing finished on a list service (signed out, or a new account) falls back
     * to the latest title they have played well into on this device.
     */
    fun featuredSeed(
        graph: NeighbourGraph,
        list: Collection<ListRating>,
        played: Collection<PlayedTitle>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int? {
        val hasNeighbours = { id: Int -> graph.neighbourRange(id)?.isEmpty() == false }
        val weights = signals(list, played, nowMs = nowMs).associate { it.anilistId to it.weight }
        val finished = list
            .filter { it.status.uppercase() in FINISHED_STATUSES && (weights[it.anilistId] ?: 0.0) > 0 }
            .sortedByDescending { it.updatedAtMs }
            .map { it.anilistId }
            .firstOrNull(hasNeighbours)
        if (finished != null) return finished
        val onList = list.mapTo(HashSet()) { it.anilistId }
        return played
            .filter { it.anilistId !in onList && it.episodesWatched >= FALLBACK_MIN_EPISODES }
            .sortedByDescending { it.updatedAtMs }
            .map { it.anilistId }
            .firstOrNull(hasNeighbours)
    }

    private fun diversify(ranked: MutableList<Recommendation>, limit: Int): List<Recommendation> {
        val credited = HashMap<Int, Int>()
        val picked = ArrayList<Recommendation>(limit)
        while (picked.size < limit && ranked.isNotEmpty()) {
            var bestIndex = 0
            var bestValue = Double.NEGATIVE_INFINITY
            ranked.forEachIndexed { index, candidate ->
                val value = candidate.score * REPEAT_SEED_DECAY.pow(credited[candidate.becauseId] ?: 0)
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            val choice = ranked.removeAt(bestIndex)
            credited[choice.becauseId] = (credited[choice.becauseId] ?: 0) + 1
            picked += choice
        }
        return picked
    }

    private data class Seed(val root: Int, val weight: Double, val members: List<Int>, val displayId: Int)

    /** Scores under this are a dislike on any viewer's scale. */
    private const val LOW_SCORE = 5.0
    private const val YEAR_MS = 365.0 * 24 * 60 * 60 * 1000
    private const val MIN_RECENCY = 0.35
    private const val REPEAT_SEED_DECAY = 0.8
    private const val CANDIDATE_POOL_FACTOR = 4
    private val FINISHED_STATUSES = setOf("COMPLETED", "REPEATING")
    private const val FALLBACK_MIN_EPISODES = 3

    /** Weight of a title opened from the dice, and of one swiped away. */
    const val DICE_OPENED = 0.3
    const val DICE_SKIPPED = -0.1
}
