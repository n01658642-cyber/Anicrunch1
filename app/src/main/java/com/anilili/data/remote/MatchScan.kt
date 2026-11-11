package com.anilili.data.remote

import java.util.regex.Matcher

/**
 * Walks [this] pattern's matches in [input] through a single [Matcher].
 *
 * `Regex.findAll` is the obvious way to write a page walk and the wrong one on Android. Its
 * iterator — `MatcherMatchResult.next()` — builds a **fresh matcher per match**
 * (`matcher.pattern().matcher(input)`, confirmed in kotlin-stdlib 2.1.21's bytecode), and Android's
 * `java.util.regex` is ICU-backed, so *constructing* a matcher copies the entire input into native
 * memory as UTF-16. A walk therefore costs `matches x input x 2` bytes of native heap. On a desktop
 * JVM the same code is free, because there a matcher only holds a reference to the input — which is
 * why this never showed up in a unit test.
 *
 * None of that cost is visible to the Java GC: the object it can see is a ~100 byte matcher, so a
 * calm Java heap gives it no reason to collect while the native heap runs away.
 *
 * Measured on the Skyworth SW-22AE that reported it (armeabi-v7a, Android 14, ~590MB free) while a
 * 22-provider catalogue fan-out parsed One Piece's ~1000-episode pages: native heap went 40MB ->
 * 1195MB inside one 30s sampling window, system free RAM fell to 57MB, and the low-memory killer
 * took the app out of the *foreground* twice. The Java heap sat at 44MB of 512MB throughout. A
 * twelve-episode title pays roughly 1/450th of that, which is why the report was "it only dies on
 * One Piece" — and why the same walk also blew the 15s provider budget, since the cost is paid in
 * CPU as well as bytes.
 *
 * A bare [Matcher.find] resumes from the end of the previous match rather than resetting, so the
 * input is copied once for the whole walk instead of once per match.
 *
 * [action] receives the matcher **live**, and it is reused across iterations: read the groups you
 * need inside the lambda and do not retain it or its [MatchResult][java.util.regex.MatchResult].
 */
internal inline fun Regex.forEachMatch(input: CharSequence, action: (Matcher) -> Unit) {
    val matcher = toPattern().matcher(input)
    while (matcher.find()) action(matcher)
}

/**
 * [forEachMatch] for the `findAll(…).mapNotNull { … }.toList()` shape, keeping the single matcher.
 *
 * Returning null from [transform] drops the match, exactly as `mapNotNull` does.
 */
internal inline fun <T : Any> Regex.mapMatches(input: CharSequence, transform: (Matcher) -> T?): List<T> {
    val results = mutableListOf<T>()
    forEachMatch(input) { matcher -> transform(matcher)?.let(results::add) }
    return results
}

/**
 * The first non-null [transform] of a match, stopping the walk there.
 *
 * The lazy `findAll(…).firstNotNullOfOrNull { … }` this replaces already stopped early; it just
 * paid a full native copy of the input for every match it looked at on the way.
 */
internal inline fun <T : Any> Regex.firstMatchNotNullOf(input: CharSequence, transform: (Matcher) -> T?): T? {
    val matcher = toPattern().matcher(input)
    while (matcher.find()) transform(matcher)?.let { return it }
    return null
}
