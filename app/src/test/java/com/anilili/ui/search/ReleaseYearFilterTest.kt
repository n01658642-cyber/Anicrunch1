package com.anilili.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseYearFilterTest {
    @Test
    fun `a part-typed year does not filter yet`() {
        // Every one of these used to reset the filter, which cleared the box the digit had just
        // been typed into. The box could never hold anything.
        assertNull(releaseYearFilterValue("2"))
        assertNull(releaseYearFilterValue("20"))
        assertNull(releaseYearFilterValue("202"))
    }

    @Test
    fun `a complete year filters`() {
        assertEquals(2024, releaseYearFilterValue("2024"))
        assertEquals(1900, releaseYearFilterValue("1900"))
        assertEquals(2100, releaseYearFilterValue("2100"))
    }

    @Test
    fun `a complete year outside the range does not filter`() {
        assertNull(releaseYearFilterValue("1899"))
        assertNull(releaseYearFilterValue("2101"))
        assertNull(releaseYearFilterValue("0000"))
    }

    @Test
    fun `an empty box clears the filter`() {
        assertNull(releaseYearFilterValue(""))
    }
}
