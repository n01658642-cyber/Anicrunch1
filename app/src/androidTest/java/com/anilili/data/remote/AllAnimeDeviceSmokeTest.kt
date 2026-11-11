package com.anilili.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import java.time.Duration
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live Samsung/device smoke test for MKissa's current catalog and encrypted episode route. */
@RunWith(AndroidJUnit4::class)
class AllAnimeDeviceSmokeTest {
    @Test
    fun currentMkissaCatalogAndEpisodeHandshakeRemainReachable() {
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        val media = Media(
            id = 52991,
            idMal = 52991,
            title = MediaTitle(english = "Frieren: Beyond Journey's End", romaji = "Sousou no Frieren"),
        )

        val availability = provider.episodeAvailability(media)
        assertTrue("MKissa catalog returned no sub episodes", availability.sub.isNotEmpty())

        val sourceAttempt = runCatching {
            provider.sources(media, audio = "sub", episode = 1, allowLegacyFallback = false)
        }
        sourceAttempt.onSuccess { result ->
            assertTrue("MKissa returned no playable source candidates", result.streams.isNotEmpty())
            assertTrue(
                "Expected the current MKissa source route",
                provider.lastSourceRoute == AllAnimeProvider.SourceRoute.CURRENT,
            )
        }.onFailure { failure ->
            val captcha = generateSequence(failure) { it.cause }
                .filterIsInstance<AllAnimeCaptchaRequiredException>()
                .firstOrNull()
            assertTrue(
                "MKissa source handshake failed before reaching a valid current-route result or CAPTCHA: ${failure.message}",
                captcha?.currentRoute == true,
            )
        }
    }
}
