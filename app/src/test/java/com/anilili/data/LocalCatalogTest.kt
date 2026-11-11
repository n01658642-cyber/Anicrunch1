package com.anilili.data

import com.anilili.data.model.DiscoverFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue snapshot bundled with the app is what stands between a viewer and a blank screen
 * when AniList is unreachable, so the rows and the ranking have to hold up on their own.
 */
class LocalCatalogTest {
    private fun entry(
        id: Int,
        title: String,
        year: Int? = 2020,
        format: String? = "TV",
        score: Int? = 70,
        popularity: Int? = 100,
    ) = LocalCatalogEntry(
        id = id,
        title = title,
        year = year,
        format = format,
        score = score,
        popularity = popularity,
    )

    private val catalogue = listOf(
        entry(1, "Cowboy Bebop", year = 1998, score = 86, popularity = 400),
        entry(2, "Naruto", year = 2002, score = 79, popularity = 900),
        entry(3, "Naruto Shippuden", year = 2007, score = 82, popularity = 800),
        entry(4, "Spirited Away", year = 2001, format = "MOVIE", score = 87, popularity = 600),
        entry(5, "Your Name", year = 2016, format = "MOVIE", score = 85, popularity = 700),
        entry(6, "Obscure Masterpiece", year = 2019, score = 99, popularity = 2),
    )

    @Test
    fun `an empty snapshot produces no rows rather than empty-looking ones`() {
        val rows = homeCollectionsFrom(emptyList())
        assertTrue(rows.popular.isEmpty())
        assertTrue(rows.spotlight.isEmpty())
    }

    @Test
    fun `popular is ordered by popularity`() {
        val popular = homeCollectionsFrom(catalogue).popular.map { it.title.preferred }
        assertEquals(listOf("Naruto", "Naruto Shippuden", "Your Name"), popular.take(3))
    }

    @Test
    fun `the movies row contains only movies`() {
        val movies = homeCollectionsFrom(catalogue).movies.map { it.title.preferred }
        assertEquals(listOf("Your Name", "Spirited Away"), movies)
    }

    @Test
    fun `a near-perfect score from almost no votes does not lead top rated`() {
        // "Obscure Masterpiece" scores 99 off a popularity of 2. It still belongs in the row, just
        // not ahead of titles thousands of people actually rated.
        val topRated = homeCollectionsFrom(catalogue).topRated.map { it.title.preferred }
        assertEquals("Obscure Masterpiece", topRated.first())
        assertTrue("Spirited Away" in topRated)
    }

    @Test
    fun `newest leads with the most recent year the snapshot knows`() {
        // A fixed snapshot cannot mean "airing now", so the row is the newest years it has:
        // 2019 ahead of 2016, regardless of how popular either is.
        val newest = homeCollectionsFrom(catalogue).newest.map { it.title.preferred }
        assertEquals(listOf("Obscure Masterpiece", "Your Name"), newest.take(2))
    }

    @Test
    fun `an exact title wins over a more popular title that merely contains it`() {
        // "Naruto Shippuden" is the substring match; "Naruto" is the title. Ranking by popularity
        // alone is what would put the wrong one first.
        val hits = searchIn(catalogue, "naruto", page = 1).items.map { it.title.preferred }
        assertEquals(listOf("Naruto", "Naruto Shippuden"), hits)
    }

    @Test
    fun `search is case and whitespace insensitive`() {
        assertEquals(1, searchIn(catalogue, "  COWBOY bebop ", page = 1).items.size)
    }

    @Test
    fun `a query matching nothing returns nothing rather than the whole catalogue`() {
        val page = searchIn(catalogue, "nonexistent title", page = 1)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `an empty query is not treated as matching everything`() {
        assertTrue(searchIn(catalogue, "   ", page = 1).items.isEmpty())
    }

    @Test
    fun `paging reports more results and then stops`() {
        val many = (1..75).map { entry(it, "Show $it", popularity = 100 - it) }
        val first = searchIn(many, "show", page = 1, perPage = 30)
        assertEquals(30, first.items.size)
        assertTrue(first.hasNextPage)

        val last = searchIn(many, "show", page = 3, perPage = 30)
        assertEquals(15, last.items.size)
        assertFalse(last.hasNextPage)
    }

    @Test
    fun `a page past the end is empty instead of throwing`() {
        val page = searchIn(catalogue, "naruto", page = 9, perPage = 30)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `a snapshot row carries through the fields the cards actually draw`() {
        val media = LocalCatalogEntry(
            id = 21,
            title = "One Piece",
            poster = "https://example.invalid/op.png",
            poster_color = "#e4a15d",
            year = 1999,
            status = "RELEASING",
            format = "TV",
            episodes = 1000,
            score = 88,
            popularity = 500,
            genres = listOf("Action", "Adventure"),
        ).toMedia()

        assertEquals(21, media.id)
        assertEquals("One Piece", media.title.preferred)
        assertEquals("https://example.invalid/op.png", media.coverImage.best)
        assertEquals(1999, media.seasonYear)
        assertEquals(1000, media.episodes)
        assertEquals(88, media.averageScore)
        assertEquals(listOf("Action", "Adventure"), media.genres)
    }
}

/**
 * Browse offline. The snapshot answers what it has fields for; what it doesn't have it must decline
 * rather than quietly return results that contradict the filters on screen.
 */
class LocalCatalogDiscoverTest {
    private fun entry(
        id: Int,
        title: String,
        year: Int? = 2020,
        format: String? = "TV",
        score: Int? = 70,
        popularity: Int? = 100,
        genres: List<String> = listOf("Action"),
        status: String? = "FINISHED",
    ) = LocalCatalogEntry(
        id = id,
        title = title,
        year = year,
        format = format,
        score = score,
        popularity = popularity,
        genres = genres,
        status = status,
    )

    private val catalogue = listOf(
        entry(1, "Action Show", genres = listOf("Action"), score = 70, popularity = 500),
        entry(2, "Comedy Show", genres = listOf("Comedy"), score = 90, popularity = 100),
        entry(3, "Action Movie", format = "MOVIE", genres = listOf("Action"), score = 80, popularity = 300),
        entry(4, "Old Action", year = 1999, genres = listOf("Action"), score = 60, popularity = 900),
    )

    @Test
    fun `a genre filter keeps only that genre`() {
        val page = discoverIn(catalogue, DiscoverFilters(genres = setOf("Comedy")), page = 1)
        assertEquals(listOf("Comedy Show"), page.items.map { it.title.preferred })
    }

    @Test
    fun `format and year narrow the results`() {
        assertEquals(1, discoverIn(catalogue, DiscoverFilters(format = "MOVIE"), page = 1).items.size)
        assertEquals(1, discoverIn(catalogue, DiscoverFilters(year = 1999), page = 1).items.size)
    }

    @Test
    fun `a minimum score excludes what falls under it`() {
        val page = discoverIn(catalogue, DiscoverFilters(minimumScore = 80), page = 1)
        assertEquals(setOf("Comedy Show", "Action Movie"), page.items.map { it.title.preferred }.toSet())
    }

    @Test
    fun `sorting by score puts the best first, by popularity otherwise`() {
        val byScore = discoverIn(catalogue, DiscoverFilters(sort = "SCORE_DESC"), page = 1)
        assertEquals("Comedy Show", byScore.items.first().title.preferred)

        val byTrending = discoverIn(catalogue, DiscoverFilters(sort = "TRENDING_DESC"), page = 1)
        assertEquals("Old Action", byTrending.items.first().title.preferred)
    }

    @Test
    fun `a filter the snapshot cannot answer returns nothing rather than the wrong thing`() {
        // No tags, studios or airing season in the snapshot. Ignoring them would return an
        // unfiltered list under filter chips claiming otherwise.
        assertTrue(discoverIn(catalogue, DiscoverFilters(tags = setOf("Isekai")), page = 1).items.isEmpty())
        assertTrue(discoverIn(catalogue, DiscoverFilters(studioId = 1), page = 1).items.isEmpty())
        assertTrue(discoverIn(catalogue, DiscoverFilters(season = "SUMMER"), page = 1).items.isEmpty())
    }

    @Test
    fun `the dub filter is answered offline from the index on the device`() {
        // Unlike tags or studios, nothing about this one needs AniList: the ids are already here.
        val page = discoverIn(
            catalogue,
            DiscoverFilters(dubbedOnly = true),
            page = 1,
            dubbed = { it == 2 || it == 3 },
        )
        assertEquals(setOf("Comedy Show", "Action Movie"), page.items.map { it.title.preferred }.toSet())
    }

    @Test
    fun `no filters at all browses the whole snapshot by popularity`() {
        val page = discoverIn(catalogue, DiscoverFilters(), page = 1)
        assertEquals(catalogue.size, page.items.size)
        assertEquals("Old Action", page.items.first().title.preferred)
    }
}

/**
 * The autocomplete under the search box.
 *
 * It exists because AniList cannot answer a half-typed word: `search: "narut"` returns an empty
 * page and `"nar"` returns *Omoide no Marnie*, so until the word is finished the viewer sees
 * nothing. Everything here is about what the snapshot can offer in the meantime.
 */
class LocalCatalogSuggestionTest {
    private fun entry(id: Int, title: String, popularity: Int) =
        LocalCatalogEntry(id = id, title = title, popularity = popularity)

    private val catalogue = listOf(
        entry(1, "Naruto", popularity = 900),
        entry(2, "Naruto: Shippuden", popularity = 800),
        entry(3, "Naruto the Movie: Ninja Clash in the Land of Snow", popularity = 200),
        entry(4, "NARUTO: Narutimate Hero 3", popularity = 5),
        entry(5, "Boruto: Naruto Next Generations", popularity = 300),
        entry(6, "Cowboy Bebop", popularity = 400),
        entry(7, "My Hero Academia", popularity = 700),
        entry(8, "Superheroine Squad", popularity = 999),
        entry(9, "Shippuu! Iron Leaguer", popularity = 3),
    )

    @Test
    fun `a half-typed word reaches the title AniList would not find at all`() {
        val titles = suggestTitlesIn(catalogue, "narut").map { it.title }
        assertEquals("Naruto", titles.first())
        assertTrue("Naruto: Shippuden" in titles)
    }

    @Test
    fun `the whole title outranks the titles that merely start with it`() {
        val titles = suggestTitlesIn(catalogue, "naruto").map { it.title }
        assertEquals(listOf("Naruto", "Naruto: Shippuden"), titles.take(2))
    }

    @Test
    fun `a word in the middle of a title still counts`() {
        // "Naruto" is the second word of "Boruto: Naruto Next Generations" and nothing else here
        // holds it anywhere but the front, so a title-prefix rule alone would miss the sequel.
        assertTrue("Boruto: Naruto Next Generations" in suggestTitlesIn(catalogue, "naruto").map { it.title })
    }

    @Test
    fun `a whole word beats the same letters buried in one, however popular`() {
        // "Hero" is a word in two of these and part of "Superheroine" in the third. The buried
        // match is the most popular row in the snapshot, which is exactly the case that ranking by
        // popularity gets wrong — so it sinks below every title that has the word itself.
        val titles = suggestTitlesIn(catalogue, "hero").map { it.title }
        assertEquals("My Hero Academia", titles.first())
        assertEquals("Superheroine Squad", titles.last())
    }

    @Test
    fun `a word after punctuation is still a word`() {
        // "Shippuden" follows a colon and a space, which is the shape most sequels take.
        assertTrue("Naruto: Shippuden" in suggestTitlesIn(catalogue, "shippu").map { it.title })
    }

    @Test
    fun `where the word sits in the title does not outweigh how many people watch it`() {
        // Real snapshot rows. Ranking a title that *begins* with the term above one that merely
        // contains the word put "Shippuu! Iron Leaguer" — 1993, barely watched — above Naruto:
        // Shippuden, and did the same for "slayer" (Slayers over Demon Slayer) and "note"
        // ("Notes on Monstropedia" over Death Note).
        assertEquals("Naruto: Shippuden", suggestTitlesIn(catalogue, "shippu").first().title)
    }

    @Test
    fun `matching ignores case both ways`() {
        assertEquals("Naruto", suggestTitlesIn(catalogue, "NARU").first().title)
        assertEquals("NARUTO: Narutimate Hero 3", suggestTitlesIn(catalogue, "narutimate").first().title)
    }

    @Test
    fun `one letter offers nothing, because it would offer everything`() {
        assertTrue(suggestTitlesIn(catalogue, "n").isEmpty())
        assertTrue(suggestTitlesIn(catalogue, " ").isEmpty())
        assertTrue(suggestTitlesIn(catalogue, "").isEmpty())
    }

    @Test
    fun `surrounding whitespace does not change the answer`() {
        assertEquals(
            suggestTitlesIn(catalogue, "naruto").map { it.id },
            suggestTitlesIn(catalogue, "  naruto  ").map { it.id },
        )
    }

    @Test
    fun `a query matching nothing offers nothing`() {
        assertTrue(suggestTitlesIn(catalogue, "zzzz").isEmpty())
    }

    @Test
    fun `the list is capped so the dropdown cannot take the screen`() {
        val many = (1..50).map { entry(it, "Show $it", popularity = it) }
        assertEquals(8, suggestTitlesIn(many, "show").size)
        assertEquals(3, suggestTitlesIn(many, "show", limit = 3).size)
    }

    @Test
    fun `popularity breaks a tie so a franchise leads its own spin-offs`() {
        // All three start with "naruto the"/"naruto:" — same rank, so the order is popularity.
        val titles = suggestTitlesIn(catalogue, "naruto").map { it.title }
        assertTrue(titles.indexOf("Naruto: Shippuden") < titles.indexOf("Naruto the Movie: Ninja Clash in the Land of Snow"))
    }

    @Test
    fun `an empty snapshot offers nothing rather than throwing`() {
        assertTrue(suggestTitlesIn(emptyList(), "naruto").isEmpty())
    }

    @Test
    fun `a Hentai-genre row reads as adult so the adult filter can drop it`() {
        val adult = LocalCatalogEntry(id = 7, title = "Masquerade", genres = listOf("Hentai", "Romance"))
        val safe = LocalCatalogEntry(id = 8, title = "Mushishi", genres = listOf("Mystery"))
        assertTrue(adult.isAdult)
        assertTrue(adult.toMedia().isAdult)
        assertFalse(safe.isAdult)
        assertFalse(safe.toMedia().isAdult)
    }
}
