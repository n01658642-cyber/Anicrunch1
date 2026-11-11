package com.anilili.data.remote

import com.anilili.parity.ParityCorpus
import com.anilili.parity.ParityCorpus.cases
import com.anilili.parity.ParityCorpus.describe
import com.anilili.parity.ParityCorpus.double
import com.anilili.parity.ParityCorpus.nullableInt
import com.anilili.parity.ParityCorpus.string
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parity/titles.json`: how a provider picks a site's entry for an AniList title, as both apps must
 * decide it. Each test runs every case in its section and reports all the disagreements at once,
 * so one changed rule shows its whole reach instead of the first case it breaks.
 */
class TitleParityTest {
    private val corpus = ParityCorpus.load("titles.json")

    @Test
    fun `season numbers match the corpus`() = checkAll("seasonNumber") { case ->
        val expected = case.nullableInt("expected")
        val actual = NativeProviderParsers.seasonNumber(case.string("title"))
        if (actual == expected) null else "${case.describe("title")}: expected $expected, got $actual"
    }

    @Test
    fun `base series titles match the corpus`() = checkAll("baseSeriesTitle") { case ->
        val expected = case.string("expected")
        val actual = NativeProviderParsers.baseSeriesTitle(case.string("title"))
        if (actual == expected) null else "${case.describe("title")}: expected \"$expected\", got \"$actual\""
    }

    @Test
    fun `title scores match the corpus`() = checkAll("titleScore") { case ->
        val score = NativeProviderParsers.titleScore(case.string("query"), case.string("candidate"))
        val label = case.describe("query", "candidate")
        when {
            "exactly" in case -> {
                val expected = case.double("exactly")
                if (abs(score - expected) <= TOLERANCE) null else "$label: expected $expected, got $score"
            }
            "above" in case -> {
                val floor = case.double("above")
                if (score > floor) null else "$label: expected above $floor, got $score"
            }
            else -> "$label: the case says neither \"exactly\" nor \"above\""
        }
    }

    @Test
    fun `title selection prefers what the corpus prefers`() = checkAll("selectionOrder") { case ->
        val query = case.string("query")
        val better = NativeProviderParsers.titleSelectionScore(query, case.string("better"))
        val worse = NativeProviderParsers.titleSelectionScore(query, case.string("worse"))
        if (better > worse) {
            null
        } else {
            "${case.describe("query", "better", "worse")}: better scored $better, worse scored $worse"
        }
    }

    /** Runs [check] on every case in [section]; it returns null for a pass or why the case failed. */
    private fun checkAll(section: String, check: (JsonObject) -> String?) {
        val failures = corpus.cases(section).mapNotNull(check)
        assertTrue(
            "${failures.size} $section case(s) disagree with the parity corpus:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private companion object {
        /** A JSON `1.0` and a computed one need not be the same bits. */
        const val TOLERANCE = 1e-9
    }
}
