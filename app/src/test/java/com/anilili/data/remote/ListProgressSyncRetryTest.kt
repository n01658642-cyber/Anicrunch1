package com.anilili.data.remote

import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiException
import com.anilili.errors.asFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListProgressSyncRetryTest {
    @Test
    fun `failures that never reached the service keep retrying`() {
        listOf(
            ConnectException("Failed to connect"),
            SocketTimeoutException("timeout"),
            SSLHandshakeException("captive portal certificate"),
            // AniListClient rewraps timeouts and DNS failures with advice; the cause still counts.
            IOException("AniList is unreachable (DNS).", UnknownHostException("graphql.anilist.co")),
            IOException("unexpected end of stream", java.io.EOFException()),
        ).forEach { error ->
            assertTrue(error.toString(), ListProgressSyncWorker.isTransientDeliveryFailure(error))
            assertEquals(20, ListProgressSyncWorker.attemptLimit(error))
        }
    }

    @Test
    fun `a rate-limit lockout is not the push's fault`() {
        val limited = AnililiException(AnililiError.ACCOUNT_RATE_LIMITED.asFailure("AniList"))
        assertTrue(ListProgressSyncWorker.isTransientDeliveryFailure(limited))
    }

    @Test
    fun `an answer from the service keeps the short limit`() {
        listOf(
            IOException("AniList HTTP 400: validation."),
            IOException("AniList GraphQL error: Not Found"),
            AnililiException(AnililiError.ACCOUNT_SESSION_EXPIRED.asFailure("AniList")),
            IllegalStateException("This anime could not be matched to MyAnimeList"),
        ).forEach { error ->
            assertFalse(error.toString(), ListProgressSyncWorker.isTransientDeliveryFailure(error))
            assertEquals(6, ListProgressSyncWorker.attemptLimit(error))
        }
    }
}
