package com.anilili.errors

/**
 * One failure, resolved: what a viewer reads, what they quote, and what nobody should read unless
 * they ask for it.
 *
 * This is a plain value, not a [Throwable], because most failures here are *states* rather than
 * control flow — an export that refused to publish, a list row that couldn't sync — and holding a
 * stack trace in a `StateFlow` for the life of a screen is the wrong shape. Throw sites that do
 * need an exception wrap this in [AnililiException].
 *
 * @property message the resolved one-liner, already formatted. This is the whole of what a list row
 *   or an inline notice shows.
 * @property detail everything the one-liner deliberately leaves out — codec, host, file name,
 *   response body. Shown only when someone opens the details, and carried into diagnostics.
 * @property platformCode the underlying library's own code, when it has one: `2004` from Media3's
 *   `PlaybackException`, `403` from an HTTP response. Kept because it is more precise than anything
 *   this app could invent, and because it makes a report actionable without a bundle.
 */
data class AnililiFailure(
    val error: AnililiError,
    val message: String,
    val detail: String? = null,
    val platformCode: String? = null,
) {
    /**
     * What a viewer quotes: `EXP-04`, or `PLAY-02/2004` when a platform code narrows it.
     *
     * The two halves answer different questions. `PLAY-02` says which of this app's paths gave up,
     * which is what you grep a diagnostics bundle for. `2004` says the player's own reason, which
     * often ends the investigation on its own.
     */
    val code: String
        get() = if (platformCode.isNullOrBlank()) error.code else "${error.code}/$platformCode"

    val action: ErrorAction? get() = error.action
    val retryable: Boolean get() = error.retryable

    /**
     * `Couldn't save this episode as an MP4. It still plays in the app. · EXP-04`
     *
     * [then] is one more sentence for the few places with room for it — a settings notice naming the
     * folder to copy a backup into, say. It sits before the code rather than after so the code stays
     * where the eye already looks for it, which is the end.
     */
    fun withCode(then: String? = null): String =
        if (then.isNullOrBlank()) "$message · $code" else "$message $then · $code"

    /**
     * The first sentence only.
     *
     * A dense list row has space for a label, not an explanation — the reassuring second half
     * ("It still plays in the app") is worth saying once in a notice and never eleven times down a
     * downloads list. The catalogue guarantees messages are single-line sentences, so taking the
     * first one is well defined rather than a guess at where to cut.
     */
    val shortMessage: String
        get() = message.indexOf(". ").let { if (it < 0) message else message.substring(0, it + 1) }

    /** What a list row shows: `Couldn't save this episode as an MP4. · EXP-04` */
    fun rowLabel(): String = "$shortMessage · $code"

    override fun toString(): String = withCode()
}

/** Builds a failure from a catalog entry without reporting it. Prefer [report]. */
fun AnililiError.asFailure(
    vararg args: Any?,
    detail: String? = null,
    platformCode: String? = null,
): AnililiFailure = AnililiFailure(
    error = this,
    message = message(*args),
    detail = detail,
    platformCode = platformCode,
)

/**
 * A failure travelling as control flow.
 *
 * The [Throwable.message] is deliberately code-first — `EXP-04 Couldn't save this episode…` — so
 * that anything which logs a bare exception message, including Android's own crash handler and any
 * path this catalog has not reached yet, still emits something greppable.
 */
class AnililiException(
    val failure: AnililiFailure,
    cause: Throwable? = null,
) : Exception("${failure.code} ${failure.message}", cause) {

    val error: AnililiError get() = failure.error
    val code: String get() = failure.code
    val userMessage: String get() = failure.message
}

/** The failure behind a throwable, if it carries one. Walks the cause chain. */
fun Throwable.anililiFailure(): AnililiFailure? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<AnililiException>().firstOrNull()?.failure
