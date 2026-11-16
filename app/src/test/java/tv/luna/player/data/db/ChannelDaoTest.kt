package tv.luna.player.data.db

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.model.Kind

@RunWith(RobolectricTestRunner::class)
class ChannelDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    @Test
    fun observeByCategoryOrdersByEffectiveNumberAndExcludesHidden() = runTest {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "1", name = "News", sortOrder = 0)))
        val catId = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id

        fun ch(remoteId: String, number: Int?, customNumber: Int? = null, hidden: Boolean = false) =
            ChannelEntity(providerId = p, categoryId = catId, remoteId = remoteId, name = "Ch$remoteId",
                logo = null, number = number, customNumber = customNumber, hidden = hidden,
                tvgId = null, streamUrl = "http://x/$remoteId")

        db.channelDao().upsert(listOf(ch("three", 3), ch("one", 1), ch("custom", 7, customNumber = 0), ch("hid", 2, hidden = true)))

        val ids = db.channelDao().observeByCategory(catId).first().map { it.remoteId }

        assertEquals(listOf("custom", "one", "three"), ids)
    }

    @Test
    fun countsAndGuideVersionEmitOnChange() = runBlocking {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "1", name = "News")))
        val cat = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id
        val counts = Channel<List<CategoryCount>>(Channel.UNLIMITED)
        val versions = Channel<Long>(Channel.UNLIMITED)
        val jobs = listOf(
            launch(Dispatchers.IO) { db.channelDao().observeCounts(p).collect { counts.send(it) } },
            launch(Dispatchers.IO) { db.epgDao().observeGuideVersion().collect { versions.send(it) } },
        )
        withTimeout(5_000) {
            assertEquals(emptyList<CategoryCount>(), counts.receive())
            val v0 = versions.receive()
            db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = cat, remoteId = "a", name = "A",
                logo = null, number = null, tvgId = null, streamUrl = "http://x/a")))
            assertEquals(listOf(CategoryCount(cat, 1)), counts.receive())
            val ch = db.channelDao().byProvider(p).single().id
            val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0))
            db.epgDao().upsertLinks(listOf(EpgLinkEntity(ch, s, "x", manual = false)))
            var v = versions.receive()
            while (v == v0) v = versions.receive()
        }
        jobs.forEach { it.cancel() }
    }
}
