package tv.luna.player.data.logos

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogoRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private val fixture = javaClass.getResource("/tvlogos/tree.json")!!.readText()
    private val day = 24 * 3600_000L
    private var clock = 1_000_000_000_000L

    @Before fun setUp() { db = inMemoryDb(); server = MockWebServer(); server.start() }
    @After fun tearDown() { db.close(); server.shutdown() }

    private fun repo(dir: File = tmp.root) =
        LogoRepository(db, OkHttpClient(), dir, now = { clock }, treeUrl = server.url("/tree").toString())

    private suspend fun channel(name: String, country: String? = "GB", builtin: String? = null): Long {
        val p = db.providerDao().all().firstOrNull()?.id ?: db.insertTestProvider()
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "c$name", name = "x", country = country)))
        val cat = db.categoryDao().allKinds(p).single { it.remoteId == "c$name" }.id
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = cat, remoteId = name, name = name, logo = null,
            number = 1, tvgId = null, streamUrl = "http://s/$name", builtinLogo = builtin)))
        return p
    }

    private suspend fun builtin(p: Long, name: String) = db.channelDao().byProvider(p).single { it.remoteId == name }.builtinLogo

    @Test fun refreshBuiltinWritesAndClears() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val p = channel("UK: Sky Sports Main Event ᵁᴴᴰ")
        val r = repo()
        assertEquals(1, r.refreshBuiltin(p))
        assertEquals("countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png", builtin(p, "UK: Sky Sports Main Event ᵁᴴᴰ"))

        val ch = db.channelDao().byProvider(p).single()
        db.channelDao().update(ch.copy(name = "Nothing Like It"))
        assertEquals(0, r.refreshBuiltin(p))
        assertNull(builtin(p, "UK: Sky Sports Main Event ᵁᴴᴰ"))
    }

    @Test fun cacheUsedForAWeek() = runTest {
        server.enqueue(MockResponse().setBody(fixture)); server.enqueue(MockResponse().setBody(fixture))
        val r = repo()
        assertNotNull(r.index()); assertEquals(1, server.requestCount)
        clock += 6 * day
        assertNotNull(repo().index()); assertEquals(1, server.requestCount)
        clock += day
        assertNotNull(repo().index()); assertEquals(2, server.requestCount)
    }

    @Test fun staleCacheUsedOn403() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        repo().index()
        clock += 8 * day
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        val idx = repo().index()
        assertEquals(2, server.requestCount)
        assertEquals(10, idx!!.size)
    }

    @Test fun noCacheAndFetchFailsLeavesBuiltinUntouched() = runTest {
        val p = channel("BBC One", builtin = "x")
        server.shutdown()
        assertEquals(0, repo().refreshBuiltin(p))
        assertEquals("x", builtin(p, "BBC One"))
    }

    @Test fun customFileCopiedAndOldOneDeleted() = runTest {
        val p = channel("Dave")
        val id = db.channelDao().byProvider(p).single().id
        val r = repo()
        r.setCustomFromFile(id, { "one".byteInputStream() }, "png")
        val first = db.channelDao().get(id)!!.customLogo!!
        clock += 1
        r.setCustomFromFile(id, { "two".byteInputStream() }, "jpg")
        val second = db.channelDao().get(id)!!.customLogo!!
        assertNotEquals(first, second)
        assertEquals(listOf(File(second).name), File(tmp.root, "custom").list()!!.toList())
        assertEquals("two", File(second).readText())
    }

    @Test fun useAutomaticClearsCustom() = runTest {
        val p = channel("Dave")
        val id = db.channelDao().byProvider(p).single().id
        val r = repo()
        r.setCustomFromFile(id, { "one".byteInputStream() }, "png")
        val file = File(db.channelDao().get(id)!!.customLogo!!)
        r.clearCustom(id)
        assertNull(db.channelDao().get(id)!!.customLogo); assertFalse(file.exists())

        r.setCustomUrl(id, TV_LOGOS_RAW + "countries/united-kingdom/dave-uk.png")
        assertEquals(TV_LOGOS_RAW + "countries/united-kingdom/dave-uk.png", db.channelDao().get(id)!!.customLogo)
        r.clearCustom(id)
        assertNull(db.channelDao().get(id)!!.customLogo)
    }

    @Test fun candidatesForGivesUrls() = runTest {
        server.enqueue(MockResponse().setBody(fixture))
        val p = channel("UK: Sky News")
        assertEquals(TV_LOGOS_RAW + "countries/united-kingdom/sky-news.png", repo().candidatesFor(db.channelDao().byProvider(p).single()).first())
    }
}
