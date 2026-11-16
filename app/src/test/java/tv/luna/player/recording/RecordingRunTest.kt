package tv.luna.player.recording

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.StreamFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecordingRunTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private val server = MockWebServer()
    private var now = 1_000_000_000L
    private val MiB = 1 shl 20

    @Before fun setUp() { db = inMemoryDb(); server.start() }
    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun recording(path: String = "/live/~iptvu~/~iptvp~/1.ts"): Long {
        val p = db.providerDao().insert(ProviderEntity(name = "p", type = ProviderType.XTREAM, url = server.url("/").toString(),
            username = "secretuser", password = "secretpass", userAgent = "UA", streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = null))
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "c", name = "c")))
        val cat = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = cat, remoteId = "1", name = "BBC One", logo = null,
            number = 1, tvgId = null, streamUrl = server.url(path).toString())))
        val ch = db.channelDao().byProvider(p).single()
        return db.recordingDao().insert(RecordingEntity(providerId = p, channelId = ch.id, channelName = "BBC One", channelLogo = null,
            title = "News", description = null, scheduledStart = now, scheduledEnd = now + 3_600_000, programmeStart = null))
    }

    private fun store() = FileStore(RecStorage.INTERNAL, File(tmp.root, "rec"))
    private fun capture() = StreamCapture(OkHttpClient(), clock = { now }, sleep = { now += it })
    private suspend fun run(id: Long, others: Boolean = false) = runRecording(id, db, store(), capture(), otherStreams = { others }, now = { now })

    @Test fun finishedRunIsDoneWithBytesAndLocation() = runTest {
        val id = recording()
        var served = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (served++ > 0) now += 3_600_000 // the programme has ended by the time the stream is reopened
                return MockResponse().setBody(Buffer().write(ByteArray(MiB) { 1 }))
            }
        }
        assertEquals(RecStatus.DONE, run(id))
        val r = db.recordingDao().get(id)!!
        assertEquals(RecStatus.DONE, r.status); assertEquals(RecStorage.INTERNAL, r.storage)
        assertTrue(File(r.location!!).length() > 0); assertEquals(File(r.location!!).length(), r.bytes)
        assertEquals(1_000_000_000L, r.actualStart)
        assertEquals("UA", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun lostStreamIsStoppedAndKeepsFile() = runTest {
        val id = recording()
        var n = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (n++ == 0) MockResponse().setBody(Buffer().write(ByteArray(MiB) { 1 })) else MockResponse().setResponseCode(503)
        }
        assertEquals(RecStatus.STOPPED, run(id))
        val r = db.recordingDao().get(id)!!
        assertEquals("Stopped — stream lost", r.statusReason); assertEquals(MiB.toLong(), r.bytes); assertTrue(File(r.location!!).exists())
    }

    @Test fun refusedFirstRequestFailsAndLeavesNoFile() = runTest {
        val id = recording()
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(RecStatus.FAILED, run(id, others = true))
        val r = db.recordingDao().get(id)!!
        assertEquals("Failed — provider refused a second stream", r.statusReason); assertNull(r.location)
        assertEquals(0, File(tmp.root, "rec").listFiles().orEmpty().size)
    }

    @Test fun refusedAloneSaysChannelDidNotStart() = runTest {
        val id = recording()
        server.enqueue(MockResponse().setResponseCode(404))
        run(id)
        assertEquals("Failed — the channel didn't start (HTTP 404)", db.recordingDao().get(id)!!.statusReason)
    }

    @Test fun fileNameHasNoUrlOrLogin() = runTest {
        val id = recording()
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        val stored = mutableListOf<String>()
        val spy = object : RecordingStore by store() { override fun create(fileName: String) = store().create(fileName).also { stored += it.first } }
        runRecording(id, db, spy, capture(), otherStreams = { false }, now = { now })
        assertTrue(server.takeRequest().path!!.contains("/secretuser/secretpass/")) // the login reaches the provider…
        val loc = stored.single()
        assertFalse(loc.contains("secret")); assertFalse(loc.contains("http")) // …but never the file name
    }
}
