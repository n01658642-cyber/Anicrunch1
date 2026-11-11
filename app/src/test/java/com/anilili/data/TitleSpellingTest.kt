package com.anilili.data

import com.anilili.parity.ParityCorpus
import com.anilili.parity.ParityCorpus.cases
import com.anilili.parity.ParityCorpus.describe
import com.anilili.parity.ParityCorpus.string
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Did you mean" for title searches.
 *
 * AniList returns an empty page for any misspelled word (`naurto`, `atack on titan`, `freiren`,
 * probed 2026-10-06), so these are the only thing between a slipped key and "Nothing matched".
 */
class TitleSpellingTest {
    /** The cases the desktop app answers too (`parity/spelling.json`). */
    @Test
    fun `corrects spelling as the desktop app does`() {
        val corpus = ParityCorpus.load("spelling.json")
        val speller = TitleSpeller(
            corpus.cases("titles").map { title ->
                LocalCatalogEntry(
                    id = title["id"]!!.jsonPrimitive.int,
                    title = title.string("title"),
                    popularity = title["popularity"]!!.jsonPrimitive.int,
                    genres = if (title["adult"]!!.jsonPrimitive.boolean) listOf("Hentai") else emptyList(),
                )
            },
        )
        val wrong = corpus.cases("cases").mapNotNull { case ->
            val expect = case["expect"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val answer = speller.suggest(case.string("query"), case["hideAdult"]!!.jsonPrimitive.boolean)
            val offers = case["offers"]?.jsonArray?.map { it.jsonPrimitive.int }
            when {
                answer?.text != expect -> "${case.describe("query", "hideAdult")}: ${answer?.text} (wanted $expect)"
                offers != null && answer?.titles?.map { it.id }?.take(offers.size) != offers ->
                    "${case.describe("query")}: offered ${answer?.titles?.map { it.id }} (wanted $offers first)"
                else -> null
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `edit distance counts a swap of neighbouring letters as one`() {
        assertEquals(1, boundedEditDistance("naurto", "nauroto", 2))
        assertEquals(1, boundedEditDistance("freiren", "frieren", 2))
        assertEquals(3, boundedEditDistance("abc", "xyz123", 2))
    }

    /**
     * The real snapshot, with the misspellings that motivated this. Skipped quietly where the asset
     * is not on disk, so the suite still runs from a checkout without the dataset.
     */
    @Test
    fun `the shipped snapshot corrects common misspellings of popular titles`() {
        val asset = File("src/main/assets/index.json")
        if (!asset.exists()) return
        val json = Json { ignoreUnknownKeys = true }
        val entries = json.decodeFromString(ListSerializer(LocalCatalogEntry.serializer()), asset.readText())
        val real = TitleSpeller(entries)
        val expected = mapOf(
            "naurto" to "naruto",
            "atack on titan" to "attack on titan",
            "jujutsu kaisn" to "jujutsu kaisen",
            "demon slayr" to "demon slayer",
            "one pice" to "one piece",
            "freiren" to "frieren",
            "deth note" to "death note",
            "one peace" to "one piece",
            "fullmetal alchemst" to "fullmetal alchemist",
            "cowbow bebop" to "cowboy bebop",
            "swrod art online" to "sword art online",
            "hunter x huntr" to "hunter x hunter",
            "chainsaw mna" to "chainsaw man",
            "spy x famliy" to "spy x family",
            "blue lcok" to "blue lock",
            "vinland saag" to "vinland saga",
            "bleahc" to "bleach",
            "tokyo ghuol" to "tokyo ghoul",
        )
        val wrong = expected.mapNotNull { (typed, meant) ->
            val got = real.suggest(typed, hideAdult = true)?.text
            if (got == meant) null else "$typed -> $got (wanted $meant)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        for (spelled in listOf("naruto", "one piece", "attack on titan", "frieren", "steins gate", "bleach")) {
            assertNull(spelled, real.suggest(spelled, hideAdult = true))
        }
    }
}
