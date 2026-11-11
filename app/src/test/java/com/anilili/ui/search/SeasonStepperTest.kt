package com.anilili.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonStepperTest {
    @Test
    fun calendarMonthsMapToAniListQuarters() {
        assertEquals(SeasonDate("WINTER", 2026), seasonDateFor(month = 1, year = 2026))
        assertEquals(SeasonDate("WINTER", 2026), seasonDateFor(month = 3, year = 2026))
        assertEquals(SeasonDate("SPRING", 2026), seasonDateFor(month = 4, year = 2026))
        assertEquals(SeasonDate("SUMMER", 2026), seasonDateFor(month = 9, year = 2026))
        assertEquals(SeasonDate("FALL", 2026), seasonDateFor(month = 10, year = 2026))
        assertEquals(SeasonDate("FALL", 2026), seasonDateFor(month = 12, year = 2026))
    }

    @Test
    fun steppingForwardRollsTheYearAtWinter() {
        assertEquals(
            SeasonDate("WINTER", 2025),
            stepSeasonDate(SeasonDate("FALL", 2024), delta = 1),
        )
        assertEquals(
            SeasonDate("SPRING", 2025),
            stepSeasonDate(SeasonDate("FALL", 2024), delta = 2),
        )
        assertEquals(
            SeasonDate("FALL", 2025),
            stepSeasonDate(SeasonDate("FALL", 2024), delta = 4),
        )
    }

    @Test
    fun steppingBackwardRollsTheYearAtFall() {
        assertEquals(
            SeasonDate("FALL", 2024),
            stepSeasonDate(SeasonDate("WINTER", 2025), delta = -1),
        )
        assertEquals(
            SeasonDate("SUMMER", 2024),
            stepSeasonDate(SeasonDate("WINTER", 2025), delta = -2),
        )
        assertEquals(
            SeasonDate("WINTER", 2024),
            stepSeasonDate(SeasonDate("WINTER", 2025), delta = -4),
        )
    }

    @Test
    fun theStepperWalksTwoSeasonsPastToday() {
        val today = SeasonDate("SUMMER", 2026)
        assertEquals(
            SeasonDate("WINTER", 2027),
            stepSeasonWithinBounds(SeasonDate("FALL", 2026), delta = 1, today = today),
        )
        assertNull(stepSeasonWithinBounds(SeasonDate("WINTER", 2027), delta = 1, today = today))
    }

    @Test
    fun theStepperStopsAtTheEarliestYearTheYearBoxAccepts() {
        val today = SeasonDate("SUMMER", 2026)
        assertEquals(
            SeasonDate("FALL", 1900),
            stepSeasonWithinBounds(SeasonDate("SUMMER", 1900), delta = 1, today = today),
        )
        assertNull(stepSeasonWithinBounds(SeasonDate("WINTER", 1900), delta = -1, today = today))
    }

    @Test
    fun barLabelsCapitaliseTheEnumAndCarryTheYear() {
        assertEquals("Summer 2024", seasonLabel("SUMMER", 2024))
        assertEquals("Fall", seasonLabel("FALL", null))
    }

    @Test
    fun decadeLabelsDescribeTheBoundsTheyFilter() {
        assertEquals("2020s", yearRangeLabel(2020, 2029))
        assertEquals("1985–1994", yearRangeLabel(1985, 1994))
        assertEquals("Before 1960", yearRangeLabel(null, 1959))
        assertEquals("2010+", yearRangeLabel(2010, null))
        assertEquals("Any year", yearRangeLabel(null, null))
    }

    @Test
    fun decadesRunBackFromTheCurrentOneWithAnOpenEndedOldestBucket() {
        val decades = decadeChoices(currentYear = 2026)
        assertEquals(listOf("2020s", "2010s", "2000s", "1990s", "1980s", "Earlier"), decades.map { it.label })
        assertEquals(RangeChoice("2020s", 2020, 2029), decades.first())
        assertEquals(RangeChoice("Earlier", null, 1979), decades.last())
    }
}
