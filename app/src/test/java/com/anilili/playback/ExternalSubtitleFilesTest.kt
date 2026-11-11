package com.anilili.playback

import androidx.media3.common.MimeTypes
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalSubtitleFilesTest {
    @Test
    fun recognizesSupportedSubtitleExtensionsCaseInsensitively() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeTypeFor("Episode.SRT", null))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeTypeFor("episode.vtt", null))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeTypeFor("episode.ass", null))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeTypeFor("episode.ssa", null))
        assertEquals(MimeTypes.APPLICATION_TTML, subtitleMimeTypeFor("episode.ttml", null))
    }

    @Test
    fun usesProviderMimeWhenContentUriHasNoExtension() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleMimeTypeFor("subtitle", "application/x-subrip"),
        )
    }

    @Test
    fun rejectsUnrecognizedFiles() {
        assertNull(subtitleMimeTypeFor("subtitle.zip", "application/zip"))
    }

    @Test
    fun keepsUtf8SubtitlesExactlyAsWritten() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nJe suis désolé — 「ありがとう」\n"

        assertEquals(text, decodeSubtitleBytes(text.toByteArray(Charsets.UTF_8), null))
        // A UTF-8 byte-order mark must not survive into the first cue number.
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)
        assertEquals(text, decodeSubtitleBytes(withBom, null))
    }

    @Test
    fun convertsLegacyCodePagesUsingTheDeclaredLanguage() {
        val cyrillic = "Привет, мир"
        val encoded = cyrillic.toByteArray(Charset.forName("windows-1251"))

        // Without the language hint these bytes are not valid UTF-8 and would reach the player
        // as mojibake; with it they come back intact.
        assertEquals(cyrillic, decodeSubtitleBytes(encoded, "ru"))
        assertNotEquals(cyrillic, decodeSubtitleBytes(encoded, "fr"))

        val western = "Voilà — déjà vu"
        assertEquals(western, decodeSubtitleBytes(western.toByteArray(Charset.forName("windows-1252")), "fr"))
        // An unknown or missing language still decodes rather than throwing.
        assertEquals(western, decodeSubtitleBytes(western.toByteArray(Charset.forName("windows-1252")), null))
    }

    @Test
    fun regionalLanguageTagsPickTheirBaseLanguage() {
        val cyrillic = "Здравей"
        val encoded = cyrillic.toByteArray(Charset.forName("windows-1251"))

        assertEquals(cyrillic, decodeSubtitleBytes(encoded, "bg-BG"))
        assertEquals(cyrillic, decodeSubtitleBytes(encoded, "RU_ru"))
    }
}
