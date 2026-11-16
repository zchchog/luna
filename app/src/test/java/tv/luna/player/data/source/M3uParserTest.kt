package tv.luna.player.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.luna.player.data.model.Kind

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.byteInputStream())

    @Test
    fun parsesAttributes() {
        val r = parse(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-logo=\"http://x/l.png\" group-title=\"UK, News\",BBC One HD\nhttp://h/u/p/1\n"
        )
        val e = r.entries.single()
        assertEquals("UK, News", e.attrs["group-title"])
        assertEquals("bbc1.uk", e.attrs["tvg-id"])
        assertEquals("http://x/l.png", e.attrs["tvg-logo"])
        assertEquals("BBC One HD", e.name)
        assertEquals("http://h/u/p/1", e.url)
    }

    @Test
    fun handlesBomAndCrlf() {
        val r = M3uParser.parse(javaClass.getResourceAsStream("/m3u/bom_crlf.m3u")!!)
        assertEquals(2, r.entries.size)
        assertEquals("Alpha", r.entries[0].name)
        assertEquals("http://h/u/p/2", r.entries[1].url)
        assertEquals(0, r.skipped)
    }

    @Test
    fun displayNameMayContainCommas() {
        val e = parse("#EXTM3U\n#EXTINF:-1 group-title=\"Films, UK\",Lock, Stock and Two Smoking Barrels\nhttp://h/movie/u/p/7.mkv\n").entries.single()
        assertEquals("Films, UK", e.attrs["group-title"])
        assertEquals("Lock, Stock and Two Smoking Barrels", e.name)
    }

    @Test
    fun missingAttributesOk() {
        val e = parse("#EXTM3U\n#EXTINF:-1,Plain Name\nhttp://h/u/p/9\n").entries.single()
        assertTrue(e.attrs.isEmpty())
        assertEquals("Plain Name", e.name)
    }

    @Test
    fun skipsMalformed() {
        val r = parse("#EXTM3U\n#EXTINF:-1,No Url\n#EXTINF:-1,Good\nhttp://h/u/p/2\n")
        assertEquals(1, r.skipped)
        assertEquals(listOf("Good"), r.entries.map { it.name })
    }

    @Test
    fun skipsTrailingEntryWithoutUrl() {
        val r = parse("#EXTM3U\n#EXTINF:-1,Good\nhttp://h/u/p/2\n#EXTINF:-1,Dangling\n")
        assertEquals(1, r.skipped)
        assertEquals(1, r.entries.size)
    }

    @Test
    fun classify() {
        fun k(url: String) = M3uParser.classify(M3uEntry("x", emptyMap(), url))
        assertEquals(Kind.MOVIE, k("http://h/movie/u/p/1.mkv"))
        assertEquals(Kind.SERIES, k("http://h/series/u/p/2.mp4"))
        assertEquals(Kind.LIVE, k("http://h/u/p/3"))
        assertEquals(Kind.MOVIE, k("http://h/x/film.mp4"))
        assertEquals(Kind.LIVE, k("http://h/live/u/p/4.ts"))
        assertEquals(Kind.LIVE, k("http://h/u/p/5.m3u8?token=a.mp4x"))
    }

    @Test
    fun seasonEpisode() {
        assertEquals(2 to 4, M3uParser.seasonEpisode("Harbour Lights S02E04"))
        assertEquals(1 to 12, M3uParser.seasonEpisode("s1 e12"))
        assertNull(M3uParser.seasonEpisode("News"))
    }

    @Test fun extvlcoptHeadersRideOnTheUrl() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="News",A
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            #EXTVLCOPT:http-referrer=https://site/
            http://h/a.m3u8
            #EXTINF:-1,B
            http://h/b.m3u8
        """.trimIndent()
        val e = M3uParser.parse(m3u.byteInputStream()).entries
        assertEquals("http://h/a.m3u8" to mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://site/"), splitStreamHeaders(e[0].url))
        assertEquals("http://h/b.m3u8", e[1].url) // options don't leak to the next entry
    }

    @Test fun extinfHeaderAttributesRideOnTheUrl() {
        val m3u = "#EXTM3U\n#EXTINF:-1 http-user-agent=\"Mozilla/5.0 (X11, Linux)\" http-referrer=\"https://s/\" group-title=\"Sports\",C\nhttp://h/c.m3u8"
        val e = M3uParser.parse(m3u.byteInputStream()).entries.single()
        assertEquals("http://h/c.m3u8" to mapOf("User-Agent" to "Mozilla/5.0 (X11, Linux)", "Referer" to "https://s/"), splitStreamHeaders(e.url))
        assertEquals("C", e.name)
    }
}
