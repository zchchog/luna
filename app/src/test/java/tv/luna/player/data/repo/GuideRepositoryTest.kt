package tv.luna.player.data.repo

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider

@RunWith(RobolectricTestRunner::class)
class GuideRepositoryTest {
    private lateinit var db: AppDatabase
    private var p = 0L
    private val min = 60_000L

    @Before fun setUp() = runTest { db = inMemoryDb(); p = db.insertTestProvider() }
    @After fun tearDown() { db.close() }

    private suspend fun channel(remoteId: String, offset: Int? = null): ChannelEntity {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = null, remoteId = remoteId, name = remoteId,
            logo = null, number = null, tvgId = null, streamUrl = "http://h/$remoteId", epgOffsetMinutes = offset)))
        return db.channelDao().byProvider(p).single { it.remoteId == remoteId }
    }

    private suspend fun prog(source: Long, id: String, startMin: Long, stopMin: Long, title: String) =
        db.programmeDao().insertAll(listOf(ProgrammeEntity(sourceId = source, epgChannelKey = ProgrammeEntity.key(source, id),
            start = startMin * min, stop = stopMin * min, title = title, desc = null, episodeNum = null, icon = null)))

    @Test
    fun linkedChannelGetsProgrammesInWindowUnlinkedGetsNone() = runTest {
        val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0))
        val a = channel("a")
        val b = channel("b")
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(a.id, s, "x", manual = false)))
        prog(s, "x", 0, 50, "Before")
        prog(s, "x", 50, 120, "Now")
        prog(s, "x", 120, 180, "Next")
        prog(s, "x", 300, 360, "Later")

        val rows = GuideRepository(db).programmes(p, listOf(a, b), from = 60 * min, to = 240 * min)

        assertEquals(listOf("Now", "Next"), rows[a.id]!!.map { it.title })
        assertEquals(emptyList<GuideProgramme>(), rows[b.id] ?: emptyList<GuideProgramme>())
    }

    @Test
    fun offsetShiftsProgrammeTimes() = runTest {
        val s = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "u", priority = 0, offsetMinutes = 30))
        val a = channel("a")          // uses source offset +30
        val b = channel("b", offset = -60) // own offset wins
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(a.id, s, "x", manual = false), EpgLinkEntity(b.id, s, "x", manual = false)))
        prog(s, "x", 600, 660, "Show")

        val rows = GuideRepository(db).programmes(p, listOf(a, b), from = 0, to = 2000 * min)

        assertEquals(630 * min, rows[a.id]!!.single().start)
        assertEquals(540 * min, rows[b.id]!!.single().start)
        assertEquals(600 * min, rows[b.id]!!.single().stop)
    }
}
