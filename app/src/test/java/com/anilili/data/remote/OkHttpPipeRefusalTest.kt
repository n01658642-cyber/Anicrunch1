package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpPipeRefusalTest {

    @Test
    fun `a 444 is the same answer on every mirror so the walk stops`() {
        assertFalse(pipeWalkContinuesAfter(PIPE_NOT_THIS_EPISODE_OR_VARIANT))
    }

    @Test
    fun `a challenge on one edge does not rule out the others`() {
        // The four mirrors exist for exactly this: 403 and 503 are the zone hardening, and it has
        // been observed hardening per-edge rather than per-zone.
        assertTrue(pipeWalkContinuesAfter(403))
        assertTrue(pipeWalkContinuesAfter(503))
    }

    @Test
    fun `a transport failure with no status keeps walking`() {
        // -1 is "the request never produced a response" — a timeout or a dropped handshake, which
        // says nothing at all about the next mirror.
        assertTrue(pipeWalkContinuesAfter(-1))
    }

    @Test
    fun `ordinary server errors still try the next mirror`() {
        assertTrue(pipeWalkContinuesAfter(500))
        assertTrue(pipeWalkContinuesAfter(502))
    }
}
