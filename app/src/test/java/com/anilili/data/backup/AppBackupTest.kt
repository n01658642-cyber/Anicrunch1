package com.anilili.data.backup

import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.WatchlistEntry
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.anililiFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupTest {

    private val json = Json { encodeDefaults = true }

    private val document = BackupDocument(
        createdAt = 1_756_000_000_000L,
        appVersion = "0.1.99",
        settings = buildJsonObject {
            put("autoplay", false)
            put("skip_button_seconds", 90)
            put("default_playback_speed", 1.25f)
            put("default_quality", "720")
        },
        watchlist = listOf(
            WatchlistEntry(anilistId = 1, title = "Cowboy Bebop", cover = null, addedAt = 42L),
        ),
        history = listOf(
            HistoryEntry(
                anilistId = 2,
                title = "Frieren",
                cover = null,
                episodeNumber = 7.0,
                provider = "auto",
                category = "sub",
                positionMs = 1_000L,
                durationMs = 2_000L,
                updatedAt = 99L,
                watchedEpisodes = setOf(1.0, 2.0),
            ),
        ),
        searchHistory = listOf("frieren", "bebop"),
    )

    @Test
    fun `a backup survives the round trip intact`() {
        val restored = AppBackup.decode(AppBackup.encode(document))
        assertEquals(document, restored)
    }

    @Test
    fun `an older document restores as itself, with the missing sections empty`() {
        // What a build that only ever backed up settings would have written.
        val text = """
            {
              "format": "anilili-backup",
              "version": 1,
              "settings": { "autoplay": true }
            }
        """.trimIndent()
        val restored = AppBackup.decode(text)
        assertEquals(1, restored.settings.size)
        assertTrue(restored.watchlist.isEmpty())
        assertTrue(restored.history.isEmpty())
        assertTrue(restored.searchHistory.isEmpty())
    }

    @Test
    fun `a field this build has never heard of does not fail the file`() {
        val text = """
            {
              "format": "anilili-backup",
              "version": 1,
              "somethingFromTheFuture": { "a": 1 },
              "searchHistory": ["bebop"]
            }
        """.trimIndent()
        assertEquals(listOf("bebop"), AppBackup.decode(text).searchHistory)
    }

    @Test
    fun `the MyAnimeList export sitting next to it is refused by name`() {
        assertEquals(
            AnililiError.BACKUP_NOT_A_BACKUP,
            refusal("<?xml version=\"1.0\"?><myanimelist/>")?.error,
        )
    }

    @Test
    fun `valid JSON that is not a backup is refused`() {
        val refusal = refusal("""{ "format": "something-else", "version": 1 }""")
        assertEquals(AnililiError.BACKUP_NOT_A_BACKUP, refusal?.error)
        // The wrong `format` is the fact that identifies this file, and the reason it is in the
        // detail rather than the line: the line has to fit a notice, and it already says enough.
        assertTrue(refusal?.detail.orEmpty(), refusal?.detail.orEmpty().contains("something-else"))
    }

    /**
     * All four refusals are one code and four details. A viewer can do exactly one thing about any
     * of them — choose a different file — so splitting them into four entries would give four codes
     * to the same next step, while losing which file was wrong would leave the commonest mistake
     * (picking the MyAnimeList export beside it) unexplainable from a bundle.
     */
    @Test
    fun `each refusal keeps what it knows in the detail`() {
        assertTrue(refusal("   ")?.detail.orEmpty().contains("empty"))

        val newer = AppBackup.encode(document.copy(version = AppBackup.VERSION + 1))
        assertTrue(refusal(newer)?.detail.orEmpty().contains("newer version"))
    }

    private fun refusal(text: String): AnililiFailure? =
        runCatching { AppBackup.decode(text) }.exceptionOrNull()?.anililiFailure()

    @Test
    fun `the file name is dated so two backups do not read as one`() {
        val name = AppBackup.fileName(java.time.LocalDate.of(2026, 8, 23))
        assertEquals("Anilili-backup-20260823.json", name)
    }

    @Test
    fun `describing a backup skips the sections it has none of`() {
        assertEquals("4 settings · 1 saved · 1 continue watching · 2 searches", document.describe())
        assertEquals("1 setting", BackupDocument(settings = buildJsonObject { put("autoplay", true) }).describe())
        assertEquals("nothing", BackupDocument().describe())
    }

    @Test
    fun `sealed logins survive the round trip, and the file names no account`() {
        val credentials = BackupCredentials(
            aniListToken = "a-very-real-token",
            openSubtitlesUsername = "someone",
            openSubtitlesPassword = "hunter2",
        )
        val withLogins = document.copy(
            credentials = BackupCrypto.seal(
                passphrase = "K7M4-9QXR",
                plaintext = """{"aniListToken":"a-very-real-token"}""",
                associatedData = document.credentialAssociatedData(),
            ).copy(services = credentials.services()),
        )
        val text = AppBackup.encode(withLogins)
        assertEquals(withLogins, AppBackup.decode(text))
        assertTrue("the token must not be readable in the file", "a-very-real-token" !in text)
        assertTrue("the username must not be readable in the file", "someone" !in text)
        assertEquals(
            listOf(BackupCredentials.SERVICE_ANILIST, BackupCredentials.SERVICE_OPEN_SUBTITLES),
            withLogins.credentials?.services,
        )
        assertTrue(withLogins.describe().endsWith("2 logins"))
    }

    @Test
    fun `a backup with nothing to seal is not described as carrying logins`() {
        assertTrue(BackupCredentials().isEmpty)
        assertTrue(BackupCredentials(openSubtitlesUsername = "someone").isEmpty)
        assertFalse(BackupCredentials(aniListToken = "t").isEmpty)
        assertEquals(emptyList<String>(), BackupCredentials().services())
    }

    @Test
    fun `a MyAnimeList hand-off needs both halves to count as one`() {
        // The code is worthless without the verifier it was bound to, and vice versa, so half a
        // hand-off must not be announced as a login the restore can use.
        assertFalse(BackupCredentials(malCode = "code").hasMalHandoff)
        assertFalse(BackupCredentials(malVerifier = "verifier").hasMalHandoff)
        assertTrue(BackupCredentials(malCode = "code").isEmpty)
        val handoff = BackupCredentials(malCode = "code", malVerifier = "verifier")
        assertTrue(handoff.hasMalHandoff)
        assertFalse(handoff.isEmpty)
        assertEquals(listOf(BackupCredentials.SERVICE_MAL), handoff.services())
    }

    @Test
    fun `a sealed hand-off is not readable in the file`() {
        val credentials = BackupCredentials(
            malCode = "an-authorization-code",
            malVerifier = "the-pkce-verifier",
            malIssuedAt = 1_756_000_000_000L,
        )
        val sealed = BackupCrypto.seal(
            passphrase = "K7M4-9QXR",
            plaintext = json.encodeToString(BackupCredentials.serializer(), credentials),
            associatedData = document.credentialAssociatedData(),
        ).copy(services = credentials.services())
        val text = AppBackup.encode(document.copy(credentials = sealed))
        assertTrue("an-authorization-code" !in text)
        assertTrue("the-pkce-verifier" !in text)

        val restored = AppBackup.decode(text).credentials!!
        val opened = json.decodeFromString(
            BackupCredentials.serializer(),
            BackupCrypto.open("K7M4-9QXR", restored, document.credentialAssociatedData()),
        )
        assertEquals(credentials, opened)
    }

    @Test
    fun `services are named the way the dialogs say them`() {
        assertEquals("AniList", BackupCredentials.label(BackupCredentials.SERVICE_ANILIST))
        assertEquals("MyAnimeList", BackupCredentials.label(BackupCredentials.SERVICE_MAL))
        assertEquals("OpenSubtitles", BackupCredentials.label(BackupCredentials.SERVICE_OPEN_SUBTITLES))
    }

    @Test
    fun `a warning rides alongside what the restore did land`() {
        val summary = RestoreSummary(
            settings = 30,
            watchlist = 0,
            history = 0,
            searchHistory = 0,
            logins = 0,
            warnings = listOf("MyAnimeList refused the sign-in in that backup."),
        )
        assertFalse(summary.isEmpty)
        assertEquals("30 settings", summary.describe())
    }

    @Test
    fun `a restore that changed nothing reports as much`() {
        assertTrue(RestoreSummary(0, 0, 0, 0).isEmpty)
        assertFalse(RestoreSummary(0, 0, 1, 0).isEmpty)
        assertEquals("3 settings · 1 continue watching", RestoreSummary(3, 0, 1, 0).describe())
    }
}
