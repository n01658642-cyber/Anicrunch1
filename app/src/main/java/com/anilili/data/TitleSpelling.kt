package com.anilili.data

/**
 * What a misspelled search most likely meant: [text] to search instead, and the snapshot's titles
 * that [text] names, most watched first, for the box's autocomplete to offer.
 */
data class TitleSpelling(val text: String, val titles: List<TitleSuggestion>)

/**
 * "Did you mean" for title searches, built from the words of the bundled snapshot's titles.
 *
 * The desktop app ports this (`crates/core/src/spelling.rs`); `parity/spelling.json` holds the
 * cases both must answer alike, so change the two together.
 *
 * AniList has no answer for a misspelled word at all: probed 2026-10-06, `naurto`, `atack on
 * titan`, `jujutsu kaisn`, `freiren` and `one pice` each came back as an empty page, while every
 * correct spelling found its show. The snapshot cannot help either, because it matches text
 * exactly. So a single slipped key on a remote left the viewer with "Nothing matched", and they
 * read that as "this app doesn't have it".
 *
 * The correction lines the typed words up against real titles rather than fixing each word alone.
 * Word by word, "one peace" is already fine because "peace" is a word; checked as a phrase, no
 * title says "one peace" and a very popular one says "one piece". A phrase that some title already
 * contains is never corrected, which is what stops a correctly spelled but obscure title from being
 * "fixed" into a popular one. The last word may also be the start of a title word, since search
 * runs while the viewer is still typing: "atack on ti" means Attack on Titan, and "naru" and
 * "death not" are not typos.
 *
 * Allowed slips grow with the word: none for one or two letters ("no", "x" and "on" are as likely
 * to be right as anything near them), one up to four letters, two from five. A swap of two
 * neighbouring letters counts as one slip, because on a phone keyboard it is the commonest one.
 * Among candidates as close as each other, the more popular title wins.
 */
internal class TitleSpeller(entries: List<LocalCatalogEntry>) {
    private class Word(val text: String) {
        /** How much this word is worth as a correction: the popularity of the titles using it. */
        var weight = 0L
        /** The same, counting only titles that are not adult, for when those are hidden. */
        var safeWeight = 0L
    }

    private val vocabulary = HashMap<String, Word>()
    private val wordsByLength: Array<List<Word>>
    private val titleWords: Array<Array<String>>
    private val ids: IntArray
    private val titles: Array<String>
    private val popularity: IntArray
    private val adult: BooleanArray

    init {
        titleWords = Array(entries.size) { index ->
            val entry = entries[index]
            val words = searchWords(entry.title)
            val worth = (entry.popularity ?: 0).toLong() + 1
            // Interned through the vocabulary so 18,000 titles share one copy of each word.
            Array(words.size) { at ->
                val word = vocabulary.getOrPut(words[at]) { Word(words[at]) }
                // Once per title, however often the title repeats it ("Hunter x Hunter").
                if (words.indexOf(words[at]) == at) {
                    word.weight += worth
                    if (!entry.isAdult) word.safeWeight += worth
                }
                word.text
            }
        }
        ids = IntArray(entries.size) { entries[it].id }
        titles = Array(entries.size) { entries[it].title }
        popularity = IntArray(entries.size) { entries[it].popularity ?: 0 }
        adult = BooleanArray(entries.size) { entries[it].isAdult }
        val longest = vocabulary.keys.maxOfOrNull { it.length } ?: 0
        val buckets = Array(longest + 1) { ArrayList<Word>() }
        vocabulary.values.forEach { buckets[it.text.length].add(it) }
        wordsByLength = Array(buckets.size) { buckets[it] }
    }

    /**
     * The search [query] was most likely meant to be, or null when it is spelled like something the
     * snapshot holds — or like nothing close enough to it to guess.
     */
    fun suggest(query: String, hideAdult: Boolean, limit: Int = 8): TitleSpelling? {
        val typed = searchWords(query)
        if (typed.isEmpty() || typed.size > MAX_QUERY_WORDS) return null
        val candidates = typed.map { candidatesFor(it, hideAdult) }
        return fromTitles(typed, candidates, hideAdult, limit) ?: fromWords(typed, candidates, hideAdult)
    }

    /** The phrase as some title actually spells it; see the class note. */
    private fun fromTitles(
        typed: List<String>,
        candidates: List<Map<String, Int>>,
        hideAdult: Boolean,
        limit: Int,
    ): TitleSpelling? {
        val last = typed.last()
        var bestCost = Int.MAX_VALUE
        var bestText: String? = null
        var bestPopularity = -1
        val matched = ArrayList<Int>()
        for (title in titleWords.indices) {
            if (hideAdult && adult[title]) continue
            val words = titleWords[title]
            if (words.size < typed.size) continue
            for (start in 0..words.size - typed.size) {
                var cost = 0
                for (at in typed.indices) {
                    val word = words[start + at]
                    val slips = if (at == typed.lastIndex && word.startsWith(last)) 0 else candidates[at][word]
                    if (slips == null) {
                        cost = -1
                        break
                    }
                    cost += slips
                }
                if (cost < 0 || cost > bestCost) continue
                if (cost < bestCost) {
                    bestCost = cost
                    bestText = null
                    bestPopularity = -1
                    matched.clear()
                }
                if (matched.lastOrNull() != title) matched.add(title)
                if (popularity[title] > bestPopularity) {
                    bestPopularity = popularity[title]
                    // A last word that only starts a title word is kept as typed: the viewer may
                    // not have meant the word this title happens to finish it with.
                    bestText = typed.indices.joinToString(" ") { at ->
                        val word = words[start + at]
                        if (at == typed.lastIndex && word.startsWith(last)) typed[at] else word
                    }
                }
            }
        }
        // Spelled the way some title spells it: nothing to correct.
        if (bestCost == 0) return null
        val text = bestText ?: return null
        val offered = matched.sortedByDescending { popularity[it] }.take(limit)
            .map { TitleSuggestion(id = ids[it], title = titles[it]) }
        return TitleSpelling(text, offered)
    }

    /**
     * Each unknown word on its own, for a phrase no title holds in that order: "naurto shippuden
     * movie" is three right ideas, one misspelled, that no single title strings together.
     */
    private fun fromWords(
        typed: List<String>,
        candidates: List<Map<String, Int>>,
        hideAdult: Boolean,
    ): TitleSpelling? {
        val corrected = typed.mapIndexed { at, word ->
            if (isKnown(word, hideAdult)) return@mapIndexed word
            if (at == typed.lastIndex && startsAnyWord(word, hideAdult)) return@mapIndexed word
            candidates[at].entries
                .minWithOrNull(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { worth(vocabulary.getValue(it.key), hideAdult) }
                        // Never the map's iteration order, which the desktop's port cannot share.
                        .thenBy { it.key },
                )
                ?.key ?: word
        }
        if (corrected == typed) return null
        return TitleSpelling(corrected.joinToString(" "), emptyList())
    }

    /** The snapshot's words within reach of [word], each with how many slips away it is. */
    private fun candidatesFor(word: String, hideAdult: Boolean): Map<String, Int> {
        val found = HashMap<String, Int>()
        if (isKnown(word, hideAdult)) found[word] = 0
        val allowed = allowedSlips(word)
        if (allowed == 0) return found
        val shortest = (word.length - allowed).coerceAtLeast(1)
        val longest = minOf(word.length + allowed, wordsByLength.lastIndex)
        for (length in shortest..longest) {
            for (candidate in wordsByLength[length]) {
                if (worth(candidate, hideAdult) == 0L || candidate.text == word) continue
                // Numbers are names here — "86", "2nd" — and one digit off is a different show.
                if (candidate.text.any(Char::isDigit)) continue
                val slips = boundedEditDistance(word, candidate.text, allowed)
                if (slips <= allowed) found[candidate.text] = slips
            }
        }
        return found
    }

    private fun isKnown(word: String, hideAdult: Boolean): Boolean =
        vocabulary[word]?.let { worth(it, hideAdult) > 0 } == true

    private fun startsAnyWord(prefix: String, hideAdult: Boolean): Boolean =
        vocabulary.values.any { it.text.startsWith(prefix) && worth(it, hideAdult) > 0 }

    private fun worth(word: Word, hideAdult: Boolean): Long = if (hideAdult) word.safeWeight else word.weight

    private fun allowedSlips(word: String): Int = when {
        word.any(Char::isDigit) -> 0
        word.length <= 2 -> 0
        word.length <= 4 -> 1
        else -> 2
    }

    private companion object {
        /** Past this, the query is a sentence rather than a title, and guessing at it is noise. */
        const val MAX_QUERY_WORDS = 8
    }
}

/**
 * A title or a query as the words a search compares: lowercased, split on anything that is not a
 * letter or digit, apostrophes dropped first so "Journey's" and "journeys" are the same word.
 *
 * Walked by hand rather than split on a Regex: this runs over every title in the snapshot, and
 * Android's regex is ICU, which costs a native copy of the input per call — see the TV catalogue
 * parse, which was 30-60x slower on a TV box for exactly that.
 */
internal fun searchWords(text: String): List<String> {
    val words = ArrayList<String>(4)
    val word = StringBuilder()
    for (char in text) {
        when {
            char.isLetterOrDigit() -> word.append(char.lowercaseChar())
            char == '\'' || char == '’' -> Unit
            word.isNotEmpty() -> {
                words.add(word.toString())
                word.setLength(0)
            }
        }
    }
    if (word.isNotEmpty()) words.add(word.toString())
    return words
}

/**
 * Edits between [a] and [b] — insertions, deletions, substitutions and swaps of two neighbouring
 * letters — or anything above [limit] once it is certain to exceed it, which is most of the
 * vocabulary for any one word and the reason this stops early.
 */
internal fun boundedEditDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var beforePrevious = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowBest = i
        for (j in 1..b.length) {
            val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitution)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                value = minOf(value, beforePrevious[j - 2] + 1)
            }
            current[j] = value
            if (value < rowBest) rowBest = value
        }
        if (rowBest > limit) return limit + 1
        val spare = beforePrevious
        beforePrevious = previous
        previous = current
        current = spare
    }
    return previous[b.length]
}
