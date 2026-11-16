package tv.luna.player.data.logos

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class LogoPackTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private var clock = 1_000L
    private lateinit var repo: LogoPackRepository

    @Before fun setUp() { db = inMemoryDb(); dir = tmp.newFolder("logos"); repo = LogoPackRepository(db, dir) { clock++ } }
    @After fun tearDown() { db.close() }

    private fun ch(id: Long, name: String, tvgId: String? = null) = ChannelEntity(id = id, providerId = 1, categoryId = null,
        remoteId = "$id", name = name, logo = null, number = id.toInt(), tvgId = tvgId, streamUrl = "http://s/$id")

    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); b?.let(z::write); z.closeEntry() } }
    }.toByteArray()

    private fun img(seed: Int = 0) = Random(seed).nextBytes(64)

    private suspend fun insert(p: Long, vararg names: String) = db.channelDao().upsert(names.mapIndexed { i, n ->
        ChannelEntity(providerId = p, categoryId = null, remoteId = n, name = n, logo = null, number = i, tvgId = null, streamUrl = "http://s/$n")
    })

    private suspend fun pack(p: Long, name: String) = db.channelDao().byProvider(p).single { it.remoteId == name }.packLogo

    @Test fun matchesByTvgIdThenName() {
        val chs = listOf(ch(1, "UK: BBC One HD", tvgId = "bbc1.uk"), ch(2, "Sport hevc"), ch(3, "Sport RAW"), ch(4, "Sky News"))
        val m = matchPack(listOf("BBC1.UK.png", "bbc-one.png", "sport.jpg", "sky-news-hd.png", "sky-news.webp"), chs)
        assertEquals("BBC1.UK.png", m[1]); assertEquals("sport.jpg", m[2]); assertEquals("sport.jpg", m[3]); assertEquals("sky-news.webp", m[4])
    }

    @Test fun importCountsAndStores() = runTest {
        val p = db.insertTestProvider(); insert(p, "BBC One", "ITV 1", "Dave")
        val r = repo.import(p) { ByteArrayInputStream(zip("bbc-one.png" to img(1), "itv-1.JPG" to img(2), "unused.webp" to img(3))) }
        assertEquals(PackResult(3, 2), r)
        val bbc = pack(p, "BBC One")!!
        assertTrue(File(bbc).exists()); assertTrue(bbc.startsWith(File(dir, "$p").absolutePath))
        assertTrue(pack(p, "ITV 1")!!.endsWith("itv-1.JPG")); assertNull(pack(p, "Dave"))
        assertEquals(2, repo.observeCount(p).first())
    }

    @Test fun zipSlipAndJunkEntriesIgnored() = runTest {
        val p = db.insertTestProvider(); insert(p, "Evil")
        val r = repo.import(p) {
            ByteArrayInputStream(zip("../../evil.png" to img(), "__MACOSX/._a.png" to img(), "._b.png" to img(), "notes.txt" to img(), "sub/" to null))
        }
        assertEquals(PackResult(1, 1), r)
        val evil = File(pack(p, "Evil")!!)
        assertEquals("evil.png", evil.name); assertEquals(File(dir, "$p").absolutePath, evil.parentFile!!.parentFile!!.absolutePath)
        assertEquals(listOf("evil.png"), evil.parentFile!!.list()!!.toList())
        assertFalse(File(dir.parentFile, "evil.png").exists())
    }

    @Test fun emptyOrCorruptZipChangesNothing() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        repo.import(p) { ByteArrayInputStream(zip("dave.png" to img())) }
        val before = pack(p, "Dave")!!

        val empty = runCatching { repo.import(p) { ByteArrayInputStream(zip("readme.txt" to img())) } }.exceptionOrNull()
        assertEquals("No images in this zip", (empty as PackException).message)
        val big = zip("big.png" to Random(9).nextBytes(20_000))
        val corrupt = runCatching { repo.import(p) { ByteArrayInputStream(big.copyOf(1000)) } }.exceptionOrNull()
        assertEquals("This zip could not be read", (corrupt as PackException).message)

        assertEquals(before, pack(p, "Dave")); assertTrue(File(before).exists())
        assertEquals(1, File(dir, "$p").list()!!.size)
    }

    @Test fun reimportReplacesPreviousPack() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        repo.import(p) { ByteArrayInputStream(zip("dave.png" to img(1))) }
        val old = pack(p, "Dave")!!
        repo.import(p) { ByteArrayInputStream(zip("dave.png" to img(2))) }
        val new = pack(p, "Dave")!!
        assertNotEquals(old, new)
        assertFalse(File(old).exists()); assertFalse(File(old).parentFile!!.exists())
        assertTrue(File(new).readBytes().contentEquals(img(2)))
    }

    /** Review: a double OK starts two imports; the result must point at files that exist. */
    @Test fun concurrentImportsLeaveAWorkingPack() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        val big = { seed: Int -> zip("dave.png" to img(seed), *Array(200) { "x$it.png" to Random(it).nextBytes(2000) }) }
        val a = async(kotlinx.coroutines.Dispatchers.Default) { repo.import(p) { ByteArrayInputStream(big(1)) } }
        val b = async(kotlinx.coroutines.Dispatchers.Default) { repo.import(p) { ByteArrayInputStream(big(2)) } }
        a.await(); b.await()
        assertTrue(File(pack(p, "Dave")!!).exists())
        assertEquals(1, File(dir, "$p").list()!!.size)
    }

    @Test fun packRematchedAfterSync() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        repo.import(p) { ByteArrayInputStream(zip("dave.png" to img(), "quest.png" to img(1))) }
        insert(p, "Quest")   // a later sync adds it
        assertNull(pack(p, "Quest"))
        repo.rematch(p)
        assertTrue(pack(p, "Quest")!!.endsWith("quest.png"))
    }

    @Test fun rematchWithoutPackIsANoOp() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        repo.rematch(p)
        assertNull(pack(p, "Dave"))
    }

    @Test fun removeClearsFieldsAndFiles() = runTest {
        val p = db.insertTestProvider(); insert(p, "Dave")
        repo.import(p) { ByteArrayInputStream(zip("dave.png" to img())) }
        repo.remove(p)
        assertNull(pack(p, "Dave")); assertFalse(File(dir, "$p").exists())
        assertEquals(0, repo.observeCount(p).first())
        runCatching { repo.rematch(p) }.onFailure { fail("rematch after remove: $it") }
    }
}
