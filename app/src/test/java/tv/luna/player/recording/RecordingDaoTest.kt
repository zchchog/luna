package tv.luna.player.recording

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.inMemoryDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingDaoTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun rec(title: String, start: Long, status: RecStatus, actual: Long? = null, channelId: Long? = 1) = RecordingEntity(
        providerId = 1, channelId = channelId, channelName = "C", channelLogo = null, title = title, description = null,
        scheduledStart = start, scheduledEnd = start + 100, programmeStart = start, actualStart = actual, status = status)

    @Test fun observeAllPutsUpcomingFirstThenNewest() = runTest {
        val dao = db.recordingDao()
        dao.insert(rec("old", 10, RecStatus.DONE, actual = 10))
        dao.insert(rec("later", 500, RecStatus.SCHEDULED))
        dao.insert(rec("new", 20, RecStatus.STOPPED, actual = 20))
        dao.insert(rec("soon", 300, RecStatus.SCHEDULED))
        dao.insert(rec("now", 200, RecStatus.RECORDING, actual = 200))
        assertEquals(listOf("now", "soon", "later", "new", "old"), dao.observeAll().first().map { it.title })
    }

    @Test fun onChannelFiltersByStatus() = runTest {
        val dao = db.recordingDao()
        dao.insert(rec("a", 1, RecStatus.SCHEDULED))
        dao.insert(rec("b", 2, RecStatus.DONE))
        dao.insert(rec("c", 3, RecStatus.SCHEDULED, channelId = 2))
        assertEquals(listOf("a"), dao.onChannel(1, listOf(RecStatus.SCHEDULED, RecStatus.RECORDING)).map { it.title })
        assertEquals(listOf("a", "c"), dao.withStatus(RecStatus.SCHEDULED).map { it.title }.sorted())
    }

    @Test fun positionAndBytes() = runTest {
        val dao = db.recordingDao()
        val id = dao.insert(rec("a", 1, RecStatus.DONE))
        dao.setPosition(id, 1234); dao.setBytes(id, 99)
        val r = dao.get(id)!!
        assertEquals(1234L, r.positionMs); assertEquals(99L, r.bytes)
        dao.delete(id)
        assertEquals(null, dao.get(id))
    }
}
