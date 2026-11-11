package com.anilili.ui.random

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.anilili.data.AppGraph
import com.anilili.data.library.WatchedTitles
import com.anilili.data.model.Media
import com.anilili.data.model.WatchedFilter
import com.anilili.data.recs.Recommendations
import com.anilili.data.recs.TasteStore
import com.anilili.data.remote.RANDOM_POOL_PAGES
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.toFailure
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the pick's card stack shows: the card on top, the one waiting under it, whether a draw is
 * under way, and why one failed.
 */
data class RandomAnimeState(
    val pick: Media? = null,
    val upNext: Media? = null,
    val rolling: Boolean = false,
    val failure: AnililiFailure? = null,
    /** What the viewer can deal from: anything, or one of their own lists. */
    val sources: List<DiceSource> = listOf(DiceSource.ANYWHERE),
    val source: DiceSource = DiceSource.ANYWHERE,
)

/**
 * Home's dice. Held by the activity rather than the popup, so closing and reopening it keeps the
 * deck — and the titles already shown — instead of fetching a fresh page every time.
 */
class RandomAnimeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppGraph.repository

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The source chosen last, kept across launches: a viewer who rolls from their list keeps doing so. */
    private var sourceKey: String = prefs.getString(KEY_SOURCE, null) ?: DiceSource.ANYWHERE_KEY
    private var source: DiceSource = DiceSource.ANYWHERE
    private var deck = anywhereDeck()

    private fun anywhereDeck() = RandomAnimeDeck(
        fetchPage = { page -> repo.randomPool(page).items },
        pageCount = RANDOM_POOL_PAGES,
        // A title already started is not a discovery. Hiding it is the point of rolling.
        skip = { WatchedTitles.hides(it, WatchedFilter.STARTED) },
        // Every third card leans toward the viewer's taste; the rest stay random.
        tasteSource = { Recommendations.diceCandidates() },
    )

    /**
     * One of the viewer's own lists as a deck. Pages are the list in AniList's batches of 50, in id
     * order so each batch's cache key is stable from one roll to the next; the deck picks pages and
     * shuffles within them, which is all the randomness a list needs. Nothing is skipped — "Watching"
     * is all started titles, and that is what was asked for — and no taste picks are mixed in, since
     * a card from outside the list would break the promise of the chip.
     */
    private fun listDeck(ids: List<Int>): RandomAnimeDeck {
        val pages = ids.sorted().chunked(LIST_PAGE)
        return RandomAnimeDeck(
            fetchPage = { page -> pages.getOrNull(page - 1)?.let { repo.mediaByIds(it) }.orEmpty() },
            pageCount = pages.size,
        )
    }

    private val _state = MutableStateFlow(RandomAnimeState())
    val state = _state.asStateFlow()
    private var rollJob: Job? = null

    /**
     * Deals the next title. A draw already under way is left to finish rather than stacked.
     *
     * [tumble] holds the result back long enough for the die to turn over, for the first card of
     * a popup, which has nothing else to look at while it arrives. A swiped card does not want it:
     * the next card is already showing underneath, and a pause there reads as the stack sticking.
     */
    fun roll(tumble: Boolean = false) {
        if (rollJob?.isActive == true) return
        _state.update { it.copy(rolling = true, failure = null) }
        rollJob = viewModelScope.launch {
            val pause = async { if (tumble) delay(MIN_TUMBLE_MS) }
            val result = runCatching { deck.draw() }
            pause.await()
            result.onFailure { it.rethrowIfCancellation() }
            val pick = result.getOrNull()
            _state.update { current ->
                if (pick != null) {
                    current.copy(pick = pick, upNext = deck.upNext, rolling = false, failure = null)
                } else {
                    current.copy(
                        pick = null,
                        upNext = null,
                        rolling = false,
                        failure = (result.exceptionOrNull() ?: IllegalStateException("random pool returned no titles"))
                            .toFailure(AnililiError.RANDOM_PICK_FAILED),
                    )
                }
            }
            if (pick != null) preloadUpNext()
        }
    }

    /**
     * Opening the popup reads the viewer's lists afresh and deals a card, unless it is still holding
     * the one it was closed on. A list that has changed since the last popup gets a new deck; one
     * that has emptied, or been deleted, falls back to anything.
     */
    fun ensurePick() {
        val sources = currentDiceSources()
        val wanted = sources.firstOrNull { it.key == sourceKey } ?: DiceSource.ANYWHERE
        _state.update { it.copy(sources = sources, source = wanted) }
        if (wanted != source) {
            useSource(wanted)
            roll(tumble = true)
        } else if (_state.value.pick == null) {
            roll(tumble = true)
        }
    }

    /** The viewer picked a chip: a fresh deck from that source, and the card on top dealt from it. */
    fun selectSource(key: String) {
        val chosen = _state.value.sources.firstOrNull { it.key == key } ?: return
        if (chosen == source && _state.value.pick != null) return
        sourceKey = chosen.key
        prefs.edit().putString(KEY_SOURCE, chosen.key).apply()
        _state.update { it.copy(source = chosen) }
        useSource(chosen)
        roll(tumble = true)
    }

    /** Swaps the deck, dropping the cards dealt from the old one and any draw still under way. */
    private fun useSource(next: DiceSource) {
        rollJob?.cancel()
        source = next
        deck = if (next.isAnywhere) anywhereDeck() else listDeck(next.ids)
        _state.update { it.copy(pick = null, upNext = null, rolling = false, failure = null) }
    }

    /**
     * The viewer took the pick to its page. The next popup should deal something new rather than
     * offer the title just opened, so the deck moves on behind their back.
     */
    fun opened() {
        _state.value.pick?.takeIf { source.isAnywhere }?.let { TasteStore.recordDice(it.id, opened = true) }
        roll()
    }

    /**
     * The viewer swiped the card away: a light "not this", for the taste model — but only for a
     * discovery. Passing over a title on their own list means "not tonight", not "not for me".
     */
    fun skipped() {
        _state.value.pick?.takeIf { source.isAnywhere }?.let { TasteStore.recordDice(it.id, opened = false) }
        roll()
    }

    /** Warms the card under the top one, so a swipe uncovers a picture rather than a blank. */
    private fun preloadUpNext() {
        val url = deck.upNext?.coverImage?.best ?: return
        val context = getApplication<Application>()
        context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
    }

    private companion object {
        const val MIN_TUMBLE_MS = 450L
        /** AniList's batch size for cards by id, and so one page of a list deck. */
        const val LIST_PAGE = 50
        const val PREFS = "random_pick"
        const val KEY_SOURCE = "source"
    }
}
