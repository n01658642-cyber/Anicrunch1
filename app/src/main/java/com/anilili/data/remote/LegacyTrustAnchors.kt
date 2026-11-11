package com.anilili.data.remote

import android.os.Build
import com.anilili.diagnostics.DiagnosticsLog
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Teaches pre-7.1.1 Android about ISRG Root X1, the root every Let's Encrypt certificate now
 * chains to.
 *
 * Android's trust store is baked into the system image, and ISRG Root X1 was only added in 7.1.1
 * (API 25). Older devices used to reach Let's Encrypt hosts anyway because the chain carried a
 * cross-signature from DST Root CA X3, which those images do have — but that expired on
 * 2021-09-30 and the cross-signed chain is no longer served. So on anything below API 25 every
 * Let's Encrypt host now fails the handshake, and nothing else about the app is wrong.
 *
 * `minSdk` here is 22, so this is a supported configuration, and it is in the field: a diagnostics
 * bundle on 2026-09-12 came from an Amlogic set-top box on a 2018 AOSP 5.1.1 build (API 22,
 * `isLowRamDevice`, no leanback flags, mains-powered) whose episode thumbnails were all blank.
 * Nothing in the log looked like a failure, because every *other* host the app uses happens to
 * chain to a root old images do have — AniList's posters and API go through GTS Root R4 and
 * jsDelivr through GlobalSign R3, both cross-signed for exactly this reason, and the pipe's
 * provider hosts sit behind Cloudflare on GTS as well. Measured on 2026-09-12, the only two the
 * app touches that do not are:
 *
 * - `image.tmdb.org` → Let's Encrypt YR2 → ISRG Root YR → **ISRG Root X1**. Every episode still,
 *   from Konoha and from TMDB alike, which is why the titles loaded and the pictures did not.
 *   AniList's `streamingEpisodes` thumbnails are Crunchyroll-hosted and *did* load, so a list
 *   showed a stray working thumbnail wherever that last-resort source had filled a row.
 * - `flixcloud.cc` → Let's Encrypt YE2 → ISRG Root YE → **ISRG Root X2, cross-signed by X1**.
 *   ReAnime's streams. Fixed by the same anchor, since the chain is rooted in X1.
 *
 * One certificate covers both, and it is the self-signed root rather than any cross-signature, so
 * it does not expire with one: valid to 2035-06-04.
 *
 * Additive, and only below API 25. The bundled root is added *beside* everything the platform
 * already trusts rather than replacing it, so nothing the device trusts stops being trusted and no
 * user or enterprise anchor is dropped. From API 25 up the platform has this root itself and gets
 * to do its own verification, which is better than ours — hence the version gate rather than
 * installing this everywhere.
 */
object LegacyTrustAnchors {

    /**
     * ISRG Root X1, self-signed.
     *
     * SHA-256 `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6` — checked against
     * the published fingerprint, and pinned by [LegacyTrustAnchorsTest] so a careless edit to this
     * blob cannot quietly install a different certificate.
     */
    internal const val ISRG_ROOT_X1_PEM: String = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
"""

    /** API 25 is where ISRG Root X1 arrives in the system image; below it, the app must supply it. */
    internal fun needsBundledIsrgRoot(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.N_MR1

    internal fun parseIsrgRootX1(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(ISRG_ROOT_X1_PEM.trim().toByteArray()))
            as X509Certificate

    /**
     * The merged trust manager, or null when the platform's own is already sufficient or the merge
     * could not be built.
     *
     * Built once. A failure here must leave the client alone rather than take the app's networking
     * with it: an unmodified client is exactly as capable as before this existed, whereas a
     * half-built SSL context is not.
     */
    private val trustManager: X509TrustManager? by lazy {
        if (!needsBundledIsrgRoot(Build.VERSION.SDK_INT)) return@lazy null
        runCatching {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // Everything the platform trusts, kept. Read from the default trust manager rather
            // than from a file, so device and enterprise anchors come along too.
            val platform = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .flatMap { it.acceptedIssuers.asList() }
            platform.forEachIndexed { index, certificate ->
                store.setCertificateEntry("system-$index", certificate)
            }
            store.setCertificateEntry("isrg-root-x1", parseIsrgRootX1())
            val merged = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
            DiagnosticsLog.event(
                "LegacyTrustAnchors merged ISRG Root X1 sdk=${Build.VERSION.SDK_INT} " +
                    "systemAnchors=${platform.size} installed=${merged != null}",
            )
            merged
        }.onFailure {
            DiagnosticsLog.throwable("LegacyTrustAnchors could not merge ISRG Root X1", it)
        }.getOrNull()
    }

    /**
     * Adds the bundled root to [this] on the devices that need it, and changes nothing on the rest.
     *
     * Every client the app builds goes through here. The thumbnails were the visible symptom, but
     * the fault is one the whole process shares — Coil derives its client from the shared one,
     * playback and downloads build their own, and a Let's Encrypt stream host fails for all of
     * them identically.
     */
    fun OkHttpClient.Builder.withLegacyTrustAnchors(): OkHttpClient.Builder {
        val manager = trustManager ?: return this
        return runCatching {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            sslSocketFactory(context.socketFactory, manager)
        }.onFailure {
            DiagnosticsLog.throwable("LegacyTrustAnchors could not install ISRG Root X1", it)
        }.getOrDefault(this)
    }
}
