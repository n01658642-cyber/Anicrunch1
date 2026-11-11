package com.anilili.errors

import javax.net.ssl.SSLException

/**
 * Whether a TLS handshake was answered by something that is not TLS at all.
 *
 * That is what a network filter looks like from the inside: an ISP or router "protection" feature
 * answers port 443 with its own plain-HTTP block page, and the handshake reads the first bytes of
 * that page as a record header. A tester's Samsung failed every episode on two video hosts this way
 * for a whole session while the same hosts played everywhere else, and the screen said "Lost the
 * connection to this server", which sent them to retry something that could not work.
 *
 * Matched on the message, not the type, and only on these two: the platform's TLS reports this as a
 * plain `SSLException` it also uses for everything from an expired certificate to a reset, and
 * Cronet's own exception class is renamed by R8 in our build. Everything else — a certificate this
 * device cannot verify included — stays with the entries that already cover it.
 */
internal fun Throwable.isTlsIntercepted(): Boolean = causeChain().any { error ->
    val message = error.message.orEmpty()
    (error is SSLException && CONSCRYPT_NOT_TLS in message) || CRONET_PROTOCOL_ERROR in message
}

/** Conscrypt, when the bytes after a ClientHello do not parse as a TLS record. */
private const val CONSCRYPT_NOT_TLS = "Unable to parse TLS packet header"

/** Cronet's net error -107, the same condition seen through Chromium's stack. */
private const val CRONET_PROTOCOL_ERROR = "net::ERR_SSL_PROTOCOL_ERROR"
