package com.anilili.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun `watch destination keeps episode list flag optional`() {
        assertEquals(
            "watch/{id}/{provider}/{category}/{episode}" +
                "?showEpisodes={showEpisodes}&autoAdvanced={autoAdvanced}&fullscreen={fullscreen}&startMs={startMs}",
            Routes.WATCH,
        )
        assertEquals("watch/{id}/{provider}/{category}/{episode}", Routes.tabRoute(Routes.WATCH))
        assertFalse("watch/20/reanime/sub/1.5".contains("showEpisodes"))
    }

    @Test
    fun `a linked start rides on the watch route only when there is one`() {
        // Built on a literal route: Routes.watch goes through Uri.encode, a stub under unit tests.
        assertEquals("watch/21/auto/sub/6.5?startMs=754200", Routes.watchFromStart("watch/21/auto/sub/6.5", 754_200))
        assertEquals("watch/21/auto/sub/6.5", Routes.watchFromStart("watch/21/auto/sub/6.5", null))
        assertEquals("watch/21/auto/sub/6.5", Routes.watchFromStart("watch/21/auto/sub/6.5", 0))
    }

    @Test
    fun `season route explicitly opens episode list`() {
        val route = Routes.withEpisodeList("watch/21/auto/dub/1")

        assertEquals("watch/21/auto/dub/1?showEpisodes=true", route)
        assertTrue(route.endsWith("showEpisodes=true"))
    }

    @Test
    fun `detail route can name the episode the player came back from`() {
        // The episode-bearing builder itself is left unpinned here: it goes through Uri.encode,
        // which is a stub in a JVM unit test and answers null. What matters to the host is the
        // pattern and that the stack still reports the argument-free route, because that is what
        // every currentRoute comparison reads.
        assertEquals("detail/{id}?episode={episode}", Routes.DETAIL_DESTINATION)
        assertEquals(Routes.DETAIL, Routes.tabRoute(Routes.DETAIL_DESTINATION))
        assertEquals("detail/153567", Routes.detail(153567))
    }

    @Test
    fun `studio search destination remains part of search tab`() {
        assertEquals(
            "search?studioId={studioId}&studioName={studioName}&genre={genre}&query={query}",
            Routes.SEARCH_DESTINATION,
        )
        assertEquals(Routes.SEARCH, Routes.tabRoute(Routes.SEARCH_DESTINATION))
    }

    @Test
    fun `download player is a child destination`() {
        assertEquals("download/{downloadId}", Routes.DOWNLOAD)
        assertFalse(Routes.DOWNLOAD in Routes.tabRoutes)
    }

    @Test
    fun `both player destinations count as watching`() {
        // What callers actually get from the back stack is the pattern minus its optional
        // arguments, so that is what has to match — a seek or an episode change never alters it.
        assertTrue(Routes.hostsPlayer(Routes.tabRoute(Routes.WATCH)))
        assertTrue(Routes.hostsPlayer(Routes.tabRoute(Routes.DOWNLOAD)))
    }

    @Test
    fun `browsing destinations are not watching`() {
        assertFalse(Routes.hostsPlayer(Routes.HOME))
        assertFalse(Routes.hostsPlayer(Routes.DETAIL))
        assertFalse(Routes.hostsPlayer(Routes.tabRoute(Routes.SEARCH_DESTINATION)))
        assertFalse(Routes.hostsPlayer(null))
    }

    @Test
    fun `home clears child routes while secondary tabs restore their state`() {
        assertFalse(Routes.shouldRestoreTabState(Routes.HOME))
        assertTrue(Routes.shouldRestoreTabState(Routes.SEARCH))
        assertTrue(Routes.shouldRestoreTabState(Routes.SCHEDULE))
        assertTrue(Routes.shouldRestoreTabState(Routes.MORE))
        assertTrue(Routes.shouldRestoreTabState(Routes.SETTINGS))
    }
}
