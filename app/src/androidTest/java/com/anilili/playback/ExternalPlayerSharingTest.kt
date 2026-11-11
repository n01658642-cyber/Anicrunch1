package com.anilili.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anilili.data.model.SubtitleItem
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPlayerSharingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun downloadedAndImportedSubtitlesUseReadableContentUrisEverywhere() {
        val files = listOf(
            File(context.filesDir, "episode-download-subtitles/sharing-test.ass"),
            File(context.cacheDir, "external-subtitles/sharing-test.srt"),
        )
        try {
            files.forEach { it.parentFile!!.mkdirs(); it.writeText("subtitle fixture") }
            val intent = externalPlayerIntent(context, ExternalPlaybackRequest(
                url = "https://example.test/video.mp4", title = "Sharing test", streamType = "mp4",
                resumePositionMs = 0, headers = emptyMap(),
                subtitles = files.map { SubtitleItem(Uri.fromFile(it).toString(), "English", "en") },
            ))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(2, intent.clipData!!.itemCount)
            val subs = intent.getParcelableArrayExtra("subs")!!.map { it as Uri }
            val subtitleUris = intent.getParcelableArrayExtra("subtitle_uri")!!.map { it as Uri }
            assertEquals(subs, subtitleUris)
            assertEquals(subs.first().toString(), intent.getStringExtra("subtitles_location"))
            assertEquals(subs.first().toString(), intent.getStringExtra("forcedsrt"))
            subs.forEachIndexed { index, uri ->
                assertEquals("content", uri.scheme)
                assertEquals(uri, intent.clipData!!.getItemAt(index).uri)
                assertEquals("subtitle fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun unrelatedPrivateFilesAreNotExposed() {
        val file = File(context.filesDir, "sharing-private-test.txt").apply { writeText("private") }
        try {
            try {
                externalShareUri(context, Uri.fromFile(file).toString())
                fail("An unconfigured private directory must not be shared")
            } catch (_: IllegalArgumentException) { }
        } finally { file.delete() }
    }

    @Test
    fun existingContentAndRemoteUrisRemainUnchanged() {
        listOf("content://documents.example/item/1", "https://example.test/en.vtt").forEach {
            assertEquals(it, externalShareUri(context, it).toString())
        }
    }
}
