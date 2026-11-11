package com.anilili.errors

import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * The one place a failure becomes both a sentence on screen and an event in the log.
 *
 * Before this, those were two independent acts — a string written at the throw site, and a separate
 * `DiagnosticsLog.event("Episode export …")` whose category was guessed from its first word. They
 * drifted, and the log could only be searched by prose. Going through [report] makes drift
 * impossible: there is one catalog entry, and the screen and the bundle are two views of it.
 *
 * Diagnostics events written here are filed as `category=<domain prefix>`, `name=<code>`, so a
 * bundle answers "how often does EXP-04 happen" with one grep instead of a guess at wording.
 */
fun AnililiError.report(
    vararg args: Any?,
    detail: String? = null,
    cause: Throwable? = null,
    attributes: Map<String, Any?> = emptyMap(),
): AnililiFailure = AnililiFailure(
    error = this,
    message = message(*args),
    detail = detail ?: cause?.technicalDetail(),
    platformCode = cause?.platformErrorCode(),
).report(cause = cause, attributes = attributes)

/**
 * Records a failure that was built earlier.
 *
 * For the places where deciding *which* failure this is and deciding that it has actually happened
 * are two different moments: `describePairingFailure` classifies a socket exception as a pure
 * function so every branch can be tested, and the view model that puts it on screen is what makes it
 * real. Without this, such a caller would either have to re-derive the entry or log nothing.
 */
fun AnililiFailure.report(
    cause: Throwable? = null,
    attributes: Map<String, Any?> = emptyMap(),
): AnililiFailure {
    // A cancelled coroutine is not a failure — it is the caller changing its mind, and logging it
    // as an error buries the real ones. The failure is still returned so a caller that genuinely
    // wants to render something can, but nothing is recorded.
    if (cause?.isCancellation() != true) {
        DiagnosticsLog.failure(
            category = error.domain.prefix,
            name = error.code,
            message = message,
            attributes = buildMap {
                put("code", code)
                put("domain", error.domain.prefix)
                platformCode?.let { put("platformCode", it) }
                cause?.platformErrorName()?.let { put("platformError", it) }
                detail?.let { put("detail", it) }
                putAll(attributes)
            },
            throwable = cause,
        )
    }
    return this
}

/** [report], then throw. For paths where the failure is control flow rather than state. */
fun AnililiError.raise(
    vararg args: Any?,
    detail: String? = null,
    cause: Throwable? = null,
    attributes: Map<String, Any?> = emptyMap(),
): Nothing = throw AnililiException(
    report(*args, detail = detail, cause = cause, attributes = attributes),
    cause,
)

/**
 * Turns a caught throwable into a catalogued failure, borrowing the platform's own classification
 * where it has one.
 *
 * The standard library is genuinely good at naming transport failures — `UnknownHostException` and
 * `SocketTimeoutException` say more, and more reliably, than any wrapper this app would write. So
 * those map straight onto NET entries and [fallback] is only used for what the platform leaves
 * unclassified.
 *
 * An already-catalogued failure passes through untouched, so wrapping at several layers cannot
 * relabel an error that a deeper layer already identified precisely.
 */
fun Throwable.toFailure(
    fallback: AnililiError = AnililiError.UNEXPECTED,
    vararg args: Any?,
    attributes: Map<String, Any?> = emptyMap(),
): AnililiFailure {
    anililiFailure()?.let { return it }
    val mapped = (if (isTlsIntercepted()) AnililiError.NETWORK_INTERCEPTED else classifyTransport()) ?: fallback
    val mappedArgs = if (mapped === fallback) args else emptyArray()
    return mapped.report(*mappedArgs, cause = this, attributes = attributes)
}

/**
 * The transport-level entry this throwable clearly is, or null to let the caller's own domain
 * decide.
 *
 * Deliberately conservative. A bare [IOException] is *not* mapped: at this level it means "some I/O
 * went wrong", which is as likely to be a full disk or an unreadable file as a network fault, and
 * telling someone to check their connection when the real problem is storage sends them the wrong
 * way. Only exceptions that can only mean a network fault are claimed here.
 */
private fun Throwable.classifyTransport(): AnililiError? = generateSequence(this) { current ->
    current.cause?.takeIf { it !== current }
}.take(8).firstNotNullOfOrNull { error ->
    when (error) {
        is UnknownHostException -> AnililiError.NETWORK_DNS_BLOCKED
        is SSLException -> AnililiError.NETWORK_TLS_REJECTED
        is SocketTimeoutException, is TimeoutException -> AnililiError.NETWORK_TIMEOUT
        is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
            AnililiError.NETWORK_UNREACHABLE
        else -> null
    }
}

/** Cancellation travels as an exception but is never an error; callers must not report it. */
fun Throwable.isCancellation(): Boolean = generateSequence(this) { current ->
    current.cause?.takeIf { it !== current }
}.take(8).any { it is CancellationException }
