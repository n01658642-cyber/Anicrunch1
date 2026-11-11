package com.anilili.playback

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a real segment whose audio clock steps backwards through the framework muxer, with and
 * without [MonotonicAudioMuxer].
 *
 * The segment is not checked in. Push the first segment of an affected encode — MegaPlay's
 * Reincarnated as a Sword dub, whose AAC timestamps jump back 73 ms at 5.53 s — to
 * `/sdcard/Android/data/<package>/files/backward-audio.ts`; the test is skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class BackwardAudioExportProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = File(context.getExternalFilesDir(null), "backward-audio.ts")

    private fun export(muxerFactory: Muxer.Factory?): Pair<File, ExportException?> {
        val output = File(context.cacheDir, "backward-audio-${System.nanoTime()}.mp4")
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val builder = Transformer.Builder(context)
                .setLooper(Looper.getMainLooper())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure = exportException
                        done.countDown()
                    }
                })
            muxerFactory?.let(builder::setMuxerFactory)
            builder.build().start(MediaItem.fromUri(Uri.fromFile(source)), output.absolutePath)
        }
        assertTrue("export timed out", done.await(60, TimeUnit.SECONDS))
        return output to failure
    }

    @Test
    fun theFrameworkMuxerRefusesItAndTheWrapperExportsIt() {
        assumeTrue("push backward-audio.ts first", source.isFile)

        val (_, stock) = export(muxerFactory = null)
        Log.i(TAG, "stock: ${stock?.errorCodeName} ${stock?.cause?.message}")
        assertNotNull("the stock muxer accepted backward audio", stock)
        assertEquals(ExportException.ERROR_CODE_MUXING_FAILED, stock!!.errorCode)

        var dropped = -1
        val (output, wrapped) = export(MonotonicAudioMuxer.Factory { dropped = it })
        Log.i(TAG, "wrapped: ${wrapped?.errorCodeName} dropped=$dropped size=${output.length()}")
        assertNull("the wrapped export failed: ${wrapped?.cause}", wrapped)
        assertTrue(dropped > 0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            Log.i(TAG, "tracks: $tracks")
            assertEquals(2, tracks.size)
            val audio = tracks.indexOfFirst { it.getString("mime")!!.startsWith("audio/") }
            extractor.selectTrack(audio)
            var last = Long.MIN_VALUE
            var samples = 0
            while (extractor.sampleTime >= 0) {
                assertTrue(extractor.sampleTime > last)
                last = extractor.sampleTime
                samples++
                extractor.advance()
            }
            Log.i(TAG, "audio samples=$samples lastUs=$last")
            assertTrue(samples > 250)
        } finally {
            extractor.release()
            output.delete()
        }
    }

    private companion object {
        const val TAG = "BackwardAudioProbe"
    }
}
