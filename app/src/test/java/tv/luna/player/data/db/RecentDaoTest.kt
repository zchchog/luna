package tv.luna.player.data.db

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
class RecentDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private suspend fun channels(p: Long, n: Int): List<ChannelEntity> {
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "c", name = "c", sortOrder = 0)))
        val cat = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id
        db.channelDao().upsert((1..n).map { ChannelEntity(providerId = p, categoryId = cat, remoteId = "$it", name = "$it", logo = null,
            number = null, tvgId = null, streamUrl = "http://s/$p/$it") })
        return db.channelDao().byProvider(p).sortedBy { it.remoteId.toInt() }
    }

    @Test fun capOfEightNewestFirst() = runTest {
        val p = db.insertTestProvider()
        val ch = channels(p, 10)
        ch.forEachIndexed { i, c -> db.recentDao().record(p, c.id, (i + 1).toLong()) }

        assertEquals((10 downTo 3).map { "$it" }, db.recentDao().observe(p).first().map { it.remoteId })
    }

    @Test fun rewatchMovesToFront() = runTest {
        val p = db.insertTestProvider()
        val (a, b) = channels(p, 2)
        db.recentDao().record(p, a.id, 1)
        db.recentDao().record(p, b.id, 2)
        db.recentDao().record(p, a.id, 3)

        assertEquals(listOf(a.id, b.id), db.recentDao().observe(p).first().map { it.id })
    }

    @Test fun capIsPerProvider() = runTest {
        val p = db.insertTestProvider("p")
        val q = db.insertTestProvider("q")
        val pc = channels(p, 9)
        val qc = channels(q, 2)
        qc.forEachIndexed { i, c -> db.recentDao().record(q, c.id, i.toLong()) }
        pc.forEachIndexed { i, c -> db.recentDao().record(p, c.id, 10L + i) }

        assertEquals(8, db.recentDao().observe(p).first().size)
        assertEquals(listOf(qc[1].id, qc[0].id), db.recentDao().observe(q).first().map { it.id })
    }

    @Test fun hiddenExcluded() = runTest {
        val p = db.insertTestProvider()
        val (a, b) = channels(p, 2)
        db.recentDao().record(p, a.id, 1)
        db.recentDao().record(p, b.id, 2)
        db.channelDao().setHidden(b.id, true)

        assertEquals(listOf(a.id), db.recentDao().observe(p).first().map { it.id })
    }
}
