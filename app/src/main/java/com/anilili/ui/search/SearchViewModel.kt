package com.anilili.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.library.SearchHistoryStore
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.DiscoverOptions
import com.anilili.data.MIN_SUGGESTION_CHARS
import com.anilili.data.TitleSuggestion
import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import com.anilili.data.model.StudioNode
import com.anilili.data.model.WatchedFilter
import com.anilili.data.settings.SettingsStore
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CatalogChoice(val value: String, val label: String)

/**
 * A misspelled search and what it most likely meant. [applied] when the typed term found nothing
 * and the results on screen are [suggested]'s ("Showing results for"); otherwise the typed term's
 * own thin results are on screen, and [suggested] is only offered ("Did you mean").
 */
data class SearchCorrection(val typed: String, val suggested: String, val applied: Boolean)

class SearchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    // Opens with the viewer's standing choice about watched titles; every other filter starts clear.
    var filters by mutableStateOf(DiscoverFilters(hideWatched = SettingsStore.browseHideWatched.value))
        private set

    val query: String get() = filters.query

    /** Past search terms, most recent first; surfaced when the field is empty. */
    val searchHistory = SearchHistoryStore.history

    /**
     * Titles from the bundled snapshot that match what is being typed right now.
     *
     * Kept apart from [searchHistory] — one is what this viewer searched before, the other is what
     * exists — but both are offered in the same list under the box, because to the person typing
     * they are the same thing: a way to stop typing.
     */
    private val _titleSuggestions = MutableStateFlow<List<TitleSuggestion>>(emptyList())
    val titleSuggestions = _titleSuggestions.asStateFlow()
    private var suggestJob: Job? = null

    /** Applies a term from the recent-searches list as if the user had typed it. */
    fun applyHistoryQuery(term: String) {
        SearchHistoryStore.record(term)
        clearTitleSuggestions()
        update(filters.copy(query = term), delayMs = 0)
    }

    /**
     * Runs a term an `anilili://search` link carried, as if it had been typed and searched. Not
     * added to the recent searches: nobody here typed it.
     */
    fun applyLinkedQuery(term: String) {
        val value = term.trim()
        if (value.isEmpty()) return
        clearTitleSuggestions()
        if (filters.query == value && _appliedFilters.value.query == value) return
        update(filters.copy(query = value), delayMs = 0)
    }

    /**
     * A picked title is a finished search, so it is the *title* that goes into the recent list —
     * not the two letters that happened to surface it, which would be no use to anyone next time.
     */
    fun recordSuggestion(suggestion: TitleSuggestion) {
        SearchHistoryStore.record(suggestion.title)
        clearTitleSuggestions()
    }

    /**
     * The spelling the results were corrected to, or null. Held apart from [state] so the notice
     * and the grid can never disagree about which term the grid is showing.
     */
    private val _correction = MutableStateFlow<SearchCorrection?>(null)
    val correction = _correction.asStateFlow()

    /**
     * The term "Search instead for" asked to be taken as typed. Compared against the query each
     * search runs, so it lapses by itself the moment the box changes.
     */
    private var exactQuery: String? = null

    /** Searches the term as typed, after the results were corrected to something else. */
    fun searchInstead() {
        val shown = _correction.value?.takeIf { it.applied } ?: return
        if (shown.typed != filters.query.trim()) return
        exactQuery = filters.query
        submit(delayMs = 0)
    }

    /** Searches what "Did you mean" offered, as if the viewer had typed it themselves. */
    fun acceptCorrection() {
        val offered = _correction.value ?: return
        SearchHistoryStore.record(offered.suggested)
        clearTitleSuggestions()
        update(filters.copy(query = offered.suggested), delayMs = 0)
    }

    fun removeHistoryQuery(term: String) = SearchHistoryStore.remove(term)
    fun clearHistory() = SearchHistoryStore.clear()

    /**
     * Persist the current query as a real search. Called when the user commits — presses the
     * keyboard's search key, or opens a result — never on every keystroke, so partial terms
     * ("fr", "fri") never pollute the list.
     */
    fun recordCurrentSearch() {
        // A corrected search is remembered the way it found something: "naurto" next time would
        // only have to be corrected again.
        val corrected = _correction.value?.takeIf { it.applied && it.typed == filters.query.trim() }
        SearchHistoryStore.record(corrected?.suggested ?: filters.query)
        // Committing is the viewer saying the term is finished, so the list of ways to finish it
        // has nothing left to offer and would only sit over the results they asked for.
        clearTitleSuggestions()
        commitTypedQuery()
    }

    /**
     * Runs the typed term when "Search as you type" is off and it has not been searched yet. The
     * keyboard's search key calls it through [recordCurrentSearch]; leaving the box calls it too,
     * because a TV keyboard dismissed with Back never sends the search key.
     */
    fun commitTypedQuery() {
        if (SettingsStore.searchAsYouType.value) return
        if (_appliedFilters.value.query == filters.query && _state.value is UiState.Success) return
        submit(delayMs = 0)
    }

    private val _state = MutableStateFlow<UiState<List<Media>>>(UiState.Loading)
    val state = _state.asStateFlow()

    /**
     * The filters that produced the results currently on screen.
     *
     * [filters] is Compose state that changes on every keystroke, so anything reading it
     * recomposes with the typing. The results header wants the query the results are *for*
     * anyway, so it reads this instead and the grid stays still while the user types.
     */
    private val _appliedFilters = MutableStateFlow(DiscoverFilters())
    val appliedFilters = _appliedFilters.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    /**
     * How many titles the last successful request matched in total, or 0 when AniList did not say.
     *
     * The grid only ever holds one page, so this is the only place the size of the answer is
     * knowable. "412 results" is what tells a viewer a filter narrowed things rather than emptied
     * them, and it is why the filter sheet can show a live number while it is still open.
     */
    private val _resultTotal = MutableStateFlow(0)
    val resultTotal = _resultTotal.asStateFlow()

    private val _options = MutableStateFlow(DiscoverOptions(genres = DEFAULT_GENRES))
    val options = _options.asStateFlow()

    /**
     * True when this app is allowing adult content and the signed-in AniList account is not, so an
     * empty answer is AniList's doing rather than anything the viewer can change here.
     */
    private val _aniListHidesAdultContent = MutableStateFlow(false)
    val aniListHidesAdultContent = _aniListHidesAdultContent.asStateFlow()

    var studioQuery by mutableStateOf("")
        private set
    private val _studioSuggestions = MutableStateFlow<List<StudioNode>>(emptyList())
    val studioSuggestions = _studioSuggestions.asStateFlow()
    private val _isStudioLookupLoading = MutableStateFlow(false)
    val isStudioLookupLoading = _isStudioLookupLoading.asStateFlow()

    private var searchJob: Job? = null
    /** What the grid's pages are being fetched for: [appliedFilters], unless spelling corrected it. */
    private var resultsFilters = DiscoverFilters()
    private var loadMoreJob: Job? = null
    private var studioLookupJob: Job? = null
    private var currentPage = 1
    private var hasNextPage = false
    private var requestGeneration = 0

    init {
        viewModelScope.launch {
            runCatching { repo.discoverOptions() }
                .onSuccess { loaded ->
                    _options.value = loaded.copy(
                        genres = (DEFAULT_GENRES + loaded.genres).distinct(),
                    )
                }
        }
        submit(delayMs = 0)
    }

    fun onQueryChange(value: String) {
        if (!SettingsStore.searchAsYouType.value) {
            // No work per keystroke: the grid keeps the results it has until the term is committed.
            // Clearing the box is the exception, since an empty box has nothing left to commit.
            filters = filters.copy(query = value)
            clearTitleSuggestions()
            if (value.isBlank()) submit(delayMs = 0)
            return
        }
        update(filters.copy(query = value), 350)
        suggestTitles(value)
    }

    /**
     * Refresh the autocomplete list for [value].
     *
     * Its own debounce, shorter than the 350 ms the grid waits on, because this answer never leaves
     * the device: the whole point of the list is that it is there before the network could have
     * been asked. Long enough to skip the letters of a fast burst, short enough that a remote's
     * slow typing gets an answer between presses.
     */
    private fun suggestTitles(value: String) {
        suggestJob?.cancel()
        val term = value.trim()
        if (term.length < MIN_SUGGESTION_CHARS) {
            _titleSuggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(90)
            val matches = runCatching { repo.titleSuggestions(term) }.getOrElse {
                it.rethrowIfCancellation()
                emptyList()
            }
            // The snapshot can take seconds to parse on a TV box. By the time it answers the
            // viewer may have typed past this term, and offering matches for a term that is no
            // longer in the box is worse than offering none.
            if (filters.query.trim() == term) _titleSuggestions.value = matches
        }
    }

    private fun clearTitleSuggestions() {
        suggestJob?.cancel()
        _titleSuggestions.value = emptyList()
    }

    fun toggleGenre(value: String) = update(
        filters.copy(genres = filters.genres.toggle(value)),
    )

    /**
     * An exact release year.
     *
     * Clears any decade range: the two are alternatives, and sending both would intersect them
     * down to the single year — which would read as the range chips quietly ignoring themselves.
     */
    fun setYear(value: Int?) = update(filters.copy(year = value, yearFrom = null, yearTo = null))

    fun setSeason(value: String?) = update(filters.copy(season = value))

    /** An inclusive span of years, from a decade chip; see [setYear] for the exclusion. */
    fun setYearRange(from: Int?, to: Int?) =
        update(filters.copy(year = null, yearFrom = from, yearTo = to))

    /**
     * Step the season browsing bar one quarter at a time, rolling the year at Winter.
     *
     * Both fields move in a single [update] so an arrow press is one request rather than two:
     * against AniList's degraded 30/min budget, the difference between browsing and a rate limit.
     *
     * Clamped to the seasons AniList can answer for — [SEASONS_AHEAD] past today, beyond which it is
 * reliably empty —
     * and to the same 1900 floor the year box accepts, so the two controls agree.
     */
    fun stepSeason(delta: Int) {
        if (delta == 0) return
        val today = LocalDate.now()
        val current = seasonDateFor(today.monthValue, today.year)
        val from = filters.season
            ?.takeIf { it in SEASON_ORDER }
            ?.let { SeasonDate(it, filters.year ?: today.year) }
            ?: current
        val target = stepSeasonWithinBounds(from, delta, current) ?: return
        update(
            filters.copy(
                season = target.season,
                year = target.year,
                yearFrom = null,
                yearTo = null,
            ),
        )
    }

    /**
     * Turn on season browsing at the season that is airing now, for the quick chip beside the
     * genre shortcuts. The year comes along because a season is only ever a quarter of one.
     */
    fun startSeasonBrowsing() {
        val today = LocalDate.now()
        val current = seasonDateFor(today.monthValue, today.year)
        if (filters.season == current.season && filters.year == current.year) return
        update(
            filters.copy(
                season = current.season,
                year = current.year,
                yearFrom = null,
                yearTo = null,
            ),
            delayMs = 0,
        )
    }

    /**
     * Turn season browsing off, taking the year it paired with it.
     *
     * The two were set together by every season control, so leaving the year behind would keep the
     * grid narrowed with no season on screen to explain it.
     */
    fun clearSeason() = update(filters.copy(season = null, year = null))

    fun setStatus(value: String?) = update(filters.copy(status = value))
    fun setFormat(value: String?) = update(filters.copy(format = value))
    fun setCountry(value: String?) = update(filters.copy(country = value))
    fun setSource(value: String?) = update(filters.copy(source = value))

    /** An inclusive episode-count bucket; either end may be absent ("52+", "up to 12"). */
    fun setEpisodes(min: Int?, max: Int?) =
        update(filters.copy(episodesMin = min, episodesMax = max))

    fun setMinimumScore(value: Int?) = update(filters.copy(minimumScore = value))
    fun setDubbedOnly(value: Boolean) = update(filters.copy(dubbedOnly = value))
    fun setHideWatched(value: WatchedFilter) = update(filters.copy(hideWatched = value))
    fun setSort(value: String) = update(filters.copy(sort = value))

    /**
     * Cycle one genre off → include → exclude → off.
     *
     * One chip carrying all three states rather than a second "exclude" row of the same list:
     * `genre_not_in` is what makes "everything but" answerable at all, and exclusion sits between
     * include and off so reaching it never passes through the empty state, which would drop the
     * results off a cliff mid-cycle.
     */
    fun cycleGenre(value: String) {
        val next = when {
            value in filters.excludedGenres -> filters.copy(
                excludedGenres = filters.excludedGenres - value,
            )
            value in filters.genres -> filters.copy(
                genres = filters.genres - value,
                excludedGenres = filters.excludedGenres + value,
            )
            else -> filters.copy(genres = filters.genres + value)
        }
        update(next)
    }

    /** The same off → include → exclude → off cycle for tags; see [cycleGenre]. */
    fun cycleTag(value: String) {
        val next = when {
            value in filters.excludedTags -> filters.copy(
                excludedTags = filters.excludedTags - value,
            )
            value in filters.tags -> filters.copy(
                tags = filters.tags - value,
                excludedTags = filters.excludedTags + value,
            )
            else -> filters.copy(tags = filters.tags + value)
        }
        update(next)
    }

    /**
     * Remove one genre chip, from whichever side of the include/exclude line it sits on.
     *
     * The active-filter row under the search box shows both kinds together, and a ✕ on "Not Ecchi"
     * has to mean the exclusion, not the include it would toggle into.
     */
    fun removeGenre(value: String, excluded: Boolean) = update(
        if (excluded) filters.copy(excludedGenres = filters.excludedGenres - value)
        else filters.copy(genres = filters.genres - value),
    )

    /** The tag counterpart of [removeGenre]. */
    fun removeTag(value: String, excluded: Boolean) = update(
        if (excluded) filters.copy(excludedTags = filters.excludedTags - value)
        else filters.copy(tags = filters.tags - value),
    )

    fun onStudioQueryChange(value: String) {
        studioQuery = value
        if (filters.studioId != null && value.trim() != filters.studioName) {
            update(filters.copy(studioId = null, studioName = null))
        }
        studioLookupJob?.cancel()
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        val term = value.trim()
        if (term.length < 2 || term == filters.studioName) return
        studioLookupJob = viewModelScope.launch {
            delay(250)
            _isStudioLookupLoading.value = true
            val matches = runCatching { repo.searchStudios(term) }.getOrElse {
                it.rethrowIfCancellation()
                emptyList()
            }
            if (studioQuery.trim() == term) _studioSuggestions.value = matches
            _isStudioLookupLoading.value = false
        }
    }

    fun selectStudio(studio: StudioNode) {
        val name = studio.name?.trim().orEmpty()
        if (studio.id <= 0 || name.isEmpty()) return
        studioLookupJob?.cancel()
        studioQuery = name
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(filters.copy(studioId = studio.id, studioName = name), delayMs = 0)
    }

    fun selectFirstStudioSuggestion() {
        _studioSuggestions.value.firstOrNull()?.let(::selectStudio)
    }

    fun clearStudio() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(filters.copy(studioId = null, studioName = null), delayMs = 0)
    }

    /** Applies an exact genre received from a home-pill navigation route. */
    fun applyGenreFilter(genre: String) {
        val value = genre.trim()
        if (value.isEmpty()) return
        if (filters.genres == setOf(value)) return
        update(filters.copy(genres = setOf(value)), delayMs = 0)
    }

    /** Applies an exact studio received from a detail-page navigation route. */
    fun applyStudioFilter(studioId: Int, studioName: String) {
        val name = studioName.trim()
        if (studioId <= 0 || name.isEmpty()) return
        if (filters.studioId == studioId && filters.studioName == name) {
            studioQuery = name
            return
        }
        selectStudio(StudioNode(id = studioId, name = name, isAnimationStudio = true))
    }

    fun clearFilters() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        update(DiscoverFilters(query = filters.query))
    }

    fun clearAll() {
        studioLookupJob?.cancel()
        studioQuery = ""
        _studioSuggestions.value = emptyList()
        _isStudioLookupLoading.value = false
        clearTitleSuggestions()
        update(DiscoverFilters())
    }
    fun retry() = submit(delayMs = 0)
    fun refresh() = submit(delayMs = 0, force = true)

    private fun update(updated: DiscoverFilters, delayMs: Long = 120) {
        filters = updated
        // Saved as it changes, Clear included, so the next visit opens the way this one was left.
        if (updated.hideWatched != SettingsStore.browseHideWatched.value) {
            SettingsStore.setBrowseHideWatched(updated.hideWatched)
        }
        submit(delayMs)
    }

    private fun submit(delayMs: Long, force: Boolean = false) {
        requestGeneration++
        val generation = requestGeneration
        searchJob?.cancel()
        loadMoreJob?.cancel()
        _isLoadingMore.value = false
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            // Only the very first load has nothing to show. Refining a query used to tear the
            // whole grid down to a spinner and rebuild it on the next keystroke, which on a TV
            // box cost more per character than the box could paint — typing appeared to be
            // dropped. Keep the previous results up and mark the screen as refreshing instead.
            if (_state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            val requestedFilters = filters
            runCatching { discoverCorrectingSpelling(requestedFilters, force) }
                .onSuccess { answer ->
                    if (generation != requestGeneration) return@onSuccess
                    val page = answer.page
                    currentPage = page.page
                    hasNextPage = page.hasNextPage
                    _resultTotal.value = page.total
                    _appliedFilters.value = requestedFilters
                    resultsFilters = answer.filters
                    _correction.value = answer.correction
                    _state.value = UiState.Success(page.items)
                    refreshAniListAdultBlock(page.items.isEmpty())
                }
                .onFailure {
                    it.rethrowIfCancellation()
                    if (generation != requestGeneration) return@onFailure
                    _correction.value = null
                    // This screen serves two requests through one call. "Search didn't come back"
                    // is a lie when nothing was typed, and "couldn't load the catalogue" is a lie
                    // when something was, so the code follows whichever the viewer actually made.
                    val fallback =
                        if (requestedFilters.query.isBlank()) AnililiError.CATALOG_LOAD_FAILED
                        else AnililiError.SEARCH_FAILED
                    _state.value = UiState.Error(
                        it.toFailure(fallback, attributes = mapOf("filters" to requestedFilters.activeCount)),
                    )
                }
            if (generation == requestGeneration) _isRefreshing.value = false
        }
    }

    private class CorrectedPage(
        val page: MediaPage,
        val filters: DiscoverFilters,
        val correction: SearchCorrection?,
    )

    /**
     * The first page for [requested], corrected for spelling the way a web search does it.
     *
     * AniList returns nothing at all for a misspelled word, so "naurto" used to end on "Nothing
     * matched". When the term finds nothing and the snapshot knows what it most likely meant, the
     * grid shows that instead and says so; when it found a little, the grid keeps it and the
     * likelier spelling is only offered. A term that found a page's worth is never second-guessed,
     * and the spelling is only consulted after a thin answer, never per keystroke.
     */
    private suspend fun discoverCorrectingSpelling(requested: DiscoverFilters, force: Boolean): CorrectedPage {
        val page = repo.discover(requested, page = 1, force = force)
        val typed = requested.query.trim()
        if (typed.isEmpty() || page.items.size >= SPELLING_CHECK_BELOW || exactQuery == requested.query) {
            return CorrectedPage(page, requested, null)
        }
        val suggested = repo.spellingSuggestion(typed) ?: return CorrectedPage(page, requested, null)
        if (page.items.isNotEmpty()) {
            return CorrectedPage(page, requested, SearchCorrection(typed, suggested, applied = false))
        }
        val correctedFilters = requested.copy(query = suggested)
        val corrected = runCatching { repo.discover(correctedFilters, page = 1, force = force) }.getOrElse {
            it.rethrowIfCancellation()
            null
        }
        // A correction that finds nothing either is no help; the plain empty state says enough.
        if (corrected == null || corrected.items.isEmpty()) return CorrectedPage(page, requested, null)
        return CorrectedPage(corrected, correctedFilters, SearchCorrection(typed, suggested, applied = true))
    }

    /**
     * An empty answer while this app is allowing adult content is the one moment AniList's own 18+
     * switch could be the reason: every request carries the account's token and AniList applies
     * that option server-side, so the Hentai chip returns nothing however this app is set. Asked
     * only then — an ordinary search never spends a request on it — and asked again each time
     * rather than remembered, so the answer follows the account instead of going stale the moment
     * the viewer changes it on AniList and comes back.
     */
    private fun refreshAniListAdultBlock(resultsWereEmpty: Boolean) {
        if (!resultsWereEmpty || SettingsStore.hideAdultContent.value || !AuthManager.isLoggedIn) {
            _aniListHidesAdultContent.value = false
            return
        }
        viewModelScope.launch {
            // Only a definite "no" is worth saying. A failed lookup leaves the plain empty state,
            // which is never wrong, rather than blaming an account we could not ask about.
            val displaysAdult = runCatching { repo.aniListDisplaysAdultContent() }
                .onFailure { it.rethrowIfCancellation() }
                .getOrNull()
            _aniListHidesAdultContent.value = displaysAdult == false
        }
    }

    fun loadMore() {
        if (!hasNextPage || _isLoadingMore.value || loadMoreJob?.isActive == true) return
        val existing = (_state.value as? UiState.Success)?.data ?: return
        // The term the grid is showing, which a spelling correction makes different from the box.
        val requestedFilters = resultsFilters
        val typedFilters = filters
        val generation = requestGeneration
        val nextPage = currentPage + 1
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            runCatching { repo.discover(requestedFilters, page = nextPage) }
                .onSuccess { page ->
                    if (generation != requestGeneration || typedFilters != filters) return@onSuccess
                    currentPage = page.page
                    hasNextPage = page.hasNextPage
                    _state.value = UiState.Success((existing + page.items).distinctBy(Media::id))
                }
                .onFailure { it.rethrowIfCancellation() }
            if (generation == requestGeneration) _isLoadingMore.value = false
        }
    }

    companion object {
        /**
         * Below this many results a title search is checked for a misspelling. The same line as
         * the snapshot padding's: a term that found a dozen titles meant what it said.
         */
        private const val SPELLING_CHECK_BELOW = 10

        val DEFAULT_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance",
            "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Mystery", "Thriller",
        )

        val SORTS = listOf(
            CatalogChoice("TRENDING_DESC", "Trending"),
            CatalogChoice("POPULARITY_DESC", "Most popular"),
            CatalogChoice("SCORE_DESC", "Highest rated"),
            CatalogChoice("FAVOURITES_DESC", "Most favorited"),
            CatalogChoice("START_DATE_DESC", "Newest"),
            CatalogChoice("START_DATE", "Oldest"),
            CatalogChoice("EPISODES_DESC", "Most episodes"),
            CatalogChoice("TITLE_ROMAJI", "A–Z"),
        )

        /**
         * AniList's own season names. Its northern-hemisphere quarters are what every seasonal
         * chart means by "Summer 2024", so the labels stay AniList's rather than being reworded
         * into months that would disagree with the charts people compare this against.
         */
        val SEASONS = listOf(
            CatalogChoice("WINTER", "Winter"),
            CatalogChoice("SPRING", "Spring"),
            CatalogChoice("SUMMER", "Summer"),
            CatalogChoice("FALL", "Fall"),
        )

        val STATUSES = listOf(
            CatalogChoice("RELEASING", "Ongoing"),
            CatalogChoice("FINISHED", "Completed"),
            CatalogChoice("NOT_YET_RELEASED", "Upcoming"),
            CatalogChoice("HIATUS", "On hiatus"),
            CatalogChoice("CANCELLED", "Cancelled"),
        )

        val FORMATS = listOf(
            CatalogChoice("TV", "TV"),
            CatalogChoice("MOVIE", "Movie"),
            CatalogChoice("TV_SHORT", "TV short"),
            CatalogChoice("OVA", "OVA"),
            CatalogChoice("ONA", "ONA"),
            CatalogChoice("SPECIAL", "Special"),
            CatalogChoice("MUSIC", "Music"),
        )

        val RATINGS = listOf(60, 70, 80, 90)

        /** ISO 3166-1 origins worth separating: donghua and aeni are otherwise just "anime" here. */
        val COUNTRIES = listOf(
            CatalogChoice("JP", "Japan"),
            CatalogChoice("CN", "China"),
            CatalogChoice("KR", "South Korea"),
        )

        /**
         * What a title was adapted from, as AniList's `MediaSource` names it unless the label
         * differs from the enum a viewer would never see.
         */
        val SOURCES = listOf(
            CatalogChoice("ORIGINAL", "Original"),
            CatalogChoice("MANGA", "Manga"),
            CatalogChoice("LIGHT_NOVEL", "Light novel"),
            CatalogChoice("NOVEL", "Novel"),
            CatalogChoice("VISUAL_NOVEL", "Visual novel"),
            CatalogChoice("VIDEO_GAME", "Game"),
            CatalogChoice("OTHER", "Other"),
        )

        /** Inclusive episode-count buckets, the way people actually shelf a season. */
        val EPISODES = listOf(
            RangeChoice("1–12", 1, 12),
            RangeChoice("13–26", 13, 26),
            RangeChoice("27–51", 27, 51),
            RangeChoice("52+", 52, null),
        )
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * A filter that is an inclusive span rather than a single value.
 *
 * Either end may be absent, which is what a decade list needs at its oldest end ("Earlier") and
 * what an episode list needs at its newest ("52+") — AniList's range arguments are open-ended
 * bounds, so a one-sided span is a request it can answer directly.
 */
data class RangeChoice(val label: String, val from: Int?, val to: Int?)

/**
 * Decades back from the current one, newest first, with a final bucket for everything older.
 *
 * Five decades is what anyone browses by year at all; the year box still reaches further. The last
 * bucket is open on the left so it cannot grow a gap every time January rolls a decade forward.
 */
internal fun decadeChoices(currentYear: Int, count: Int = 5): List<RangeChoice> {
    val currentDecade = currentYear / 10 * 10
    val decades = (0 until count).map { back ->
        val start = currentDecade - back * 10
        RangeChoice("${start}s", start, start + 9)
    }
    return decades + RangeChoice("Earlier", null, decades.last().from!! - 1)
}

/** One season of one year — the unit Browse's season stepper moves. */
internal data class SeasonDate(val season: String, val year: Int) {
    /** Seasons since Winter of year zero, so a step is plain integer arithmetic. */
    val index: Int get() = year * SEASON_ORDER.size + SEASON_ORDER.indexOf(season)
}

/** AniList's season names in broadcast order, which is the order the stepper walks them. */
internal val SEASON_ORDER = listOf("WINTER", "SPRING", "SUMMER", "FALL")

/** The earliest year the season stepper walks to; the year box accepts the same floor. */
internal const val EARLIEST_SEASON_YEAR = 1900

/** The season a calendar month falls in, in AniList's quarters. */
internal fun seasonDateFor(month: Int, year: Int): SeasonDate = when (month) {
    in 1..3 -> SeasonDate("WINTER", year)
    in 4..6 -> SeasonDate("SPRING", year)
    in 7..9 -> SeasonDate("SUMMER", year)
    else -> SeasonDate("FALL", year)
}

/** [delta] seasons on from [from], minting or consuming a year at each Winter it crosses. */
internal fun stepSeasonDate(from: SeasonDate, delta: Int): SeasonDate {
    val moved = from.index + delta
    return SeasonDate(
        season = SEASON_ORDER[Math.floorMod(moved, SEASON_ORDER.size)],
        year = Math.floorDiv(moved, SEASON_ORDER.size),
    )
}

/**
 * [delta] seasons on from [from], or null when the step leaves the range AniList can answer for.
 *
 * More than [SEASONS_AHEAD] past [today] there is reliably nothing to list, and before
 * [EARLIEST_SEASON_YEAR] the answer is always empty — both are places the stepper stops rather
 * than walking into a grid that says "nothing matched" for reasons the viewer cannot see.
 */
internal fun stepSeasonWithinBounds(from: SeasonDate, delta: Int, today: SeasonDate): SeasonDate? {
    val target = stepSeasonDate(from, delta)
    if (target.year < EARLIEST_SEASON_YEAR) return null
    if (target.index > stepSeasonDate(today, SEASONS_AHEAD).index) return null
    return target
}

/**
 * How far past the airing season the stepper walks. Announcements land well before the next
 * season: in September 2026 AniList listed 50+ titles for Winter 2027 but only a dozen for
 * Spring — so two ahead, not one.
 */
internal const val SEASONS_AHEAD = 2

/** "Summer 2024", or the bare season when no year has been paired with it yet. */
internal fun seasonLabel(season: String, year: Int?): String {
    val pretty = season.lowercase().replaceFirstChar { it.uppercase() }
    return if (year == null) pretty else "$pretty $year"
}

/**
 * A decade span as its chip label, derived from the bounds so the row and the active-filter strip
 * cannot disagree: "2020s" for a whole decade, "Before 1960" and "2010+" for the open ends.
 */
internal fun yearRangeLabel(from: Int?, to: Int?): String = when {
    from == null && to == null -> "Any year"
    from == null -> "Before ${to!! + 1}"
    to == null -> "$from+"
    from % 10 == 0 && to == from + 9 -> "${from}s"
    else -> "$from–$to"
}
