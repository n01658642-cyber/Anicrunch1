package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DubIndexTest {
    @Test fun `overlay adds ids without losing bundled positives`() {
        assertEquals(setOf(1, 2, 3), parseDubIndex("[1,2]") + parseDubIndex("[2,3,3]"))
    }
    @Test fun `invalid downloads cannot become cached indices`() {
        listOf("[]", "[-1]", "[0]", "[1.5]", "[2147483648]", "<html>404</html>").forEach {
            assertThrows(Exception::class.java) { parseDubIndex(it) }
        }
    }
}
