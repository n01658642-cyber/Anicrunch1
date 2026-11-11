package com.anilili.data.remote

/**
 * Recovers MKissa's rotating build ID and four public mask seeds from its obfuscated JS bundle.
 * Adapted under Apache-2.0 from yuzono/anime-extensions revision
 * d4d64d315f127b9cf2ae60e2f2d53754ce722c02:
 * https://github.com/yuzono/anime-extensions/tree/d4d64d315f127b9cf2ae60e2f2d53754ce722c02/src/en/mkissa
 */
internal object AllAnimeBundleParser {
    data class BuildInfo(
        val buildId: String,
        val seeds: List<String>,
        val scheme: AllAnimeMkissaCrypto.Scheme = AllAnimeMkissaCrypto.Scheme.LEGACY,
    )

    /**
     * The build id and mask seeds, or null if this chunk is not the one or no longer has the shape.
     *
     * Both halves come out of the *same* rotation of the same string table, which is why they are
     * recovered together rather than by two independent passes. MKissa's bundle stopped writing the
     * build id as a literal (2026-09-13): `AllAnimeKeyManager` failed with "Unable to obtain MKissa
     * build material" because [BUILD_ID_REGEX] matched nothing, while the seeds machinery beside it
     * was still intact. The id now sits in the obfuscated table like everything else, reached
     * through a getter — `function uy(){return sd||""}` with `const sd=fr(945)` — so it is resolved
     * with the same decoder the seeds use, at the rotation the seeds proved correct.
     */
    fun parse(js: String): BuildInfo? {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { match ->
            match.groupValues[1] to Base(match.groupValues[4], fold(match.groupValues[3]))
        }
        val aliases = buildMap {
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { match ->
                val callee = match.groupValues[4]
                if (callee !in bases) return@forEach
                val numericDelta = match.groupValues[6]
                val objectDelta = match.groupValues[7]
                put(
                    match.groupValues[1],
                    Alias(
                        base = callee,
                        argumentIndex = if (match.groupValues[5] == match.groupValues[2]) 0 else 1,
                        delta = when {
                            numericDelta.isNotEmpty() -> fold(numericDelta)
                            // `-{_0x453117:564}._0x453117` is a 564 written the long way round.
                            objectDelta.isNotEmpty() -> -(objectDelta.toIntOrNull() ?: return@forEach)
                            else -> 0
                        },
                    ),
                )
            }
        }

        val literalBuildId = BUILD_ID_REGEX.find(js)?.groupValues?.get(1)
        val buildIdCall = buildIdCall(js)
        // Present from build 175. A chunk that has one and cannot be read must fail: signing with
        // the legacy constants in its place is exactly the silently-wrong token this replaces.
        val schemeSource = schemeSource(js)

        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val elements = splitTopLevel(match.groupValues[1])
            if (elements.size != AllAnimeMkissaCrypto.SEED_COUNT) continue
            val table = CALL_REGEX.find(elements.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue
            val statementBuildIdCall = statementBuildIdCall(js, match.range.first)
            val candidates = table.indices.mapNotNull { rotation ->
                val seeds = seedsAt(elements, rotation, tables, bases, aliases) ?: return@mapNotNull null
                val buildId = literalBuildId
                    ?: buildIdCall?.let { resolve(it, rotation, tables, bases, aliases) }
                    ?: statementBuildIdCall?.let { resolve(it, rotation, tables, bases, aliases) }
                    ?: return@mapNotNull null
                if (buildId.isBlank() || !buildId.all(Char::isDigit)) return@mapNotNull null
                val scheme = if (schemeSource == null) {
                    AllAnimeMkissaCrypto.Scheme.LEGACY
                } else {
                    schemeAt(schemeSource, rotation, tables, bases, aliases) ?: return@mapNotNull null
                }
                BuildInfo(buildId, seeds, scheme)
            }
            // More than one rotation producing valid seeds means the table was misread; deriving a
            // key from a guess is worse than failing, because the request looks authentic and is not.
            candidates.singleOrNull()?.let { return it }
        }
        return null
    }

    private data class Base(val table: String, val offset: Int)
    private data class Alias(val base: String, val argumentIndex: Int, val delta: Int)

    /**
     * The table lookup holding the build id, as `fr(945)`, or null if the bundle still inlines it.
     *
     * Found through the getter rather than by looking for a likely-looking constant: the bundle
     * names nothing, so `function uy(){return sd||""}` is the only thing that says which of a
     * thousand table reads is the build id.
     */
    private fun buildIdCall(js: String): String? {
        val variable = BUILD_ID_GETTER_REGEX.find(js)?.groupValues?.get(1) ?: return null
        val declaration = Regex("""(?<![A-Za-z0-9_${'$'}])${Regex.escape(variable)}\s*=\s*($CALL_PATTERN)""")
        return declaration.find(js)?.groupValues?.get(1)
    }

    /**
     * The table lookup for the build id when no getter names it: the first declaration of the
     * `const` statement the seed array belongs to.
     *
     * Build 175 (2026-09-22) replaced the getter `function uy(){return sd||""}` with one whose
     * `||` hides behind a string-table property name, so [buildIdCall] finds nothing — while the
     * declarations moved together: `const fd=Dt(-274,-322),Zk=Number(…),eT=Number(…),hm=[…]`,
     * build id first, seeds last. Anivexa recovers it the same way (`previousArrayAndBuildDeclaration`,
     * 2026-09-21). The rotation the seeds prove, plus the all-digits check in [parse], is what
     * stops this from accepting an unrelated first declaration.
     */
    private fun statementBuildIdCall(js: String, arrayAt: Int): String? {
        val statementAt = js.lastIndexOf("const ", arrayAt).takeIf { it >= 0 } ?: return null
        if (js.substring(statementAt, arrayAt).contains(';')) return null
        return STATEMENT_HEAD_REGEX.find(js, statementAt)
            ?.takeIf { it.range.first == statementAt }
            ?.groupValues?.get(1)
    }

    /**
     * The body of the `{…saltMul:…}` object literal, or null when the bundle has none.
     *
     * Found by brace depth rather than a pattern: its string values are table lookups with their own
     * parentheses and commas, and a regex that stops at the first `}` or `,` reads half of them.
     */
    private fun schemeSource(js: String): String? {
        val key = SCHEME_KEY_REGEX.find(js) ?: return null
        val open = js.lastIndexOf('{', key.range.first).takeIf { it >= 0 } ?: return null
        var depth = 0
        var quote: Char? = null
        var index = open
        while (index < js.length) {
            val character = js[index]
            when {
                quote != null -> when (character) {
                    '\\' -> index++
                    quote -> quote = null
                }
                character == '"' || character == '\'' -> quote = character
                character == '{' || character == '[' || character == '(' -> depth++
                character == '}' || character == ']' || character == ')' -> {
                    depth--
                    if (depth == 0) return js.substring(open + 1, index)
                }
            }
            index++
        }
        return null
    }

    /** The config object read at [rotation], or null if any field fails to resolve or validate. */
    private fun schemeAt(
        source: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): AllAnimeMkissaCrypto.Scheme? {
        val fields = splitTopLevel(source).associate { entry ->
            entry.substringBefore(':').trim() to entry.substringAfter(':', "").trim()
        }
        fun number(name: String): Int? = fields[name]?.takeIf { NUMBER_REGEX.matches(it) }?.let(::fold)
        fun text(name: String): String? = fields[name]?.let { stringValue(it, rotation, tables, bases, aliases) }
        val parts = fields["parts"]
            ?.takeIf { it.startsWith('[') && it.endsWith(']') }
            ?.let { list -> splitTopLevel(list.substring(1, list.length - 1)) }
            ?.map { stringValue(it, rotation, tables, bases, aliases) ?: return null }
            ?.takeIf { it.isNotEmpty() && AllAnimeMkissaCrypto.Scheme.FIELDS.containsAll(it) }
            ?: return null
        return AllAnimeMkissaCrypto.Scheme(
            saltMul = number("saltMul") ?: return null,
            saltAdd = number("saltAdd") ?: return null,
            fragMul = number("fragMul") ?: return null,
            fragAdd = number("fragAdd") ?: return null,
            bootPrefix = text("bootPrefix")?.takeIf(String::isNotEmpty) ?: return null,
            join = text("join") ?: return null,
            parts = parts,
            omitEmptyLane = when (fields["omitEmptyLane"]) {
                null, "!1", "false" -> false
                "!0", "true" -> true
                else -> return null
            },
        )
    }

    /** A `+`-joined run of table lookups and quoted literals, as `Dt(-292,-365)+Ut(1127)+":"`. */
    private fun stringValue(
        expression: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val value = StringBuilder()
        for (term in splitTopLevel(expression, separator = '+')) {
            value.append(
                when {
                    term.length >= 2 && (term.first() == '"' || term.first() == '\'') && term.last() == term.first() ->
                        term.substring(1, term.length - 1)
                    CALL_REGEX.matches(term) -> resolve(term, rotation, tables, bases, aliases) ?: return null
                    else -> return null
                },
            )
        }
        return value.toString()
    }

    /**
     * [body] cut at every top-level [separator], ignoring those inside brackets and quotes.
     *
     * A plain `split(',')` was enough while every table read took one argument. Build 175's reads
     * take two — `Dt(-460,-418)` — so splitting on the comma cut each seed in half and no seed
     * array ever had four elements.
     */
    private fun splitTopLevel(body: String, separator: Char = ','): List<String> {
        val pieces = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var index = 0
        while (index < body.length) {
            val character = body[index]
            when {
                quote != null -> when (character) {
                    '\\' -> index++
                    quote -> quote = null
                }
                character == '"' || character == '\'' -> quote = character
                character == '(' || character == '[' || character == '{' -> depth++
                character == ')' || character == ']' || character == '}' -> depth--
                character == separator && depth == 0 -> {
                    pieces += body.substring(start, index).trim()
                    start = index + 1
                }
            }
            index++
        }
        pieces += body.substring(start).trim()
        return pieces.filter(String::isNotEmpty)
    }

    private fun seedsAt(
        elements: List<String>,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): List<String>? {
        val seeds = elements.mapNotNull { element ->
            // A seed used to be two table reads and is now four; counting them here rather than in
            // the pattern means the next change of arity costs nothing.
            val parts = CALL_REGEX.findAll(element)
                .map { call -> resolve(call.value, rotation, tables, bases, aliases) }
                .toList()
            if (parts.isEmpty() || parts.any { it == null }) return@mapNotNull null
            parts.joinToString("").takeIf(SEED_REGEX::matches)
        }
        return seeds.takeIf { it.size == AllAnimeMkissaCrypto.SEED_COUNT }
    }

    private fun resolve(
        call: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = aliases[match.groupValues[1]] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table]?.takeIf(List<String>::isNotEmpty) ?: return null
        val arguments = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val argument = arguments.getOrNull(alias.argumentIndex) ?: return null
        val index = argument + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        TABLE_HEAD_REGEX.findAll(js).forEach { match ->
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    /** Refuses partial arrays so a bundle-format change cannot silently derive the wrong key. */
    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var index = open + 1
        while (index < js.length) {
            when (val character = js[index]) {
                ']' -> return items
                ',', ' ' -> index++
                '"', '\'' -> {
                    val value = StringBuilder()
                    index++
                    while (index < js.length && js[index] != character) {
                        if (js[index] == '\\') {
                            if (index + 1 >= js.length) return null
                            value.append(js[index + 1])
                            index += 2
                        } else {
                            value.append(js[index++])
                        }
                    }
                    if (index >= js.length) return null
                    index++
                    items += value.toString()
                }
                else -> return null
            }
        }
        return null
    }

    private fun fold(expression: String): Int {
        var total = 0
        TERM_REGEX.findAll(expression.replace(" ", "")).map(MatchResult::value).forEach { term ->
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }
            var value = 1
            body.split('*').forEach { factor -> value *= factor.toIntOrNull() ?: return 0 }
            total += sign * value
        }
        return total
    }

    /**
     * A minified JavaScript identifier, which `\w` is not.
     *
     * `\w` excludes `$`, and the bundle's own decoder is `function $r(e,t){return e=e-340,Yc()[e]}`
     * with `function fr(e,t){return $r(e-377)}` in front of it. Every pattern below that named an
     * identifier with `\w` therefore skipped the base decoder and dropped every alias pointing at
     * it, which is one half of why build material stopped resolving. Upstream's Anivexa hit the
     * same thing in the same week and fixed it the same way.
     */
    private const val ID = """[A-Za-z0-9_${'$'}]+"""

    private val BUILD_ID_REGEX = Regex("""!==\s*["']string["']\s*\?\s*["'](\d+)["']\s*:\s*["']["']""")
    /** `function uy(){return sd||""}` — names the variable holding the build id. */
    private val BUILD_ID_GETTER_REGEX = Regex("""function $ID\(\)\{return ($ID)\|\|""\}""")
    private val TABLE_HEAD_REGEX = Regex("""function ($ID)\(\)\s*\{\s*(?:const|let|var)\s+$ID\s*=\s*\[""")
    private val BASE_DECODER_REGEX =
        Regex("""function ($ID)\(($ID)(?:,$ID)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,($ID)\(\)\[\2\]\}""")
    // Group 6 is a plain numeric delta, group 7 the value inside an object-literal one.
    private val ALIAS_DECODER_REGEX = Regex(
        """function ($ID)\(($ID),($ID)\)\{return ($ID)\(($ID)""" +
            """(?:([-+][\d+*\s-]+)|-\{$ID:\s*(-?\d+)\s*\}\.$ID)?\)\}""",
    )
    private const val CALL_PATTERN = """($ID)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)
    /**
     * Four comma-separated elements, each one or more table reads joined by `+`.
     *
     * The repetition is bounded rather than `*` because this runs over a 1.1MB chunk, where an
     * unbounded nested quantifier is a backtracking trap rather than a nicety.
     */
    private const val SEED_ELEMENT = """$CALL_PATTERN(?:\+$CALL_PATTERN){0,7}"""
    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$SEED_ELEMENT,){3}$SEED_ELEMENT)]""")
    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")
    /** `const fd=Dt(-274,-322),` — the first declaration of a statement, when it is a table read. */
    private val STATEMENT_HEAD_REGEX = Regex("""const\s+$ID\s*=\s*($CALL_PATTERN)\s*,""")
    private val SCHEME_KEY_REGEX = Regex("""[{,]\s*saltMul\s*:""")
    private val NUMBER_REGEX = Regex("""-?\d+(?:\s*[-+*]\s*-?\d+)*""")
    private val TERM_REGEX = Regex("""[-+]*[^-+]+""")
}
