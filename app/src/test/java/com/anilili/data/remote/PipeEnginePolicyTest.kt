package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeEnginePolicyTest {

    /** The two devices this exists for: TCL UnionTV and MiTV-AESP0, both low-RAM televisions. */
    @Test
    fun `a low-RAM television gets no pipe engine`() {
        assertFalse(canHostPipeEngine(isTv = true, isLowRamDevice = true))
    }

    /**
     * A television that reports enough memory keeps the pipe. The reports are about the engine
     * running in-process, which is what `isLowRamDevice` forces; a roomier box does not pay it.
     */
    @Test
    fun `a television with room keeps the pipe`() {
        assertTrue(canHostPipeEngine(isTv = true, isLowRamDevice = false))
    }

    /**
     * Phones are untouched even when low-RAM. They were never the report, they are not the shape
     * of device that spends minutes on a resolve overlay, and taking Miruro off them would be a
     * loss with nothing bought for it.
     */
    @Test
    fun `a low-RAM phone keeps the pipe`() {
        assertTrue(canHostPipeEngine(isTv = false, isLowRamDevice = true))
        assertTrue(canHostPipeEngine(isTv = false, isLowRamDevice = false))
    }
}
