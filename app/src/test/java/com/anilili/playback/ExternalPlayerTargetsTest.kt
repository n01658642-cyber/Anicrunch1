package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerOrderTest {

    private fun target(packageName: String, label: String) = ExternalPlayerTarget(
        packageName = packageName,
        label = label,
        builtForTv = packageName in setOf(MPVNOVA_PACKAGE, "org.videolan.vlc"),
    )

    // "Just Player" sorts ahead of every television player here, which is what makes the two
    // orderings below tell each other apart: alphabetically it leads, on a television it cannot.
    private val justPlayer = target("com.brouken.player", "Just Player")
    private val mxPlayer = target("com.mxtech.videoplayer.ad", "MX Player")
    private val mpvNova = target(MPVNOVA_PACKAGE, "mpvNova")
    private val vlc = target("org.videolan.vlc", "VLC")
    private val all = listOf(vlc, justPlayer, mxPlayer, mpvNova)

    /**
     * The whole point of ordering by hand: resolver order routinely puts a touch-only player above
     * one built for the remote actually in the viewer's hand, and on a television that is the one
     * row that matters.
     */
    @Test
    fun `television players lead on a television, sorted among themselves`() {
        assertEquals(
            listOf("mpvNova", "VLC", "Just Player", "MX Player"),
            orderExternalPlayerTargets(all, isTv = true).map(ExternalPlayerTarget::label),
        )
    }

    /** A phone has no such preference, so the same four are only ever alphabetical there. */
    @Test
    fun `a phone orders by name alone`() {
        assertEquals(
            listOf("Just Player", "mpvNova", "MX Player", "VLC"),
            orderExternalPlayerTargets(all, isTv = false).map(ExternalPlayerTarget::label),
        )
    }

    /** Case must not split the alphabet: "mpvNova" belongs between "Just Player" and "MX Player". */
    @Test
    fun `sorting ignores case`() {
        val ordered = orderExternalPlayerTargets(listOf(mxPlayer, mpvNova), isTv = false)
        assertEquals(listOf("mpvNova", "MX Player"), ordered.map(ExternalPlayerTarget::label))
    }

    @Test
    fun `nothing installed is an empty list rather than a failure`() {
        assertEquals(emptyList<ExternalPlayerTarget>(), orderExternalPlayerTargets(emptyList(), isTv = true))
    }

    /**
     * mpvNova is an Android-TV-first fork of mpv-android and is the reason the known-player table
     * exists; losing its package name would silently demote it back into alphabetical order.
     */
    @Test
    fun `mpvNova is a known television player`() {
        assertEquals("app.mpvnova.player", MPVNOVA_PACKAGE)
        assertTrue(target(MPVNOVA_PACKAGE, "mpvNova").builtForTv)
    }
}

class ExternalPlayerProbeTest {

    /**
     * The probe decides which players the picker can offer, so its type has to be the one a real
     * hand-off actually goes out with. mpvNova's `ExternalPlayerActivity` matches http(s) with a
     * `video/…` type, and separately with a known file extension in the path — a proxied URL is
     * `http://127.0.0.1:PORT/<token>`, which has no extension at all, so the type is the only half
     * that can match. If [externalPlayerMimeType] ever stopped answering the wildcard for a
     * manifest, this probe would be asking about a hand-off that no longer happens.
     */
    @Test
    fun `the probe type is the type nearly every handoff carries`() {
        assertEquals(EXTERNAL_PLAYER_PROBE_TYPE, externalPlayerMimeType("https://cdn/v.m3u8", "hls"))
        assertEquals(EXTERNAL_PLAYER_PROBE_TYPE, externalPlayerMimeType("http://127.0.0.1:8080/9f8e", ""))
    }

    /** Only an `http` probe can stand in for a loopback-proxied stream, which is always plain http. */
    @Test
    fun `the probe url is a plain http address`() {
        assertTrue(EXTERNAL_PLAYER_PROBE_URL.startsWith("http://"))
    }
}
