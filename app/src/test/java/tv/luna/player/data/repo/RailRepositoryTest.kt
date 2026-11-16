package tv.luna.player.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.CustomGroupEntity
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind

@RunWith(RobolectricTestRunner::class)
class RailRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RailRepository

    @Before fun setUp() { db = inMemoryDb(); repo = RailRepository(db) }
    @After fun tearDown() { db.close() }

    private suspend fun cat(p: Long, r: String, order: Int, hidden: Boolean = false): Long {
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = r, name = r, sortOrder = order, hidden = hidden)))
        return db.categoryDao().byRemoteIds(p, Kind.LIVE).single { it.remoteId == r }.id
    }

    private suspend fun ch(p: Long, cat: Long, r: String, hidden: Boolean = false): ChannelEntity {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = cat, remoteId = r, name = r, logo = null,
            number = null, tvgId = null, streamUrl = "http://s/$r", hidden = hidden)))
        return db.channelDao().byProvider(p).single { it.remoteId == r }
    }

    private suspend fun fav(p: Long, c: ChannelEntity, at: Long) = db.favouriteDao().add(FavouriteEntity(p, Kind.LIVE, c.id, at))

    @Test
    fun railOrder() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        val c2 = cat(p, "c2", 1, hidden = true)
        val a = ch(p, c1, "a")
        ch(p, c2, "b")
        fav(p, a, 1)
        val g = db.customGroupDao().create(p, "Mine")
        db.customGroupDao().addChannel(g, a.id)

        val rail = repo.rail(p).first()

        assertEquals(listOf("fav", "g$g", "c$c1"), rail.map { it.entry.key })
        assertEquals(listOf(1, 1, 1), rail.map { it.count })
    }

    @Test
    fun placementOrdersRail() = runTest {
        val p = db.insertTestProvider()
        val cp = cat(p, "P", 0)
        val a = ch(p, cp, "a")
        fav(p, a, 1)
        val gA = db.customGroupDao().create(p, "A")
        val gB = db.customGroupDao().create(p, "B")
        val gC = db.customGroupDao().create(p, "C")
        listOf(gA, gB, gC).forEach { db.customGroupDao().addChannel(it, a.id) }
        db.customGroupDao().setPlacement(gA, tv.luna.player.data.db.GroupPlacement.TOP)
        db.customGroupDao().setPlacement(gC, tv.luna.player.data.db.GroupPlacement.BOTTOM)

        assertEquals(listOf("g$gA", "fav", "g$gB", "c$cp", "g$gC"), repo.rail(p).first().map { it.entry.key })
    }

    @Test
    fun emptyEntriesOmitted() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        cat(p, "empty", 1)
        ch(p, c1, "a")
        db.customGroupDao().create(p, "Nothing yet")

        assertEquals(listOf("c$c1"), repo.rail(p).first().map { it.entry.key })
    }

    @Test
    fun customGroupKeepsInsertionOrder() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        val (x, y, z) = listOf(ch(p, c1, "x"), ch(p, c1, "y"), ch(p, c1, "z"))
        val g = db.customGroupDao().create(p, "Mine")
        listOf(z, x, y).forEach { db.customGroupDao().addChannel(g, it.id) }
        db.customGroupDao().addChannel(g, z.id) // already in: stays where it was

        val entry = repo.rail(p).first().first { it.entry.key == "g$g" }.entry
        assertEquals(listOf("z", "x", "y"), repo.channels(p, entry).first().map { it.remoteId })
        assertEquals(listOf(g), db.customGroupDao().groupsOf(x.id))
    }

    @Test
    fun hiddenChannelExcludedFromFavourites() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        val a = ch(p, c1, "a")
        val b = ch(p, c1, "b", hidden = true)
        val c = ch(p, c1, "c")
        fav(p, a, 1); fav(p, b, 2); fav(p, c, 3)

        assertEquals(listOf("c", "a"), repo.channels(p, RailEntry.Favourites).first().map { it.remoteId })
        assertEquals(2, repo.rail(p).first().first { it.entry == RailEntry.Favourites }.count)
    }

    @Test
    fun deletingChannelRemovesItFromGroups() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        val a = ch(p, c1, "a")
        val g = db.customGroupDao().create(p, "Mine")
        db.customGroupDao().addChannel(g, a.id)

        db.channelDao().deleteMissing(p, emptyList())

        assertEquals(emptyList<Long>(), db.customGroupDao().groupsOf(a.id))
        assertEquals(emptyList<String>(), repo.rail(p).first().map { it.entry.key })
    }

    @Test
    fun railShowsRecentAfterFavourites() = runTest {
        val p = db.insertTestProvider()
        val c1 = cat(p, "c1", 0)
        val a = ch(p, c1, "a")
        fav(p, a, 1)
        db.recentDao().record(p, a.id, 5)

        assertEquals(listOf("fav", "recent", "c$c1"), repo.rail(p).first().map { it.entry.key })
        assertEquals(listOf("a"), repo.channels(p, RailEntry.Recent).first().map { it.remoteId })
    }
}
