package tv.luna.player.data.search

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.MovieEntity
import tv.luna.player.data.db.SeriesEntity
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind

@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SearchRepository
    private var p = 0L

    @Before fun setUp() = runTest {
        // Direct executors: Room's invalidation tracker then reports each write before the next line runs.
        db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        repo = SearchRepository(db)
        p = db.insertTestProvider()
    }
    @After fun tearDown() { db.close() }

    private suspend fun cat(kind: Kind, remoteId: String, hidden: Boolean = false): Long {
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = kind, remoteId = remoteId, name = remoteId, hidden = hidden)))
        return db.categoryDao().byRemoteIds(p, kind).single { it.remoteId == remoteId }.id
    }

    private suspend fun channels(cat: Long, vararg c: ChannelEntity) = db.channelDao().upsert(c.map { it.copy(providerId = p, categoryId = cat) })
    private fun ch(id: String, name: String, customName: String? = null, number: Int? = null, hidden: Boolean = false) =
        ChannelEntity(providerId = 0, categoryId = null, remoteId = id, name = name, customName = customName, logo = null,
            number = number, hidden = hidden, tvgId = null, streamUrl = "http://x/$id")
    private fun movie(cat: Long, id: String, name: String) =
        MovieEntity(providerId = p, categoryId = cat, remoteId = id, name = name, poster = null, year = null, rating = null, streamUrl = null)

    @Test fun hiddenExcluded() = runTest {
        val shown = cat(Kind.LIVE, "news")
        val gone = cat(Kind.LIVE, "gone", hidden = true)
        channels(shown, ch("1", "BBC News"), ch("2", "BBC Hidden", hidden = true), ch("4", "#### BBC ####"))
        channels(gone, ch("3", "BBC In Hidden Cat"))
        val mShown = cat(Kind.MOVIE, "films")
        val mGone = cat(Kind.MOVIE, "hiddenfilms", hidden = true)
        db.movieDao().upsert(listOf(movie(mShown, "m1", "BBC Film"), movie(mGone, "m2", "BBC Secret")))
        val r = repo.universal(p, emptyList(), "bbc")
        assertEquals(listOf("BBC News"), r.channels.items.map { it.name })
        assertEquals(listOf("BBC Film"), r.movies.items.map { it.name })
        assertEquals(0, r.series.total)
    }

    @Test fun eitherNameMatches() = runTest {
        channels(cat(Kind.LIVE, "uk"), ch("1", "UK: BBC ONE ᴴᴰ", customName = "Beeb"))
        assertEquals(1, repo.universal(p, listOf("UK:"), "beeb").channels.total)
        assertEquals(1, repo.universal(p, listOf("UK:"), "hd").channels.total)
        assertEquals(0, repo.universal(p, listOf("UK:"), "itv").channels.total)
    }

    @Test fun capAndTotal() = runTest {
        val c = cat(Kind.MOVIE, "all")
        db.movieDao().upsert((1..60).map { movie(c, "m$it", "Minecraft %02d".format(it)) })
        val r = repo.universal(p, emptyList(), "minecraft")
        assertEquals(50, r.movies.items.size)
        assertEquals(60, r.movies.total)
        assertEquals("Minecraft 01", r.movies.items.first().name)
        assertTrue(repo.universal(p, emptyList(), "   ").movies.items.isEmpty())
    }

    @Test fun inCategoryScoped() = runTest {
        val a = cat(Kind.SERIES, "a")
        val b = cat(Kind.SERIES, "b")
        db.seriesDao().upsert(listOf(
            SeriesEntity(providerId = p, categoryId = a, remoteId = "s1", name = "Doctor Who", poster = null, year = null, rating = null),
            SeriesEntity(providerId = p, categoryId = b, remoteId = "s2", name = "Doctor Foster", poster = null, year = null, rating = null),
        ))
        assertEquals(listOf("Doctor Who"), repo.inCategory(p, Kind.SERIES, a, "doctor").map { it.name })
        assertEquals(listOf("Doctor Foster", "Doctor Who"), repo.inCategory(p, Kind.SERIES, null, "doctor").map { it.name })
        assertTrue(repo.inCategory(p, Kind.SERIES, null, "").isEmpty())
    }

    @Test fun cacheRebuildsWhenStripOrSyncChanges() = runTest {
        val c = cat(Kind.LIVE, "uk")
        channels(c, ch("1", "UK: Sky News"), ch("2", "UK: Sky Sports"))
        assertEquals(2, repo.universal(p, emptyList(), "sky").channels.total)
        val sports = db.channelDao().byProvider(p).single { it.remoteId == "2" }.id
        db.channelDao().setHidden(sports, true)
        assertEquals(listOf("UK: Sky News"), repo.universal(p, emptyList(), "sky").channels.items.map { it.name })
        // Rename: the new shown name is found at once.
        db.channelDao().setCustomName(db.channelDao().byProvider(p).single { it.remoteId == "1" }.id, "Rolling")
        assertEquals(1, repo.universal(p, emptyList(), "rolling").channels.total)
        // Strip "UK:": the shown names now start with "sky", so they rank alphabetically with "Sky Zebra".
        db.channelDao().setCustomName(db.channelDao().byProvider(p).single { it.remoteId == "1" }.id, null)
        db.channelDao().setHidden(sports, false)
        channels(c, ch("3", "Sky Zebra"))
        db.providerDao().setLastSync(p, 1) // a new channel only arrives by a sync
        assertEquals(listOf("3", "1", "2"), repo.universal(p, emptyList(), "sky").channels.items.map { it.remoteId })
        assertEquals(listOf("1", "2", "3"), repo.universal(p, listOf("UK:"), "sky").channels.items.map { it.remoteId })
    }
}
