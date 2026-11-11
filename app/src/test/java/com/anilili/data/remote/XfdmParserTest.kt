package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are lifted verbatim from live pages (2026-09-14), including the site's unquoted CSS
 * `url(...)` backdrop and the lazy-loading `data:image/gif;base64` placeholder that stands in for
 * the real thumbnail. The parser exists to cope with exactly that shape, so tidying it here would
 * test markup the site never sends.
 */
class XfdmParserTest {
    @Test
    fun `a search result is chosen by its Bangumi cover id, not by name`() {
        // Searching for the fourth season returns the third and a side story beside it, all three
        // named almost identically. This is the case that makes the provider id-keyed: a name
        // comparison has to guess here, and the cover id does not.
        val path = XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 515594)

        assertEquals("/bangumi/3462.html", path)
    }

    @Test
    fun `the neighbouring seasons on the same page resolve to their own pages`() {
        assertEquals("/bangumi/2161.html", XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 407573))
        assertEquals("/bangumi/2090.html", XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 421174))
    }

    @Test
    fun `a subject the page does not offer is not matched to some other card`() {
        // 571784 is a different show entirely. Returning any of these three would be worse than
        // returning nothing: the viewer would be handed the wrong series and no error.
        assertNull(XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 571784))
    }

    @Test
    fun `the last card does not claim the covers in the page's recommendation rail`() {
        // The final card's slice runs to the end of the document, so it contains the "热播影片" rail
        // and its dozen unrelated covers. Only the card's own backdrop may count, or the last
        // result on every search page would answer to almost any id.
        assertEquals("/bangumi/2090.html", XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 421174))
        assertNull(XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 627136))
        assertNull(XfdmParser.subjectPathInSearchPage(SLIME_SEARCH, 633836))
    }

    @Test
    fun `the suggest payload answers the same question as the search page`() {
        // 290 bytes against the search page's 33KB, and no HTML to parse — which is what a
        // television is worst at. The id is confirmed by the same cover filename either way.
        val root = Json.parseToJsonElement(SUGGEST) as JsonObject

        assertEquals("/bangumi/3500.html", XfdmParser.subjectPathForBangumiId(root, 571784))
    }

    @Test
    fun `a suggest row for a different subject is not accepted`() {
        val root = Json.parseToJsonElement(SUGGEST) as JsonObject

        assertNull(XfdmParser.subjectPathForBangumiId(root, 515594))
    }

    @Test
    fun `a suggest payload with no list is a miss, not a crash`() {
        val empty = Json.parseToJsonElement("""{"code":0,"msg":"暂无数据"}""") as JsonObject

        assertNull(XfdmParser.subjectPathForBangumiId(empty, 571784))
        assertNull(XfdmParser.subjectPathForBangumiId(null, 571784))
    }

    @Test
    fun `episodes carry their channel and are numbered from the label`() {
        val episodes = XfdmParser.parseEpisodes(EPISODE_ROWS)

        assertEquals(listOf(1, 1, 2, 2), episodes.map { it.number })
        assertEquals(listOf(1, 2, 1, 2), episodes.map { it.channel })
        assertEquals("/watch/3500/1/1.html", episodes.first().watchPath)
    }

    @Test
    fun `a channel that opens with a special does not shift every episode after it`() {
        // The trailing path segment is the site's ordinal within the channel, not the episode
        // number: here ordinal 1 is a special and ordinal 2 is episode 1. Reading the path would
        // report this channel as starting at episode 1 and silently misalign the whole run.
        val episodes = XfdmParser.parseEpisodes(
            """
            <ul class="anthology-list-play size">
            <li class="box border"><a class="hide" href="/watch/3500/4/1.html">第00集</a></li>
            <li class="box border"><a class="hide" href="/watch/3500/4/2.html">第01集</a></li>
            </ul>
            """.trimIndent(),
        )

        assertEquals(listOf(0, 1), episodes.map { it.number })
        assertEquals("/watch/3500/4/2.html", episodes.last().watchPath)
    }

    @Test
    fun `the player blob gives the file with its JSON escaping undone`() {
        assertEquals(
            "https://play.xfvod.pro:8088/temp/2607/%E8%B6%85%E5%B8%8201.mp4",
            XfdmParser.videoUrl(WATCH_PAGE),
        )
    }

    @Test
    fun `a watch page with no player blob yields nothing rather than a partial URL`() {
        assertNull(XfdmParser.videoUrl("<html><body>404</body></html>"))
    }

    private companion object {
        /**
         * Three cards from `search.html?wd=关于我转生变成史莱姆这档事 第四季`, trimmed to the two elements
         * that matter — the unquoted `.cover` backdrop and the `thumb-menu` link — plus a tail
         * standing in for the recommendation rail that the last card's slice swallows.
         */
        const val SLIME_SEARCH = """
<div class="public-list-box search-box flex rel">
    <div class="cover" style="background-image: linear-gradient(180deg, rgb(17, 19, 25) 0%, rgba(25,26,33,0.90) 52%, rgb(17, 19, 25) 100%), url(https://img2.cycimg.me/r/800/pic/cover/l/6c/a4/515594_Uj20c.jpg);"></div>
    <img referrerpolicy="no-referrer" src="data:image/gif;base64,iVBORw0KGgo=" class="lazy lazy1" alt="关于我转生变成史莱姆这档事 第四季封面图" data-src="https://img2.cycimg.me/r/800/pic/cover/l/6c/a4/515594_Uj20c.jpg" />
    <div class="thumb-content"><div class="thumb-txt cor4 hide">关于我转生变成史莱姆这档事 第四季</div></div>
    <div class="thumb-menu flex"><a target="_self" href="/bangumi/3462.html" class="button cr3">播放正片</a></div>
</div>
<div class="public-list-box search-box flex rel">
    <div class="cover" style="background-image: url(https://img2.cycimg.me/r/800/pic/cover/l/aa/1b/407573_Kq81m.jpg);"></div>
    <div class="thumb-content"><div class="thumb-txt cor4 hide">关于我转生变成史莱姆这档事 第三季</div></div>
    <div class="thumb-menu flex"><a target="_self" href="/bangumi/2161.html" class="button cr3">播放正片</a></div>
</div>
<div class="public-list-box search-box flex rel">
    <div class="cover" style="background-image: url(https://img2.cycimg.me/r/800/pic/cover/l/3d/07/421174_Pw54t.jpg);"></div>
    <div class="thumb-content"><div class="thumb-txt cor4 hide">关于我转生变成史莱姆这档事 柯里乌斯之梦</div></div>
    <div class="thumb-menu flex"><a target="_self" href="/bangumi/2090.html" class="button cr3">播放正片</a></div>
</div>
<div class="wap-diy-vod-e"><h5>热播影片</h5>
    <a href="/bangumi/9001.html"><img data-src="https://img2.cycimg.me/r/800/pic/cover/l/11/22/627136_Zz11a.jpg" /></a>
    <a href="/bangumi/9002.html"><img data-src="https://img2.cycimg.me/r/800/pic/cover/l/33/44/633836_Yy22b.jpg" /></a>
</div>
"""

        /** `/index.php/ajax/suggest?mid=1&wd=…`, verbatim (2026-09-14). */
        const val SUGGEST = """
{"code":1,"msg":"数据列表","page":1,"pagecount":1,"limit":10,"total":1,"list":[{"id":3500,"name":"在超市后门吸烟的二人","en":"duozaichaoshihoumenchouyandeliangren","pic":"https:\/\/img2.xfmanga.top\/pic\/cover\/l\/6f\/42\/571784_leRlL.jpg"}],"url":"\/search\/wd\/mac_wd.html"}
"""

        const val EPISODE_ROWS = """
<div class="anthology-list-box none"><ul class="anthology-list-play size">
    <li class="box border"><a class="hide" href="/watch/3500/1/1.html">第01集</a></li>
    <li class="box border"><a class="hide" href="/watch/3500/1/2.html">第02集</a></li>
</ul></div>
<div class="anthology-list-box none"><ul class="anthology-list-play size">
    <li class="box border"><a class="hide" href="/watch/3500/2/1.html">第01集</a></li>
    <li class="box border"><a class="hide" href="/watch/3500/2/2.html">第02集</a></li>
</ul></div>
"""

        /** MacCMS state from `/watch/3500/2/1.html`, with the URL escaped the way the site emits it. */
        const val WATCH_PAGE = """
<script type="text/javascript">var player_aaaa={"flag":"play","encrypt":0,"trysee":0,"points":0,"link":"\/watch\/3500\/2\/1.html","link_next":"\/watch\/3500\/2\/2.html","link_pre":"","vod_data":{"vod_name":"在超市后门吸烟的二人"},"url":"https:\/\/play.xfvod.pro:8088\/temp\/2607\/%E8%B6%85%E5%B8%8201.mp4","url_next":"https:\/\/play.xfvod.pro:8088\/temp\/2607\/%E8%B6%85%E5%B8%8202.mp4","from":"AL","server":"no","note":"","id":"3500","sid":2,"nid":1}</script>
"""
    }
}
