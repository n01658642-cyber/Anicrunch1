package com.anilili.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DiagnosticSubmissionPolicyTest {
    @Test
    fun `description is bounded and passes through the central redactor`() {
        val description = "Playback failed. password=hunter2 user@example.com " +
            "x".repeat(DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS)

        val normalized = DiagnosticSubmissionPolicy.normalizeDescription(description)

        assertEquals(DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS, normalized.length)
        assertEquals(false, "hunter2" in normalized)
        assertEquals(false, "user@example.com" in normalized)
    }

    @Test
    fun `screenshot format is detected from bytes rather than filename`() {
        assertEquals(
            "image/png",
            DiagnosticSubmissionPolicy.screenshotContentType(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            ),
        )
        assertEquals(
            "image/jpeg",
            DiagnosticSubmissionPolicy.screenshotContentType(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            ),
        )
        assertEquals(
            "image/webp",
            DiagnosticSubmissionPolicy.screenshotContentType("RIFF0000WEBP".toByteArray()),
        )
        assertNull(DiagnosticSubmissionPolicy.screenshotContentType("not-an-image".toByteArray()))
    }

    @Test
    fun `manual context is embedded in the shared diagnostic zip`() {
        val screenshotBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3,
        )
        val screenshot = File.createTempFile("diagnostic-share", ".png").apply {
            writeBytes(screenshotBytes)
        }
        try {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putDiagnosticSubmission(
                    "Playback failed. password=hunter2",
                    DiagnosticScreenshot(screenshot, "image/png"),
                )
            }

            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }

            assertEquals("Playback failed. password=<redacted>", entries["user-description.txt"]?.decodeToString())
            assertArrayEquals(screenshotBytes, requireNotNull(entries["user-screenshot.png"]))
        } finally {
            screenshot.delete()
        }
    }
}
