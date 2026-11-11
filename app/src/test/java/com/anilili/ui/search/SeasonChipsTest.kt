package com.anilili.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonChipsTest {

    @Test
    fun `the year chips run from this year backwards`() {
        val years = seasonYearChoices(currentYear = 2026, selected = null)

        assertEquals(SEASON_YEAR_CHOICES, years.size)
        assertEquals(2026, years.first())
        assertEquals(2016, years.last())
        assertEquals(years.sortedDescending(), years)
    }

    @Test
    fun `a year inside the window does not appear twice`() {
        val years = seasonYearChoices(currentYear = 2026, selected = 2024)

        assertEquals(SEASON_YEAR_CHOICES, years.size)
        assertEquals(1, years.count { it == 2024 })
    }

    @Test
    fun `a year typed outside the window is shown rather than dropped`() {
        // Otherwise the box above narrows the results to 1999 while every chip in the season row
        // reads unselected, and nothing on screen says which year is being asked for.
        val older = seasonYearChoices(currentYear = 2026, selected = 1999)
        assertTrue(1999 in older)
        assertEquals(1999, older.last())

        val ahead = seasonYearChoices(currentYear = 2026, selected = 2030)
        assertTrue(2030 in ahead)
        assertEquals(2030, ahead.first())
    }

    @Test
    fun `a season says which year it will return, and says so honestly with no year`() {
        assertEquals("Summer 2025", seasonChipLabel("Summer", 2025))
        // "Summer" alone really is every Summer there has been — AniList answers season without a
        // seasonYear across all years — so the bare label is the true one, not an unfinished one.
        assertEquals("Summer", seasonChipLabel("Summer", null))
    }
}
