package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAnimeMkissaCryptoTest {
    @Test
    fun usesCurrentSevenDayEpochAndPreviousEpochDuringOneDayGrace() {
        val weekMs = 7L * 24L * 60L * 60L * 1_000L
        val dayMs = 24L * 60L * 60L * 1_000L

        assertEquals(
            listOf(2_952L, 2_953L),
            AllAnimeMkissaCrypto.epochCandidates(nowMs = 2_953L * weekMs + dayMs - 1L),
        )
        assertEquals(
            listOf(2_953L),
            AllAnimeMkissaCrypto.epochCandidates(nowMs = 2_953L * weekMs + dayMs),
        )
    }
}
