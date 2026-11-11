package com.anilili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandingTest {
    @Test
    fun `tv suffix belongs only to the tv flavor`() {
        assertEquals("TV", tvBrandSuffixFor("tv"))
        assertNull(tvBrandSuffixFor("mobile"))
        assertNull(tvBrandSuffixFor(""))
    }
}
