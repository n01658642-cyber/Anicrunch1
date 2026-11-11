package com.anilili.data

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRaceTest {

    private fun candidate(name: String) = ProviderSourceCandidate(name, "watch/$name/1/sub/$name-1")

    private fun playable(name: String) = SourcesResult(
        streams = listOf(StreamItem("https://$name/master.m3u8", "hls", null, null, null, true, null, null)),
        subtitles = emptyList(),
        skip = null,
        download = null,
    )

    private suspend fun race(
        candidates: List<ProviderSourceCandidate>,
        outcomes: MutableList<String>,
        hedgeDelayMs: Long = 100,
        maxAttempts: Int = 5,
        isExclusive: (String) -> Boolean = { false },
        started: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        load: suspend (ProviderSourceCandidate) -> SourcesResult,
    ) = resolveProviderCandidatesHedged(
        candidates = candidates,
        excludedProviders = emptySet(),
        maxAttempts = maxAttempts,
        hedgeDelayMs = hedgeDelayMs,
        attemptTimeoutMsFor = { 5_000 },
        isExclusive = isExclusive,
        onAttempt = { started += it },
        onSourceOutcome = { c, outcome, _ -> outcomes += "${c.provider}:${outcome.name}" },
        load = load,
    )

    @After
    fun reset() = ProviderSpeed.resetForTest()

    @Test
    fun `a quick first choice wins alone and nothing else is asked`() = runBlocking {
        val outcomes = mutableListOf<String>()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val result = race(listOf(candidate("anipm"), candidate("zoko")), outcomes, started = started) {
            delay(10)
            playable(it.provider)
        }
        assertEquals("anipm", result.resolved?.provider)
        assertEquals(listOf("anipm"), started)
        assertEquals(listOf("anipm:OK"), outcomes)
    }

    @Test
    fun `a slow first choice is hedged and the quicker second wins without the loser being blamed`() = runBlocking {
        val outcomes = mutableListOf<String>()
        val started = Collections.synchronizedList(mutableListOf<String>())
        val result = race(listOf(candidate("slow"), candidate("quick")), outcomes, started = started) {
            if (it.provider == "slow") awaitCancellation()
            delay(20)
            playable(it.provider)
        }
        assertEquals("quick", result.resolved?.provider)
        assertEquals(listOf("slow", "quick"), started)
        // The loser was cancelled, not refused, so nothing may be recorded against it.
        assertEquals(listOf("quick:OK"), outcomes)
        assertTrue(result.unavailableSources.isEmpty())
    }

    @Test
    fun `a refusal hands over at once instead of waiting out the hedge`() = runBlocking {
        val outcomes = mutableListOf<String>()
        val begun = System.nanoTime()
        val result = race(listOf(candidate("broken"), candidate("works")), outcomes, hedgeDelayMs = 2_000) {
            if (it.provider == "broken") throw IOException("HTTP 403")
            playable(it.provider)
        }
        val tookMs = (System.nanoTime() - begun) / 1_000_000
        assertEquals("works", result.resolved?.provider)
        assertEquals(listOf("broken:FAILED", "works:OK"), outcomes)
        assertTrue("took ${tookMs}ms, so it waited for the hedge", tookMs < 1_000)
    }

    @Test
    fun `a browser-backed resolver never runs beside another attempt`() = runBlocking {
        val outcomes = mutableListOf<String>()
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        var overlapped = false
        val result = race(
            listOf(candidate("reanime"), candidate("anipm")),
            outcomes,
            hedgeDelayMs = 20,
            isExclusive = { it == "reanime" },
        ) {
            if (inFlight.incrementAndGet() > 1) overlapped = true
            try {
                delay(if (it.provider == "reanime") 150 else 10)
                if (it.provider == "reanime") throw IOException("no stream") else playable(it.provider)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        assertEquals("anipm", result.resolved?.provider)
        assertFalse("an exclusive resolver shared the engine", overlapped)
    }

    @Test
    fun `nothing playable leaves every refusal on record`() = runBlocking {
        val outcomes = mutableListOf<String>()
        val result = race(listOf(candidate("a"), candidate("b")), outcomes) {
            SourcesResult(emptyList(), emptyList(), null, null)
        }
        assertNull(result.resolved)
        assertEquals(setOf("a", "b"), result.unavailableProviders)
        assertEquals(setOf("a:EMPTY", "b:EMPTY"), outcomes.toSet())
    }

    @Test
    fun `measured speed reorders servers and failures sink them`() {
        ProviderSpeed.resetForTest()
        // Both unmeasured and both fast by catalogue: the catalogue's own order breaks the tie.
        val before = ProviderSpeed.rank(listOf("anikoto", "anipm"))
        assertEquals(ProviderSpeed.rank(listOf("anipm", "anikoto")), before)

        repeat(5) { ProviderSpeed.record("anipm", succeeded = true, durationMs = 300) }
        repeat(5) { ProviderSpeed.record("anikoto", succeeded = true, durationMs = 1_500) }
        assertEquals(listOf("anipm", "anikoto"), ProviderSpeed.rank(listOf("anikoto", "anipm")))

        // Quick when it works is no use if it mostly does not.
        repeat(5) { ProviderSpeed.record("anipm", succeeded = false, durationMs = 8_000) }
        assertEquals(listOf("anikoto", "anipm"), ProviderSpeed.rank(listOf("anipm", "anikoto")))
    }

    @Test
    fun `each network keeps its own ranking and a new one borrows the device's`() {
        ProviderSpeed.resetForTest()
        ProviderSpeed.currentNetwork = { "wifi:home" }
        repeat(6) { ProviderSpeed.record("anipm", succeeded = true, durationMs = 300) }
        repeat(6) { ProviderSpeed.record("anikoto", succeeded = true, durationMs = 1_500) }

        // The carrier blocks anipm outright, while anikoto still answers.
        ProviderSpeed.currentNetwork = { "cell:22201" }
        repeat(6) { ProviderSpeed.record("anipm", succeeded = false, durationMs = 8_000) }
        repeat(6) { ProviderSpeed.record("anikoto", succeeded = true, durationMs = 1_800) }
        assertEquals(listOf("anikoto", "anipm"), ProviderSpeed.rank(listOf("anipm", "anikoto")))

        // Back home, the carrier's failures do not follow the phone in.
        ProviderSpeed.currentNetwork = { "wifi:home" }
        assertEquals(listOf("anipm", "anikoto"), ProviderSpeed.rank(listOf("anikoto", "anipm")))

        // A network never seen before has nothing of its own, so it uses the device-wide record.
        ProviderSpeed.currentNetwork = { "wifi:friend" }
        val device = ProviderSpeed.score("anikoto")
        ProviderSpeed.currentNetwork = { ProviderSpeed.DEVICE }
        assertEquals(device, ProviderSpeed.score("anikoto"), 0.0)
    }

    @Test
    fun `an answer served from memory does not flatter a server`() {
        ProviderSpeed.resetForTest()
        val cold = ProviderSpeed.score("zoko")
        ProviderSpeed.record("zoko", succeeded = true, durationMs = 2)
        assertEquals(cold, ProviderSpeed.score("zoko"), 0.0)
    }
}
