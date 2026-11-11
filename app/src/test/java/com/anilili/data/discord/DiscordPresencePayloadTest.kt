package com.anilili.data.discord

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordPresencePayloadTest {

    /** Asset ids, because that is what an activity accepts — never the key they were uploaded under. */
    private val PAUSED_ASSET_ID = "1545331260329558027"
    private val ICON_ASSET_ID = "1545331260329558028"

    private fun watching(
        seriesTitle: String = "Frieren: Beyond Journey's End",
        episodeNumber: String? = "7",
        episodeTitle: String? = null,
        totalEpisodes: Int? = 28,
        artworkUrl: String? = null,
        anilistId: Int = 154587,
        positionMs: Long = 0,
        durationMs: Long = 24 * 60 * 1000,
        paused: Boolean = false,
    ) = DiscordWatching(
        seriesTitle = seriesTitle,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        totalEpisodes = totalEpisodes,
        artworkUrl = artworkUrl,
        anilistId = anilistId,
        positionMs = positionMs,
        durationMs = durationMs,
        paused = paused,
    )

    private fun payload(
        watching: DiscordWatching,
        largeImage: String? = null,
        smallImage: String? = if (watching.paused) PAUSED_ASSET_ID else ICON_ASSET_ID,
        nowMs: Long = 1_700_000_000_000,
    ): JSONObject = buildPresencePayload(
        watching = watching,
        appName = "Anilili",
        largeImage = largeImage,
        smallImage = smallImage,
        nowMs = nowMs,
    )

    private fun JSONObject.activity(): JSONObject = getJSONArray("activities").getJSONObject(0)

    @Test
    fun `the card names the series and the episode`() {
        val activity = payload(watching()).activity()

        assertEquals("Anilili", activity.getString("name"))
        assertEquals(3, activity.getInt("type"))
        assertEquals("Frieren: Beyond Journey's End", activity.getString("details"))
        assertEquals("Ep: 7/28", activity.getString("state"))
    }

    /** The member list reads "Watching <series>", as on desktop, not "Watching Anilili". */
    @Test
    fun `the member list shows the series`() {
        assertEquals(2, payload(watching()).activity().getInt("status_display_type"))

        // No series line to point at: the app's name is left in place.
        val untitled = payload(watching(seriesTitle = "K")).activity()
        assertFalse(untitled.has("status_display_type"))
    }

    /**
     * The one thing a progress bar has to get right. Discord is handed a start time and runs the
     * bar from it, so resuming forty minutes in has to report a start forty minutes ago — not now,
     * which would restart every viewer's bar at zero on a resume.
     */
    @Test
    fun `the start is back-dated by how far into the episode the viewer is`() {
        val now = 1_700_000_000_000
        val position = 40L * 60 * 1000
        val duration = 90L * 60 * 1000

        val timestamps = payload(
            watching(positionMs = position, durationMs = duration),
            nowMs = now,
        ).activity().getJSONObject("timestamps")

        assertEquals(now - position, timestamps.getLong("start"))
        assertEquals(now - position + duration, timestamps.getLong("end"))
    }

    /**
     * Discord cannot hold a bar still, and a bar that keeps advancing through a pause is a card
     * that lies about where somebody is. No timestamps is the honest answer.
     */
    @Test
    fun `a paused episode carries no timestamps at all`() {
        val activity = payload(watching(positionMs = 60_000, paused = true)).activity()

        assertFalse(activity.has("timestamps"))
    }

    @Test
    fun `a paused episode is marked as paused`() {
        val assets = payload(watching(paused = true)).activity().getJSONObject("assets")

        assertEquals(PAUSED_ASSET_ID, assets.getString("small_image"))
        assertEquals("Paused", assets.getString("small_text"))
    }

    /**
     * The key an asset was uploaded under is not a value an activity accepts. Sending it renders
     * Discord's "?" placeholder — and `small_text` still resolves, so the card hovers as "Paused"
     * over a broken image and reads as a failed upload rather than a bad reference.
     */
    @Test
    fun `a badge that could not be resolved is left off entirely`() {
        val assets = payload(watching(paused = true), largeImage = "mp:external/a/b.png", smallImage = null)
            .activity()
            .getJSONObject("assets")

        assertFalse(assets.has("small_image"))
        assertFalse(assets.has("small_text"))
    }

    /**
     * The badge is the only thing on the card that names the app once the cover fills the large
     * slot, so playing gets the app's mark rather than an empty corner.
     */
    @Test
    fun `a playing episode wears the app icon`() {
        val assets = payload(watching(), largeImage = "mp:external/abc/def.png")
            .activity()
            .getJSONObject("assets")

        assertEquals(ICON_ASSET_ID, assets.getString("small_image"))
    }

    /** "Anilili" here would only repeat the card's own "Watching Anilili" header. */
    @Test
    fun `the app icon carries no tooltip of its own`() {
        val assets = payload(watching(), largeImage = "mp:external/abc/def.png")
            .activity()
            .getJSONObject("assets")

        assertFalse(assets.has("small_text"))
    }

    /** Nothing to show is sent as nothing, not as an empty object Discord has to interpret. */
    @Test
    fun `a card with no cover and no tooltip omits assets entirely`() {
        val activity = payload(watching(episodeTitle = null), largeImage = null, smallImage = null)
            .activity()

        assertFalse(activity.has("assets"))
    }

    @Test
    fun `an episode with no duration still publishes, without a bar`() {
        val activity = payload(watching(durationMs = 0)).activity()

        assertFalse(activity.has("timestamps"))
        assertEquals("Frieren: Beyond Journey's End", activity.getString("details"))
    }

    @Test
    fun `the cover is passed through as it was resolved`() {
        val assets = payload(watching(), largeImage = "mp:external/abc/def.png")
            .activity()
            .getJSONObject("assets")

        assertEquals("mp:external/abc/def.png", assets.getString("large_image"))
    }

    /** The second line is the number, then the episode's title: "Ep: 7/28 - Its Title". */
    @Test
    fun `an episode title follows the number on the state line`() {
        val activity = payload(watching(episodeTitle = "Like a Fairy Tale"), largeImage = "mp:external/abc/def.png")
            .activity()

        assertEquals("Frieren: Beyond Journey's End", activity.getString("details"))
        assertEquals("Ep: 7/28 - Like a Fairy Tale", activity.getString("state"))
        assertFalse(activity.getJSONObject("assets").has("large_text"))
    }

    @Test
    fun `an episode title without a known total`() {
        assertEquals(
            "Ep: 7 - Like a Fairy Tale",
            stateLine(watching(totalEpisodes = null, episodeTitle = "Like a Fairy Tale")),
        )
    }

    @Test
    fun `a blank episode title is treated as none`() {
        val activity = payload(watching(episodeTitle = "   ")).activity()

        assertEquals("Ep: 7/28", activity.getString("state"))
    }

    @Test
    fun `the button opens the Anilili Discord server`() {
        val activity = payload(watching()).activity()

        assertEquals("Get Anilili", activity.getJSONArray("buttons").getString(0))
        assertEquals(
            "https://discord.gg/Anilili",
            activity.getJSONObject("metadata").getJSONArray("button_urls").getString(0),
        )
    }

    @Test
    fun `the watch button does not depend on an AniList id`() {
        val activity = payload(watching(anilistId = 0)).activity()

        assertEquals(1, activity.getJSONArray("buttons").length())
        assertEquals(
            "https://discord.gg/Anilili",
            activity.getJSONObject("metadata").getJSONArray("button_urls").getString(0),
        )
    }

    /** Discord drops a field under two characters rather than rendering it, so it is not sent. */
    @Test
    fun `a one character title is omitted rather than sent`() {
        val activity = payload(watching(seriesTitle = "K", episodeNumber = null, episodeTitle = null))
            .activity()

        assertFalse(activity.has("details"))
    }

    @Test
    fun `the application id is a number Discord can read back exactly`() {
        val activity = payload(watching()).activity()

        assertEquals(DiscordAuthManager.APPLICATION_ID.toLong(), activity.getLong("application_id"))
    }

    @Test
    fun `clearing publishes an empty activity list`() {
        val cleared = clearedPresencePayload()

        assertEquals(0, cleared.getJSONArray("activities").length())
        assertEquals("online", cleared.getString("status"))
    }

    @Test
    fun `the default presence is the one 0_1_79 published`() {
        // 0.1.80 sent afk:true and since:0 (and a presence in IDENTIFY), and no card was shown
        // anywhere. These are the values that worked; changing them needs a signed-in test first.
        for (presence in listOf(payload(watching()), payload(watching(paused = true)), clearedPresencePayload())) {
            assertEquals("online", presence.getString("status"))
            assertEquals(false, presence.getBoolean("afk"))
            assertTrue(presence.isNull("since"))
            assertTrue(presence.has("activities"))
        }
    }

    @Test
    fun `the chosen status is what the session announces`() {
        val nowMs = 1_700_000_000_000
        val dnd = buildPresencePayload(watching(), "Anilili", null, status = "dnd", nowMs = nowMs)
        assertEquals("dnd", dnd.getString("status"))
        assertTrue(dnd.isNull("since"))

        // Idle carries when the client went idle; the other statuses say it never did.
        val idle = buildPresencePayload(watching(), "Anilili", null, status = "idle", nowMs = nowMs)
        assertEquals("idle", idle.getString("status"))
        assertEquals(nowMs, idle.getLong("since"))

        assertEquals("online", payload(watching()).getString("status"))
    }

    @Test
    fun `the episode label drops the total when the series length is unknown`() {
        assertEquals("Ep: 7", episodeLabel(watching(totalEpisodes = null)))
        assertEquals("Ep: 7", episodeLabel(watching(totalEpisodes = 0)))
    }

    @Test
    fun `a film with no episode number shows its title`() {
        assertEquals(
            "The Last Attack",
            stateLine(watching(episodeNumber = null, episodeTitle = "The Last Attack")),
        )
    }

    @Test
    fun `a film with neither has no second line`() {
        assertNull(stateLine(watching(episodeNumber = null, episodeTitle = null)))
    }

    /**
     * The identity is what decides whether an update is worth spending a presence write on.
     * Discord advances the bar by itself, so a card that differs only in position is the same
     * card, and republishing it would burn the gateway's five-per-twenty-seconds budget to no end.
     */
    @Test
    fun `the card identity ignores the clock`() {
        assertEquals(
            watching(positionMs = 0).cardIdentity,
            watching(positionMs = 15L * 60 * 1000).cardIdentity,
        )
    }

    @Test
    fun `the card identity changes with the episode and with a pause`() {
        val playing = watching(episodeNumber = "7")

        assertTrue(playing.cardIdentity != watching(episodeNumber = "8").cardIdentity)
        assertTrue(playing.cardIdentity != playing.copy(paused = true).cardIdentity)
    }

    /** Titles arrive after the numbered skeleton; the card has to pick them up when they do. */
    @Test
    fun `the card identity changes when the episode title arrives`() {
        val skeleton = watching(episodeTitle = null)

        assertTrue(skeleton.cardIdentity != skeleton.copy(episodeTitle = "Like a Fairy Tale").cardIdentity)
    }
}
