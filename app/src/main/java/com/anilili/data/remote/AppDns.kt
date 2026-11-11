package com.anilili.data.remote

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Who answers "what address is this host at", when the network's own answer cannot be trusted.
 *
 * The block this exists for is the cheapest one an ISP can apply: leave the source's servers
 * reachable and simply refuse to hand out their address. The site is up, the app's requests never
 * leave the phone, and from the inside it is indistinguishable from every source being down at
 * once. Naming a resolver here moves that one lookup onto an HTTPS request the ISP can see the
 * destination of but not the contents of, and the source loads again.
 *
 * Forcing a resolver on everyone would be wrong: one reached over the open internet is slower than
 * the one on the other side of the router, it is a third party learning every host the app asks
 * for, and on a network that blocks nothing it buys nothing. But leaving it as a switch for people
 * who already know their ISP is the problem left everyone else looking at a dead app with no reason
 * to suspect DNS — testers reported exactly that. So the default is [AUTO], which keeps the
 * network's resolver and reaches for a secure one only where the network is seen blocking; the
 * named resolvers below remain for anyone who wants one forced.
 *
 * ### The bootstrap addresses
 *
 * Every entry names its resolver by hostname and then hard-codes the addresses that hostname is
 * at, because otherwise the first thing a DoH lookup needs is a DNS lookup — from the resolver
 * being bypassed, for a hostname it has every reason to block. Each address here was checked
 * against its own hostname: TLS with that SNI, HTTP/2, and a real RFC 8484 answer.
 *
 * They are IPv4 only. Each provider documents IPv6 twins, but they could not be reached from the
 * machine this was written on to confirm, and an unreachable bootstrap address is not free — OkHttp
 * walks the list one address at a time, so a dead entry costs a connect timeout on every lookup
 * that has to fall through it. On an IPv6-only network the resolver simply cannot be bootstrapped
 * and [AppDns] falls back to the system one, which is the same thing that happens on any network
 * that blocks the resolver outright.
 */
enum class DohProvider(
    val storedValue: String,
    val label: String,
    /** Shown under the name in the picker: which host answers, and whether it filters. */
    val description: String,
    /** The RFC 8484 endpoint, or null for [NONE]. */
    val url: String?,
    /** Verified addresses of [url]'s host, IPv4 first. Empty for [NONE]. */
    val bootstrap: List<String>,
) {
    /**
     * The network's own resolver, and a secure one only for sources the network blocks. See
     * [AutoDns]. The default for anyone who has never chosen, so a viewer on a blocking ISP gets
     * their sources back without first having to guess that DNS is the problem.
     */
    AUTO(
        storedValue = "auto",
        label = "Automatic",
        description = "Your network's DNS, switching to secure DNS where it blocks a source",
        url = null,
        bootstrap = emptyList(),
    ),
    NONE(
        storedValue = "none",
        label = "None",
        description = "Whatever your network hands out",
        url = null,
        bootstrap = emptyList(),
    ),
    GOOGLE(
        storedValue = "google",
        label = "Google",
        description = "dns.google · no filtering",
        url = "https://dns.google/dns-query",
        bootstrap = listOf("8.8.8.8", "8.8.4.4"),
    ),
    CLOUDFLARE(
        storedValue = "cloudflare",
        label = "Cloudflare",
        description = "cloudflare-dns.com · no filtering",
        url = "https://cloudflare-dns.com/dns-query",
        bootstrap = listOf("1.1.1.1", "1.0.0.1"),
    ),
    CLOUDFLARE_SECURITY(
        storedValue = "cloudflare_security",
        label = "Cloudflare Security",
        description = "security.cloudflare-dns.com · blocks hosts it rates malicious",
        url = "https://security.cloudflare-dns.com/dns-query",
        bootstrap = listOf("1.1.1.2", "1.0.0.2"),
    ),
    // Cloudflare publishes a third resolver on 1.1.1.3 — family.cloudflare-dns.com, malware plus
    // adult content — and it is deliberately not here. Its adult list answers 0.0.0.0 for
    // www.miruro.bz, which is the single endpoint the whole catalogue comes from, and for every
    // mirror but miruro.to, hanime.tv, and nine sources including anizone, anineko, anikoto and
    // 2dhive. Choosing it would empty the app rather than filter it, from a menu whose entire
    // reason to exist is getting a blocked source to load. The one above filters malware only and
    // was checked against every host the app resolves without a single refusal.

    ADGUARD(
        storedValue = "adguard",
        label = "AdGuard",
        description = "dns.adguard-dns.com · blocks ads and trackers",
        url = "https://dns.adguard-dns.com/dns-query",
        bootstrap = listOf("94.140.14.14", "94.140.15.15"),
    ),
    QUAD9(
        storedValue = "quad9",
        label = "Quad9",
        description = "dns.quad9.net · blocks hosts it rates malicious",
        url = "https://dns.quad9.net/dns-query",
        bootstrap = listOf("9.9.9.9", "149.112.112.112"),
    ),
    DNS_SB(
        storedValue = "dns_sb",
        label = "DNS.SB",
        description = "doh.dns.sb · no filtering",
        url = "https://doh.dns.sb/dns-query",
        bootstrap = listOf("185.222.222.222", "45.11.45.11"),
    ),
    CANADIAN_SHIELD(
        storedValue = "canadian_shield",
        label = "Canadian Shield",
        description = "private.canadianshield.cira.ca · no filtering",
        url = "https://private.canadianshield.cira.ca/dns-query",
        bootstrap = listOf("149.112.121.10", "149.112.122.10"),
    );

    companion object {
        /**
         * Nothing stored means the viewer never chose, and gets [AUTO]. An explicit "none" is a
         * choice and is kept: someone who turned DNS handling off meant it.
         */
        fun fromStored(value: String?): DohProvider =
            if (value == null) AUTO else entries.firstOrNull { it.storedValue == value } ?: AUTO
    }
}

/**
 * The one [Dns] every OkHttp client in the app is built with, so the resolver can be changed
 * without rebuilding any of them.
 *
 * Installed at startup, before the stored preference has been read off disk, and switched later by
 * `SettingsStore`. That ordering is the whole reason this is an object holding a swappable resolver
 * rather than a resolver passed to a builder: `AppGraph.httpClient` is built on the launch path and
 * cannot wait for a disk read, and the download and playback clients are built later still, in
 * other processes' worth of lifecycle. All of them read this, and a change reaches every one of
 * them at once.
 *
 * What it does **not** reach: Media3's Cronet transport and every WebView. Both carry their own
 * resolver stacks with no seam to hand a [Dns] to, which is why [PlaybackService] prefers its
 * OkHttp transport while a resolver is [active], and why a provider that has to be resolved through
 * a WebView is beyond this setting's help.
 */
object AppDns : Dns {
    /**
     * Small on purpose. A DoH answer is a few hundred bytes and only useful until its TTL expires,
     * so this is sized to hold a working set of hosts rather than a history of them.
     */
    private const val CACHE_BYTES = 2L * 1024 * 1024

    /** The two answers [DnsOverHttps] reports from a resolver that actually replied. */
    private const val NXDOMAIN_SUFFIX = ": NXDOMAIN"

    private val lock = Any()

    @Volatile private var cacheDir: File? = null
    @Volatile private var resolver: DnsOverHttps? = null
    @Volatile private var degraded = false

    @Volatile
    var provider: DohProvider = DohProvider.NONE
        private set

    /**
     * Whether lookups are currently going over HTTPS rather than to the network's own resolver.
     * Under [DohProvider.AUTO] that is only true on a network [AutoDns] has seen blocking, which is
     * what moves playback onto the one transport that reads this resolver, there and nowhere else.
     */
    val active: Boolean get() = resolver != null || (provider == DohProvider.AUTO && auto.engagedHere())

    private const val AUTO_PREFS = "dns_auto"
    private const val AUTO_KEY = "engaged_networks"

    /** How long a network stays switched over before its own resolver is given another chance. */
    private const val AUTO_ENGAGED_TTL_MS = 30L * 24 * 60 * 60 * 1000

    @Volatile private var prefs: android.content.SharedPreferences? = null

    /**
     * The secure resolvers [DohProvider.AUTO] falls back to, Cloudflare first and Google second. Each
     * has a cache directory of its own: two caches over one directory corrupt each other's journal.
     */
    private val autoResolvers: List<DnsOverHttps> by lazy {
        listOf(DohProvider.CLOUDFLARE, DohProvider.GOOGLE).map { build(it, it.url!!, "doh-auto-${it.storedValue}") }
    }

    private val auto: AutoDns by lazy {
        val now = System.currentTimeMillis()
        val remembered = prefs?.getStringSet(AUTO_KEY, emptySet()).orEmpty().mapNotNull { entry ->
            val (network, since) = entry.split('@', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            since.toLongOrNull()?.takeIf { now - it < AUTO_ENGAGED_TTL_MS }?.let { network to it }
        }.toMap()
        AutoDns(
            system = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (com.anilili.data.ProviderBenchmark.sinkholes(hostname)) {
                        listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
            },
            secure = { autoResolvers },
            networkKey = com.anilili.data.NetworkIdentity::current,
            isNameDenied = ::isNameDenied,
            engaged = remembered,
            onEngaged = { network, host, reason ->
                DiagnosticsLog.event(
                    category = "dns",
                    name = "dns.auto.engaged",
                    attributes = mapOf("network" to network, "host" to host, "reason" to reason),
                )
                persistAuto()
            },
        )
    }

    /** Records where the answer cache may live. Called once, before any preference has been read. */
    fun attach(context: Context) {
        cacheDir = File(context.applicationContext.cacheDir, "doh")
        prefs = context.applicationContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * For a request whose TLS came back with another site's certificate: whether that was the
     * network's DNS redirecting it, in which case this network now uses the secure resolver and the
     * request is worth making again. See [AutoDns.interceptionSuspected] and [InterceptionRetry].
     */
    fun interceptionSuspected(hostname: String): Boolean =
        provider == DohProvider.AUTO && runCatching { auto.interceptionSuspected(hostname) }.getOrDefault(false)

    private fun persistAuto() {
        val store = prefs ?: return
        store.edit()
            .putStringSet(AUTO_KEY, auto.engagedNetworks().map { (network, since) -> "$network@$since" }.toSet())
            .apply()
    }

    /**
     * Retries a request once when its certificate was for a different site and [AutoDns] confirms
     * DNS sent it there — the shape of an ISP block page. Added to every client that uses [AppDns];
     * inert unless [DohProvider.AUTO] is selected.
     */
    object InterceptionRetry : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            return try {
                chain.proceed(request)
            } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
                if (!interceptionSuspected(request.url.host)) throw e
                chain.proceed(request)
            }
        }
    }

    /**
     * Points lookups at [value]'s resolver, or back at the system one for [DohProvider.NONE].
     *
     * Idempotent because it has to be: the settings pipeline re-applies every preference on every
     * write, so this is called each time anything at all is saved, and rebuilding the resolver
     * there would throw away its connection and its cached answers for nothing.
     */
    fun select(value: DohProvider) {
        if (value == provider) return
        val replaced = synchronized(lock) {
            if (value == provider) return
            val previous = resolver
            resolver = value.url?.let { build(value, it) }
            provider = value
            degraded = false
            DiagnosticsLog.event("AppDns resolver=${value.storedValue}")
            previous
        }
        // Both resolvers cache into the same directory, and two live caches over one directory
        // corrupt each other's journal rather than failing where anyone would see it. The old one
        // has no traffic left to serve the moment the field above is reassigned, so it is closed
        // outside the lock, where a disk write cannot hold up the next lookup.
        replaced?.let { old ->
            runCatching { old.client.cache?.close() }
            runCatching { old.client.connectionPool.evictAll() }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (provider == DohProvider.AUTO) return auto.lookup(hostname)
        val doh = resolver ?: return Dns.SYSTEM.lookup(hostname)
        return try {
            doh.lookup(hostname).also { if (degraded) recovered() }
        } catch (error: UnknownHostException) {
            if (isNameDenied(error)) throw error
            degrade(hostname, error)
            Dns.SYSTEM.lookup(hostname)
        } catch (error: Exception) {
            // [Dns.lookup] may only fail with UnknownHostException, and a caller deep inside
            // OkHttp's connection code is entitled to assume that. The one way anything else gets
            // here is a lookup that was already running when [select] closed the resolver out from
            // under it, and the right answer to that is the same as to any resolver that stopped
            // answering: ask the system one.
            degrade(hostname, error)
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * Whether the resolver said the name does not exist, as opposed to not being reachable to say
     * anything at all.
     *
     * The difference decides whether a failed lookup falls back to the network's own resolver. A
     * resolver that answered has done its job and its answer is worth more than the ISP's — a
     * blocking resolver is likelier to hand back a landing page's address than nothing at all, and
     * connecting to that is worse than failing. A resolver that could not be reached has said
     * nothing, and refusing to look the host up any other way would take the whole app down with
     * it: a network that blocks the resolver, a captive portal, an outage.
     *
     * [DnsOverHttps] draws the line by hand: it rethrows an [UnknownHostException] its own decoder
     * raised — only ever `"<host>: NXDOMAIN"` or `"<host>: SERVFAIL"` — and wraps anything else in a
     * fresh one carrying the transport failure as its cause. So the message is the signal, and
     * `AppDnsFallbackTest` pins it against the real resolver rather than against this comment.
     * SERVFAIL is treated as a failure to answer, because that is what it is.
     */
    internal fun isNameDenied(error: UnknownHostException): Boolean =
        error.message?.endsWith(NXDOMAIN_SUFFIX) == true

    private fun build(value: DohProvider, url: String, cacheName: String? = null): DnsOverHttps = DnsOverHttps.Builder()
        .client(client(cacheName))
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(value.bootstrap.map(InetAddress::getByName))
        .build()

    /**
     * A client of its own rather than the app's.
     *
     * `newBuilder()` would share the app client's dispatcher, and a DoH lookup blocks its caller
     * until a request it enqueues on that same dispatcher comes back. Enough connections opening at
     * once and every one of the dispatcher's slots is held by a thread waiting on a lookup that can
     * never be dispatched. Its own dispatcher and pool cannot deadlock against the traffic it is
     * resolving for.
     *
     * The timeouts are much shorter than the app's for the same reason a lookup is not a download:
     * a resolver that has not answered in a few seconds is one to give up on, and every second
     * spent here is a second added to a request that has not started yet.
     */
    private fun client(cacheName: String? = null): OkHttpClient = OkHttpClient.Builder()
        .withLegacyTrustAnchors()
        // GET, which is the default, so answers are ordinary cacheable responses; DnsOverHttps
        // reads this cache before it opens a connection.
        .cache(
            cacheDir?.let { dir -> cacheName?.let { File(dir.parentFile, it) } ?: dir }
                ?.let { dir -> runCatching { Cache(dir, CACHE_BYTES) }.getOrNull() },
        )
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Logged on the way into and out of the fallback rather than per lookup: an unreachable
     * resolver fails every lookup the app makes, and a line each would bury the diagnostics bundle
     * under the one fact it is trying to report.
     */
    private fun degrade(hostname: String, error: Throwable) {
        if (degraded) return
        degraded = true
        DiagnosticsLog.event(
            "AppDns ${provider.storedValue} unreachable at $hostname (${error.message}); " +
                "falling back to the system resolver",
        )
    }

    private fun recovered() {
        degraded = false
        DiagnosticsLog.event("AppDns ${provider.storedValue} answering again")
    }
}
