package tv.luna.player.data.epg

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class EpgImporterTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private val now = Instant.parse("2026-11-08T18:15:00Z").toEpochMilli()

    @Before fun setUp() { server.start(); db = inMemoryDb() }
    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun source(path: String): EpgSourceEntity {
        val p = db.insertTestProvider()
        val id = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = server.url(path).toString(), priority = 0))
        return db.epgDao().source(id)!!
    }

    private fun importer(sink: ((List<ProgrammeEntity>) -> Unit)? = null) =
        EpgImporter(db, OkHttpClient(), XmltvParser(), now = { now }, programmeSink = sink)

    private fun fixture() = javaClass.getResource("/xmltv/guide.xml")!!.readBytes()

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(bytes) } }.toByteArray()

    private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    private fun generated(count: Int, startMs: Long, stepMs: Long = 60_000): String = buildString {
        append("<tv><channel id=\"c\"><display-name>C</display-name></channel>")
        for (i in 0 until count) {
            val s = startMs + i * stepMs
            append("<programme start=\"${fmt.format(Instant.ofEpochMilli(s))} +0000\" stop=\"${fmt.format(Instant.ofEpochMilli(s + stepMs))} +0000\" channel=\"c\"><title>P$i</title></programme>")
        }
        append("</tv>")
    }

    @Test
    fun importsPlainFixture() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        val src = source("/guide.xml")
        val n = importer().import(src, daysToKeep = 7, pastDays = 1)
        assertEquals(3, n)
        val rows = db.programmeDao().window(listOf(ProgrammeEntity.key(src.id, "bbc1.uk")), 0, Long.MAX_VALUE)
        assertEquals(listOf("News at Six", "Regional News"), rows.map { it.title })
        val names = db.epgDao().channels(src.id).filter { it.xmltvId == "bbc1.uk" }
        assertEquals(setOf("BBC One", "BBC One HD"), names.map { it.displayName }.toSet())
        assertEquals("bbc one hd", names.single { it.displayName == "BBC One HD" }.normalisedName)
    }

    @Test
    fun gzipDetected() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(gzip(fixture()))))
        val src = source("/guide.xml")
        importer().import(src, daysToKeep = 7, pastDays = 1)
        assertEquals(3, db.programmeDao().count())
    }

    @Test
    fun batchesInserts() = runTest {
        server.enqueue(MockResponse().setBody(generated(4500, now)))
        val src = source("/big.xml")
        val batches = mutableListOf<Int>()
        val dao = db.programmeDao()
        importer { batch -> batches += batch.size; dao.insertAllBlocking(batch) }.import(src, daysToKeep = 7, pastDays = 1, batchSize = 2000)
        assertEquals(4500, dao.count())
        assertEquals(listOf(2000, 2000, 500), batches)
    }

    @Test
    fun retentionWindow() = runTest {
        val day = 86_400_000L
        val old = generated(2, now - 10 * day).removeSuffix("</tv>").removePrefix("<tv>")
        val current = generated(2, now).removeSuffix("</tv>").removePrefix("<tv>").substringAfter("</channel>")
        val farFuture = generated(2, now + 9 * day).removeSuffix("</tv>").removePrefix("<tv>").substringAfter("</channel>")
        server.enqueue(MockResponse().setBody("<tv>$old$current$farFuture</tv>"))
        val src = source("/g.xml")
        val n = importer().import(src, daysToKeep = 7, pastDays = 1)
        assertEquals(2, n)
        assertEquals(2, db.programmeDao().count())
    }

    @Test
    fun replacesPreviousImport() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        val src = source("/guide.xml")
        importer().import(src, daysToKeep = 7, pastDays = 1)
        importer().import(src, daysToKeep = 7, pastDays = 1)
        assertEquals(3, db.programmeDao().count())
        assertEquals(3, db.epgDao().channels(src.id).size)
        assertEquals(now, db.epgDao().source(src.id)!!.lastFetched)
    }

    @Test
    fun channelWithoutDisplayNameKeepsItsId() = runTest {
        server.enqueue(MockResponse().setBody("<tv><channel id=\"bare.id\"/><channel id=\"empty.id\"><display-name> </display-name></channel></tv>"))
        val src = source("/g.xml")
        importer().import(src, daysToKeep = 7, pastDays = 1)
        assertEquals(setOf("bare.id", "empty.id"), db.epgDao().channels(src.id).map { it.xmltvId }.toSet())
    }

    @Test
    fun failedDownloadKeepsPreviousData() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        server.enqueue(MockResponse().setBody("<tv><programme start=\"20261108180000\" stop=\"20261108183000\" channel=\"x\"><title>T</title>"))
        val src = source("/guide.xml")
        importer().import(src, daysToKeep = 7, pastDays = 1)
        runCatching { importer().import(src, daysToKeep = 7, pastDays = 1) }
        assertEquals(3, db.programmeDao().count())
    }

    @Test
    fun guideKeepsTheOldImportUntilTheNewOneIsComplete() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        val src = source("/guide.xml")
        importer().import(src, daysToKeep = 7, pastDays = 1)
        fun shown() = db.openHelper.readableDatabase.query(
            "SELECT count(*) FROM programme p WHERE gen = (SELECT gen FROM epg_source WHERE id = p.sourceId)",
        ).use { it.moveToFirst(); it.getInt(0) }
        val seenDuringImport = mutableListOf<Int>()
        importer { batch -> db.programmeDao().insertAllBlocking(batch); seenDuringImport += shown() }
            .import(src, daysToKeep = 7, pastDays = 1, batchSize = 1)
        assertEquals(listOf(3, 3, 3), seenDuringImport) // the old 3 throughout, never a mix
        assertEquals(3, shown())
        assertEquals(3, db.programmeDao().count()) // the old generation is gone afterwards
    }

    @Test
    fun logUrlDropsCredentials() {
        assertEquals("http://h:8080/xmltv.php", EpgImporter.logUrl("http://h:8080/xmltv.php?username=alice&password=s3cret"))
        assertEquals("http://e/guide.xml.gz", EpgImporter.logUrl("http://e/guide.xml.gz"))
    }
}
