package com.anilili.ui.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The grid's date run is also the range its badges are fetched for, so an off-by-one here is not
 * just a misdrawn calendar — it is a month of counts lined up against the wrong days.
 */
class MonthGridDaysTest {

    @Test
    fun `a month starting mid-week leads with the days that finish the previous one`() {
        // September 2026 opens on a Tuesday, so a Monday-first grid starts on 31 August.
        val days = monthGridDays(YearMonth.of(2026, 9), DayOfWeek.MONDAY)

        assertEquals(LocalDate.of(2026, 8, 31), days.first())
        assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertTrue(LocalDate.of(2026, 9, 30) in days)
    }

    @Test
    fun `the week starts where the viewer's locale says it does`() {
        val sundayFirst = monthGridDays(YearMonth.of(2026, 9), DayOfWeek.SUNDAY)

        assertEquals(LocalDate.of(2026, 8, 30), sundayFirst.first())
        assertEquals(DayOfWeek.SUNDAY, sundayFirst.first().dayOfWeek)
    }

    @Test
    fun `every grid is whole weeks, and only as many as the month needs`() {
        for (month in 1..12) {
            for (start in DayOfWeek.entries) {
                val days = monthGridDays(YearMonth.of(2026, month), start)
                assertEquals("$month from $start is not whole weeks", 0, days.size % 7)
                assertTrue("$month from $start is oversized", days.size <= 42)
                // No wasted row: dropping the last week would cut into the month itself.
                assertTrue(
                    "$month from $start has a trailing week of nothing",
                    days.takeLast(7).any { YearMonth.from(it) == YearMonth.of(2026, month) },
                )
            }
        }
    }

    @Test
    fun `the run is consecutive and covers the whole month`() {
        val month = YearMonth.of(2026, 2)
        val days = monthGridDays(month, DayOfWeek.MONDAY)

        days.zipWithNext { a, b -> assertEquals(b, a.plusDays(1)) }
        assertEquals(month.lengthOfMonth(), days.count { YearMonth.from(it) == month })
    }

    @Test
    fun `a February that is exactly four weeks still draws as four`() {
        // 2027-02 starts on a Monday and has 28 days: the one shape with no neighbouring days.
        val days = monthGridDays(YearMonth.of(2027, 2), DayOfWeek.MONDAY)

        assertEquals(28, days.size)
        assertEquals(LocalDate.of(2027, 2, 1), days.first())
        assertEquals(LocalDate.of(2027, 2, 28), days.last())
    }
}
