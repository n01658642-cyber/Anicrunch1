package com.anilili.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A download keeps one audio rendition, so the sub/dub category has to be expressed as a track
 * override before the manifest's own DEFAULT flag decides it. Getting this wrong is not a
 * preference that can be corrected later: the track that is not selected is never written to disk.
 */
class DownloadAudioSelectionTest {
    private fun audioFormat(label: String, language: String): Format =
        Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .setLabel(label)
            .setLanguage(language)
            .build()

    /** A dual-audio manifest, each rendition its own group, as HLS publishes them. */
    private fun dualAudioTracks(): Tracks {
        val groups = listOf(
            audioFormat("Native", "ja"),
            audioFormat("English", "en"),
        ).map { format ->
            Tracks.Group(
                TrackGroup(format),
                /* adaptiveSupported= */ false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(format.language == "ja"), // the manifest default: Japanese
            )
        }
        return Tracks(ImmutableList.copyOf(groups))
    }

    private val base: TrackSelectionParameters = TrackSelectionParameters.Builder().build()

    @Test
    fun `a dub pins the english track of a dual-audio manifest`() {
        val result = categoryAudioParameters(base, dualAudioTracks(), category = "dub")
        val override = result.overrides.values.single()
        assertEquals("English", override.mediaTrackGroup.getFormat(0).label)
    }

    @Test
    fun `a sub pins the native track rather than trusting the default flag`() {
        val result = categoryAudioParameters(base, dualAudioTracks(), category = "sub")
        val override = result.overrides.values.single()
        assertEquals("Native", override.mediaTrackGroup.getFormat(0).label)
    }

    @Test
    fun `a single-audio manifest is left exactly as it was`() {
        val tracks = Tracks(
            ImmutableList.of(
                Tracks.Group(
                    TrackGroup(audioFormat("Japanese", "ja")),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )
        // Every ordinary provider serves one audio track per stream, and pinning it would only add
        // an override for Media3 to resolve back to the same track.
        assertEquals(base, categoryAudioParameters(base, tracks, category = "dub"))
    }

    @Test
    fun `a manifest with no track for the category keeps its own default`() {
        val tracks = Tracks(
            ImmutableList.copyOf(
                listOf(audioFormat("Hindi", "hi"), audioFormat("Tamil", "ta")).map {
                    Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
                },
            ),
        )
        assertEquals(base, categoryAudioParameters(base, tracks, category = "dub"))
    }

    @Test
    fun `the video ceiling the caller asked for survives the audio override`() {
        // The audio choice is added to the caller's parameters, not substituted for them: losing
        // the height cap here would quietly download every dub at full resolution.
        val capped = TrackSelectionParameters.Builder()
            .setMaxVideoSize(Int.MAX_VALUE, 480)
            .build()
        val result = categoryAudioParameters(capped, dualAudioTracks(), category = "dub")
        assertEquals(480, result.maxVideoHeight)
        assertTrue(result.overrides.isNotEmpty())
    }
}
