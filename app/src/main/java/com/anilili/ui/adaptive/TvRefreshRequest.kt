package com.anilili.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Carries "the viewer asked for a reload" from the television's top navigation down to the screen.
 *
 * A phone refreshes by pulling the page down. `PullRefreshContainer` gives a television nothing at
 * all — it renders a bare box there, deliberately, so the gesture cannot fight D-pad scrolling —
 * and no remote key was bound to refresh either. So the only way to get fresh data on a television
 * was to wait out the cache (Home is half an hour) or clear it from Settings. Reopening the app
 * does not help: a cold start loads with `force = false`, which is served from the same cache, so
 * the one thing a viewer would try first provably does nothing.
 *
 * The two ends live in different branches of the composition — the navigation bar is drawn over the
 * `NavHost`, not inside it — so they are bridged by a shared holder, the same way
 * [TvFocusTarget] bridges the bar's Down key to the content it hands focus to.
 *
 * A counter rather than an event: it is read during composition, so it survives a recomposition
 * between the press and the screen seeing it, and it needs no coroutine scope to emit into.
 */
@Stable
class TvRefreshRequest {
    var count by mutableIntStateOf(0)
        private set

    fun request() {
        count++
    }
}

@Composable
fun rememberTvRefreshRequest(): TvRefreshRequest = remember { TvRefreshRequest() }

/**
 * Runs [onRefresh] when [request] is raised, and never for the raise that happened before this
 * screen was looking.
 *
 * The baseline is captured in a `remember`, so a screen that is composed afresh — returning to Home
 * from a detail page, say — starts level with the counter rather than treating every past press as
 * outstanding and reloading on arrival.
 */
@Composable
fun OnTvRefreshRequest(request: TvRefreshRequest?, onRefresh: () -> Unit) {
    if (request == null) return
    val current by rememberUpdatedState(onRefresh)
    var seen by remember(request) { mutableIntStateOf(request.count) }
    LaunchedEffect(request.count) {
        if (request.count == seen) return@LaunchedEffect
        seen = request.count
        current()
    }
}
