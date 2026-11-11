package com.anilili.ui.watch

import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.StreamItem
import com.anilili.data.settings.DefaultQuality
import com.anilili.data.settings.PlayerContentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourcePolicyTest {

    @Test
    fun `embed caption list parses the JSON-quoted answer evaluateJavascript returns`() {
        // What MegaPlay's JW Player answered on Frieren, as WebView hands it back: a JSON string.
        val raw = "\"{\\\"current\\\":2,\\\"labels\\\":[\\\"Arabic\\\",\\\"English\\\",\\\"French\\\"]}\""
        assertEquals(
            EmbedCaptionList(current = 2, labels = listOf("Arabic", "English", "French")),
            parseEmbedCaptionList(raw),
        )
        assertNull(parseEmbedCaptionList("null"))
        assertNull(parseEmbedCaptionList(null))
        assertNull(parseEmbedCaptionList("\"not json\""))
    }

    @Test
    fun `a subtitle the stream carries twice is listed once, as the copy that is playing`() {
        // Ani.pm: each language as a side-loaded file and again inside the manifest.
        val tracks = listOf("English" to false, "Arabic" to false, "English" to true, "Arabic" to false)
        assertEquals(
            listOf("English" to true, "Arabic" to false),
            collapseSameNamed(tracks, { it.first }, { it.second }),
        )
        // Nothing playing: the first copy, the side-loaded file, stands in.
        val idle = listOf("English" to false, "English" to false)
        assertTrue(collapseSameNamed(idle, { it.first }, { it.second }).single() === idle.first())
    }

    @Test
    fun `track names keep the label that tells same-language tracks apart`() {
        // ReAnime's three English tracks, which Media3's name provider called "English" three times.
        assertEquals(
            "English (Full Subtitles [9volt])",
            trackDisplayName("English", "English (Full Subtitles [9volt])"),
        )
        assertEquals("English (Signs & Songs)", trackDisplayName("English", "English (Signs & Songs)"))
        assertEquals("Japanese · Native", trackDisplayName("Japanese", "Native"))
        assertEquals("English", trackDisplayName("English", "english"))
        assertEquals("English", trackDisplayName("English", " "))
        assertEquals("English", trackDisplayName("English", null))
    }

    @Test
    fun `saved screen size maps to the native player resize mode`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, playerResizeMode(PlayerContentScale.FIT))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, playerResizeMode(PlayerContentScale.CROP))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, playerResizeMode(PlayerContentScale.FILL))
    }

    @Test
    fun `subtitle default can disable app captions for every audio category`() {
        assertEquals(false, subtitlesEnabledByDefault("sub", subtitlesByDefault = false, subtitlesWithDub = true))
        assertEquals(false, subtitlesEnabledByDefault("dub", subtitlesByDefault = true, subtitlesWithDub = false))
        assertEquals(true, subtitlesEnabledByDefault("sub", subtitlesByDefault = true, subtitlesWithDub = false))
        assertEquals(true, subtitlesEnabledByDefault("DUB", subtitlesByDefault = true, subtitlesWithDub = true))
    }

    @Test
    fun `a burnt-in picture does not also get a sidecar track switched on`() {
        // Bonk falls back from S-SUB to H-SUB on the same server and still returns the sidecar
        // files; switching one on drew a second line of text over the burnt-in one.
        assertEquals(
            false,
            subtitlesEnabledByDefault(
                "hsub",
                subtitlesByDefault = true,
                subtitlesWithDub = true,
                burnedInSubtitles = true,
            ),
        )
        // Soft subs on the same server are unaffected, and so is every existing caller.
        assertEquals(
            true,
            subtitlesEnabledByDefault(
                "ssub",
                subtitlesByDefault = true,
                subtitlesWithDub = false,
                burnedInSubtitles = false,
            ),
        )
    }

    @Test
    fun `turning captions off in the player outlives the episode it was turned off on`() {
        // The complaint this answers: the track policy is reset for every new item, so an "off"
        // chosen in the sheet was gone by the next episode and text came back on every time.
        val off = captionDefaultsAfterChoice(
            category = "sub",
            captionsOn = false,
            subtitlesByDefault = true,
            subtitlesWithDub = false,
        )
        assertEquals(false, off.subtitlesByDefault)
        assertEquals(
            false,
            subtitlesEnabledByDefault("sub", off.subtitlesByDefault, off.subtitlesWithDub),
        )
    }

    @Test
    fun `choosing a track turns the default back on for the episodes after it`() {
        val on = captionDefaultsAfterChoice(
            category = "sub",
            captionsOn = true,
            subtitlesByDefault = false,
            subtitlesWithDub = false,
        )
        assertEquals(true, on.subtitlesByDefault)
        // A sub episode says nothing about dubbed audio, so that switch is left where it was.
        assertEquals(false, on.subtitlesWithDub)
        assertEquals(true, subtitlesEnabledByDefault("sub", on.subtitlesByDefault, on.subtitlesWithDub))
    }

    @Test
    fun `off over a dub answers only for dubbed audio`() {
        // "Not over the dub" is its own setting. Folding this into the general default instead
        // would silently strip captions from every subtitled episode as well.
        val off = captionDefaultsAfterChoice(
            category = "dub",
            captionsOn = false,
            subtitlesByDefault = true,
            subtitlesWithDub = true,
        )
        assertEquals(true, off.subtitlesByDefault)
        assertEquals(false, off.subtitlesWithDub)
        assertEquals(false, subtitlesEnabledByDefault("dub", off.subtitlesByDefault, off.subtitlesWithDub))
        assertEquals(true, subtitlesEnabledByDefault("sub", off.subtitlesByDefault, off.subtitlesWithDub))
    }

    @Test
    fun `on over a dub has to set both switches to show anything`() {
        // The two defaults are an and: with the general one off, the dub switch alone shows nothing,
        // so the next dubbed episode would open without the captions just asked for.
        val on = captionDefaultsAfterChoice(
            category = "DUB",
            captionsOn = true,
            subtitlesByDefault = false,
            subtitlesWithDub = false,
        )
        assertEquals(true, on.subtitlesByDefault)
        assertEquals(true, on.subtitlesWithDub)
        assertEquals(true, subtitlesEnabledByDefault("dub", on.subtitlesByDefault, on.subtitlesWithDub))
    }

    @Test
    fun `playback speeds cover rewatching without crowding the slider`() {
        // The list is slider notches, so its length is the usability budget. It used to spend
        // eight of fifteen steps between 0.9x and 1.3x, leaving nothing above 2x for rewatching.
        assertTrue("normal speed must be selectable", 1f in PlaybackSpeeds)
        assertTrue("a nudge either side of normal is enough", PlaybackSpeeds.containsAll(listOf(0.9f, 1.1f)))
        assertTrue("rewatching wants the fast end", PlaybackSpeeds.containsAll(listOf(2f, 3f, 4f)))
        assertEquals("4x is the ceiling", 4f, PlaybackSpeeds.max())
        assertTrue("the slider stays readable", PlaybackSpeeds.size <= 15)

        assertEquals(PlaybackSpeeds.sorted(), PlaybackSpeeds)
        assertEquals(PlaybackSpeeds.distinct(), PlaybackSpeeds)
        assertEquals("2.5x", 2.5f.formatPlaybackSpeed())
        assertEquals("4x", 4f.formatPlaybackSpeed())
    }
    @Test
    fun `kiwi plays its direct renditions natively, not the embed`() {
        // Its renditions used to fail, which is why the embed led for months. The cause was Kwik's
        // filename-derived AES IV, fixed in KwikPlaylistDataSource; verified playing 1080p on a
        // Galaxy S25 on 2026-08-11. Native wins because the app's own controls, subtitle delay,
        // PiP and Cast cannot reach inside a WebView.
        val hls = stream("https://vault.example/1080.m3u8", "hls", "1080p", active = true)
        val embed = stream("https://kwik.example/e/id", "embed", "1080p", active = true)

        assertEquals(hls, pickProviderStream("kiwi", sources(hls, embed)))
    }

    @Test
    fun `a failed kiwi rendition falls back to the embed before giving up on kiwi`() {
        // The embed remains the safety net rather than the whole provider being abandoned for an
        // unrelated server on the first failure.
        val hls = stream("https://vault.example/1080.m3u8", "hls", "1080p", active = true)
        val embed = stream("https://kwik.example/e/id", "embed", "1080p", active = true)

        assertEquals(
            embed,
            nextProviderStream(
                provider = "kiwi",
                sources = sources(hls, embed),
                currentUrl = hls.url,
                failedUrls = setOf(hls.url),
            ),
        )

        // With both tried there is nothing left, so the caller moves on to another provider.
        assertNull(
            nextProviderStream(
                provider = "kiwi",
                sources = sources(hls, embed),
                currentUrl = embed.url,
                failedUrls = setOf(hls.url, embed.url),
            ),
        )
    }

    @Test
    fun `every provider with another distinct stream gets an internal fallback`() {
        val failed = stream("https://segments.example/master.m3u8", "hls", "auto", active = true)
        val alternate = stream("https://embed.example/e/id", "embed", "backup")

        assertTrue(hasProviderStreamFallback(sources(failed, alternate), failed.url))
        assertEquals(
            alternate,
            nextProviderStream("bonk", sources(failed, alternate), failed.url, setOf(failed.url)),
        )
        assertEquals(false, hasProviderStreamFallback(sources(failed), failed.url))
        assertEquals(false, hasProviderStreamFallback(sources(failed, failed), failed.url))
    }

    /**
     * Bonk lists two streams per episode and the first one is dead: its segments sit on a ByteDance
     * domain that answers `{"code":1004,"error":"domain forbidden"}` to every request, header or no
     * header. The second is a Cloudflare Worker that plays. Re-resolving used to hand back the dead
     * one every time, so changing server and changing back cost another fifteen seconds of retries.
     */
    @Test
    fun `a stream that already refused this episode is not chosen again`() {
        val dead = stream("https://vivibebe.site/public/stream/x/1080.m3u8", "hls", "auto", active = true)
        val worker = stream("https://proxy.workers.dev/x/1080p/index.m3u8", "hls", "auto")

        assertEquals(dead, firstUnrefusedStream("bonk", sources(dead, worker), emptySet()))
        assertEquals(worker, firstUnrefusedStream("bonk", sources(dead, worker), setOf(dead.url)))
    }

    /**
     * The same dead host, episode after episode. `refusedStreamUrls` is per-episode by design, so
     * every Next re-learned it from scratch and spent the whole buffer timeout doing it — 10.8s,
     * 11.0s, 12.7s and 18.2s on four consecutive episodes, each ending in the same 403.
     */
    @Test
    fun `a host that refused an earlier episode is not led with again`() {
        val dead = stream("https://vivibebe.site/public/stream/ep6/1080.m3u8", "hls", "auto", active = true)
        val worker = stream("https://proxy.workers.dev/ep6/1080p/index.m3u8", "hls", "auto")

        // This episode's URLs are new, so only the host memory can demote the dead one.
        assertEquals(
            worker,
            firstUnrefusedStream(
                provider = "bonk",
                sources = sources(dead, worker),
                refusedUrls = emptySet(),
                refusedHosts = setOf("vivibebe.site"),
            ),
        )
    }

    @Test
    fun `a refused host is demoted, never dropped`() {
        // One refusal is not grounds for leaving an episode unplayable: when the bad host is all
        // the provider published, its stream is still the answer.
        val onlyStream = stream("https://vivibebe.site/public/stream/ep7/1080.m3u8", "hls", "auto", active = true)

        assertEquals(
            onlyStream,
            firstUnrefusedStream(
                provider = "bonk",
                sources = sources(onlyStream),
                refusedUrls = emptySet(),
                refusedHosts = setOf("vivibebe.site"),
            ),
        )
    }

    @Test
    fun `this episode's own refusal still outranks the host memory`() {
        // A URL that refused *this* episode is skipped outright; the host list only orders what is
        // left. With the worker already refused here, the demoted host is what remains.
        val dead = stream("https://vivibebe.site/public/stream/ep8/1080.m3u8", "hls", "auto", active = true)
        val worker = stream("https://proxy.workers.dev/ep8/1080p/index.m3u8", "hls", "auto")

        assertEquals(
            dead,
            firstUnrefusedStream(
                provider = "bonk",
                sources = sources(dead, worker),
                refusedUrls = setOf(worker.url),
                refusedHosts = setOf("vivibebe.site"),
            ),
        )
    }

    @Test
    fun `stream hosts compare without scheme, port, credentials or www`() {
        assertEquals("vivibebe.site", streamHostOf("https://VIVIBEBE.site:443/public/stream/x.m3u8"))
        assertEquals("vivibebe.site", streamHostOf("https://www.vivibebe.site/x.m3u8"))
        assertEquals("vivibebe.site", streamHostOf("https://user:pw@vivibebe.site/x.m3u8"))
        // A relative or malformed URL names no host, and must not match every other one by being
        // equally empty.
        assertNull(streamHostOf("segment-0.ts"))
        assertNull(streamHostOf("https:///x.m3u8"))
    }

    @Test
    fun `every stream refused still starts one rather than nothing`() {
        // The fallback ladder runs from there exactly as before; an episode is never left blank
        // because both of its streams failed once.
        val dead = stream("https://vivibebe.site/public/stream/x/1080.m3u8", "hls", "auto", active = true)
        val worker = stream("https://proxy.workers.dev/x/1080p/index.m3u8", "hls", "auto")

        assertEquals(
            pickProviderStream("bonk", sources(dead, worker)),
            firstUnrefusedStream("bonk", sources(dead, worker), setOf(dead.url, worker.url)),
        )
        assertNull(firstUnrefusedStream("bonk", sources(), setOf(dead.url)))
    }

    @Test
    fun `ally uses progressive file before unreliable HLS mirror`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://mirror.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(direct, pickProviderStream("ally", sources(direct, hls)))
    }

    @Test
    fun `other providers retain active HLS preference`() {
        val direct = stream("https://files.example/video.mp4", "video", "1080p")
        val hls = stream("https://cdn.example/master.m3u8", "hls", "auto", active = true)

        assertEquals(hls, pickProviderStream("bonk", sources(direct, hls)))
    }

    @Test
    fun `allanime orders every stream before provider fallback`() {
        val mp4 = stream("https://files.example/video.mp4", "mp4", "AllAnime 1080p Yt-mp4", active = true)
        val primaryHls = stream("https://primary.example/master.m3u8", "hls", "AllAnime Ac")
        val backupHls = stream("https://backup.example/master.m3u8", "hls", "AllAnime Luf-hls")
        val embed = stream("https://embed.example/e/id", "embed", "AllAnime Ok")
        val result = sources(mp4, primaryHls, backupHls, embed)

        assertEquals(
            listOf(primaryHls, backupHls, mp4, embed),
            providerStreamOrder("allanime", result),
        )
    }

    @Test
    fun `allanime advances through untried streams and stops when exhausted`() {
        val primaryHls = stream("https://primary.example/master.m3u8", "hls", "AllAnime Ac", active = true)
        val backupHls = stream("https://backup.example/master.m3u8", "hls", "AllAnime Luf-hls")
        val mp4 = stream("https://files.example/video.mp4", "mp4", "AllAnime 1080p Yt-mp4")
        val embed = stream("https://embed.example/e/id", "embed", "AllAnime Ok")
        val result = sources(primaryHls, backupHls, mp4, embed)

        assertEquals(
            backupHls,
            nextProviderStream("allanime", result, primaryHls.url, setOf(primaryHls.url)),
        )
        assertEquals(
            mp4,
            nextProviderStream("allanime", result, backupHls.url, setOf(primaryHls.url, backupHls.url)),
        )
        assertEquals(
            embed,
            nextProviderStream("allanime", result, mp4.url, setOf(primaryHls.url, backupHls.url, mp4.url)),
        )
        assertNull(
            nextProviderStream(
                "allanime",
                result,
                embed.url,
                setOf(primaryHls.url, backupHls.url, mp4.url, embed.url),
            ),
        )
        assertEquals(
            primaryHls,
            nextProviderStream(
                "allanime",
                sources(primaryHls, backupHls),
                backupHls.url,
                setOf(backupHls.url),
            ),
        )
    }

    @Test
    fun `saved provider overrides each watch route`() {
        assertEquals("allanime", preferredProviderForWatch("allanime", "bonk"))
        assertEquals("allanime", preferredProviderForWatch(" AllAnime ", "kiwi"))
    }

    @Test
    fun `first watch keeps route provider until user chooses a global provider`() {
        assertEquals("bonk", preferredProviderForWatch("auto", "bonk"))
        assertEquals("kiwi", preferredProviderForWatch(null, " Kiwi "))
    }

    @Test
    fun `blank watch provider falls back to automatic selection`() {
        assertEquals("auto", preferredProviderForWatch("auto", " "))
    }

    @Test
    fun `a dub-only saved provider is given up on a sub launch`() {
        // RareAnimes has no Japanese-audio offering at all, so keeping it selected on a sub launch
        // guaranteed a resolve, a failure, and "RareAnimes is unavailable for this episode" on a
        // title other servers carried.
        val catalog = EpisodesResult(
            listOf(
                ProviderData("rareanimes", sub = emptyList(), dub = listOf(episode(1))),
                ProviderData("anichan", sub = listOf(episode(1)), dub = emptyList()),
            ),
        )
        assertEquals(
            "auto",
            preferredProviderForCategory("rareanimes", Category.SUB, catalog, 1.0),
        )
        // The choice still stands for the dubs it was made for.
        assertEquals(
            "rareanimes",
            preferredProviderForCategory("rareanimes", Category.DUB, catalog, 1.0),
        )
    }

    @Test
    fun `a saved provider that stops short of this episode is given up`() {
        val catalog = EpisodesResult(
            listOf(ProviderData("rareanimes", sub = emptyList(), dub = listOf(episode(1), episode(2)))),
        )
        assertEquals(
            "auto",
            preferredProviderForCategory("rareanimes", Category.DUB, catalog, 3.0),
        )
        assertEquals(
            "rareanimes",
            preferredProviderForCategory("rareanimes", Category.DUB, catalog, 2.0),
        )
    }

    @Test
    fun `a provider the catalog has not answered for keeps its turn`() {
        // Absent means "not looked up yet" — the remaining scrapers merge in behind this point,
        // and demoting on a partial catalog would throw the choice away for no reason.
        val partial = EpisodesResult(listOf(ProviderData("anichan", sub = listOf(episode(1)), dub = emptyList())))
        assertEquals(
            "rareanimes",
            preferredProviderForCategory("rareanimes", Category.SUB, partial, 1.0),
        )
        // Automatic selection is not a preference to give up.
        assertEquals("auto", preferredProviderForCategory("auto", Category.SUB, partial, 1.0))
    }

    @Test
    fun `a multi-audio provider keeps a dub launch through its sub list`() {
        // kaa lists one file carrying both tracks under sub; reading dub literally would demote a
        // server that can serve the dub perfectly well.
        val catalog = EpisodesResult(
            listOf(ProviderData("kaa", sub = listOf(episode(1)), dub = emptyList())),
        )
        assertEquals("kaa", preferredProviderForCategory("kaa", Category.DUB, catalog, 1.0))
    }

    @Test
    fun `source miss retries when the first catalog snapshot predated the full merge`() {
        assertTrue(
            shouldRetryWithMergedCatalog(
                hasResolvedSource = false,
                initialCatalogIncludedFullAnivexa = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithMergedCatalog(
                hasResolvedSource = false,
                initialCatalogIncludedFullAnivexa = true,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithMergedCatalog(
                hasResolvedSource = true,
                initialCatalogIncludedFullAnivexa = false,
            ),
        )
    }

    @Test
    fun `background sweep never starts a hidden player during playback`() {
        assertEquals(false, validatesDuringPlayback("reanime"))
        assertEquals(false, validatesDuringPlayback("bonk"))
        assertEquals(false, validatesDuringPlayback("allanime"))
        assertTrue(validatesDuringPlayback("anichan"))
    }

    @Test
    fun `tv controls keep progress display out of remote focus order`() {
        assertEquals(
            listOf(
                TvPlayerControl.PREVIOUS,
                TvPlayerControl.REWIND,
                TvPlayerControl.PLAY_PAUSE,
                TvPlayerControl.FORWARD,
                TvPlayerControl.NEXT,
                TvPlayerControl.MUTE,
                TvPlayerControl.CAPTIONS,
                TvPlayerControl.SETTINGS,
                TvPlayerControl.FULLSCREEN,
            ),
            tvPlayerControlOrder(hasCaptions = true, hasSettings = true, hasFullscreen = true),
        )
    }

    @Test
    fun `direction and confirm keys open tv controls`() {
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(opensTvPlayerControls(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(false, opensTvPlayerControls(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun `rapid tv seeks accumulate and stay inside the episode`() {
        val first = tvSeekTargetMs(currentPositionMs = 30_000L, durationMs = 60_000L, offsetMs = TV_SEEK_STEP_MS)
        val second = tvSeekTargetMs(currentPositionMs = first, durationMs = 60_000L, offsetMs = TV_SEEK_STEP_MS)

        assertEquals(50_000L, second)
        assertEquals(60_000L, tvSeekTargetMs(second, 60_000L, 20_000L))
        assertEquals(0L, tvSeekTargetMs(5_000L, 60_000L, -TV_SEEK_STEP_MS))
    }

    @Test
    fun `only a recent seek io error receives same stream recovery`() {
        assertTrue(shouldRecoverSeekError(errorCode = 2_002, elapsedSinceSeekMs = 500L, recoveryAlreadyAttempted = false))
        assertEquals(
            false,
            shouldRecoverSeekError(errorCode = 2_002, elapsedSinceSeekMs = 500L, recoveryAlreadyAttempted = true),
        )
        assertEquals(
            false,
            shouldRecoverSeekError(
                errorCode = 2_002,
                elapsedSinceSeekMs = SEEK_ERROR_RECOVERY_WINDOW_MS + 1L,
                recoveryAlreadyAttempted = false,
            ),
        )
        assertEquals(
            false,
            shouldRecoverSeekError(errorCode = 4_001, elapsedSinceSeekMs = 500L, recoveryAlreadyAttempted = false),
        )
    }

    @Test
    fun `a seek that was never recorded gets no recovery`() {
        // This is the shape of the reported "my server changes when I skip" bug: the seek marker
        // was only written by the TV D-pad path, so a phone scrub or a seek-bar drag left elapsed
        // at MAX_VALUE and the stale-segment error that follows a seek failed straight over to
        // another provider. PlayerSurface now records every seek via onPositionDiscontinuity.
        assertEquals(
            false,
            shouldRecoverSeekError(
                errorCode = 2_002,
                elapsedSinceSeekMs = Long.MAX_VALUE,
                recoveryAlreadyAttempted = false,
            ),
        )
        // ...and with the seek recorded, the same error is retried on the current server instead.
        assertTrue(
            shouldRecoverSeekError(
                errorCode = 2_002,
                elapsedSinceSeekMs = 0L,
                recoveryAlreadyAttempted = false,
            ),
        )
        assertTrue(
            shouldRecoverSeekError(
                errorCode = 2_999,
                elapsedSinceSeekMs = SEEK_ERROR_RECOVERY_WINDOW_MS,
                recoveryAlreadyAttempted = false,
            ),
        )
    }

    @Test
    fun `quality height is recovered from provider label`() {
        assertEquals(1080, declaredVideoHeight("AllAnime 1080p Yt-mp4"))
        assertEquals(720, declaredVideoHeight("720P"))
        assertEquals(360, declaredVideoHeight("360p Data Saver"))
        assertEquals(null, declaredVideoHeight("AllAnime auto"))
    }

    @Test
    fun `audio decoder failure skips ineffective video resolution retry`() {
        assertEquals(
            false,
            shouldRetryDecoderAtLowerVideoResolution(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                rendererName = "MediaCodecAudioRenderer",
                sampleMimeType = "audio/mp4a-latm",
                errorMessage = "Decoder failed: c2.android.aac.decoder",
            ),
        )
        assertEquals(
            false,
            shouldRetryDecoderAtLowerVideoResolution(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                rendererName = null,
                sampleMimeType = null,
                errorMessage = "MediaCodecAudioRenderer error",
            ),
        )
    }

    @Test
    fun `video decoder failure retains one lower resolution recovery`() {
        assertTrue(
            shouldRetryDecoderAtLowerVideoResolution(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                rendererName = "MediaCodecVideoRenderer",
                sampleMimeType = "video/avc",
                errorMessage = "Decoder failed",
            ),
        )
        assertEquals(
            false,
            shouldRetryDecoderAtLowerVideoResolution(
                errorCode = 2_002,
                rendererName = "MediaCodecVideoRenderer",
                sampleMimeType = "video/avc",
                errorMessage = "Network error",
            ),
        )
    }

    @Test
    fun `automatic source validation is bounded while explicit validation is exhaustive`() {
        assertEquals(1, sourceValidationConcurrency(isTv = true))
        assertEquals(2, sourceValidationConcurrency(isTv = false))
        assertEquals(6, sourceValidationCandidateLimit(exhaustive = false))
        assertEquals(Int.MAX_VALUE, sourceValidationCandidateLimit(exhaustive = true))
    }

    @Test
    fun `data saver explains when a server has no 360p rendition`() {
        assertEquals(
            "360p is unavailable on this server. Data Saver is using 480p.",
            dataSaverFallbackMessage(DefaultQuality.P360, listOf(1080, 720, 480)),
        )
        assertEquals(null, dataSaverFallbackMessage(DefaultQuality.P360, listOf(720, 360)))
        assertEquals(null, dataSaverFallbackMessage(DefaultQuality.P480, listOf(1080, 720)))
    }

    @Test
    fun `navigation spine removes duplicate rows and orders episode numbers`() {
        val preferred = ProviderData("bonk", listOf(episode(1), episode(2)), emptyList())
        val noisy = ProviderData("hop", listOf(episode(2), episode(1), episode(2), episode(3)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, noisy)), "bonk", Category.SUB)

        assertEquals(listOf(1.0, 2.0, 3.0), spine.map(EpisodeItem::number))
    }

    @Test
    fun `navigation spine keeps preferred provider on a normalized tie`() {
        val preferredEpisode = episode(1, id = "preferred")
        val preferred = ProviderData("bonk", listOf(preferredEpisode, episode(2)), emptyList())
        val other = ProviderData("hop", listOf(episode(1), episode(2), episode(2)), emptyList())

        val spine = pickNavigationSpine(EpisodesResult(listOf(preferred, other)), "bonk", Category.SUB)

        assertEquals("preferred", spine.first().pipeId)
    }

    /**
     * The spine pick skips normalizing a provider whose raw list cannot beat the best so far. That
     * bound is only sound because de-duplication shrinks a list and never grows it, so a long list
     * of duplicates must still lose to a shorter clean one — the case that would break if the raw
     * size were ever treated as the answer rather than as an upper bound on it.
     */
    @Test
    fun `navigation spine prefers a shorter clean list over a longer duplicated one`() {
        val preferred = ProviderData("bonk", listOf(episode(1)), emptyList())
        val duplicated = ProviderData(
            "hop",
            listOf(episode(1), episode(1), episode(1), episode(1), episode(1)),
            emptyList(),
        )
        val clean = ProviderData("bee", listOf(episode(1), episode(2)), emptyList())

        val spine = pickNavigationSpine(
            EpisodesResult(listOf(preferred, duplicated, clean)),
            "bonk",
            Category.SUB,
        )

        assertEquals(listOf(1.0, 2.0), spine.map(EpisodeItem::number))
    }

    /**
     * One Piece 1180: Miruro's `hop` lists more rows than anyone (recaps as fractional numbers) but
     * stops an episode short, and picking it made the playing episode fall back to index 0.
     */
    @Test
    fun `navigation spine prefers a list holding the playing episode over a longer one`() {
        val preferred = ProviderData("bonk", listOf(episode(1)), emptyList())
        val recapHeavy = ProviderData(
            "hop",
            listOf(1.0, 1.5, 2.0, 2.5, 3.0).map { number ->
                EpisodeItem(pipeId = "hop-$number", number = number, title = null, image = null, filler = false)
            },
            emptyList(),
        )
        val current = ProviderData("bee", (1..4).map { episode(it) }, emptyList())
        val catalog = EpisodesResult(listOf(preferred, recapHeavy, current))

        assertEquals(
            listOf(1.0, 2.0, 3.0, 4.0),
            pickNavigationSpine(catalog, "bonk", Category.SUB, playing = 4.0).map(EpisodeItem::number),
        )
        // Nobody listing the episode leaves the longest list in charge, as before.
        assertEquals(5, pickNavigationSpine(catalog, "bonk", Category.SUB, playing = 9.0).size)
        assertEquals(5, pickNavigationSpine(catalog, "bonk", Category.SUB).size)
    }

    @Test
    fun `late sub-only catalog cannot erase active dubbed navigation spine`() {
        val activeDub = listOf(episode(1, id = "active-dub"))
        val lateCatalog = EpisodesResult(
            listOf(ProviderData("bonk", sub = listOf(episode(1)), dub = emptyList())),
        )
        val lateDubSpine = pickNavigationSpine(lateCatalog, "bonk", Category.DUB)

        assertTrue(lateDubSpine.isEmpty())
        assertEquals(activeDub, retainNonEmptyNavigationSpine(activeDub, lateDubSpine))
    }

    @Test
    fun `late dubbed catalog still refreshes active navigation spine`() {
        val activeDub = listOf(episode(1, id = "active-dub"))
        val refreshedDub = listOf(episode(1, id = "refreshed-dub"), episode(2))

        assertEquals(refreshedDub, retainNonEmptyNavigationSpine(activeDub, refreshedDub))
    }

    @Test
    fun `anilist progress waits until most of a full integer episode was watched`() {
        assertTrue(shouldSyncAniListProgress(3.0, positionMs = 1_200_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 600_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.5, positionMs = 1_300_000, durationMs = 1_440_000))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 45_000, durationMs = 50_000))
    }

    @Test
    fun `watched threshold follows the viewer's chosen percentage`() {
        // 50% of a 24-minute episode: watched at 70, not yet at the 80% default.
        val half = 720_000L
        val full = 1_440_000L
        assertEquals(false, shouldSyncAniListProgress(3.0, half, full, thresholdPercent = 80))
        assertTrue(shouldSyncAniListProgress(3.0, half, full, thresholdPercent = 50))
        assertEquals(false, shouldSyncAniListProgress(3.0, 1_200_000, full, thresholdPercent = 90))
        assertTrue(shouldSyncAniListProgress(3.0, 1_300_000, full, thresholdPercent = 90))
    }

    @Test
    fun `watched threshold outside the supported range is clamped`() {
        // A 100% threshold would never be reached, so the ceiling still lets an episode finish.
        assertTrue(shouldSyncAniListProgress(3.0, positionMs = 1_440_000, durationMs = 1_440_000, thresholdPercent = 100))
        assertEquals(false, shouldSyncAniListProgress(3.0, positionMs = 600_000, durationMs = 1_440_000, thresholdPercent = 0))
    }

    /**
     * Mushoku Tensei II is thirteen episodes on AniList; AniBD and RareAnimes both file the whole
     * season under it and list twenty-four. The spine takes the longest list, so eleven titleless
     * rows wearing the series poster appeared fifteen seconds after the episode opened — when the
     * slower catalog merged — and vanished again on the next change of server.
     */
    @Test
    fun `a finished series does not grow past the episode count AniList states`() {
        val listed = (1..24).map { episode(it) }

        assertEquals(13, clampSpineToStatedTotal(listed, statedTotal = 13, keep = 10.0).size)
        assertEquals(13.0, clampSpineToStatedTotal(listed, 13, keep = 10.0).last().number, 0.0)
    }

    @Test
    fun `a spine no longer than the stated count is left alone`() {
        val listed = (1..12).map { episode(it) }

        assertEquals(listed, clampSpineToStatedTotal(listed, statedTotal = 13, keep = 1.0))
        // No count to measure against — a releasing series is not trimmed at all, since its total
        // is a plan and a server ahead of AniList on a Sunday is right where the plan is not.
        assertEquals(listed, clampSpineToStatedTotal(listed, statedTotal = null, keep = 1.0))
        assertEquals(listed, clampSpineToStatedTotal(listed, statedTotal = 0, keep = 1.0))
    }

    @Test
    fun `the episode being watched is never trimmed away`() {
        // Opened from a server's own longer list: trimming would renumber the viewer to episode 1
        // mid-playback, which is worse than a list with extra rows on the end.
        val listed = (1..24).map { episode(it) }

        assertEquals(listed, clampSpineToStatedTotal(listed, statedTotal = 13, keep = 20.0))
    }

    private fun sources(vararg streams: StreamItem) = SourcesResult(streams.toList(), emptyList(), null, null)

    private fun stream(url: String, type: String, quality: String, active: Boolean = false) = StreamItem(
        url = url,
        type = type,
        quality = quality,
        audio = null,
        referer = null,
        isActive = active,
        width = null,
        height = null,
    )

    private fun episode(number: Int, id: String = "episode-$number") = EpisodeItem(
        pipeId = id,
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
    )
}

/**
 * `WatchData.current` reads `episodes[currentIndex]`, so an episode list that arrived empty takes
 * the whole screen down the moment Compose draws it — three crash reports of "Empty list doesn't
 * contain element at index 0". The guard is at the point the state is built; this pins the property
 * that made it fatal.
 */
class WatchDataCurrentEpisodeTest {
    private fun episode(number: Double) = EpisodeItem(
        pipeId = "ep$number",
        number = number,
        title = null,
        image = null,
        filler = false,
    )

    private fun data(episodes: List<EpisodeItem>, index: Int) = WatchData(
        episodes = episodes,
        currentIndex = index,
        provider = "bonk",
        category = Category.SUB,
        sourceOptions = emptyList(),
        anilistId = 1,
        sources = SourcesResult(emptyList(), emptyList(), null, null),
        chosenStream = null,
        seriesTitle = "Anime",
        artworkUrl = null,
    )

    @Test
    fun `current returns the episode at the index`() {
        val data = data(listOf(episode(1.0), episode(2.0)), index = 1)
        assertEquals(2.0, data.current.number, 0.0)
    }

    @Test
    fun `an empty episode list is why the state must never be built`() {
        val data = data(emptyList(), index = 0)
        assertThrows(IndexOutOfBoundsException::class.java) { data.current }
    }

    @Test
    fun `the picker highlights the cell a viewer pressed even when a hidden variant answered`() {
        // ally publishes hard sub only, so that is its one cell. When Miruro refuses the hard sub
        // for this episode the resolver falls back to ally's soft sub, and a strict variant match
        // would leave the grid with nothing lit at all — the server they just chose looking
        // unchosen while its stream plays.
        val data = data(listOf(episode(1.0)), index = 0).copy(
            provider = "ally",
            sourceVariant = SourceVariant.SOFT_SUB,
            sourceOptions = listOf(sourceOption("ally", SourceVariant.HARD_SUB)),
        )

        assertEquals(SourceVariant.HARD_SUB, data.pickerVariant)
    }

    @Test
    fun `a published variant still highlights its own cell`() {
        val data = data(listOf(episode(1.0)), index = 0).copy(
            provider = "bonk",
            sourceVariant = SourceVariant.HARD_SUB,
            sourceOptions = listOf(
                sourceOption("bonk", SourceVariant.SOFT_SUB),
                sourceOption("bonk", SourceVariant.HARD_SUB),
            ),
        )

        assertEquals(SourceVariant.HARD_SUB, data.pickerVariant)
    }

    private fun sourceOption(provider: String, variant: SourceVariant) = WatchSourceOption(
        provider = provider,
        category = Category.SUB,
        hasCurrentEpisode = true,
        episodeCount = 1,
        variant = variant,
    )
}
