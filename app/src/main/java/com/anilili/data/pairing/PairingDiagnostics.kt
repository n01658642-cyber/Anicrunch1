package com.anilili.data.pairing

import android.net.nsd.NsdManager
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An on-screen trace of everything pairing does.
 *
 * Pairing fails on other people's networks, not on ours: client isolation, guest VLANs, a TV on
 * Ethernet while the phone is on Wi-Fi, a router that never forwards multicast. None of that is
 * reproducible here and none of it is visible to a viewer holding a remote — so the state that
 * would otherwise only exist in logcat is put on the screen instead, where someone can photograph
 * it and send it in.
 */
object PairingDiagnostics {
    private const val MAX_ENTRIES = 60

    data class Entry(val atMs: Long, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun log(text: String) {
        val entry = Entry(System.currentTimeMillis(), text)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** `mm:ss.S` since it is only ever read as "what happened just before what". */
    fun stamp(atMs: Long): String {
        val seconds = atMs / 1000
        return "%02d:%02d.%d".format((seconds / 60) % 60, seconds % 60, (atMs % 1000) / 100)
    }
}

/**
 * Every address this device could be reached on, with the interface that owns it.
 *
 * A list rather than a single answer on purpose: a television with Ethernet *and* Wi-Fi up has two,
 * and printing only the first is how you end up telling someone to type an address that their phone
 * cannot route to.
 */
internal fun localPairingAddresses(): List<Pair<String, String>> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { iface ->
            iface.inetAddresses.asSequence()
                .filter { it is Inet4Address && it.isSiteLocalAddress }
                .map { iface.name to it.hostAddress.orEmpty() }
        }
        .filter { it.second.isNotBlank() }
        .toList()
}.getOrDefault(emptyList())

/** The first address, for the headline instruction. */
internal fun localPairingAddress(): String? = localPairingAddresses().firstOrNull()?.second

/**
 * NsdManager reports failures as bare ints. Naming them is the difference between a viewer's photo
 * being useful and being a screenshot of the number 3.
 */
internal fun nsdErrorName(errorCode: Int): String = when (errorCode) {
    NsdManager.FAILURE_INTERNAL_ERROR -> "INTERNAL_ERROR"
    NsdManager.FAILURE_ALREADY_ACTIVE -> "ALREADY_ACTIVE"
    NsdManager.FAILURE_MAX_LIMIT -> "MAX_LIMIT"
    else -> "code $errorCode"
}
