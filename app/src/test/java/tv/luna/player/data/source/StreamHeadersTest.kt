package tv.luna.player.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamHeadersTest {
    @Test fun plainUrlHasNoHeaders() =
        assertEquals("http://h/a.m3u8" to emptyMap<String, String>(), splitStreamHeaders("http://h/a.m3u8"))

    @Test fun kodiSuffixGivesHeaders() = assertEquals(
        "http://h/a.m3u8?x=1" to mapOf("User-Agent" to "Mozilla/5.0 (X)", "Referer" to "https://site/"),
        splitStreamHeaders("http://h/a.m3u8?x=1|User-Agent=Mozilla%2F5.0%20(X)&referrer=https://site/"))

    @Test fun headersRoundTrip() {
        val h = mapOf("User-Agent" to "VLC/3.0 & co", "Referer" to "https://a.b/c?d=e")
        assertEquals("http://h/s.ts" to h, splitStreamHeaders(withStreamHeaders("http://h/s.ts", h)))
    }

    @Test fun streamRequestFillsLoginAndHeaders() {
        val login = tv.luna.player.data.security.Login("bob", "pw")
        val r = streamRequest("http://h/live/~iptvu~/~iptvp~/1.ts|User-Agent=x&Referer=r", userAgent = "playlist", login = login)
        assertEquals("http://h/live/bob/pw/1.ts", r.url)
        assertEquals(mapOf("User-Agent" to "x", "Referer" to "r"), r.headers)
        assertEquals(mapOf("User-Agent" to "playlist"), streamRequest("http://h/1.ts", "playlist", null).headers)
        assertEquals(emptyMap<String, String>(), streamRequest("http://h/1.ts", null, null).headers)
    }
}
