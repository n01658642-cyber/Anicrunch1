package com.anilili.data.backup

import com.anilili.parity.ParityCorpus
import com.anilili.parity.ParityCorpus.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `parity/backup-sealed.json`: a backup whose logins were sealed by an implementation that shares
 * no code with either app (Python's `cryptography`).
 *
 * [BackupCryptoTest] proves this app opens what it seals itself, which a key schedule wrong in a
 * self-consistent way would also pass. This is the outside answer: if the PBKDF2, the GCM tag
 * layout or the associated data drifted from the scheme, a file from the desktop app would stop
 * opening here, and this is where that shows first.
 */
class BackupParityTest {
    private val case = ParityCorpus.load("backup-sealed.json")

    /** As `BackupManager.unsealCredentials` reads the opened seal. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a backup sealed elsewhere opens with its passphrase and holds what it should`() {
        val document = AppBackup.decode(case.getValue("file").toString())
        val sealed = checkNotNull(document.credentials) { "the corpus backup carries no credentials" }

        val plaintext = BackupCrypto.open(case.string("passphrase"), sealed, document.credentialAssociatedData())
        val credentials = json.decodeFromString(BackupCredentials.serializer(), plaintext)

        val expect = case.getValue("expect").jsonObject
        assertEquals(expect.string("anilistToken"), credentials.aniListToken)
        assertEquals(expect.getValue("malHandoff").jsonPrimitive.boolean, credentials.hasMalHandoff)
        assertEquals(expect.getValue("malIssuedAt").jsonPrimitive.long, credentials.malIssuedAt)
    }
}
