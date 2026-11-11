package com.anilili.ui.random

import com.anilili.data.model.Media
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The random pick's draw pile: a shuffled page of the pool, dealt one title at a time.
 *
 * A roll costs no request while the pile lasts — a page holds 50 titles — and only an empty pile
 * fetches another page, chosen at random from those not yet dealt. Nothing is shown twice in a
 * session until every page has been through the pile, so "roll again" never hands back the title
 * it just replaced.
 *
 * [skip] leaves out titles not worth offering (ones the viewer has already started), unless that
 * would leave a page with nothing to deal — a viewer who has watched every popular title still gets
 * a pick rather than an error.
 *
 * [tasteSource], when given, supplies titles picked for the viewer's taste, and every [tasteEvery]th
 * card comes from it instead of the pool. Only one in a few: a dice that only ever dealt what it
 * thinks you will like would stop being a dice. A source that comes back empty (no history yet, no
 * graph offline) is not asked again until the pool has gone round once.
 */
class RandomAnimeDeck(
    private val fetchPage: suspend (page: Int) -> List<Media>,
    pageCount: Int,
    private val random: Random = Random.Default,
    private val skip: (Media) -> Boolean = { false },
    private val tasteSource: (suspend () -> List<Media>)? = null,
    private val tasteEvery: Int = 3,
) {
    private val lock = Mutex()
    private var pageCount = pageCount.coerceAtLeast(1)
    private val dealtPages = mutableSetOf<Int>()
    private val shownIds = mutableSetOf<Int>()
    private val pile = ArrayDeque<Media>()
    private val tastePile = ArrayDeque<Media>()
    private var tasteDry = false
    private var dealt = 0
    private var lastDealtId: Int? = null

    /** The title the next [draw] will deal, if the pile already holds one; for preloading its art. */
    val upNext: Media? get() = (if (tasteTurn(dealt)) tastePile.firstOrNull() else null) ?: pile.firstOrNull()

    private fun tasteTurn(count: Int) =
        tasteSource != null && tasteEvery > 0 && count % tasteEvery == tasteEvery - 1

    /** Deals one title, fetching a page when the pile is empty. Null when the pool gave nothing. */
    suspend fun draw(): Media? = lock.withLock {
        if (tasteTurn(dealt)) {
            drawTaste()?.let { media ->
                shownIds += media.id
                lastDealtId = media.id
                dealt++
                return@withLock media
            }
        }
        dealt++
        repeat(MAX_FETCHES_PER_DRAW) {
            if (pile.isEmpty()) refill()
            pile.removeFirstOrNull()?.let { media ->
                shownIds += media.id
                lastDealtId = media.id
                return@withLock media
            }
        }
        null
    }

    /** The next taste pick not already shown, refilling once from the source; null to fall back. */
    private suspend fun drawTaste(): Media? {
        val source = tasteSource ?: return null
        if (tastePile.isEmpty() && !tasteDry) {
            val fresh = runCatching { source() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
                .filterNot { it.id in shownIds || skip(it) }
            if (fresh.isEmpty()) tasteDry = true else tastePile.addAll(fresh)
        }
        while (tastePile.isNotEmpty()) {
            val media = tastePile.removeFirst()
            if (media.id !in shownIds) return media
        }
        return null
    }

    private suspend fun refill() {
        if (dealtPages.size >= pageCount) {
            // The whole pool has been through the pile: start again rather than run dry.
            dealtPages.clear()
            shownIds.clear()
            tasteDry = false
        }
        val page = (1..pageCount).filterNot { it in dealtPages }.random(random)
        val items = fetchPage(page)
        if (items.isEmpty()) {
            // The pool has shrunk below this page since it was measured. Everything past it is
            // empty too, so the range stops short of it from now on.
            pageCount = (page - 1).coerceAtLeast(1)
            dealtPages.removeAll { it > pageCount }
            if (page == 1) dealtPages += page
            return
        }
        dealtPages += page
        val fresh = items.filterNot { it.id in shownIds }
        val wanted = fresh.filterNot(skip).ifEmpty { fresh }
        pile.addAll(wanted.shuffled(random))
        // Going round again can shuffle the card just thrown back on top. A short pool — one of
        // the viewer's own lists — goes round often, and dealing the same title twice running
        // reads as the swipe not having worked.
        if (pile.size > 1 && pile.first().id == lastDealtId) pile.addLast(pile.removeFirst())
    }

    private companion object {
        /** Bounds one draw: a pool that keeps answering empty is down, not merely unlucky. */
        const val MAX_FETCHES_PER_DRAW = 4
    }
}
