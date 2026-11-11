package com.anilili.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

private val ALL = LibraryView.entries.toList()
private val SIGNED_OUT = listOf(LibraryView.WATCHLIST)

class LibraryViewSelectionTest {
    @Test
    fun `the default shows what is being watched and what is saved`() {
        assertEquals(
            listOf(LibraryView.WATCHLIST, LibraryView.WATCHING),
            libraryViewsFrom(defaultLibraryViewNames(), ALL),
        )
    }

    @Test
    fun `sections keep enum order however the chips were tapped`() {
        // Dropped first, Watching second — the page must still read Watching, then Dropped, or a
        // section would appear above the one a viewer had just been looking at.
        val names = toggledLibraryViews(
            toggledLibraryViews("", LibraryView.DROPPED),
            LibraryView.WATCHING,
        )
        assertEquals(listOf(LibraryView.WATCHING, LibraryView.DROPPED), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `tapping a chosen list again removes it`() {
        val names = toggledLibraryViews(defaultLibraryViewNames(), LibraryView.WATCHING)
        assertEquals(listOf(LibraryView.WATCHLIST), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `turning every list off shows none of them`() {
        var names = defaultLibraryViewNames()
        names = toggledLibraryViews(names, LibraryView.WATCHING)
        names = toggledLibraryViews(names, LibraryView.WATCHLIST)
        assertEquals(emptyList<LibraryView>(), libraryViewsFrom(names, ALL))
    }

    @Test
    fun `a signed-out viewer falls back to the watchlist rather than an empty page`() {
        // The account's lists are still the saved selection; none of them can be shown yet.
        assertEquals(SIGNED_OUT, libraryViewsFrom("WATCHING,COMPLETED", SIGNED_OUT))
    }

    @Test
    fun `an available list survives the account's lists being unavailable`() {
        assertEquals(SIGNED_OUT, libraryViewsFrom(defaultLibraryViewNames(), SIGNED_OUT))
    }

    @Test
    fun `a name from an older build is ignored rather than crashing the page`() {
        // Saved state outlives a rename, and valueOf on the missing name would have thrown.
        assertEquals(
            listOf(LibraryView.PAUSED),
            libraryViewsFrom("ON_HOLD,PAUSED", ALL),
        )
    }
}
