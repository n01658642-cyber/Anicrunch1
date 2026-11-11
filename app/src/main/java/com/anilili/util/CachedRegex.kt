package com.anilili.util

import java.util.concurrent.ConcurrentHashMap

/**
 * A [Regex] compiled once per pattern and reused, for patterns written inline in a function.
 *
 * `Regex(...)` compiles its pattern every time it runs, and on Android that compile is ICU's —
 * measured at 30-60x slower on a television than on a phone for the same provider page, which
 * is enough for a late provider to miss its catalogue budget. Provider parsers mostly built
 * their patterns where they used them, so a lookup that ran a pattern per item compiled it per
 * item. Hoisting each one into a named top-level constant is the other fix; this one keeps the
 * pattern beside the code that reads it.
 *
 * Only for constant patterns. The cache is never cleared, so a pattern built from runtime data
 * (an id, a title) would grow it without bound — construct those with `Regex(...)` as before.
 *
 * `getOrPut` rather than `computeIfAbsent`, which arrived in API 24 and this app still runs on 22.
 * Two threads racing on a cold pattern can therefore each compile it and the later write wins,
 * which costs one compile and changes no answer: a [Regex] is immutable and every operation on it
 * makes its own matcher, so the copy that loses is as usable as the one that wins.
 */
fun Regex.Companion.cached(pattern: String): Regex =
    cache.getOrPut(Key(pattern, emptySet())) { Regex(pattern) }

fun Regex.Companion.cached(pattern: String, option: RegexOption): Regex =
    cache.getOrPut(Key(pattern, setOf(option))) { Regex(pattern, option) }

fun Regex.Companion.cached(pattern: String, options: Set<RegexOption>): Regex =
    cache.getOrPut(Key(pattern, options)) { Regex(pattern, options) }

private data class Key(val pattern: String, val options: Set<RegexOption>)

private val cache = ConcurrentHashMap<Key, Regex>()
