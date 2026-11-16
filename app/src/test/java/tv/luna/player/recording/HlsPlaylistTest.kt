package tv.luna.player.recording

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    private val base = "http://h/live/ch/index.m3u8".toHttpUrl()

    @Test fun masterPicksVariants() {
        val m = parseHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000\nhttp://o/hi.m3u8\n", base) as Hls.Master
        assertEquals(listOf(800_000L, 5_000_000L), m.variants.map { it.bandwidth })
        assertEquals(listOf("http://h/live/ch/low.m3u8", "http://o/hi.m3u8"), m.variants.map { it.url.toString() })
    }

    @Test fun mediaReadsSequenceAndSegments() {
        val m = parseHls("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:6.0,\na.ts\n#EXTINF:6.0,\nb.ts\n#EXTINF:6.0,\nc.ts\n", base) as Hls.Media
        assertEquals(100L, m.firstSeq); assertEquals(6, m.targetSec); assertEquals(3, m.segments.size)
        assertFalse(m.ended); assertNull(m.unsupported)
    }

    @Test fun relativeSegmentsResolveAgainstBase() {
        val m = parseHls("#EXTM3U\n#EXTINF:4,\n../seg/1.ts?t=2\n#EXTINF:4,\n/abs/2.ts\n#EXT-X-ENDLIST\n", base) as Hls.Media
        assertEquals(listOf("http://h/live/seg/1.ts?t=2", "http://h/abs/2.ts"), m.segments.map { it.toString() })
        assertEquals(0L, m.firstSeq); assertTrue(m.ended)
    }

    @Test fun encryptedIsUnsupported() {
        assertEquals("encrypted", (parseHls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n", base) as Hls.Media).unsupported)
        assertNull((parseHls("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n", base) as Hls.Media).unsupported)
    }

    @Test fun fmp4IsUnsupported() =
        assertEquals("fMP4", (parseHls("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n", base) as Hls.Media).unsupported)

    @Test fun notHlsIsNull() = assertNull(parseHls("G@ binary", base))

    @Test fun crlfAndBomAreFine() {
        val m = parseHls("﻿#EXTM3U\r\n#EXT-X-MEDIA-SEQUENCE:7\r\n#EXTINF:4,\r\na.ts\r\n", base) as Hls.Media
        assertEquals(7L, m.firstSeq); assertEquals("http://h/live/ch/a.ts", m.segments.single().toString())
    }
}
