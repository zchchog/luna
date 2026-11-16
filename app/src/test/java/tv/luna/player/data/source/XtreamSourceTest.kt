package tv.luna.player.data.source

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import tv.luna.player.data.model.Channel
import tv.luna.player.data.model.StreamFormat

class XtreamSourceTest {
    private val server = MockWebServer()
    private var authFixture = "auth_ok.json"
    private var authCode = 200

    private fun fixture(name: String) = javaClass.getResource("/xtream/$name")!!.readText()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath != "/player_api.php") return MockResponse().setResponseCode(404)
                return when (url.queryParameter("action")) {
                    null -> MockResponse().setResponseCode(authCode).setBody(fixture(authFixture))
                    "get_live_categories" -> MockResponse().setBody(fixture("live_categories.json"))
                    "get_live_streams" -> MockResponse().setBody(fixture("live_streams.json"))
                    "get_vod_streams" -> MockResponse().setBody(fixture("vod_streams.json"))
                    "get_series_info" -> MockResponse().setBody(fixture("series_info.json"))
                    else -> MockResponse().setBody("[]")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun source(format: StreamFormat = StreamFormat.TS) =
        XtreamSource(base, "u", "p", format, OkHttpClient(), xtreamJson(), userAgent = null)

    private suspend fun channel(id: String, format: StreamFormat = StreamFormat.TS): Channel =
        source(format).liveChannels().single { it.remoteId == id }

    @Test
    fun mapsLiveStreams() = runTest {
        val c = channel("101")
        assertEquals("5", c.categoryRemoteId)
        assertEquals(1, c.number)
        assertEquals("bbc1.uk", c.tvgId)
        assertEquals("BBC One HD", c.name)
        assertEquals("http://l/101.png", c.logo)
        assertTrue(c.streamUrl, c.streamUrl.endsWith("/live/~iptvu~/~iptvp~/101.ts"))
    }

    @Test
    fun lenientFields() = runTest {
        val c = channel("102")
        assertNull(c.logo)
        assertNull(c.number)
        assertNull(c.tvgId)
        val noExt = source().movies().single { it.remoteId == "502" }
        assertNull(noExt.rating)
        assertNull(noExt.poster)
        assertNull(noExt.categoryRemoteId)
    }

    @Test
    fun categoryIdsAsNumbersOrStrings() = runTest {
        assertEquals(listOf("5", "6"), source().liveCategories().map { it.remoteId })
    }

    @Test
    fun mapsMovies() = runTest {
        val m = source().movies().single { it.remoteId == "501" }
        assertEquals(7.4, m.rating!!, 0.001)
        assertEquals(2021, m.year)
        assertTrue(m.streamUrl!!.endsWith("/movie/~iptvu~/~iptvp~/501.mkv"))
    }

    @Test
    fun hlsFormat() = runTest {
        assertTrue(channel("101", StreamFormat.HLS).streamUrl.endsWith("/live/~iptvu~/~iptvp~/101.m3u8"))
    }

    @Test
    fun seriesInfo() = runTest {
        val d = source().seriesDetail("77")
        assertEquals(5, d.episodes.size) // fixture repeats 8001: duplicates dropped
        val s2e1 = d.episodes.single { it.season == 2 && it.number == 1 }
        assertTrue(s2e1.streamUrl, s2e1.streamUrl.endsWith("/series/~iptvu~/~iptvp~/9001.mkv"))
        assertEquals(2800, s2e1.durationSecs)
        assertEquals("http://b/1.jpg", d.backdrop)
        assertEquals("Harbour Lights", d.series.name)
        assertEquals(2019, d.series.year)
        assertEquals(2, d.episodes.single { it.remoteId == "8002" }.number)
    }

    @Test
    fun epgUrl() {
        assertEquals(listOf("$base/xmltv.php?username=%7Eiptvu%7E&password=%7Eiptvp%7E"), source().epgUrls())
    }

    @Test
    fun credentialsAreUrlEncoded() = runTest {
        val src = XtreamSource(base, "u ser", "p&s/1#", StreamFormat.TS, OkHttpClient(), xtreamJson(), userAgent = null)
        val c = src.liveChannels().single { it.remoteId == "101" }
        // Saved links hold placeholders; the login is filled in (encoded) at play time.
        val played = tv.luna.player.data.security.withLogin(c.streamUrl, tv.luna.player.data.security.Login("u ser", "p&s/1#"))
        assertTrue(played, played.endsWith("/live/u%20ser/p&s%2F1%23/101.ts"))
        assertEquals("p&s/1#", okhttp3.HttpUrl.Companion.run { played.toHttpUrl() }.pathSegments[2])
        val login = tv.luna.player.data.security.Login("u ser", "p&s/1#")
        val epg = okhttp3.HttpUrl.Companion.run { tv.luna.player.data.security.withLogin(src.epgUrls().single(), login).toHttpUrl() }
        assertEquals("p&s/1#", epg.queryParameter("password"))
        assertEquals("u ser", epg.queryParameter("username"))
        val ep = src.seriesDetail("77").episodes.first()
        assertEquals("p&s/1#", okhttp3.HttpUrl.Companion.run { tv.luna.player.data.security.withLogin(ep.streamUrl, login).toHttpUrl() }.pathSegments[2])
    }

    @Test
    fun authFailure() = runTest {
        authFixture = "auth_fail.json"
        try {
            source().liveCategories()
            fail("expected XtreamAuthException")
        } catch (e: XtreamAuthException) {
            // expected
        }
    }

    @Test
    fun http401IsAuthFailure() = runTest {
        authCode = 401
        try {
            source().liveChannels()
            fail("expected XtreamAuthException")
        } catch (e: XtreamAuthException) {
            // expected
        }
    }

    @Test fun maxConnectionsReadsUserInfo() = runTest {
        authFixture = "auth_limit2.json"
        assertEquals(2, source().maxConnections())
    }

    @Test fun maxConnectionsNullOnErrorOrZero() = runTest {
        authFixture = "auth_limit0.json"
        assertNull(source().maxConnections())
        authCode = 500
        assertNull(source().maxConnections())
        authFixture = "auth_ok.json"; authCode = 200
        assertNull(source().maxConnections()) // field missing
    }
}
