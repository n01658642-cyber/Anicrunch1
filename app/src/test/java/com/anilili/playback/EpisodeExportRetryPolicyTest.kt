package com.anilili.playback

import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiException
import com.anilili.errors.asFailure
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeExportRetryPolicyTest {
    @Test
    fun `an unconfigured export requires a deliberate retry instead of reexporting the same broken track`() {
        val unconfigured = AnililiException(AnililiError.EXPORT_NO_VIDEO_CONFIG.asFailure())

        assertFalse(shouldRetryExport(unconfigured, attempts = 1))
    }

    /**
     * The retry policy reads the code, not the exception type, so a failure that is wrapped on its
     * way out of the export still retires the request rather than looping on a track that cannot
     * come out differently.
     */
    @Test
    fun `an unconfigured export is recognised through a wrapper`() {
        val wrapped = IllegalStateException(
            "Export failed",
            AnililiException(AnililiError.EXPORT_NO_VIDEO_CONFIG.asFailure()),
        )

        assertFalse(shouldRetryExport(wrapped, attempts = 1))
    }

    /** A different catalogued failure is still worth its attempts; only EXP-04 is terminal here. */
    @Test
    fun `another catalogued export failure keeps its retries`() {
        val transient = AnililiException(AnililiError.EXPORT_PUBLISH_FAILED.asFailure("Downloads"))

        assertTrue(shouldRetryExport(transient, attempts = 1))
    }
    @Test
    fun `a device that cannot transmux is never retried`() {
        val linkage = NoClassDefFoundError("Failed resolution of: Landroid/media/metrics/LogSessionId;")

        assertTrue(isUnsupportedDeviceError(linkage))
        assertFalse(shouldRetryExport(linkage, attempts = 1))
    }

    @Test
    fun `a wrapped linkage failure is recognised through its cause chain`() {
        val wrapped = IllegalStateException(
            "Export failed",
            RuntimeException(NoClassDefFoundError("Landroid/media/metrics/LogSessionId;")),
        )

        assertTrue(isUnsupportedDeviceError(wrapped))
        assertFalse(shouldRetryExport(wrapped, attempts = 1))
    }

    @Test
    fun `transient failures get a bounded number of tries`() {
        val transient = IOException("Not enough free space to build the MP4.")

        assertFalse(isUnsupportedDeviceError(transient))
        assertTrue(shouldRetryExport(transient, attempts = 1))
        assertTrue(shouldRetryExport(transient, attempts = MAX_EXPORT_ATTEMPTS - 1))
        // The report that prompted this saw 834 attempts at one episode; the cap is what stops it.
        assertFalse(shouldRetryExport(transient, attempts = MAX_EXPORT_ATTEMPTS))
        assertFalse(shouldRetryExport(transient, attempts = 834))
    }

    @Test
    fun `a cyclic cause chain cannot spin`() {
        // The JDK refuses self-causation but not a cycle through a second throwable, and walking
        // one of those without a bound never returns.
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertFalse(isUnsupportedDeviceError(first))
    }
}
