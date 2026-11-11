package com.anilili.data

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SourceVariant
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSourceResolutionTest {

    @Test
    fun `hard-sub cooldown does not hide soft-sub on the same provider`() {
        val cooldowns = SourceFailureCooldowns { 1_000L }
        cooldowns.record(
            provider = "bonk",
            anilistId = 2890,
            episode = 1.0,
            kind = SourceFailureKind.FAILURE,
            error = IOException("pipe request failed (HTTP 444)"),
            variant = SourceVariant.HARD_SUB,
        )

        val cooling = cooldowns.coolingSources(
            anilistId = 2890,
            episode = 1.0,
            sources = listOf(
                ProviderSourceIdentity("bonk", SourceVariant.HARD_SUB),
                ProviderSourceIdentity("bonk", SourceVariant.SOFT_SUB),
            ),
        )

        assertEquals(setOf(ProviderSourceIdentity("bonk", SourceVariant.HARD_SUB)), cooling)
    }

    @Test
    fun `a hand-picked server is attempted even while it is cooling`() {
        // The picker keeps a failed server visible and faded rather than removing it, so pressing
        // it has to reach it. Only the pair that was picked is exempt: the rest of the walk still
        // skips what is cooling, which is what stops the fallback wasting the viewer's time.
        val cooling = setOf(
            ProviderSourceIdentity("ally", SourceVariant.HARD_SUB),
            ProviderSourceIdentity("ally", SourceVariant.SOFT_SUB),
            ProviderSourceIdentity("kaa", SourceVariant.GENERIC_SUB),
        )

        val honored = coolingSourcesToHonor(
            cooling = cooling,
            // Comes through UI state, so the case need not match the catalogue's.
            preferred = "Ally",
            preferredVariant = SourceVariant.HARD_SUB,
            honorCooldownForPreferred = false,
        )

        assertEquals(
            setOf(
                ProviderSourceIdentity("ally", SourceVariant.SOFT_SUB),
                ProviderSourceIdentity("kaa", SourceVariant.GENERIC_SUB),
            ),
            honored,
        )
    }

    @Test
    fun `an automatic resolve still honours every cooldown`() {
        // Nothing here is the viewer's choice, so a cooldown is exactly the right advice.
        val cooling = setOf(ProviderSourceIdentity("ally", SourceVariant.HARD_SUB))

        assertEquals(
            cooling,
            coolingSourcesToHonor(
                cooling = cooling,
                preferred = "ally",
                preferredVariant = SourceVariant.HARD_SUB,
                honorCooldownForPreferred = true,
            ),
        )
    }

    @Test
    fun `episode provider cooldown prevents immediate retry without affecting other episodes`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        val duration = cooldowns.record(
            provider = "kaa",
            anilistId = 123,
            episode = 2.0,
            kind = SourceFailureKind.FAILURE,
            error = IllegalStateException("KickAssAnime episode 2 has no native HLS server"),
        )

        assertEquals(10 * 60_000L, duration)
        assertEquals(setOf("kaa"), cooldowns.coolingProviders(123, 2.0, listOf("kaa", "animegg")))
        assertTrue(cooldowns.coolingProviders(123, 3.0, listOf("kaa")).isEmpty())
        now += duration
        assertTrue(cooldowns.coolingProviders(123, 2.0, listOf("kaa")).isEmpty())
    }

    @Test
    fun `successful resolution does not clear a playback failure cooldown`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        val duration = cooldowns.record(
            provider = "anikoto",
            anilistId = 813,
            episode = 87.0,
            kind = SourceFailureKind.PLAYBACK,
        )
        cooldowns.clearResolved("anikoto", 813, 87.0)

        assertEquals(5 * 60_000L, duration)
        assertEquals(setOf("anikoto"), cooldowns.coolingProviders(813, 87.0, listOf("anikoto")))
        now += duration
        assertTrue(cooldowns.coolingProviders(813, 87.0, listOf("anikoto")).isEmpty())
    }

    @Test
    fun `repeated provider timeouts briefly open a cross-title circuit breaker`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        cooldowns.record("animegg", 1, 1.0, SourceFailureKind.TIMEOUT)
        assertTrue(cooldowns.coolingProviders(2, 1.0, listOf("animegg")).isEmpty())

        now += 1_000L
        cooldowns.record("animegg", 2, 1.0, SourceFailureKind.TIMEOUT)

        assertEquals(setOf("animegg"), cooldowns.coolingProviders(3, 1.0, listOf("animegg")))
        assertTrue(cooldowns.coolingProviders(3, 1.0, listOf("kaa")).isEmpty())
    }

    @Test
    fun `a rate-limited stream host cools the whole provider, not just the episode`() {
        // pewe is AniDB, and on 2026-09-10 `hls.anidb.app` answered 429 (Cloudflare 1027 — the
        // origin over its plan) to every stream a tester opened. pewe *resolved* perfectly each
        // time, so nothing here ever saw a failure: the playback path recorded its message and
        // then dropped it before the cooldown could read it, and PLAYBACK was excluded from the
        // cross-title breaker outright. The server kept coming back around and kept playing
        // nothing.
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        val duration = cooldowns.record(
            provider = "pewe",
            anilistId = 99147,
            episode = 7.0,
            kind = SourceFailureKind.PLAYBACK,
            playbackMessage = "Source error (HTTP 429)",
        )

        assertEquals(10 * 60_000L, duration)
        // The point of the change: a *different* title is covered too, on one failure.
        assertEquals(setOf("pewe"), cooldowns.coolingProviders(16498, 1.0, listOf("pewe")))
    }

    @Test
    fun `a rate-limited resolve cools the provider the same as a rate-limited stream`() {
        // The resolver reaches the same branch by a different road. No player is involved, so there
        // is no `h429` to read and the status survives only in the sentence the provider wrote —
        // AnimeOnsen's reads "AnimeOnsen answered HTTP 429 for /...". That is why the prose half of
        // the test cannot simply be deleted in favour of the platform code, and why this path is
        // the one `SOURCE_RATE_LIMITED` is reported from.
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        val duration = cooldowns.record(
            provider = "animeonsen",
            anilistId = 21,
            episode = 1075.0,
            kind = SourceFailureKind.FAILURE,
            error = IllegalStateException("AnimeOnsen answered HTTP 429 for /v4/content/search"),
        )

        assertEquals(10 * 60_000L, duration)
        assertEquals(setOf("animeonsen"), cooldowns.coolingProviders(16498, 1.0, listOf("animeonsen")))
    }

    @Test
    fun `a stream host in maintenance cools the provider after it says so twice`() {
        // `anidb.app` answered 503 alongside the 429 above. A 5xx is the host talking about itself
        // and it will say the same to the next episode, so it counts toward the breaker — but only
        // on the second one inside the window, which is what separates an outage from a blip.
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        cooldowns.record("pewe", 1, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "HTTP 503 from the video server")
        assertTrue(cooldowns.coolingProviders(2, 1.0, listOf("pewe")).isEmpty())

        now += 1_000L
        cooldowns.record("pewe", 2, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "HTTP 503 from the video server")

        assertEquals(setOf("pewe"), cooldowns.coolingProviders(3, 1.0, listOf("pewe")))
    }

    @Test
    fun `an ordinary broken stream is still held against the file and not the server`() {
        // The reason PLAYBACK was excluded from the breaker in the first place, and it still is:
        // one unplayable file says nothing about the next episode, and dropping a server over it
        // would cost a viewer a working source for five minutes across their whole library.
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        cooldowns.record("bonk", 1, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "Source error")
        cooldowns.record("bonk", 2, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "Source error")

        assertEquals(setOf("bonk"), cooldowns.coolingProviders(1, 1.0, listOf("bonk")))
        assertTrue(cooldowns.coolingProviders(3, 1.0, listOf("bonk")).isEmpty())
    }

    /**
     * The same two findings, read from the player's own code instead of its prose.
     *
     * The message is no longer where the status lives — a playback failure now arrives as
     * `PLAY-02/h429` — so the branches that earned these cooldowns have to be reachable from the
     * code alone, or the AniDB session above would repeat itself the next time anyone reworded a
     * sentence.
     */
    @Test
    fun `a rate-limited host is recognised from the player's code with no message at all`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        val duration = cooldowns.record(
            provider = "pewe",
            anilistId = 99147,
            episode = 7.0,
            kind = SourceFailureKind.PLAYBACK,
            playbackMessage = "Lost the connection to this server.",
            platformCode = "h429",
        )

        assertEquals(10 * 60_000L, duration)
        assertEquals(setOf("pewe"), cooldowns.coolingProviders(16498, 1.0, listOf("pewe")))
    }

    @Test
    fun `a 5xx read from the player's code counts toward the breaker`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }

        cooldowns.record("pewe", 1, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "", platformCode = "h503")
        assertTrue(cooldowns.coolingProviders(2, 1.0, listOf("pewe")).isEmpty())

        now += 1_000L
        cooldowns.record("pewe", 2, 1.0, SourceFailureKind.PLAYBACK, playbackMessage = "", platformCode = "h503")

        assertEquals(setOf("pewe"), cooldowns.coolingProviders(3, 1.0, listOf("pewe")))
    }

    @Test
    fun `a refusal read from the player's code keeps its episode-scoped cooldown`() {
        val cooldowns = SourceFailureCooldowns { 1_000L }

        val duration = cooldowns.record(
            provider = "bonk",
            anilistId = 1,
            episode = 1.0,
            kind = SourceFailureKind.PLAYBACK,
            playbackMessage = "",
            platformCode = "h403",
        )

        assertEquals(5 * 60_000L, duration)
        // A 403 is about this link, not about the host, so the provider stays offered elsewhere.
        assertTrue(cooldowns.coolingProviders(2, 1.0, listOf("bonk")).isEmpty())
    }

    @Test
    fun `only an http status can admit to an outage`() {
        assertTrue(serverOutageStatus("h503"))
        assertTrue(serverOutageStatus("h500"))
        assertEquals(false, serverOutageStatus("h403"))
        assertEquals(false, serverOutageStatus(null))
        // Media3's own 2004 says only that *a* status was bad, never which, so it admits nothing.
        assertEquals(false, serverOutageStatus("m2004"))
    }

    @Test
    fun `only the server's own admission counts as an outage`() {
        // A timeout or a reset during playback is as likely to be the viewer's link as the host's,
        // so it stays out. A 5xx is the host answering about itself.
        assertTrue(serverOutageMessage("http 503 from the video server"))
        assertTrue(serverOutageMessage("source error (http 500)"))
        assertEquals(false, serverOutageMessage("source error"))
        assertEquals(false, serverOutageMessage("playback stalled for 20 seconds"))
        assertEquals(false, serverOutageMessage("java.net.sockettimeoutexception"))
        // 4xx is not an outage: 403 and 404 have their own, shorter, episode-scoped cooldowns.
        assertEquals(false, serverOutageMessage("source error (http 403)"))
    }

    @Test
    fun `successful provider resolution closes its global circuit breaker`() {
        var now = 1_000L
        val cooldowns = SourceFailureCooldowns { now }
        cooldowns.record("animegg", 1, 1.0, SourceFailureKind.TIMEOUT)
        cooldowns.record("animegg", 2, 1.0, SourceFailureKind.TIMEOUT)

        cooldowns.clearResolved("animegg", 3, 1.0)

        assertTrue(cooldowns.coolingProviders(4, 1.0, listOf("animegg")).isEmpty())
    }

    @Test
    fun `stalled preferred provider times out and falls through to next source`() = runBlocking {
        val attempts = mutableListOf<String>()
        val timedOut = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("kaa")),
            excludedProviders = emptySet(),
            maxAttempts = 5,
            attemptTimeoutMs = 75L,
            onAttempt = attempts::add,
            onTimeout = timedOut::add,
        ) { candidate ->
            if (candidate.provider == "animegg") awaitCancellation()
            playableSources(candidate.provider)
        }

        assertEquals(listOf("animegg", "kaa"), attempts)
        assertEquals(listOf("animegg"), timedOut)
        assertEquals(setOf("animegg"), result.unavailableProviders)
        assertEquals("kaa", result.resolved?.provider)
        assertTrue(result.resolved?.sources?.streams?.isNotEmpty() == true)
    }

    @Test
    fun `provider failure is marked unavailable and does not stop fallback`() = runBlocking {
        val failures = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("allanime")),
            excludedProviders = emptySet(),
            maxAttempts = 5,
            attemptTimeoutMs = 1_000L,
            onFailure = { provider, _ -> failures += provider },
        ) { candidate ->
            if (candidate.provider == "animegg") throw IOException("host unavailable")
            playableSources(candidate.provider)
        }

        assertEquals(listOf("animegg"), failures)
        assertEquals(setOf("animegg"), result.unavailableProviders)
        assertEquals("allanime", result.resolved?.provider)
    }

    @Test
    fun `failed hard-sub falls through to soft-sub before leaving provider`() = runBlocking {
        val attempts = mutableListOf<SourceVariant>()
        val hard = ProviderSourceCandidate("bonk", "ponyo", SourceVariant.HARD_SUB)
        val soft = ProviderSourceCandidate("bonk", "ponyo", SourceVariant.SOFT_SUB)

        val result = resolveProviderCandidates(
            candidates = listOf(hard, soft, candidate("kiwi")),
            excludedProviders = emptySet(),
            maxAttempts = 3,
            attemptTimeoutMs = 1_000L,
        ) { candidate ->
            attempts += candidate.variant
            if (candidate.variant == SourceVariant.HARD_SUB) {
                throw IOException("pipe request failed (HTTP 444)")
            }
            playableSources(candidate.provider)
        }

        assertEquals(listOf(SourceVariant.HARD_SUB, SourceVariant.SOFT_SUB), attempts)
        assertEquals("bonk", result.resolved?.provider)
        assertEquals(SourceVariant.SOFT_SUB, result.resolved?.variant)
        assertEquals(
            setOf(ProviderSourceIdentity("bonk", SourceVariant.HARD_SUB)),
            result.unavailableSources,
        )
    }

    @Test
    fun `excluded providers do not consume the attempt budget`() = runBlocking {
        val attempts = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("animegg"), candidate("kaa"), candidate("allanime")),
            excludedProviders = setOf("animegg"),
            maxAttempts = 1,
            attemptTimeoutMs = 1_000L,
            onAttempt = attempts::add,
        ) { candidate -> playableSources(candidate.provider) }

        assertEquals(listOf("kaa"), attempts)
        assertEquals("kaa", result.resolved?.provider)
    }

    @Test
    fun `an explicitly interactive provider can receive a longer timeout`() = runBlocking {
        val timedOut = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("allanime"), candidate("kaa")),
            excludedProviders = emptySet(),
            maxAttempts = 2,
            attemptTimeoutMs = 20L,
            attemptTimeoutMsFor = { provider -> if (provider == "allanime") 200L else 20L },
            onTimeout = timedOut::add,
        ) { candidate ->
            if (candidate.provider == "allanime") delay(60L)
            playableSources(candidate.provider)
        }

        assertTrue(timedOut.isEmpty())
        assertEquals("allanime", result.resolved?.provider)
    }

    @Test
    fun `parent cancellation is not mistaken for a provider timeout`() = runBlocking {
        var result: ProviderCandidateResolution? = null
        var timeout: TimeoutCancellationException? = null

        try {
            withTimeout(75L) {
                result = resolveProviderCandidates(
                    candidates = listOf(candidate("animegg")),
                    excludedProviders = emptySet(),
                    maxAttempts = 1,
                    attemptTimeoutMs = 5_000L,
                ) { awaitCancellation() }
            }
        } catch (e: TimeoutCancellationException) {
            timeout = e
        }

        assertNull(result)
        assertTrue(timeout != null)
    }

    /**
     * The synchronous providers block inside `runInterruptible`, so cancelling one interrupts its
     * socket read — and OkHttp reports that as an `InterruptedIOException`, not as an
     * `InterruptedException` that the coroutine machinery would recognise. It arrives here as an
     * ordinary exception and used to be recorded as "this server failed", cooling a healthy
     * provider for minutes because the viewer left the screen.
     */
    @Test
    fun `an interrupted read during cancellation is not recorded as a provider failure`() = runBlocking {
        val failures = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            resolveProviderCandidates(
                candidates = listOf(candidate("toonhub"), candidate("kaa")),
                excludedProviders = emptySet(),
                maxAttempts = 2,
                attemptTimeoutMs = 5_000L,
                onFailure = { provider, _ -> failures += provider },
            ) {
                runInterruptible(Dispatchers.IO) {
                    entered.complete(Unit)
                    try {
                        Thread.sleep(5_000L)
                        error("the interrupt never arrived")
                    } catch (interrupted: InterruptedException) {
                        throw InterruptedIOException("interrupted")
                    }
                }
            }
        }

        entered.await()
        job.cancelAndJoin()

        assertTrue("a cancelled attempt must not cool the provider", failures.isEmpty())
    }

    /**
     * The same interrupt, caught by the provider itself — several of them wrap their own scrape in
     * `runCatching` and answer with nothing rather than throwing. An empty answer is otherwise
     * indistinguishable from a server that has no copy of the episode, which also cools it.
     *
     * A provider reached through `runInterruptible` is already covered here, because `withContext`
     * fails a cancelled coroutine rather than delivering its value — this pins that, and covers the
     * pipe-backed providers, which return without one.
     */
    @Test
    fun `an empty answer produced by cancellation is not recorded as an empty provider`() = runBlocking {
        val empties = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            resolveProviderCandidates(
                candidates = listOf(candidate("toonhub")),
                excludedProviders = emptySet(),
                maxAttempts = 1,
                attemptTimeoutMs = 5_000L,
                onEmpty = empties::add,
            ) {
                runInterruptible(Dispatchers.IO) {
                    entered.complete(Unit)
                    runCatching { Thread.sleep(5_000L) }
                    SourcesResult(emptyList(), emptyList(), null, null)
                }
            }
        }

        entered.await()
        job.cancelAndJoin()

        assertTrue("a cancelled attempt must not cool the provider", empties.isEmpty())
    }

    /** The guard must not disarm the ordinary case: a real interrupted read still cools it. */
    @Test
    fun `an interrupted read outside cancellation is still a provider failure`() = runBlocking {
        val failures = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("toonhub"), candidate("kaa")),
            excludedProviders = emptySet(),
            maxAttempts = 2,
            attemptTimeoutMs = 1_000L,
            onFailure = { provider, _ -> failures += provider },
        ) { candidate ->
            if (candidate.provider == "toonhub") throw InterruptedIOException("read timed out")
            playableSources(candidate.provider)
        }

        assertEquals(listOf("toonhub"), failures)
        assertEquals("kaa", result.resolved?.provider)
    }

    /** An empty answer from a live attempt is still an empty server. */
    @Test
    fun `an empty answer outside cancellation is still an empty provider`() = runBlocking {
        val empties = mutableListOf<String>()

        val result = resolveProviderCandidates(
            candidates = listOf(candidate("toonhub"), candidate("kaa")),
            excludedProviders = emptySet(),
            maxAttempts = 2,
            attemptTimeoutMs = 1_000L,
            onEmpty = empties::add,
        ) { candidate ->
            if (candidate.provider == "toonhub") SourcesResult(emptyList(), emptyList(), null, null)
            else playableSources(candidate.provider)
        }

        assertEquals(listOf("toonhub"), empties)
        assertEquals("kaa", result.resolved?.provider)
    }

    private fun candidate(provider: String) = ProviderSourceCandidate(provider, "episode-$provider")

    private fun playableSources(provider: String) = SourcesResult(
        streams = listOf(
            StreamItem(
                url = "https://$provider.example/master.m3u8",
                type = "hls",
                quality = "auto",
                audio = "sub",
                referer = null,
                isActive = true,
                width = null,
                height = null,
            ),
        ),
        subtitles = emptyList(),
        skip = null,
        download = null,
    )
}
