package com.anilili.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crDroid crash: the media session and the media notification both load artwork through the
 * session's loader, and a framework that recycles a bitmap after scaling it left the other
 * consumer holding a dead one. Each call must hand back an instance nobody else has.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ArtworkBitmapLoaderTest {

    private val artworkUri = Uri.parse("https://example.invalid/still.jpg")

    /** Decodes once, like DataSourceBitmapLoader, and counts how often it was asked. */
    private class OnceLoader : BitmapLoader {
        var loads = 0
        override fun supportsMimeType(mimeType: String) = true
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = load()
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load()
        private fun load(): ListenableFuture<Bitmap> {
            loads++
            return Futures.immediateFuture(Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888))
        }
    }

    private fun loader(source: OnceLoader, capPx: Int = 960) =
        ArtworkBitmapLoader(CacheBitmapLoader(source), capPx)

    private fun metadata() = MediaMetadata.Builder().setArtworkUri(artworkUri).build()

    @Test
    fun everyConsumerGetsItsOwnInstanceFromOneDecode() {
        val source = OnceLoader()
        val loader = loader(source)

        val forNotification = Futures.getDone(loader.loadBitmapFromMetadata(metadata())!!)
        val forSession = Futures.getDone(loader.loadBitmapFromMetadata(metadata())!!)

        assertEquals(1, source.loads)
        assertNotSame(forNotification, forSession)
    }

    @Test
    fun recyclingOneConsumersCopyLeavesTheOtherUsable() {
        val loader = loader(OnceLoader())

        // What the ROM did to the notification's copy...
        Futures.getDone(loader.loadBitmapFromMetadata(metadata())!!).recycle()
        // ...must not reach the session's, which the framework then rescales on the main thread.
        val forSession = Futures.getDone(loader.loadBitmapFromMetadata(metadata())!!)

        assertFalse(forSession.isRecycled)
        Bitmap.createScaledBitmap(forSession, 100, 56, true)
    }

    @Test
    fun artworkArrivesWithinThePlatformCap() {
        val bitmap = Futures.getDone(loader(OnceLoader(), capPx = 320).loadBitmap(artworkUri))

        assertEquals(320, bitmap.width)
        assertTrue(bitmap.height <= 320)
    }
}
