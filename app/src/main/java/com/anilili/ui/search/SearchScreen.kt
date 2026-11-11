package com.anilili.ui.search

import com.anilili.ui.theme.canvasColor
import com.anilili.ui.theme.appCanvas
import android.view.KeyEvent as AndroidKeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.paneTitle
import com.anilili.ui.components.PageMenuChoice
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.data.TitleSuggestion
import com.anilili.data.model.WatchedFilter
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.DiscoverOptions
import com.anilili.data.model.Media
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.TvTextInputType
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AnimeCard
import com.anilili.ui.components.AppChromeListBottomGap
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.PageTitleBar
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.home.TvCentredScrollSpec
import com.anilili.ui.home.TvMediaCard
import java.time.Year
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.backdropBlur
import com.anilili.ui.components.glassEnabled
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import com.anilili.ui.components.PageMenuSectionLabel
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightSettingRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
    tvFieldFocusTarget: TvFocusTarget? = null,
    initialStudioId: Int? = null,
    initialStudioName: String? = null,
    initialGenre: String? = null,
    initialQuery: String? = null,
) {
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val isLoadingMore by vm.isLoadingMore.collectAsState()
    val options by vm.options.collectAsState()
    // Deliberately not vm.filters: reading that Compose state here would recompose this whole
    // screen — grid included — on every keystroke.
    val appliedFilters by vm.appliedFilters.collectAsState()
    val aniListHidesAdultContent by vm.aniListHidesAdultContent.collectAsState()
    val resultTotal by vm.resultTotal.collectAsState()
    val correction by vm.correction.collectAsState()
    val device = LocalAppDeviceProfile.current
    val gridState = rememberLazyGridState()
    var showFilters by remember { mutableStateOf(false) }
    var tvHeaderExpanded by remember { mutableStateOf(true) }
    var restoreTvHeaderFocus by remember { mutableStateOf(false) }
    val tvSeasonBarFocus = remember { FocusRequester() }
    val tvSeasonBarShown = device.isTv && appliedFilters.season != null
    // Reveal the search + categories bar when the grid is at the top or being dragged upward,
    // and tuck it away while scrolling down so results get the full screen height. On TV, moving
    // focus into a result collapses the whole block; Up from the first result row restores it.
    val scrollingUp = gridState.isScrollingUp()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    val headerHeightPx = with(density) { headerHeight.roundToPx() }
    // Read from the layout rather than firstVisibleItemIndex: with top padding the grid counts its
    // short "Discover anime" row as scrolled past once it enters the padding, about 90px in, while
    // the rest of the reserved room is still on screen. The row's own offset is how far the grid
    // has moved, and it stays in the layout for as long as any of that room is showing.
    val headerRoomOnScreen by remember(gridState, headerHeightPx) {
        derivedStateOf {
            val first = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
            first != null && first.index == 0 && first.offset.y > -headerHeightPx
        }
    }
    val topBarVisible = searchHeaderVisible(
        isTv = device.isTv,
        tvHeaderExpanded = tvHeaderExpanded,
        scrollingUp = scrollingUp,
        // Only where the bar floats: on TV it stacks above the grid and reserves nothing.
        headerRoomOnScreen = !device.isTv && headerRoomOnScreen,
    )
    val tvSearchFieldAttached = tvFieldFocusTarget?.isAttached == true
    // The bar floats over the grid rather than standing above it in a column: stacked, every
    // reveal re-measured the screen and shoved the whole result grid down mid-scroll. Floating, it
    // slides in over rows that never move, and the grid buys its clearance as *scroll* padding —
    // which scrolls away with everything else, so nothing is left behind when the bar retreats.
    // TV drives the same bar from focus and collapses it for good once a result is focused, so
    // there it keeps stacking; floating would leave a dead band where the bar used to be.
    val headerFloats = !device.isTv
    // What the floating header frosts: the result grid passing under it. Null with Browse's glass
    // switched off, and on TV, where the header does not float.
    val browseBackdrop = if (headerFloats && glassEnabled(GlassSection.BROWSE)) remember { HazeState() } else null
    val reservedHeaderHeight = if (headerFloats) headerHeight else 0.dp
    // The season bar floats over the grid on a phone, so the last row buys its clearance the way
    // the first row buys the search bar's. On TV it lives in the header and claims its room there.
    val seasonBarClearance =
        if (!device.isTv && appliedFilters.season != null) SeasonBarHeight + 12.dp else 0.dp

    // A floating bar stays composed while it is tucked away, where the column it replaced was
    // removed outright — and that removal is what used to drop the field's focus and take the
    // keyboard down with it. Scrolling the results with a keyboard still covering them is not the
    // behaviour anyone asked to change, so the bar hands focus back itself.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(headerFloats, topBarVisible) {
        if (!headerFloats || topBarVisible) return@LaunchedEffect
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(
        device.isTv,
        topBarVisible,
        restoreTvHeaderFocus,
        tvSearchFieldAttached,
    ) {
        if (!device.isTv || !topBarVisible || !restoreTvHeaderFocus) return@LaunchedEffect
        val target = tvFieldFocusTarget ?: return@LaunchedEffect
        repeat(TV_FOCUS_REQUEST_ATTEMPTS) {
            if (target.isAttached && runCatching { target.requester.requestFocus() }.isSuccess) {
                restoreTvHeaderFocus = false
                return@LaunchedEffect
            }
            withFrameNanos {}
        }
    }

    LaunchedEffect(initialGenre) {
        initialGenre?.let(vm::applyGenreFilter)
    }

    LaunchedEffect(initialQuery) {
        initialQuery?.let(vm::applyLinkedQuery)
    }

    LaunchedEffect(initialStudioId, initialStudioName) {
        val studioId = initialStudioId ?: return@LaunchedEffect
        val studioName = initialStudioName ?: return@LaunchedEffect
        vm.applyStudioFilter(studioId, studioName)
    }

    val header = @Composable {
        Column(
            Modifier
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                // A floating bar has posters passing beneath it, and its own controls do not cover
                // every pixel of it: a tap on the gap beside a category chip would otherwise reach
                // the card underneath and open something nobody aimed at. Taps stop here; drags
                // still reach the grid, so the bar remains a place to scroll the results from.
                .then(
                    if (headerFloats) {
                        // A floating bar carries the status-bar inset itself. Tucking it away
                        // slides it up by its own measured height, so an inset held by the screen
                        // instead would leave exactly that strip of bar behind — a row of
                        // half-chips stranded over the clock. Owned here, the strip is part of
                        // what slides, and the grid reserves it along with the rest.
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { } }
                            .then(
                                if (browseBackdrop != null) {
                                    Modifier.backdropBlur(
                                        browseBackdrop,
                                        tint = MaterialTheme.colorScheme.background.copy(alpha = .72f),
                                        radius = 24.dp,
                                    )
                                } else {
                                    Modifier.appCanvas()
                                },
                            )
                            .statusBarsPadding()
                    } else {
                        Modifier
                    },
                ),
        ) {
            SearchTopBar(
                translucent = browseBackdrop != null,
                vm = vm,
                options = options,
                onOpenFilters = { showFilters = true },
                tvFieldFocusTarget = tvFieldFocusTarget,
                // A picked suggestion is the title itself, so it opens rather than searching for
                // its own name — the whole saving is the trip through the results it skips.
                onOpenTitle = { suggestion ->
                    vm.recordSuggestion(suggestion)
                    onAnimeClick(suggestion.id)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .7f))
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // The floating bar holds this itself, and the grid scrolls the whole window the way
            // Home's does — rows pass under the status bar as the bar retreats.
            .then(if (headerFloats) Modifier else Modifier.statusBarsPadding())
            .appCanvas(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!headerFloats) {
                AnimatedVisibility(visible = topBarVisible) { header() }
            }
            if (tvSeasonBarShown) {
                TvSeasonBarHost(
                    vm = vm,
                    focusRequester = tvSeasonBarFocus,
                    onUp = {
                        if (tvHeaderExpanded) {
                            false
                        } else {
                            tvHeaderExpanded = true
                            restoreTvHeaderFocus = true
                            true
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = device.pagePadding)
                        .padding(top = 10.dp, bottom = 4.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (browseBackdrop != null) Modifier.hazeSource(browseBackdrop) else Modifier),
            ) {
                when (val current = state) {
                    is UiState.Loading -> LoadingBox(Modifier.padding(top = reservedHeaderHeight))
                    is UiState.Error -> ErrorBox(
                        current.failure,
                        vm::retry,
                        Modifier.padding(top = reservedHeaderHeight),
                    )
                    is UiState.Success -> PullRefreshContainer(
                        isRefreshing = isRefreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                        // The grid starts at the top of the window with the bar drawn over it, so
                        // without this the puck spins behind the bar and a pull looks ignored.
                        indicatorTopPadding = reservedHeaderHeight,
                    ) {
                        ResultsGrid(
                            results = current.data,
                            filters = appliedFilters,
                            resultTotal = resultTotal,
                            correction = correction,
                            onSearchInstead = vm::searchInstead,
                            onAcceptCorrection = vm::acceptCorrection,
                            // A tapped result is proof the query mattered, so record it before leaving.
                            onAnimeClick = { id -> vm.recordCurrentSearch(); onAnimeClick(id) },
                            gridState = gridState,
                            headerPadding = reservedHeaderHeight,
                            seasonBarClearance = seasonBarClearance,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = vm::loadMore,
                            onTvResultFocused = {
                                if (device.isTv) tvHeaderExpanded = false
                            },
                            onTvReturnToHeader = {
                                if (device.isTv) {
                                    // A season browser's next move is another season, so Up from
                                    // the first row stops at the stepper; one more Up from there
                                    // reopens the header.
                                    val onStepper = tvSeasonBarShown &&
                                        runCatching { tvSeasonBarFocus.requestFocus() }.isSuccess
                                    if (!onStepper) {
                                        tvHeaderExpanded = true
                                        restoreTvHeaderFocus = true
                                    }
                                }
                            },
                            onRetryWithAdultContent = vm::refresh,
                            aniListHidesAdultContent = aniListHidesAdultContent,
                            onSortChange = vm::setSort,
                        )
                    }
                }
            }
        }
        // Season browsing stays within thumb reach on a phone, floating over the grid the way the
        // navigation capsule does. Its own composable so this screen never subscribes to
        // vm.filters — see the note beside appliedFilters.
        if (!device.isTv && appliedFilters.season != null) {
            SeasonBarHost(
                vm = vm,
                onOpenFilters = { showFilters = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = device.pagePadding)
                    .padding(bottom = LocalAppChromeBottomInset.current + 12.dp),
            )
        }
        if (headerFloats) ScrollAwareTopBar(visible = topBarVisible) { header() }
    }

    if (showFilters) {
        // FilterSheet reads vm.filters itself so this screen never subscribes to it.
        FilterSheet(
            options = options,
            vm = vm,
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    vm: SearchViewModel,
    options: DiscoverOptions,
    onOpenFilters: () -> Unit,
    tvFieldFocusTarget: TvFocusTarget?,
    onOpenTitle: (TitleSuggestion) -> Unit,
    /** Leave the fill to the frosted glass behind the bar instead of painting over it. */
    translucent: Boolean = false,
) {
    val device = LocalAppDeviceProfile.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val filterFocusRequester = remember { FocusRequester() }
    Surface(
        color = if (translucent) Color.Transparent else canvasColor(),
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 0.dp,
    ) {
        val history by vm.searchHistory.collectAsState()
        val titles by vm.titleSuggestions.collectAsState()
        var fieldFocused by remember { mutableStateOf(false) }
        // Past searches surfaced as an autocomplete list while the field is focused: everything
        // recent when it's empty, narrowing to matches as the user types (the exact current term
        // drops out — it's already in the box). TV shows them as a horizontal chip row so D-pad
        // can reach them; mobile shows a vertical dropdown only while the field is focused.
        val suggestions = remember(history, vm.query, fieldFocused, device.isTv) {
            if (!device.isTv && !fieldFocused) {
                emptyList()
            } else {
                val term = vm.query.trim()
                history.filter { it.contains(term, ignoreCase = true) && !it.equals(term, ignoreCase = true) }
            }
        }
        // Titles follow the same focus rule as the history above them: on mobile the dropdown is
        // over the results, so it only stands while the viewer is in the box; on TV nothing is
        // covered and D-pad needs somewhere to land, so it stays.
        val titleMatches = if (device.isTv || fieldFocused) titles else emptyList()
        // Phone only: TV already folds this whole header away once focus reaches the results.
        val collapsed = !device.isTv && SettingsStore.browseHeaderCollapsed.collectAsState().value
        Column(Modifier.fillMaxWidth()) {
            // The heading is the shared page title rather than a hand-rolled one, so Browse is set
            // at the same size and weight as Schedule, My List and Downloads. Written out here it
            // had drifted to headlineSmall, which left this one tab wearing a visibly smaller name
            // than every other tab beside it in the same bar.
            PageTitleBar(
                title = "Browse",
                subtitle = if (collapsed) null else "Find your next obsession",
                background = if (translucent) Color.Transparent else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val extraFocus1 = rememberFocusFill()
                        TextButton(
                            colors = extraFocus1.buttonColors(ButtonDefaults.textButtonColors()),onClick = vm::clearAll, modifier = Modifier.focusFill(extraFocus1, RoundedCornerShape(24.dp)),) { Text("Reset") }
                        if (!device.isTv) {
                            IconButton(
                                onClick = {
                                    // The field folds away with the rest, so its keyboard goes too.
                                    if (!collapsed) {
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                    }
                                    SettingsStore.setBrowseHeaderCollapsed(!collapsed)
                                },
                            ) {
                                Icon(
                                    if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                    contentDescription = if (collapsed) "Show search and filters" else "Hide search and filters",
                                )
                            }
                        }
                    }
                },
            )
            // Folded, the header is its title row alone: Reset and the chevron stay, and the
            // search box, filter button and chip rows give their height back to the results.
            AnimatedVisibility(visible = !collapsed) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding)
                        .padding(bottom = 12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (device.isTv) {
                            TvNativeTextField(
                                value = vm.query,
                                onValueChange = vm::onQueryChange,
                                hint = "Search anime",
                                modifier = Modifier.weight(1f).widthIn(min = 0.dp, max = 720.dp),
                                imeAction = EditorInfo.IME_ACTION_SEARCH,
                                onImeAction = {
                                    vm.recordCurrentSearch()
                                    focusManager.moveFocus(FocusDirection.Down)
                                },
                                onMoveDown = {
                                    vm.commitTypedQuery()
                                    focusManager.moveFocus(FocusDirection.Down)
                                },
                                onMoveRight = {
                                    vm.commitTypedQuery()
                                    if (runCatching { filterFocusRequester.requestFocus() }.isFailure) {
                                        focusManager.moveFocus(FocusDirection.Right)
                                    }
                                },
                                tvFocusTarget = tvFieldFocusTarget,
                            )
                        } else {
                            OutlinedTextField(
                                value = vm.query,
                                onValueChange = vm::onQueryChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(min = 0.dp, max = 720.dp)
                                    .fillMaxWidth()
                                    .onFocusChanged {
                                        if (fieldFocused && !it.isFocused) vm.commitTypedQuery()
                                        fieldFocused = it.isFocused
                                    },
                                placeholder = { Text("Search anime…") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (vm.query.isNotEmpty()) {
                                        val extraFocus2 = rememberFocusFill()
                                        IconButton(
                                            colors = extraFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = { vm.onQueryChange("") }, modifier = Modifier.size(40.dp).focusFill(extraFocus2, CircleShape)) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    vm.recordCurrentSearch()
                                    keyboard?.hide()
                                    focusManager.moveFocus(FocusDirection.Down)
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                ),
                            )
                        }
                        val controlFocus1 = rememberFocusFill()
                        Button(
                            colors = controlFocus1.buttonColors(ButtonDefaults.buttonColors()),
                            onClick = onOpenFilters,
                            contentPadding = PaddingValues(horizontal = 13.dp),
                            modifier = Modifier
                                .focusRequester(filterFocusRequester)
                                .height(56.dp)
                                .focusFill(controlFocus1, RoundedCornerShape(50)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Open filters")
                            if (vm.filters.activeCount > 0) {
                                Text(" ${vm.filters.activeCount}", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (device.isTv) {
                        TvSearchSuggestions(
                            history = suggestions,
                            titles = titleMatches,
                            onPick = { term ->
                                vm.applyHistoryQuery(term)
                                focusManager.moveFocus(FocusDirection.Down)
                            },
                            onOpenTitle = onOpenTitle,
                            onRemove = vm::removeHistoryQuery,
                        )
                    } else {
                        SearchSuggestions(
                            history = suggestions,
                            titles = titleMatches,
                            onPick = { term ->
                                vm.applyHistoryQuery(term)
                                keyboard?.hide()
                                focusManager.clearFocus()
                            },
                            onOpenTitle = { suggestion ->
                                keyboard?.hide()
                                focusManager.clearFocus()
                                onOpenTitle(suggestion)
                            },
                            onRemove = vm::removeHistoryQuery,
                        )
                    }

                    Text(
                        "Categories",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        item(key = "season") {
                            val extraFocusSeason = rememberFocusFill()
                            FilterChip(
                                colors = extraFocusSeason.chipColors(FilterChipDefaults.filterChipColors()),
                                selected = vm.filters.season != null,
                                onClick = {
                                    if (vm.filters.season != null) vm.clearSeason() else vm.startSeasonBrowsing()
                                },
                                label = { Text("Season") },
                                leadingIcon = {
                                    Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.focusFill(extraFocusSeason, RoundedCornerShape(8.dp)),
                            )
                        }
                        item(key = "format-movie") {
                            val extraFocus3 = rememberFocusFill()
                            FilterChip(
                                colors = extraFocus3.chipColors(FilterChipDefaults.filterChipColors()),
                                selected = vm.filters.format == "MOVIE",
                                onClick = { vm.setFormat(if (vm.filters.format == "MOVIE") null else "MOVIE") },
                                label = { Text("Movies") }, modifier = Modifier.focusFill(extraFocus3, RoundedCornerShape(8.dp)),)
                        }
                        items(options.genres.take(14), key = { it }) { genre ->
                            val extraFocus4 = rememberFocusFill()
                            FilterChip(
                                colors = extraFocus4.chipColors(FilterChipDefaults.filterChipColors()),
                                selected = genre in vm.filters.genres,
                                onClick = { vm.toggleGenre(genre) },
                                label = { Text(genre) }, modifier = Modifier.focusFill(extraFocus4, RoundedCornerShape(8.dp)),)
                        }
                    }
                    ActiveFilterRow(vm = vm)
                    // TV's season stepper is not in here: it has its own strip between this header and
                    // the grid (TvSeasonBarHost), so it stays on screen after the header folds away.
                }
            }
        }
    }
}

/** Height the phone's floating season bar occupies; the grid reserves it as bottom padding. */
private val SeasonBarHeight = 56.dp

/**
 * The season stepper: one quarter at a time, one arrow press at a time.
 *
 * The phone's bar, floating at the bottom of the screen; a television uses [TvSeasonBarHost]
 * instead. [onOpenFilters] adds a funnel button, because at the bottom of a phone screen the bar is
 * the shortest way back to the filter sheet.
 */
@Composable
private fun SeasonBar(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenFilters: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(SeasonBarHeight).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            val seasonStepFocus1 = rememberFocusFill()
            IconButton(
                colors = seasonStepFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onPrevious,
                modifier = Modifier.focusFill(seasonStepFocus1, CircleShape),
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous season")
            }
            val seasonStepFocus2 = rememberFocusFill()
            IconButton(
                colors = seasonStepFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onNext,
                modifier = Modifier.focusFill(seasonStepFocus2, CircleShape),
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next season")
            }
            if (onOpenFilters != null) {
                val seasonStepFocus3 = rememberFocusFill()
                IconButton(
                    colors = seasonStepFocus3.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onOpenFilters,
                    modifier = Modifier.focusFill(seasonStepFocus3, CircleShape),
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Open filters")
                }
            }
        }
    }
}

/**
 * The phone's floating bar, which reads the live filters itself.
 *
 * Deliberately a separate composable: [SearchScreen] avoids subscribing to vm.filters so the grid
 * does not recompose on every chip tap, and a bar that has to relabel on the same frame as an
 * arrow press belongs on the other side of that line.
 */
@Composable
private fun SeasonBarHost(
    vm: SearchViewModel,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val season = vm.filters.season ?: return
    SeasonBar(
        label = seasonLabel(season, vm.filters.year),
        onPrevious = { vm.stepSeason(-1) },
        onNext = { vm.stepSeason(1) },
        onOpenFilters = onOpenFilters,
        modifier = modifier,
    )
}

/**
 * The season stepper as a television needs it: one focus target the width of the screen, stepped
 * with Left and Right, standing between the header and the grid.
 *
 * It used to live inside the header as the phone's bar, where only its two arrow buttons could take
 * focus. They sit at the far right, so Down from the filter chips on the left found the posters
 * beneath first — Compose's focus search prefers the candidate inside the beam — and the stepper
 * was skipped entirely. And inside the header it vanished with it: once a poster took focus the
 * header folded away, and Up from the first row reopened the whole header on the search field,
 * five rows above the one control a season browser wants next.
 *
 * Its own strip fixes both. A full-width target is inside every beam, so Down from anywhere in the
 * header lands on it; it stays on screen while the grid scrolls; and Up from the first row comes
 * here first. Up from here reopens the header only when it is folded — when it is open, the chips
 * above are one ordinary step away.
 *
 * Reads vm.filters itself for the reason [SeasonBarHost] does.
 */
@Composable
private fun TvSeasonBarHost(
    vm: SearchViewModel,
    focusRequester: FocusRequester,
    /** Called on Up; returns whether it moved focus itself (the header was folded). */
    onUp: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val season = vm.filters.season ?: return
    val fill = rememberFocusFill()
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusFill(fill, shape, focusedScale = 1.02f)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val step = when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -1
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> 0
                }
                when {
                    step != 0 -> {
                        // Repeats step again while held, the way a remote's arrow is expected to.
                        if (event.type == KeyEventType.KeyDown) vm.stepSeason(step)
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_DPAD_UP && event.type == KeyEventType.KeyDown -> onUp()
                    else -> false
                }
            }
            .focusable(),
        shape = shape,
        // The same rest and fill as every other TV control, not the phone bar's red: in a theme
        // whose focus fill is that red, a red bar looked identical focused and unfocused.
        color = fill.background(),
        contentColor = fill.content(),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(SeasonBarHeight).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                seasonLabel(season, vm.filters.year),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            // Not buttons: the whole bar is the control, and these say which way each arrow goes.
            Icon(Icons.Default.ChevronLeft, contentDescription = "Left for the previous season")
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Right for the next season",
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** One removable chip in the active-filter strip below the categories. */
private data class ActiveFilterChip(
    val key: String,
    val label: String,
    val onRemove: () -> Unit,
)

/**
 * Everything currently narrowing the catalog, in the order the sheet teaches it.
 *
 * The funnel button only ever carried a count, so finding out *what* was on meant opening the
 * sheet and reading every section. That was survivable at a dozen filters and is not at what the
 * sheet holds now.
 */
private fun activeFilterChips(vm: SearchViewModel): List<ActiveFilterChip> {
    val filters = vm.filters
    return buildList {
        filters.genres.sorted().forEach { genre ->
            add(ActiveFilterChip("genre-$genre", genre) { vm.removeGenre(genre, excluded = false) })
        }
        filters.tags.sorted().forEach { tag ->
            add(ActiveFilterChip("tag-$tag", tag) { vm.removeTag(tag, excluded = false) })
        }
        filters.excludedGenres.sorted().forEach { genre ->
            add(ActiveFilterChip("not-genre-$genre", "Not $genre") { vm.removeGenre(genre, excluded = true) })
        }
        filters.excludedTags.sorted().forEach { tag ->
            add(ActiveFilterChip("not-tag-$tag", "Not $tag") { vm.removeTag(tag, excluded = true) })
        }
        filters.studioName?.takeIf { it.isNotBlank() }?.let { name ->
            add(ActiveFilterChip("studio", name) { vm.clearStudio() })
        }
        if (filters.season != null || filters.year != null) {
            val label = listOfNotNull(
                filters.season?.let { seasonLabel(it, null) },
                filters.year?.toString(),
            ).joinToString(" ")
            add(ActiveFilterChip("season", label) { vm.clearSeason() })
        }
        if (filters.yearFrom != null || filters.yearTo != null) {
            add(
                ActiveFilterChip("year-range", yearRangeLabel(filters.yearFrom, filters.yearTo)) {
                    vm.setYearRange(null, null)
                },
            )
        }
        filters.status?.let { status ->
            add(ActiveFilterChip("status", catalogLabel(SearchViewModel.STATUSES, status)) { vm.setStatus(null) })
        }
        filters.format?.let { format ->
            add(ActiveFilterChip("format", catalogLabel(SearchViewModel.FORMATS, format)) { vm.setFormat(null) })
        }
        filters.country?.let { country ->
            add(ActiveFilterChip("country", catalogLabel(SearchViewModel.COUNTRIES, country)) { vm.setCountry(null) })
        }
        filters.source?.let { source ->
            add(ActiveFilterChip("source", catalogLabel(SearchViewModel.SOURCES, source)) { vm.setSource(null) })
        }
        if (filters.episodesMin != null || filters.episodesMax != null) {
            add(
                ActiveFilterChip("episodes", episodeLabel(filters.episodesMin, filters.episodesMax)) {
                    vm.setEpisodes(null, null)
                },
            )
        }
        filters.minimumScore?.let { score ->
            add(ActiveFilterChip("score", "$score%+") { vm.setMinimumScore(null) })
        }
        if (filters.dubbedOnly) {
            add(ActiveFilterChip("dubbed", "Dubbed") { vm.setDubbedOnly(false) })
        }
        if (filters.hideWatched != WatchedFilter.OFF) {
            val label = if (filters.hideWatched == WatchedFilter.COMPLETED) "Not completed" else "Not watched"
            add(ActiveFilterChip("watched", label) { vm.setHideWatched(WatchedFilter.OFF) })
        }
    }
}

/** The label AniList's enum value is shown under, or the raw value if a new one ever appears. */
private fun catalogLabel(choices: List<CatalogChoice>, value: String): String =
    choices.firstOrNull { it.value == value }?.label ?: value

/** The bucket label for the episode span that is set, or a plain bounds reading otherwise. */
private fun episodeLabel(min: Int?, max: Int?): String =
    SearchViewModel.EPISODES.firstOrNull { it.from == min && it.to == max }?.label
        ?: listOfNotNull(min?.toString(), max?.toString()).joinToString("–")

@Composable
private fun ActiveFilterRow(vm: SearchViewModel) {
    val filters = vm.filters
    val chips = remember(filters) { activeFilterChips(vm) }
    if (chips.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(chips, key = { it.key }) { chip ->
            val activeFocus = rememberFocusFill()
            FilterChip(
                colors = activeFocus.chipColors(FilterChipDefaults.filterChipColors()),
                selected = true,
                onClick = chip.onRemove,
                label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove ${chip.label}",
                        modifier = Modifier.size(16.dp),
                    )
                },
                modifier = Modifier.focusFill(activeFocus, RoundedCornerShape(8.dp)),
            )
        }
    }
}

/**
 * The dropdown under the search box: what this viewer searched before, then titles that match what
 * they are typing now.
 *
 * Past searches lead because they are the shorter list and the viewer put them there. A history row
 * fills the box; a title row opens that title — the two do visibly different things, so they carry
 * different icons and only the removable one has a delete button.
 */
@Composable
private fun SearchSuggestions(
    history: List<String>,
    titles: List<TitleSuggestion>,
    onPick: (String) -> Unit,
    onOpenTitle: (TitleSuggestion) -> Unit,
    onRemove: (String) -> Unit,
) {
    val recent = history.take(SUGGESTION_ROWS)
    val matches = titles.take(SUGGESTION_ROWS - recent.size)
    AnimatedVisibility(visible = recent.isNotEmpty() || matches.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                recent.forEach { term ->
                    SuggestionRow(
                        label = term,
                        icon = Icons.Default.History,
                        onClick = { onPick(term) },
                        onRemove = { onRemove(term) },
                    )
                }
                matches.forEach { suggestion ->
                    SuggestionRow(
                        label = suggestion.title,
                        icon = Icons.Default.Search,
                        onClick = { onOpenTitle(suggestion) },
                        onRemove = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (onRemove != null) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove $label",
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same two lists on TV, as horizontal chip rows D-pad can walk.
 *
 * Rows rather than a dropdown because this bar sits above the results rather than over them, and
 * because a vertical list here would put a second scrollable between the text field and the grid —
 * which is the shape that made typing in TV search cost a frame budget it did not have.
 */
@Composable
private fun TvSearchSuggestions(
    history: List<String>,
    titles: List<TitleSuggestion>,
    onPick: (String) -> Unit,
    onOpenTitle: (TitleSuggestion) -> Unit,
    onRemove: (String) -> Unit,
) {
    AnimatedVisibility(visible = titles.isNotEmpty()) {
        Column(Modifier.padding(top = 8.dp)) {
            TvSuggestionLabel("Matching titles")
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // A lazy row clips to its bounds, and the focus ring is drawn outside the chip: room
                // on every side, or the ring's top and bottom were shaved off.
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            ) {
                items(titles.take(TV_SUGGESTION_CHIPS), key = { it.id }) { suggestion ->
                    val controlFocus2 = rememberFocusFill()
                    AssistChip(
                        colors = controlFocus2.chipColors(AssistChipDefaults.assistChipColors()),
                        onClick = { onOpenTitle(suggestion) },
                        label = {
                            Text(
                                suggestion.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.focusFill(controlFocus2, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
    AnimatedVisibility(visible = history.isNotEmpty()) {
        Column(Modifier.padding(top = 8.dp)) {
            TvSuggestionLabel("Recent searches")
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // A lazy row clips to its bounds, and the focus ring is drawn outside the chip: room
                // on every side, or the ring's top and bottom were shaved off.
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            ) {
                items(history.take(10), key = { it }) { term ->
                    val controlFocus3 = rememberFocusFill()
                    AssistChip(
                        colors = controlFocus3.chipColors(AssistChipDefaults.assistChipColors()),
                        onClick = { onPick(term) },
                        label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $term",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemove(term) },
                            )
                        },
                        modifier = Modifier.focusFill(controlFocus3, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSuggestionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Eight rows is what fits under the box without the dropdown owning the screen. */
private const val SUGGESTION_ROWS = 8

/** The phone's in-header sort switch, shown while a category chip narrows the results. */
@Composable
private fun QuickSortPill(label: String, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Sort by $label" },
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SearchViewModel.SORTS.forEach { sort ->
                PageMenuChoice(sort.label, selected = sort.value == selected) {
                    expanded = false
                    if (sort.value != selected) onSelect(sort.value)
                }
            }
        }
    }
}

/** Chips are read one at a time on a remote; past about this many nobody reaches the end. */
private const val TV_SUGGESTION_CHIPS = 10

@Composable
private fun ResultsGrid(
    results: List<Media>,
    filters: DiscoverFilters,
    /** Total matches AniList reported, or 0 when it did not say. */
    resultTotal: Int,
    /** The spelling the grid was corrected to or could be; see [SearchCorrection]. */
    correction: SearchCorrection?,
    onSearchInstead: () -> Unit,
    onAcceptCorrection: () -> Unit,
    onAnimeClick: (Int) -> Unit,
    gridState: LazyGridState,
    /** Height of the search bar drawn over the top of this grid; see [SearchScreen]. */
    headerPadding: Dp,
    /** Bottom room the floating season bar needs on a phone; 0 on TV and with no season set. */
    seasonBarClearance: Dp,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onTvResultFocused: () -> Unit,
    onTvReturnToHeader: () -> Unit,
    onRetryWithAdultContent: () -> Unit,
    aniListHidesAdultContent: Boolean,
    onSortChange: (String) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val tileMinWidth = if (device.isTv) TvSearchCardWidth else device.gridMinWidth
    if (results.isEmpty()) {
        val hideAdultContent by SettingsStore.hideAdultContent.collectAsState()
        Box(
            Modifier.fillMaxSize().padding(top = headerPadding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing matched", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Try removing a filter or searching another title.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // The one filter here that can hide a title the viewer knows has a dub: the index
                // only carries what a crawl has confirmed. Worth a line exactly where the search
                // came back empty, rather than leaving it looking like the title does not exist.
                if (filters.dubbedOnly) {
                    Text(
                        "The dub filter only shows titles a dub has been found for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                // A title searched for by name and not found is most often one already watched.
                if (filters.hideWatched != WatchedFilter.OFF) {
                    Text(
                        "Titles you've already watched are hidden. Change \"Already watched\" in the filters to see them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                // The Hentai genre chip is listed like any other, but every query carries
                // `isAdult: false` while this setting is on, so picking it returns nothing and
                // looks broken. The switch lives in Settings, several screens from here, and
                // nothing on this screen said so. Same opt-in, offered where people hit the wall.
                if (hideAdultContent) {
                    Text(
                        "Explicit titles are hidden by your settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    val controlFocus4 = rememberFocusFill()
                    TextButton(
                        colors = controlFocus4.buttonColors(ButtonDefaults.textButtonColors()),
                        onClick = {
                            SettingsStore.setHideAdultContent(false)
                            onRetryWithAdultContent()
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .focusFill(controlFocus4, RoundedCornerShape(20.dp)),
                    ) {
                        Text("Show explicit content", fontWeight = FontWeight.Bold)
                    }
                } else if (aniListHidesAdultContent) {
                    // The other half of the same wall, and the half nobody can guess at: the switch
                    // above is off, so this app asks for explicit titles, but every request carries
                    // the account's token and AniList applies its own 18+ option to the answer. The
                    // Hentai chip then returns nothing with the app's own setting plainly allowing
                    // it — the one empty result the viewer cannot fix from inside this app.
                    Text(
                        "Your AniList account hides explicit titles, and applies that to " +
                            "everything this app asks for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        "Turn on adult content at anilist.co/settings/media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // The switch is on a website this app cannot watch, so nothing here re-asks on
                    // its own: coming back from anilist.co lands on this same screen, still holding
                    // the answer from before the change. Whoever has just turned it on needs a way
                    // to say so — and this forces the request rather than reading the cache.
                    val controlFocus5 = rememberFocusFill()
                    TextButton(
                        colors = controlFocus5.buttonColors(ButtonDefaults.textButtonColors()),
                        onClick = onRetryWithAdultContent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .focusFill(controlFocus5, RoundedCornerShape(20.dp)),
                    ) {
                        Text("I've turned it on", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }
    val horizontalSpacing = if (device.isTv) 18.dp else 9.dp
    // On TV the spelling notice is the grid's only item ahead of the results, and Up from the first
    // row stops on it before the header, so the remote can reach "Search instead".
    val tvLeadingItems = if (device.isTv && correction != null) 1 else 0
    val correctionFocus = remember { FocusRequester() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnCount = adaptiveColumnCount(
            availableWidth = maxWidth,
            horizontalPadding = device.pagePadding,
            minimumTileWidth = tileMinWidth,
            spacing = horizontalSpacing,
        )

        val lastVisibleIndex by remember(gridState) {
            derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        }
        LaunchedEffect(lastVisibleIndex, results.size, columnCount) {
            if (lastVisibleIndex >= results.size - columnCount * 2) onLoadMore()
        }

        // A TV result row comes to rest mid-screen, going up as well as down, as Home's rails do.
        TvCentredScrollSpec {
            Column(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = if (device.isTv) {
                        GridCells.FixedSize(TvSearchCardWidth)
                    } else {
                        GridCells.Adaptive(tileMinWidth)
                    },
                    state = gridState,
                    // The navigation capsule floats over the grid rather than displacing it, so the
                    // last row has to buy its own clearance — without this the bottom row's title and
                    // metadata sit under the bar with no way to scroll them out from under it. The
                    // gap on top of the capsule's own height is deliberate; see [AppChromeListBottomGap].
                    contentPadding = PaddingValues(
                        start = device.pagePadding,
                        end = device.pagePadding,
                        // The search bar floats over this grid the way the capsule does below it, so
                        // the first row buys its clearance here rather than being laid out beneath it.
                        top = 14.dp + headerPadding,
                        // The floating season bar is the second such overlay on a phone; when it is
                        // out, the last row reserves its height the same way.
                        bottom = AppChromeListBottomGap + LocalAppChromeBottomInset.current + seasonBarClearance,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(horizontalSpacing, Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(if (device.isTv) 16.dp else 14.dp),
                ) {
                    if (!device.isTv) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when {
                                        correction?.applied == true -> "Results for “${correction.suggested}”"
                                        filters.query.isNotBlank() -> "Results for “${filters.query}”"
                                        !filters.studioName.isNullOrBlank() -> "Anime by ${filters.studioName}"
                                        else -> "Discover anime"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sortLabel = SearchViewModel.SORTS.firstOrNull { it.value == filters.sort }?.label
                                // Browsing a category is where the order matters most — "Adventure" by
                                // trending and by highest rated are different lists — so there the sort
                                // is one tap away instead of inside the filter sheet.
                                val categoryActive = filters.season != null || filters.format == "MOVIE" ||
                                    filters.genres.isNotEmpty()
                                if (categoryActive && sortLabel != null) {
                                    resultTotal.takeIf { it > 0 }?.let { total ->
                                        Text(
                                            "$total results",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                    }
                                    QuickSortPill(label = sortLabel, selected = filters.sort, onSelect = onSortChange)
                                } else {
                                    Text(
                                        listOfNotNull(
                                            resultTotal.takeIf { it > 0 }?.let { "$it results" },
                                            sortLabel,
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (correction != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "spelling-correction") {
                            // Either button replaces the grid it sits in, taking the focused control
                            // with it; on TV that dropped focus onto the Home tab with the search box
                            // still tucked away, so the remote is handed back to the header instead.
                            SpellingCorrectionNotice(
                                correction = correction,
                                onSearchInstead = {
                                    onSearchInstead()
                                    if (device.isTv) onTvReturnToHeader()
                                },
                                onAcceptCorrection = {
                                    onAcceptCorrection()
                                    if (device.isTv) onTvReturnToHeader()
                                },
                                actionModifier = if (device.isTv) {
                                    Modifier
                                        .focusRequester(correctionFocus)
                                        .onPreviewKeyEvent { event ->
                                            if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                                return@onPreviewKeyEvent false
                                            }
                                            if (event.type == KeyEventType.KeyDown) onTvReturnToHeader()
                                            true
                                        }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                    gridItemsIndexed(results, key = { _, media -> media.id }) { index, media ->
                        if (device.isTv) {
                            TvMediaCard(
                                media = media,
                                onClick = { onAnimeClick(media.id) },
                                cardWidth = TvSearchCardWidth,
                                onFocused = { focused ->
                                    if (focused) onTvResultFocused()
                                },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    val returningToHeader =
                                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                                            isFirstTvSearchResultRow(index, columnCount)
                                    if (!returningToHeader) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) {
                                        val onNotice = correction != null &&
                                            runCatching { correctionFocus.requestFocus() }.isSuccess
                                        if (!onNotice) onTvReturnToHeader()
                                    }
                                    true
                                },
                            )
                        } else {
                            AnimeCard(
                                media = media,
                                onClick = { onAnimeClick(media.id) },
                            )
                        }
                    }
                    if (isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The line a web search draws over corrected results: "Showing results for naruto · Search instead
 * for naurto" when the grid holds the correction, "Did you mean naruto?" when it holds the typed
 * term's own few results. Only the half that does something is a button, so on TV the remote has
 * one stop here rather than a row of text to walk through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpellingCorrectionNotice(
    correction: SearchCorrection,
    onSearchInstead: () -> Unit,
    onAcceptCorrection: () -> Unit,
    /** Applied to the button; TV hangs its focus requester and Up handling here. */
    actionModifier: Modifier = Modifier,
) {
    val focus = rememberFocusFill()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        if (correction.applied) {
            Text(
                "Showing results for “${correction.suggested}”",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        FlowRow(verticalArrangement = Arrangement.Center) {
            Text(
                if (correction.applied) "Search instead for" else "Did you mean",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            TextButton(
                onClick = if (correction.applied) onSearchInstead else onAcceptCorrection,
                colors = focus.buttonColors(ButtonDefaults.textButtonColors()),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                // Room for the focus ring, which scales the button over the label beside it.
                modifier = actionModifier
                    .padding(start = 8.dp)
                    .focusFill(focus, RoundedCornerShape(20.dp)),
            ) {
                Text(
                    if (correction.applied) correction.typed else "${correction.suggested}?",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun adaptiveColumnCount(
    availableWidth: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    minimumTileWidth: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
): Int {
    val contentWidth = availableWidth - horizontalPadding * 2
    return ((contentWidth.value + spacing.value) / (minimumTileWidth.value + spacing.value))
        .toInt()
        .coerceAtLeast(1)
}

/**
 * Whether the search bar is out: on TV where focus put it, on a phone while the results are dragged
 * back upward — or while the room the grid reserves for the bar is still on screen.
 *
 * That room is scroll padding, the height of the bar, at the head of the grid. It only leaves the
 * screen once the grid has scrolled that far, so a bar that tucked away on the first downward
 * nudge left a band of nothing behind it as tall as itself — about a quarter of a phone, measured
 * on anilili_phone after a 135px swipe from the top. Held until the band has scrolled under it,
 * the bar leaves over posters instead.
 */
internal fun searchHeaderVisible(
    isTv: Boolean,
    tvHeaderExpanded: Boolean,
    scrollingUp: Boolean,
    headerRoomOnScreen: Boolean = false,
): Boolean = if (isTv) tvHeaderExpanded else scrollingUp || headerRoomOnScreen

/**
 * The year a part-typed box should actually filter by, or null to leave the filter off.
 *
 * A year only counts once all four digits are present. Applying it earlier is what made the box
 * unusable: it was driven by the filter, so "2" fell outside 1900..2100, the filter reset to
 * nothing and the box redrew empty — and since no prefix of a year is ever in range, no year could
 * be typed at all.
 */
internal fun releaseYearFilterValue(digits: String): Int? =
    digits.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2100 }

/** How many years the Season chips offer, this year included. */
internal const val SEASON_YEAR_CHOICES = 11

/**
 * The years offered beside the season chips, newest first.
 *
 * A season is only ever asked about as a quarter of a particular year, so the year belongs in the
 * same control rather than in a text box further up the sheet. Eleven years covers what anyone
 * browses this way; the box above still reaches anything older.
 *
 * A year picked outside that window — typed into the box, or carried in from a saved filter — is
 * appended rather than dropped, so it is never selected-but-invisible with every chip reading
 * unselected and nothing on screen saying why the results are narrowed.
 */
internal fun seasonYearChoices(
    currentYear: Int,
    selected: Int?,
    count: Int = SEASON_YEAR_CHOICES,
): List<Int> {
    val recent = (0 until count).map { currentYear - it }
    return if (selected == null || selected in recent) recent else (recent + selected).sortedDescending()
}

/**
 * A season chip's label. Bare while no year is chosen — "Summer" then honestly means every Summer
 * there has been — and "Summer 2025" once one is, so the chip says what it will actually return.
 */
internal fun seasonChipLabel(season: String, year: Int?): String =
    if (year == null) season else "$season $year"

internal fun isFirstTvSearchResultRow(index: Int, columnCount: Int): Boolean =
    index >= 0 && index < columnCount.coerceAtLeast(1)

/**
 * True while the grid is resting at the top or the user is dragging it back upward — the cue for
 * showing the search bar. Flips to false the moment scrolling moves downward so the bar hides.
 */
@Composable
private fun LazyGridState.isScrollingUp(): Boolean {
    var lastRealDirectionWasUp by remember(this) { mutableStateOf(true) }
    val atTop by remember(this) {
        derivedStateOf { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousOffset = firstVisibleItemScrollOffset
        snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                lastRealDirectionWasUp = index < previousIndex ||
                    (index == previousIndex && offset < previousOffset)
                previousIndex = index
                previousOffset = offset
            }
        }
    return atTop || lastRealDirectionWasUp
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
private fun FilterSheet(
    options: DiscoverOptions,
    vm: SearchViewModel,
    onDismiss: () -> Unit,
) {
    val filters = vm.filters
    val device = LocalAppDeviceProfile.current
    val studioSuggestions by vm.studioSuggestions.collectAsState()
    val studioLookupLoading by vm.isStudioLookupLoading.collectAsState()
    val resultTotal by vm.resultTotal.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val initialFocusRequester = remember { FocusRequester() }
    // AndroidView text fields claim the window's initial focus as soon as they are created. Keep
    // them out of the TV focus graph until the sheet's first Compose control has received focus.
    var tvTextFieldsFocusable by remember { mutableStateOf(!device.isTv) }
    var tagSearch by remember { mutableStateOf("") }
    // Read once: the sheet must not renumber its year chips because it happened to be open at
    // midnight on 31 December.
    val currentYear = remember { Year.now().value }
    val seasonChoices = remember(filters.year) {
        SearchViewModel.SEASONS.map { it.copy(label = seasonChipLabel(it.label, filters.year)) }
    }
    // The chosen tags, then matches for whatever is typed — never a default wall of the first
    // forty-eight alphabetically, which is what made this page the longest in the sheet.
    val visibleTags = remember(options.tags, filters.tags, filters.excludedTags, tagSearch) {
        val chosen = options.tags.filter { it.name in filters.tags || it.name in filters.excludedTags }
        val matches = if (tagSearch.isBlank()) {
            emptyList()
        } else {
            options.tags.filter { it.name.contains(tagSearch, ignoreCase = true) }
        }
        (chosen + matches).distinctBy { it.name }.take(36)
    }
    LaunchedEffect(device.isTv) {
        if (!device.isTv) return@LaunchedEffect
        repeat(TV_FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos {}
            if (runCatching { initialFocusRequester.requestFocus() }.isSuccess) {
                withFrameNanos {}
                tvTextFieldsFocusable = true
                return@LaunchedEffect
            }
        }
        tvTextFieldsFocusable = true
    }
    val scope = rememberCoroutineScope()
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filter catalog", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                // The count refreshes with the debounced request every change fires, so a filter
                // that narrows to nothing says so here rather than after dismissing the sheet and
                // finding an empty grid. Zero is also what an unasked catalog reads as, which is
                // why the promise stands in whenever there is no number yet.
                Text(
                    if (resultTotal > 0) {
                        "$resultTotal titles match"
                    } else {
                        "Combine filters to narrow the full AniList catalog."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val controlFocus6 = rememberFocusFill()
            TextButton(
                colors = controlFocus6.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = vm::clearFilters,
                modifier = if (device.isTv) Modifier.focusFill(controlFocus6, RoundedCornerShape(50)) else Modifier,
            ) { Text("Clear") }
        }
    }
    val showResults: @Composable () -> Unit = {
        val extraFocus14 = rememberFocusFill()
        Button(
            colors = extraFocus14.buttonColors(ButtonDefaults.buttonColors()),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(50.dp).focusFill(extraFocus14, RoundedCornerShape(50)),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Show results", fontWeight = FontWeight.Bold)
        }
    }
    // One page per tab. Single choices are Settings-style rows that drop a menu, so a page reads
    // as a short list of "name — current value" instead of a wall of chips; pills are kept for the
    // two places a viewer really does pick several at once, genres and tags.
    val page: @Composable ColumnScope.(FilterTab) -> Unit = { tab ->
        when (tab) {
            FilterTab.GENERAL -> {
                FilterMenuRow("Sort by", SearchViewModel.SORTS.firstOrNull { it.value == filters.sort }?.label ?: "Trending") { dismiss ->
                    SearchViewModel.SORTS.forEach { sort ->
                        PageMenuChoice(sort.label, selected = sort.value == filters.sort) { dismiss(); vm.setSort(sort.value) }
                    }
                }
                ChoiceMenuRow("Format", SearchViewModel.FORMATS, filters.format, vm::setFormat)
                ChoiceMenuRow("Status", SearchViewModel.STATUSES, filters.status, vm::setStatus)
                val episodes = SearchViewModel.EPISODES.firstOrNull {
                    it.from == filters.episodesMin && it.to == filters.episodesMax
                }
                FilterMenuRow("Episodes", episodes?.label ?: "Any") { dismiss ->
                    PageMenuChoice("Any", selected = filters.episodesMin == null && filters.episodesMax == null) {
                        dismiss(); vm.setEpisodes(null, null)
                    }
                    SearchViewModel.EPISODES.forEach { bucket ->
                        PageMenuChoice(bucket.label, selected = bucket == episodes) {
                            dismiss(); vm.setEpisodes(bucket.from, bucket.to)
                        }
                    }
                }
                FilterMenuRow("Minimum rating", filters.minimumScore?.let { "$it%+" } ?: "Any") { dismiss ->
                    PageMenuChoice("Any", selected = filters.minimumScore == null) { dismiss(); vm.setMinimumScore(null) }
                    SearchViewModel.RATINGS.forEach { rating ->
                        PageMenuChoice("$rating%+", selected = filters.minimumScore == rating) {
                            dismiss(); vm.setMinimumScore(rating)
                        }
                    }
                }
                // The index records the dubs a crawl has found and stays silent about everything it
                // has never been asked about, which is why there is no "sub only" counterpart and
                // why a title missing here is not a title without a dub.
                MidnightSettingSwitchRow(
                    title = "Dubbed only",
                    description = "Titles a dub has been found for — the DUB badge's list, not exhaustive",
                    checked = filters.dubbedOnly,
                    onCheckedChange = vm::setDubbedOnly,
                )
                // Kept between visits (see SettingsStore.browseHideWatched): it describes the viewer
                // rather than this one search.
                FilterMenuRow("Already watched", filters.hideWatched.label) { dismiss ->
                    WatchedFilter.entries.forEach { choice ->
                        PageMenuChoice(choice.label, selected = choice == filters.hideWatched) {
                            dismiss(); vm.setHideWatched(choice)
                        }
                    }
                }
            }
            FilterTab.RELEASE -> {
                val decades = remember(currentYear) { decadeChoices(currentYear) }
                val decade = decades.firstOrNull { it.from == filters.yearFrom && it.to == filters.yearTo }
                FilterMenuRow(
                    "Year",
                    filters.year?.toString() ?: decade?.label ?: "Any year",
                ) { dismiss ->
                    PageMenuChoice(
                        "Any year",
                        selected = filters.year == null && filters.yearFrom == null && filters.yearTo == null,
                    ) { dismiss(); vm.setYear(null) }
                    // Decades first: they answer "which era", which a single year cannot, and they
                    // are what the list is scrolled past to reach a year.
                    PageMenuSectionLabel("Decades")
                    decades.forEach { choice ->
                        PageMenuChoice(choice.label, selected = choice == decade) {
                            dismiss(); vm.setYearRange(choice.from, choice.to)
                        }
                    }
                    PageMenuSectionLabel("Years")
                    (currentYear + 1 downTo MENU_EARLIEST_YEAR).forEach { year ->
                        PageMenuChoice(year.toString(), selected = filters.year == year) {
                            dismiss(); vm.setYear(year)
                        }
                    }
                }
                ChoiceMenuRow("Season", seasonChoices, filters.season, vm::setSeason)
                ChoiceMenuRow("Source material", SearchViewModel.SOURCES, filters.source, vm::setSource)
                ChoiceMenuRow("Country of origin", SearchViewModel.COUNTRIES, filters.country, vm::setCountry)
            }
            FilterTab.GENRES -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.genres.forEach { genre ->
                        TriStateFilterChip(
                            label = genre,
                            included = genre in filters.genres,
                            excluded = genre in filters.excludedGenres,
                            onClick = { vm.cycleGenre(genre) },
                        )
                    }
                }
                FilterHint("Tap once to include, again to exclude.")
            }
            FilterTab.TAGS -> {
                if (device.isTv) {
                    TvNativeTextField(
                        value = tagSearch,
                        onValueChange = { tagSearch = it },
                        hint = "Search all ${options.tags.size} AniList tags",
                        modifier = Modifier.fillMaxWidth(),
                        onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                        focusable = tvTextFieldsFocusable,
                    )
                } else {
                    OutlinedTextField(
                        value = tagSearch,
                        onValueChange = { tagSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search all ${options.tags.size} AniList tags") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                if (visibleTags.isEmpty()) {
                    FilterHint(
                        if (tagSearch.isBlank()) "Type to find a tag, then tap it to include or exclude it."
                        else "No tag matches “$tagSearch”.",
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        visibleTags.forEach { tag ->
                            TriStateFilterChip(
                                label = tag.name,
                                included = tag.name in filters.tags,
                                excluded = tag.name in filters.excludedTags,
                                onClick = { vm.cycleTag(tag.name) },
                            )
                        }
                    }
                    FilterHint("Tap once to include, again to exclude.")
                }
            }
            FilterTab.STUDIO -> {
                if (device.isTv) {
                    TvNativeTextField(
                        value = vm.studioQuery,
                        onValueChange = vm::onStudioQueryChange,
                        hint = "Find a studio, for example MAPPA",
                        modifier = Modifier.fillMaxWidth(),
                        imeAction = EditorInfo.IME_ACTION_SEARCH,
                        onImeAction = {
                            vm.selectFirstStudioSuggestion()
                            focusManager.moveFocus(FocusDirection.Down)
                        },
                        onMoveDown = { focusManager.moveFocus(FocusDirection.Down) },
                        focusable = tvTextFieldsFocusable,
                    )
                } else {
                    OutlinedTextField(
                        value = vm.studioQuery,
                        onValueChange = vm::onStudioQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Find a studio (for example MAPPA)") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            when {
                                studioLookupLoading -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                                vm.studioQuery.isNotEmpty() -> {
                                    val extraFocus5 = rememberFocusFill()
                                    IconButton(
                                        colors = extraFocus5.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                                        onClick = vm::clearStudio,
                                        modifier = Modifier.focusFill(extraFocus5, CircleShape),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear studio")
                                    }
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.selectFirstStudioSuggestion()
                            keyboard?.hide()
                            focusManager.moveFocus(FocusDirection.Down)
                        }),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                if (studioSuggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        studioSuggestions.forEach { studio ->
                            val name = studio.name ?: return@forEach
                            val controlFocus7 = rememberFocusFill()
                            FilterChip(
                                colors = controlFocus7.chipColors(FilterChipDefaults.filterChipColors()),
                                selected = filters.studioId == studio.id,
                                onClick = { vm.selectStudio(studio) },
                                label = { Text(name) },
                                modifier = Modifier.focusFill(controlFocus7, RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                } else if (filters.studioId != null && !filters.studioName.isNullOrBlank()) {
                    Text(
                        "Filtering by ${filters.studioName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    val tabs = remember(options.tags.isNotEmpty()) {
        FilterTab.entries.filter { it != FilterTab.TAGS || options.tags.isNotEmpty() }
    }

    if (device.isTv) {
        // The same tabs as the phone, built for a remote. Landing on a tab is choosing it, so
        // Left/Right walks the pages with no OK press between; Down enters the page, and Up from
        // its top returns to the tab it came from (focusRestorer) rather than whichever tab is
        // nearest above, which would switch the page out from under the cursor. "Show results"
        // is pinned under the page, and focus cannot leave the panel.
        BackHandler(onBack = onDismiss)
        var tvTab by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(20f)
                .background(Color.Black.copy(alpha = 0.58f)),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.72f)
                    .background(MaterialTheme.colorScheme.surface)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .semantics { paneTitle = "Catalog filters" }
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            ) {
                header()
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .focusRestorer()
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tabs.forEachIndexed { index, tab ->
                        TvFilterTab(
                            label = tab.label,
                            active = tab.activeCount(filters),
                            selected = tvTab == index,
                            onFocused = { tvTab = index },
                            modifier = if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier,
                        )
                    }
                }
                // Keyed on the tab so each page opens at its top with its own scroll position.
                key(tvTab) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        page(tabs[tvTab.coerceIn(0, tabs.lastIndex)])
                    }
                }
                showResults()
            }
        }
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pager = rememberPagerState(pageCount = { tabs.size })
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // A fixed height, so the sheet does not jump as tabs of different lengths slide past, and
        // "Show results" stays pinned at the foot of every one of them. The keyboard takes its
        // room from the page rather than pushing the button off.
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(FILTER_SHEET_HEIGHT)
                .imePadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
        ) {
            header()
            ScrollableTabRow(
                selectedTabIndex = pager.currentPage,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {},
                modifier = Modifier.padding(top = 8.dp),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val active = tab.activeCount(filters)
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = {
                            Text(
                                if (active > 0) "${tab.label} · $active" else tab.label,
                                fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                pageSpacing = 18.dp,
            ) { index ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    page(tabs[index])
                }
            }
            showResults()
        }
    }
}

/** The filter sheet's pages, in the order its tabs run. */
private enum class FilterTab(val label: String) {
    GENERAL("General"),
    RELEASE("Release"),
    GENRES("Genres"),
    TAGS("Tags"),
    STUDIO("Studio"),
    ;

    /** How many of this page's filters are on, for the count its tab carries. */
    fun activeCount(filters: DiscoverFilters): Int = when (this) {
        GENERAL -> listOf(filters.format, filters.status, filters.minimumScore).count { it != null } +
            (if (filters.episodesMin != null || filters.episodesMax != null) 1 else 0) +
            (if (filters.dubbedOnly) 1 else 0)
        RELEASE -> listOf(filters.season, filters.source, filters.country).count { it != null } +
            (if (filters.year != null || filters.yearFrom != null || filters.yearTo != null) 1 else 0)
        GENRES -> filters.genres.size + filters.excludedGenres.size
        TAGS -> filters.tags.size + filters.excludedTags.size
        STUDIO -> if (filters.studioId != null) 1 else 0
    }
}

/** The share of the window the phone's filter sheet takes, whichever tab is showing. */
private const val FILTER_SHEET_HEIGHT = 0.86f

/** The oldest single year the Year menu lists; the decades' "Earlier" covers everything before. */
private const val MENU_EARLIEST_YEAR = 1960

private const val TV_FOCUS_REQUEST_ATTEMPTS = 6
private val TvSearchCardWidth = 128.dp

/**
 * A Settings-style row — name on the left, current value on the right — that drops a menu of
 * choices under itself. [menu] gets a dismiss callback so a pick can close it before applying.
 */
@Composable
private fun FilterMenuRow(
    title: String,
    value: String,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MidnightSettingRow(title = title, value = value, onClick = { expanded = true })
        // Anchored at the row's bottom-right, so the menu drops under the value it changes
        // instead of opening from the far edge and climbing over the sheet's header.
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // The Year menu runs to sixty-odd entries; it scrolls inside this rather than
                // reaching off the screen.
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                menu { expanded = false }
            }
        }
    }
}

/** A [FilterMenuRow] over one optional choice, with "Any" to clear it. */
@Composable
private fun ChoiceMenuRow(
    title: String,
    choices: List<CatalogChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    FilterMenuRow(title, choices.firstOrNull { it.value == selected }?.label ?: "Any") { dismiss ->
        PageMenuChoice("Any", selected = selected == null) { dismiss(); onSelect(null) }
        choices.forEach { choice ->
            PageMenuChoice(choice.label, selected = choice.value == selected) { dismiss(); onSelect(choice.value) }
        }
    }
}

/** A line of muted guidance under a group of controls. */
@Composable
private fun FilterHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * One tab of the television's filter panel. Focus is selection: a remote walking the row changes
 * the page as it goes, the way every television settings screen behaves, and OK does the same for
 * anyone who presses it anyway. The chosen tab keeps a tinted fill once the cursor has moved into
 * its page, so it still says which page is showing.
 */
@Composable
private fun TvFilterTab(
    label: String,
    active: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val fill = rememberFocusFill()
    Text(
        if (active > 0) "$label · $active" else label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = fill.content(
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        maxLines = 1,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusFill(fill, shape)
            .clip(shape)
            .background(fill.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent))
            .clickable(onClick = onFocused)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * A genre or tag chip in its three states.
 *
 * Off is the unselected chip, include is the selected one with a tick, and exclude says "Not X" in
 * the error colours — a label rather than a strikethrough because the word survives a television
 * across the room. The cycle order itself lives in [SearchViewModel.cycleGenre].
 */
@Composable
private fun TriStateFilterChip(
    label: String,
    included: Boolean,
    excluded: Boolean,
    onClick: () -> Unit,
) {
    val chipFocus = rememberFocusFill()
    val colors = if (excluded) {
        chipFocus.chipColors(
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        )
    } else {
        chipFocus.chipColors(FilterChipDefaults.filterChipColors())
    }
    FilterChip(
        colors = colors,
        selected = included || excluded,
        onClick = onClick,
        label = { Text(if (excluded) "Not $label" else label) },
        leadingIcon = if (included) {
            { Text("✓", color = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier.focusFill(chipFocus, RoundedCornerShape(8.dp)),
    )
}
