package tv.luna.player.recording

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import tv.luna.player.data.source.StreamRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class StreamCaptureTest {
    private val server = MockWebServer()
    private var now = 0L
    private val deadline = 3_600_000L
    private val MiB = 1 shl 20
    private val out = ByteArrayOutputStream()
    private var route: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = route(request) }
        server.start()
    }
    @After fun tearDown() = server.shutdown()

    private fun capture(retryForMs: Long = 60_000) = StreamCapture(OkHttpClient(), clock = { now }, sleep = { now += it }, retryForMs = retryForMs)
    private fun req(path: String) = StreamRequest(server.url(path).toString(), emptyMap())
    private fun bytes(n: Int, fill: Int) = ByteArray(n) { fill.toByte() }
    private fun body(b: ByteArray) = MockResponse().setBody(Buffer().write(b))
    private suspend fun run(path: String, sink: OutputStream = out, free: () -> Long? = { null }, stopAt: Long = Long.MAX_VALUE) =
        capture().capture(req(path), sink, deadline, free) { if (it >= stopAt) now = deadline }

    @Test fun tsBodyIsCopiedUntilDeadline() = runTest {
        val data = bytes(3 * MiB, 7)
        route = { body(data) }
        assertEquals(CaptureEnd.Finished, run("/live/1.ts", stopAt = 3L * MiB))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun cancelStopsEndlessTsBodyPromptlyAndKeepsBytes() {
        route = { body(bytes(10 * MiB, 9)).throttleBody(64 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS) }
        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { run("/live/1.ts") }
            var waited = 0
            while (out.size() == 0 && waited++ < 100) Thread.sleep(50)
            job.cancel()
            kotlinx.coroutines.withTimeout(3_000) { job.join() }
        }
        assertTrue(out.size() > 0)
        assertTrue(out.size() < 10 * MiB)
    }

    @Test fun dropAfterDataResumesIntoSameFile() = runTest {
        var n = 0
        route = { body(bytes(MiB, ++n)) }
        assertEquals(CaptureEnd.Finished, run("/live/1.ts", stopAt = 2L * MiB))
        assertArrayEquals(bytes(MiB, 1) + bytes(MiB, 2), out.toByteArray())
    }

    @Test fun dropAfterDataRetriesThenStreamLost() = runTest {
        var n = 0
        route = { if (n++ == 0) body(bytes(MiB, 1)) else MockResponse().setResponseCode(503) }
        assertEquals(CaptureEnd.StreamLost, run("/live/1.ts"))
        assertEquals(MiB, out.size())
        assertTrue(now in 60_000L..80_000L)
    }

    @Test fun firstRequestErrorIsRefused() = runTest {
        route = { MockResponse().setResponseCode(403) }
        assertEquals(CaptureEnd.Refused(403), run("/live/1.ts"))
    }

    @Test fun emptyFirstResponseIsRefused() = runTest {
        route = { MockResponse().setBody("") }
        assertEquals(CaptureEnd.Refused(null), run("/live/1.ts"))
    }

    @Test fun noSpaceOnWriteIsStorageFull() = runTest {
        route = { body(bytes(MiB, 1)) }
        val full = object : OutputStream() { override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)") }
        assertEquals(CaptureEnd.StorageFull, run("/live/1.ts", sink = full))
    }

    @Test fun otherWriteErrorIsWriteError() = runTest {
        route = { body(bytes(MiB, 1)) }
        val bad = object : OutputStream() { override fun write(b: Int) = throw IOException("Share went away") }
        assertEquals(CaptureEnd.WriteError("Share went away"), run("/live/1.ts", sink = bad))
    }

    @Test fun lowFreeSpaceIsStorageFull() = runTest {
        route = { body(bytes(MiB, 1)) }
        assertEquals(CaptureEnd.StorageFull, run("/live/1.ts", free = { 10L * MiB }))
    }

    @Test fun mpdIsUnsupported() = runTest {
        assertEquals(CaptureEnd.Unsupported("DASH"), run("/live/manifest.mpd"))
    }

    // --- HLS ---
    private fun media(seqs: LongRange, ended: Boolean = false, prefix: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${seqs.first}\n")
        seqs.forEach { append("#EXTINF:2,\n${prefix}s$it.ts\n") }
        if (ended) append("#EXT-X-ENDLIST\n")
    }
    private fun segment(r: RecordedRequest): MockResponse? =
        Regex("s(\\d+)\\.ts$").find(r.path!!.substringBefore('?'))?.let { body(bytes(1000, it.groupValues[1].toInt())) }

    @Test fun hlsAppendsNewSegmentsOnceEach() = runTest {
        var polls = 0
        route = { r -> segment(r) ?: MockResponse().setBody(if (polls++ == 0) media(1L..3) else media(2L..4, ended = true)) }
        assertEquals(CaptureEnd.StreamLost, run("/hls/index.m3u8")) // ENDLIST after data: the channel stopped
        assertArrayEquals((1..4).map { bytes(1000, it) }.reduce(ByteArray::plus), out.toByteArray())
    }

    @Test fun hlsMasterFollowsHighestVariant() = runTest {
        route = { r ->
            segment(r) ?: when (r.path) {
                "/hls/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlo/i.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhi/i.m3u8\n")
                "/hls/hi/i.m3u8" -> MockResponse().setBody(media(5L..5, ended = true))
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertEquals(CaptureEnd.StreamLost, run("/hls/master.m3u8"))
        assertEquals("/hls/hi/s5.ts", server.takeRequests().last())
    }

    @Test fun hlsSegmentsResolveAgainstRedirectedPlaylist() = runTest {
        route = { r ->
            segment(r) ?: when (r.path) {
                "/live/x.m3u8" -> MockResponse().setResponseCode(302).setHeader("Location", "/other/index.m3u8")
                "/other/index.m3u8" -> MockResponse().setBody(media(1L..1, ended = true))
                else -> MockResponse().setResponseCode(404)
            }
        }
        run("/live/x.m3u8")
        assertEquals("/other/s1.ts", server.takeRequests().last())
    }

    @Test fun hlsSegmentFailureMidPlaylistDoesNotDuplicate() = runTest {
        var polls = 0
        var s2 = 0
        route = { r ->
            if (r.path!!.endsWith("s2.ts") && s2++ == 0) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else segment(r) ?: MockResponse().setBody(if (polls++ == 0) media(1L..3) else media(1L..3, ended = true))
        }
        StreamCapture(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), clock = { now }, sleep = { now += it })
            .capture(req("/hls/index.m3u8"), out, deadline, { null }) {}
        assertArrayEquals((1..3).map { bytes(1000, it) }.reduce(ByteArray::plus), out.toByteArray())
    }

    @Test fun hlsFallsBackToOriginalUrlAfterFailedPoll() = runTest {
        var orig = 0
        route = { r ->
            segment(r) ?: when (r.path) {
                "/live/x.m3u8" -> if (orig++ == 0) MockResponse().setResponseCode(302).setHeader("Location", "/tok/index.m3u8") else MockResponse().setBody(media(2L..2, ended = true))
                "/tok/index.m3u8" -> MockResponse().setBody(media(1L..1)).also { route = { r2 -> segment(r2) ?: if (r2.path == "/live/x.m3u8") MockResponse().setBody(media(2L..2, ended = true)) else MockResponse().setResponseCode(403) } }
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertEquals(CaptureEnd.StreamLost, run("/live/x.m3u8"))
        assertArrayEquals(bytes(1000, 1) + bytes(1000, 2), out.toByteArray())
    }

    @Test fun sequenceResetKeepsAppending() = runTest {
        var polls = 0
        route = { r -> segment(r) ?: MockResponse().setBody(if (polls++ == 0) media(50L..52) else media(1L..3, ended = true)) }
        run("/hls/index.m3u8")
        assertEquals(6000, out.size())
    }

    @Test fun hlsStopsAtDeadline() = runTest {
        var seq = 0L
        route = { r -> segment(r) ?: MockResponse().setBody(media(seq..seq + 2).also { seq++ }) }
        assertEquals(CaptureEnd.Finished, capture().capture(req("/hls/index.m3u8"), out, deadline = 20_000, freeBytes = { null }) {})
        assertTrue(now >= 20_000)
    }

    @Test fun encryptedHlsIsUnsupported() = runTest {
        route = { MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n") }
        assertEquals(CaptureEnd.Unsupported("encrypted"), run("/hls/index.m3u8"))
    }

    private fun MockWebServer.takeRequests(): List<String> = generateSequence { takeRequest(0, java.util.concurrent.TimeUnit.SECONDS)?.path }.toList()
}
