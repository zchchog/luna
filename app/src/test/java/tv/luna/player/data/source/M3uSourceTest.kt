package tv.luna.player.data.source

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class M3uSourceTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-chno="101" group-title="UK",BBC One
        http://h/u/p/1
        #EXTINF:-1 group-title="Films",Big Film
        http://h/movie/u/p/10.mkv
        #EXTINF:-1 group-title="Drama",Harbour Lights S01E02
        http://h/series/u/p/21.mp4
        #EXTINF:-1 group-title="Drama",Harbour Lights S01E01
        http://h/series/u/p/20.mp4
        #EXTINF:-1 group-title="Drama",Standalone Special
        http://h/series/u/p/30.mp4
    """.trimIndent()

    @Test
    fun splitsKindsAndGroupsSeries() = runTest {
        server.enqueue(MockResponse().setBody(playlist))
        val src = M3uSource(server.url("/list.m3u").toString(), listOf("http://e/epg.xml"), OkHttpClient(), "UA/1")

        val ch = src.liveChannels().single()
        assertEquals(101, ch.number)
        assertEquals("bbc1.uk", ch.tvgId)
        assertEquals(listOf("Big Film"), src.movies().map { it.name })
        assertEquals(listOf("Harbour Lights", "Standalone Special"), src.series().map { it.name })
        val detail = src.seriesDetail(src.series().first().remoteId)
        assertEquals(listOf(1 to 1, 1 to 2), detail.episodes.map { it.season to it.number })
        assertEquals(listOf("Drama"), src.seriesCategories().map { it.name })
        assertEquals(1, server.requestCount) // fetched once per instance
        assertEquals("UA/1", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun duplicateEpisodeUrlsAppearOnce() = runTest {
        server.enqueue(MockResponse().setBody(playlist + "\n#EXTINF:-1 group-title=\"Drama\",Harbour Lights S01E01\nhttp://h/series/u/p/20.mp4\n"))
        val src = M3uSource(server.url("/list.m3u").toString(), emptyList(), OkHttpClient(), null)
        val eps = src.seriesDetail(src.series().first().remoteId).episodes
        assertEquals(listOf("http://h/series/u/p/20.mp4", "http://h/series/u/p/21.mp4"), eps.map { it.remoteId })
    }

    @Test
    fun headerSuffixIsNotPartOfTheSyncKey() = runTest { // a header change must not re-key (and drop) the user's channel data
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1 http-user-agent=\"UA/2\" group-title=\"News\",A\nhttp://h/live/a.m3u8\n#EXTINF:-1,F\nhttp://h/f.mp4|Referer=https://s/\n"))
        val src = M3uSource(server.url("/list.m3u").toString(), emptyList(), OkHttpClient(), null)
        val ch = src.liveChannels().single()
        assertEquals("http://h/live/a.m3u8", ch.remoteId)
        assertEquals("http://h/live/a.m3u8|User-Agent=UA%2F2", ch.streamUrl)
        assertEquals("http://h/f.mp4", src.movies().single().remoteId)
    }
}
