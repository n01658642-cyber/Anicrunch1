package com.anilili.data.pairing

import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.asFailure
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What went wrong, in words a viewer can act on.
 *
 * "java.net.ConnectException: failed to connect to /192.168.1.50 (port 47001): connect failed:
 * ECONNREFUSED" tells the person holding the remote nothing. Each of the ways this fails on a real
 * home network has a different cause and a different fix, and they are distinguishable — refused is
 * a closed port, a timeout is almost always the router isolating its clients, and no-route is
 * usually two devices on different networks. So they are named, one catalogue entry each.
 *
 * The [summary] is the entry's own line and the [hint] is its detail, which is what lets a pairing
 * failure be quoted as `PAIR-02` while still explaining AP isolation in full on screen — a hint this
 * long has to be the detail, and the code has to exist, because this is the one failure a viewer
 * cannot photograph a player for.
 *
 * Pure — nothing is reported from here, so every branch stays unit-testable and the view model
 * decides when a failure is real rather than a step being retried.
 */
internal data class PairingFailureAdvice(val failure: AnililiFailure) {
    /** The catalogue line, with the address filled in where the entry asks for one. */
    val summary: String get() = failure.message

    /** The long-form explanation: which router setting, which network, what to do on the television. */
    val hint: String get() = failure.detail.orEmpty()

    /** `No reply from 192.168.1.50:47001. · PAIR-02` */
    fun withCode(): String = failure.withCode()
}

internal fun describePairingFailure(
    error: Throwable,
    host: String? = null,
    port: Int? = null,
    phoneAddresses: List<String> = emptyList(),
): PairingFailureAdvice {
    val where = buildString {
        append(host ?: "the television")
        if (port != null) append(":").append(port)
    }
    val subnetNote = subnetMismatchNote(host, phoneAddresses)
    fun advice(error: AnililiError, vararg args: Any?, hint: String) =
        PairingFailureAdvice(error.asFailure(*args, detail = hint))

    return when {
        error is ConnectException || error.message?.contains("ECONNREFUSED", ignoreCase = true) == true ->
            advice(
                AnililiError.PAIRING_NOTHING_LISTENING,
                where,
                hint = "The television's pairing screen has probably closed or moved on. " +
                    "Open \"Sign in from my phone\" on it again, then retry — and check the port " +
                    "matches the one it is showing, because it changes every time.",
            )

        error is SocketTimeoutException ->
            advice(
                AnililiError.PAIRING_NO_REPLY,
                where,
                hint = subnetNote ?: "The address is right but nothing answered. Most often this is " +
                    "the router refusing to let devices talk to each other — look for \"AP isolation\", " +
                    "\"client isolation\" or a guest network, and make sure both devices are on the " +
                    "main Wi-Fi.",
            )

        error is NoRouteToHostException || error is PortUnreachableException ->
            advice(
                AnililiError.PAIRING_NO_ROUTE,
                where,
                hint = subnetNote ?: "There is no route to that address. Check both devices are on " +
                    "the same network — not one on Wi-Fi and the other on a guest or work network.",
            )

        error is UnknownHostException ->
            advice(
                AnililiError.PAIRING_BAD_ADDRESS,
                hint = "\"${host.orEmpty()}\" is not an address this phone can look up. Type it " +
                    "exactly as the television shows it, including the port after the colon.",
            )

        error is EOFException ->
            advice(
                AnililiError.PAIRING_CLOSED,
                hint = "It may have timed out, been cancelled there, or already have taken another " +
                    "connection. Reopen the pairing screen on the television and try again.",
            )

        error is PairingProtocolException ->
            advice(
                AnililiError.PAIRING_PROTOCOL_MISMATCH,
                // What exactly disagreed is the detail: it is the part that says whether this was a
                // version skew or something on the network answering in the television's place.
                hint = "${error.message ?: "The two devices did not agree on the handshake."} " +
                    "Start again on both. If the codes never matched, do not continue — " +
                    "something else on the network answered instead of your television.",
            )

        else ->
            advice(
                AnililiError.PAIRING_FAILED,
                hint = listOfNotNull(
                    error.message ?: error.javaClass.simpleName,
                    subnetNote ?: "Reopen the pairing screen on the television and try again.",
                ).joinToString(" "),
            )
    }
}

/**
 * A plain-language note when the two devices are clearly not on the same network.
 *
 * Assumes a /24, which is what essentially every home router hands out. It is a hint, not a
 * routing decision — being wrong costs a misleading sentence, and being silent costs the viewer
 * the single most common cause.
 */
internal fun subnetMismatchNote(host: String?, phoneAddresses: List<String>): String? {
    val target = ipv24Prefix(host ?: return null) ?: return null
    if (phoneAddresses.isEmpty()) return null
    val mine = phoneAddresses.mapNotNull(::ipv24Prefix)
    if (mine.isEmpty() || mine.contains(target)) return null
    return "This phone is on ${mine.joinToString(" and ") { "$it.x" }} but the television is on " +
        "$target.x — they are on different networks, so they cannot reach each other. Put both on " +
        "the same Wi-Fi."
}

/** `192.168.31.27` -> `192.168.31`. Null for anything that is not a dotted IPv4. */
internal fun ipv24Prefix(address: String): String? {
    val parts = address.trim().split('.')
    if (parts.size != 4) return null
    if (parts.any { part -> part.isEmpty() || part.toIntOrNull()?.let { it !in 0..255 } != false }) return null
    return parts.take(3).joinToString(".")
}
