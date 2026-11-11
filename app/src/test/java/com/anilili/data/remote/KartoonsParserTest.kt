package com.anilili.data.remote

import com.anilili.data.remote.KartoonsParser.Season
import com.anilili.data.remote.KartoonsParser.Show
import com.anilili.data.remote.KartoonsParser.Wanted
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every show and season below is the live kartoons record as read on 2026-10-03 — titles, counts,
 * years and numbering — because the first version of this parser was tested against an assumed
 * shape (TMDB's per-season numbering) and passed while failing every Naruto episode after 52.
 */
class KartoonsParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // --- payloads -------------------------------------------------------------------------------

    @Test
    fun `search rows parse from the live shape`() {
        val body = """
            {"success":true,"data":[
                {"_id":"681865ce24c9061ac54aa3a2","title":"Naruto","category":"Anime",
                 "image":"https://image.tmdb.org/t/p/w440_and_h660_face/a.jpg","seasons":[]},
                {"_id":"681ef19518f5a104ba5f735e","title":"Naruto Shippūden","category":"Anime"}
            ]}
        """.trimIndent()
        val rows = KartoonsParser.parseSearch(json, body)
        assertEquals(2, rows.size)
        assertEquals("Naruto", rows[0].title)
        assertNull("search rows carry no year on the live API", rows[0].releaseYear)
        assertEquals("681ef19518f5a104ba5f735e", rows[1]._id)
    }

    @Test
    fun `show record parses with its seasons`() {
        val body = """
            {"success":true,"data":{
                "_id":"681865ce24c9061ac54aa3a2","title":"Naruto","startYear":2002,
                "seasons":[
                    {"_id":"684d45cc56d04df2fd104e22","seasonNumber":1,"title":"Season 1",
                     "releaseYear":2002,"showId":"681865ce24c9061ac54aa3a2","episodeCount":52},
                    {"_id":"684d45e056d04df2fd104e26","seasonNumber":2,"title":"Season 2",
                     "releaseYear":2003,"showId":"681865ce24c9061ac54aa3a2","episodeCount":52}
                ]}}
        """.trimIndent()
        val show = KartoonsParser.parseShow(json, body)!!
        assertEquals(2002, show.startYear)
        assertEquals(52, show.seasons[0].episodeCount)
        assertEquals("681865ce24c9061ac54aa3a2", show.seasons[0].showId)
    }

    @Test
    fun `episodes parse to number and id as the site numbers them`() {
        // Naruto's season 3, which the site numbers 105-158 rather than 1-54.
        val body = """
            {"success":true,"data":[
                {"_id":"684d46dcd4b2bcd86a8bb39d","seasonId":"684d45ee7a9a416caff5b5af",
                 "episodeNumber":105,"title":"A Fierce Battle of Rolling Thunder!"},
                {"_id":"x106","episodeNumber":106},
                {"_id":"","episodeNumber":107},
                {"_id":"x0","episodeNumber":0}
            ]}
        """.trimIndent()
        assertEquals(
            mapOf(105 to "684d46dcd4b2bcd86a8bb39d", 106 to "x106"),
            KartoonsParser.parseEpisodes(json, body),
        )
    }

    @Test
    fun `token pool parses the mirror shape and drops blanks`() {
        val body = """{"v":1,"tokens":[{"t":"tokenA"},{"t":""},{"t":"tokenB"}]}"""
        assertEquals(listOf("tokenA", "tokenB"), KartoonsParser.parseTokenPool(json, body))
    }

    // --- titles ---------------------------------------------------------------------------------

    @Test
    fun `dub channel tags and fan-edit suffixes come off the title`() {
        assertEquals("Attack on Titan", KartoonsParser.cleanTitle("(MUSE) Attack on Titan"))
        assertEquals("Pokemon", KartoonsParser.cleanTitle("Pokemon (HUNGAMA)"))
        assertEquals("Haikyu!!", KartoonsParser.cleanTitle("Haikyu!! (Hotstar)"))
        assertEquals("One Punch Man", KartoonsParser.cleanTitle("One Punch Man - Definitive Hindi Dub (Fan Edit)"))
        assertEquals("Re:Zero - Starting Life in Another World", KartoonsParser.cleanTitle("Re:Zero - Starting Life in Another World"))
        assertTrue(KartoonsParser.isChannelVariant("One Piece (SONY)"))
        assertFalse(KartoonsParser.isChannelVariant("One Piece"))
    }

    @Test
    fun `a part number is not a season number`() {
        assertNull(KartoonsParser.seasonHint(listOf("Attack on Titan: The Final Season Part 2")))
        assertNull(KartoonsParser.seasonHint(listOf("Shingeki no Kyojin: The Final Season Part 2")))
        assertEquals(2, KartoonsParser.seasonHint(listOf("JUJUTSU KAISEN Season 2")))
        assertEquals(2, KartoonsParser.seasonHint(listOf("Mob Psycho 100 II")))
    }

    @Test
    fun `only cartoons are filtered out of search rows`() {
        assertTrue(KartoonsParser.isAnime(KartoonsParser.SearchRow(_id = "a", category = "Anime")))
        assertTrue(KartoonsParser.isAnime(KartoonsParser.SearchRow(_id = "a", category = null)))
        assertFalse(KartoonsParser.isAnime(KartoonsParser.SearchRow(_id = "a", category = "Cartoon")))
    }

    // --- season plans ---------------------------------------------------------------------------

    @Test
    fun `naruto is the whole series run`() {
        val plan = KartoonsParser.planSeasons(naruto, wanted("Naruto", 220, 2002))
        assertTrue(plan is KartoonsSeasonPlan.Run)
        plan as KartoonsSeasonPlan.Run
        assertEquals(listOf(1, 2, 3, 4), plan.seasons.map { it.seasonNumber })
        assertFalse(plan.partial)
    }

    @Test
    fun `a count short of the whole run takes the seasons that fit, from the start`() {
        // 219 is not 220, so this is not provably the whole series — but seasons 1-3 (158) are
        // its beginning whatever the last season holds, and nothing past them is guessed at.
        val plan = KartoonsParser.planSeasons(naruto, wanted("Naruto", 219, 2002)) as KartoonsSeasonPlan.Run
        assertEquals(listOf(1, 2, 3), plan.seasons.map { it.seasonNumber })
        assertTrue(plan.partial)
    }

    @Test
    fun `a count short of the run under another name needs the year to agree`() {
        // "Naruto: Shippuden" scores well against the show "Naruto" on its series name, and 500 is
        // more than Naruto's 220 — only the opening year (2002 against 2007) says it is not this.
        assertEquals(KartoonsSeasonPlan.None, KartoonsParser.planSeasons(naruto, wanted("Naruto: Shippuden", 500, 2007)))
    }

    @Test
    fun `aot final season part 2 is not season 2`() {
        // Twelve episodes from 2022. Season 2 is twelve episodes from 2017, and the first version
        // played it here: "Part 2" read as season 2, and TmdbClient's far-year fallback agreed.
        val plan = KartoonsParser.planSeasons(
            attackOnTitan,
            wanted(listOf("Attack on Titan: The Final Season Part 2", "Shingeki no Kyojin: The Final Season Part 2"), 12, 2022),
        )
        assertEquals(KartoonsSeasonPlan.None, plan)
    }

    @Test
    fun `aot season 2 by name is season 2`() {
        val plan = KartoonsParser.planSeasons(attackOnTitan, wanted(listOf("Attack on Titan Season 2"), 12, 2017))
        assertEquals(2, (plan as KartoonsSeasonPlan.Single).season.seasonNumber)
    }

    @Test
    fun `aot's first entry is season 1 by count and year`() {
        val plan = KartoonsParser.planSeasons(attackOnTitan, wanted("Attack on Titan", 25, 2013))
        assertEquals(1, (plan as KartoonsSeasonPlan.Single).season.seasonNumber)
    }

    @Test
    fun `jjk season 2 is trusted to its number over a wrong year`() {
        // Kartoons dates its season 2 to 2020; it aired in 2023.
        val plan = KartoonsParser.planSeasons(jujutsuKaisen, wanted(listOf("JUJUTSU KAISEN Season 2"), 23, 2023))
        assertEquals(2, (plan as KartoonsSeasonPlan.Single).season.seasonNumber)
        assertFalse(plan.partial)
    }

    @Test
    fun `a season number inside a sub-series needs the year as well`() {
        // ":re 2nd Season" is the second season of :re, not of Tokyo Ghoul — that is season 4.
        val plan = KartoonsParser.planSeasons(tokyoGhoul, wanted(listOf("Tokyo Ghoul:re 2nd Season"), 12, 2018))
        assertEquals(KartoonsSeasonPlan.None, plan)
    }

    @Test
    fun `demon slayer arcs are told apart by name, not by their identical lengths`() {
        assertEquals(
            3,
            seasonOf(KartoonsParser.planSeasons(demonSlayer, wanted("Demon Slayer: Kimetsu no Yaiba Entertainment District Arc", 11, 2021))),
        )
        assertEquals(
            4,
            seasonOf(KartoonsParser.planSeasons(demonSlayer, wanted("Demon Slayer: Kimetsu no Yaiba Swordsmith Village Arc", 11, 2023))),
        )
        assertEquals(
            5,
            seasonOf(KartoonsParser.planSeasons(demonSlayer, wanted("Demon Slayer: Kimetsu no Yaiba Hashira Training Arc", 8, 2024))),
        )
        assertEquals(1, seasonOf(KartoonsParser.planSeasons(demonSlayer, wanted("Demon Slayer: Kimetsu no Yaiba", 26, 2019))))
    }

    @Test
    fun `a named arc of the wrong length refuses rather than falling back to the count rules`() {
        // AniList's Mugen Train TV arc is 7 episodes; kartoons' "Mugen Train" season holds 11.
        val plan = KartoonsParser.planSeasons(demonSlayer, wanted("Demon Slayer: Kimetsu no Yaiba Mugen Train Arc", 7, 2021))
        assertEquals(KartoonsSeasonPlan.None, plan)
    }

    @Test
    fun `a lone count match years away is refused`() {
        // Dr. STONE NEW WORLD: 11 episodes in 2023. Season 2 (Stone Wars) is 11 from 2021.
        val plan = KartoonsParser.planSeasons(drStone, wanted("Dr. STONE NEW WORLD", 11, 2023))
        assertEquals(KartoonsSeasonPlan.None, plan)
    }

    @Test
    fun `a show that began after the entry cannot hold it`() {
        val vigilantes = Show(
            _id = "v",
            title = "My Hero Academia: Vigilantes",
            startYear = 2025,
            seasons = listOf(Season(_id = "v1", seasonNumber = 1, episodeCount = 13, releaseYear = 2025)),
        )
        assertEquals(KartoonsSeasonPlan.None, KartoonsParser.planSeasons(vigilantes, wanted("My Hero Academia", 13, 2016)))
    }

    @Test
    fun `a first season still being uploaded plans as partial`() {
        val blackClover = Show(
            _id = "bc",
            title = "Black Clover",
            startYear = 2017,
            seasons = listOf(Season(_id = "bc1", seasonNumber = 1, episodeCount = 52, releaseYear = 2017)),
        )
        val plan = KartoonsParser.planSeasons(blackClover, wanted("Black Clover", 170, 2017))
        assertTrue((plan as KartoonsSeasonPlan.Single).partial)
    }

    @Test
    fun `a hinted season still being uploaded plans as partial`() {
        val frieren = Show(
            _id = "f",
            title = "Frieren: Beyond Journey's End",
            startYear = 2023,
            seasons = listOf(
                Season(_id = "f1", seasonNumber = 1, episodeCount = 28, releaseYear = 2023),
                Season(_id = "f2", seasonNumber = 2, episodeCount = 6, releaseYear = 2026),
            ),
        )
        val plan = KartoonsParser.planSeasons(frieren, wanted(listOf("Frieren: Beyond Journey's End Season 2"), 10, 2026))
        assertEquals(2, (plan as KartoonsSeasonPlan.Single).season.seasonNumber)
        assertTrue(plan.partial)
    }

    @Test
    fun `a long entry whose seasons are partly uploaded plans as a partial run`() {
        val plan = KartoonsParser.planSeasons(pokemonHungama, wanted("Pokémon", 276, 1997))
        assertTrue((plan as KartoonsSeasonPlan.Run).partial)
        assertEquals(4, plan.seasons.size)
    }

    @Test
    fun `a run stops at the first missing season`() {
        // Dragon Ball Z lists seasons 1, 4, 5 and 6: concatenating them would number season 4's
        // first episode as 28. Season 1 alone is still the entry's beginning.
        val dbz = Show(
            _id = "dbz",
            title = "Dragon Ball Z",
            startYear = 1989,
            seasons = listOf(
                Season(_id = "d1", seasonNumber = 1, episodeCount = 27, releaseYear = 1989),
                Season(_id = "d4", seasonNumber = 4, episodeCount = 32, releaseYear = 1991),
                Season(_id = "d5", seasonNumber = 5, episodeCount = 26, releaseYear = 1992),
            ),
        )
        val plan = KartoonsParser.planSeasons(dbz, wanted("Dragon Ball Z", 291, 1989)) as KartoonsSeasonPlan.Single
        assertEquals(1, plan.season.seasonNumber)
        assertTrue(plan.partial)
    }

    @Test
    fun `a gap late in the series leaves the start usable`() {
        // Pokémon (HUNGAMA) runs seasons 1-20 then 23; AniList's original series is 276 episodes.
        val seasons = pokemonHungama.seasons + listOf(
            Season(_id = "p5", seasonNumber = 5, episodeCount = 65, releaseYear = 2001),
            Season(_id = "p6", seasonNumber = 6, episodeCount = 40, releaseYear = 2002),
            Season(_id = "p23", seasonNumber = 23, episodeCount = 48, releaseYear = 2019),
        )
        val plan = KartoonsParser.planSeasons(pokemonHungama.copy(seasons = seasons), wanted("Pokémon", 276, 1997))
        assertEquals(listOf(1, 2, 3, 4, 5), (plan as KartoonsSeasonPlan.Run).seasons.map { it.seasonNumber })
    }

    @Test
    fun `search queries and names lose their accents`() {
        assertEquals("Pokemon", KartoonsParser.searchable("Pokémon"))
        assertEquals("Naruto Shippuden", KartoonsParser.searchable("Naruto Shippūden"))
    }

    @Test
    fun `an entry named as the show is its first season, not a same-length later one`() {
        // Kartoons merges SPY x FAMILY's two 2022 cours into a season 1 of 25; AniList's first
        // entry is 12. The count rule alone took season 2 (12, 2023) through the year's slack.
        val spyFamily = Show(
            _id = "sxf",
            title = "SPY x FAMILY",
            startYear = 2022,
            seasons = listOf(
                Season(_id = "x1", seasonNumber = 1, episodeCount = 25, releaseYear = 2022),
                Season(_id = "x2", seasonNumber = 2, episodeCount = 12, releaseYear = 2023),
                Season(_id = "x3", seasonNumber = 3, episodeCount = 13, releaseYear = 2025),
            ),
        )
        assertEquals(KartoonsSeasonPlan.None, KartoonsParser.planSeasons(spyFamily, wanted("SPY x FAMILY", 12, 2022)))
        assertEquals(2, seasonOf(KartoonsParser.planSeasons(spyFamily, wanted("SPY x FAMILY Season 2", 12, 2023))))
    }

    @Test
    fun `a remake sharing the show's name is not its first season`() {
        val hunter1999 = Show(
            _id = "hxh",
            title = "Hunter x Hunter",
            startYear = 1999,
            seasons = listOf(Season(_id = "h1", seasonNumber = 1, episodeCount = 62, releaseYear = 1999)),
        )
        assertEquals(KartoonsSeasonPlan.None, KartoonsParser.planSeasons(hunter1999, wanted("Hunter x Hunter", 148, 2011)))
    }

    // --- numbering ------------------------------------------------------------------------------

    @Test
    fun `shippuden's stray copies in season 8 neither number it nor push later seasons back`() {
        // Live on 2026-10-03: season 8 claims 41 and lists 152-175 plus seventeen of season 4's
        // episodes again as 72-88. Read naively, 72 started the season and every later episode
        // landed 17+ places late (app 440 played kartoons 360).
        val counts = listOf(32, 21, 18, 17, 24, 31, 8)
        var start = 1
        val early = counts.mapIndexed { index, count ->
            val season = Season(_id = "s${index + 1}", seasonNumber = index + 1, episodeCount = count)
            (season to episodes(start until start + count, "s${index + 1}-")).also { start += count }
        }
        val s8 = Season(_id = "s8", seasonNumber = 8, episodeCount = 41)
        val s8Episodes = episodes(72..88, "stray-") + episodes(152..175, "s8-")
        val s9 = Season(_id = "s9", seasonNumber = 9, episodeCount = 21)
        val laidOut = KartoonsParser.layoutRun(early + listOf(s8 to s8Episodes, s9 to episodes(176..196, "s9-")), cap = 500)

        assertEquals("s4-72", laidOut[72])
        assertEquals("s8-152", laidOut[152])
        assertEquals("s8-175", laidOut[175])
        assertEquals("s9-176", laidOut[176])
        assertEquals("s9-196", laidOut[196])
        assertTrue(laidOut.values.none { it.startsWith("stray-") })
    }

    @Test
    fun `naruto's straight-through numbering lays out so episode 105 is season 3's first`() {
        val laidOut = KartoonsParser.layoutRun(
            listOf(
                naruto.seasons[0] to episodes(1..52, "a"),
                naruto.seasons[1] to episodes(53..104, "b"),
                naruto.seasons[2] to episodes(105..158, "c"),
                naruto.seasons[3] to episodes(159..220, "d"),
            ),
            cap = 220,
        )
        assertEquals(220, laidOut.size)
        assertEquals("a1", laidOut[1])
        assertEquals("b53", laidOut[53])
        assertEquals("c105", laidOut[105])
        assertEquals("d220", laidOut[220])
    }

    @Test
    fun `restart numbering lays out after the earlier seasons`() {
        val seasons = listOf(
            Season(_id = "s1", seasonNumber = 1, episodeCount = 25),
            Season(_id = "s2", seasonNumber = 2, episodeCount = 12),
        )
        val laidOut = KartoonsParser.layoutRun(listOf(seasons[0] to episodes(1..25, "a"), seasons[1] to episodes(1..12, "b")), cap = 37)
        assertEquals("a25", laidOut[25])
        assertEquals("b1", laidOut[26])
        assertEquals("b12", laidOut[37])
    }

    @Test
    fun `a season that runs past its claimed count pushes the next one back`() {
        // Pokémon (HUNGAMA) season 1 claims 80 and numbers to 82 (two episodes absent).
        val s1 = (1..82).filter { it != 30 && it != 31 }.associateWith { "a$it" }
        val laidOut = KartoonsParser.layoutRun(
            listOf(pokemonHungama.seasons[0] to s1, pokemonHungama.seasons[1] to episodes(1..36, "b")),
            cap = 276,
        )
        assertEquals("a82", laidOut[82])
        assertNull("absent episodes stay absent rather than shifting their neighbours", laidOut[30])
        assertEquals("b1", laidOut[83])
    }

    @Test
    fun `a single straight-through season is renumbered from its own start`() {
        // JJK season 2 lists 25-47 after a season of 24.
        val laidOut = KartoonsParser.layoutSingle(jujutsuKaisen, jujutsuKaisen.seasons[1], episodes(25..47, "j"))
        assertEquals((1..23).toSet(), laidOut.keys)
        assertEquals("j25", laidOut[1])
    }

    @Test
    fun `a catalogue that starts mid-series still renumbers from 1`() {
        // Black Butler lists only seasons 4 and 5, so nothing before season 4 to measure from.
        val laidOut = KartoonsParser.localNumbers(episodes(47..57, "k"), claimedCount = 11, offsets = listOf(0))
        assertEquals((1..11).toSet(), laidOut.keys)
    }

    @Test
    fun `a restart-numbered season missing its first episodes keeps its numbers`() {
        val laidOut = KartoonsParser.localNumbers(episodes(3..12, "r"), claimedCount = 12, offsets = listOf(12))
        assertEquals((3..12).toSet(), laidOut.keys)
    }

    // --- links and refusals ---------------------------------------------------------------------

    @Test
    fun `odelu 1 orders ahead of odelu 2 whatever the payload order`() {
        val links = listOf(
            KartoonsParser.Link(name = "Odelu 2", url = "https://x/2.m3u8"),
            KartoonsParser.Link(name = "Odelu 1", url = "https://x/1.m3u8"),
        )
        assertEquals(listOf("Odelu 1", "Odelu 2"), KartoonsParser.orderLinks(links).map { it.name })
    }

    @Test
    fun `only the API's own json refusal counts against a token`() {
        val refusal = """{"success":false,"message":"Authentication required","auth_required":true}"""
        assertTrue(KartoonsParser.isTokenRefusal(401, refusal))
        assertTrue(KartoonsParser.isTokenRefusal(403, refusal))
        assertFalse(KartoonsParser.isTokenRefusal(403, "<!DOCTYPE html><title>Just a moment...</title>"))
        assertFalse(KartoonsParser.isTokenRefusal(429, refusal))
        assertTrue(KartoonsParser.namesBan("""{"message":"Account banned"}"""))
    }

    @Test
    fun `a redacted path carries no token`() {
        assertEquals("/cloudstream/episode/e1/links", KartoonsParser.redact("/cloudstream/episode/e1/links?token=secret"))
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun wanted(title: String, episodes: Int?, year: Int?) = wanted(listOf(title), episodes, year)

    private fun wanted(titles: List<String>, episodes: Int?, year: Int?) = Wanted(episodes, year, titles)

    private fun seasonOf(plan: KartoonsSeasonPlan): Int? = (plan as? KartoonsSeasonPlan.Single)?.season?.seasonNumber

    private fun episodes(range: IntRange, prefix: String): Map<Int, String> = range.associateWith { "$prefix$it" }

    private val naruto = Show(
        _id = "681865ce24c9061ac54aa3a2",
        title = "Naruto",
        startYear = 2002,
        seasons = listOf(
            Season(_id = "684d45cc56d04df2fd104e22", seasonNumber = 1, title = "Season 1", episodeCount = 52, releaseYear = 2002),
            Season(_id = "684d45e056d04df2fd104e26", seasonNumber = 2, title = "Season 2", episodeCount = 52, releaseYear = 2003),
            Season(_id = "684d45ee7a9a416caff5b5af", seasonNumber = 3, title = "Season 3", episodeCount = 54, releaseYear = 2004),
            Season(_id = "684d460c56d04df2fd104e2a", seasonNumber = 4, title = "Season 4", episodeCount = 62, releaseYear = 2005),
        ),
    )

    private val attackOnTitan = Show(
        _id = "aot",
        title = "Attack on Titan",
        startYear = 2013,
        seasons = listOf(
            Season(_id = "a0", seasonNumber = 0, title = "Specials", episodeCount = 8, releaseYear = 2013),
            Season(_id = "a1", seasonNumber = 1, title = "Season 1", episodeCount = 25, releaseYear = 2013),
            Season(_id = "a2", seasonNumber = 2, title = "Season 2", episodeCount = 12, releaseYear = 2017),
            Season(_id = "a3", seasonNumber = 3, title = "Season 3", episodeCount = 22, releaseYear = 2018),
            Season(_id = "a4", seasonNumber = 4, title = "The Final Season", episodeCount = 30, releaseYear = 2020),
        ),
    )

    private val jujutsuKaisen = Show(
        _id = "jjk",
        title = "JUJUTSU KAISEN",
        startYear = 2020,
        seasons = listOf(
            Season(_id = "j1", seasonNumber = 1, title = "Season 1", episodeCount = 24, releaseYear = 2020),
            Season(_id = "j2", seasonNumber = 2, title = "Season 2", episodeCount = 23, releaseYear = 2020),
            Season(_id = "j3", seasonNumber = 3, title = "Season 3", episodeCount = 12, releaseYear = 2026),
        ),
    )

    private val demonSlayer = Show(
        _id = "ds",
        title = "Demon Slayer: Kimetsu no Yaiba",
        startYear = 2019,
        seasons = listOf(
            Season(_id = "d1", seasonNumber = 1, title = "Tanjiro Kamado, Unwavering Resolve Arc", episodeCount = 26, releaseYear = 2019),
            Season(_id = "d2", seasonNumber = 2, title = "Mugen Train", episodeCount = 11, releaseYear = 2021),
            Season(_id = "d3", seasonNumber = 3, title = "Entertainment District", episodeCount = 11, releaseYear = 2021),
            Season(_id = "d4", seasonNumber = 4, title = "Swordsmith Village", episodeCount = 11, releaseYear = 2023),
            Season(_id = "d5", seasonNumber = 5, title = "Hashira Training", episodeCount = 8, releaseYear = 2024),
        ),
    )

    private val tokyoGhoul = Show(
        _id = "tg",
        title = "Tokyo Ghoul",
        startYear = 2014,
        seasons = listOf(
            Season(_id = "t1", seasonNumber = 1, episodeCount = 12, releaseYear = 2014),
            Season(_id = "t2", seasonNumber = 2, episodeCount = 12, releaseYear = 2015),
            Season(_id = "t3", seasonNumber = 3, episodeCount = 12, releaseYear = 2018),
            Season(_id = "t4", seasonNumber = 4, episodeCount = 12, releaseYear = 2018),
        ),
    )

    private val drStone = Show(
        _id = "dr",
        title = "Dr. STONE",
        startYear = 2019,
        seasons = listOf(
            Season(_id = "r1", seasonNumber = 1, episodeCount = 24, releaseYear = 2019),
            Season(_id = "r2", seasonNumber = 2, episodeCount = 11, releaseYear = 2021),
            Season(_id = "r3", seasonNumber = 3, episodeCount = 22, releaseYear = 2023),
            Season(_id = "r4", seasonNumber = 4, episodeCount = 37, releaseYear = 2025),
        ),
    )

    private val pokemonHungama = Show(
        _id = "pk",
        title = "Pokemon (HUNGAMA)",
        startYear = 1997,
        seasons = listOf(
            Season(_id = "p1", seasonNumber = 1, episodeCount = 80, releaseYear = 1997),
            Season(_id = "p2", seasonNumber = 2, episodeCount = 36, releaseYear = 1999),
            Season(_id = "p3", seasonNumber = 3, episodeCount = 41, releaseYear = 2000),
            Season(_id = "p4", seasonNumber = 4, episodeCount = 52, releaseYear = 2001),
        ),
    )
}
