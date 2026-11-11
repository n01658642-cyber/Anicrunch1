package com.anilili.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadEligibilityTest {
    @Test
    fun `an embed cannot be saved`() {
        assertFalse(ProviderCatalog.supportsOfflineDownload("nun"))
    }

    @Test
    fun `an embed the app can read a manifest out of can be saved`() {
        // `twin`'s two pages both declare a plain HLS URL that no header gates, and
        // MiruroEmbedResolver takes it with one GET, so the downloader has a real manifest to
        // fetch rather than a player page. Measured live 2026-09-09: segments 200 as h264+AAC
        // MPEG-TS with no EXT-X-KEY, and Range answered 206, which is what a download needs.
        assertTrue(ProviderCatalog.supportsOfflineDownload("twin"))
        // Still an embed as far as Miruro is concerned, and still the fallback if the page
        // stops being readable.
        assertTrue(ProviderCatalog.isEmbed("twin"))
    }

    @Test
    fun `a native provider can be saved`() {
        assertTrue(ProviderCatalog.supportsOfflineDownload("bonk"))
    }

    @Test
    fun `kiwi can be saved now that its streams decrypt`() {
        // Kiwi lists direct HLS renditions beside its embed, and saving them used to produce
        // library entries that failed with "source error". That was read as a 403 from a
        // Cloudflare-protected CDN; the device says otherwise — every request answers 200, and
        // what broke the files was Kwik's AES-128 IV being the segment filename number rather
        // than the media sequence. KwikPlaylistDataSource writes the right IV out, playback and
        // a full download were verified on a Galaxy S25, so the button belongs back on screen.
        assertTrue(ProviderCatalog.supportsOfflineDownload("kiwi"))
    }

    @Test
    fun `kiwi also offers a download link on its own site`() {
        // Still deliberately separate from the offline library: this one opens the provider's own
        // download page in a browser, and a provider can offer that whether or not the app can
        // fetch the stream itself.
        assertTrue(ProviderCatalog.supportsExternalDownloads("kiwi"))
    }
}
