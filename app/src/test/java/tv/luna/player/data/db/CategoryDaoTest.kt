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
class CategoryDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun cat(p: Long, remoteId: String, name: String, sortOrder: Int, hidden: Boolean = false) =
        CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = remoteId, name = name,
            sortOrder = sortOrder, hidden = hidden)

    @Test
    fun observeVisibleExcludesHiddenAndOrdersBySortOrder() = runTest {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(cat(p, "b", "B", 1), cat(p, "a", "A", 0), cat(p, "c", "C", 2, hidden = true)))

        val names = db.categoryDao().observeVisible(p, Kind.LIVE).first().map { it.name }

        assertEquals(listOf("A", "B"), names)
    }

    @Test
    fun deleteMissingRemovesOnlyAbsentRemoteIds() = runTest {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(cat(p, "a", "A", 0), cat(p, "b", "B", 1)))

        db.categoryDao().deleteMissing(p, Kind.LIVE, listOf("a"))

        assertEquals(listOf("a"), db.categoryDao().byRemoteIds(p, Kind.LIVE).map { it.remoteId })
    }

    @Test
    fun hideOneAndShowAll() = runTest {
        val p = db.insertTestProvider()
        val dao = db.categoryDao()
        dao.upsert(listOf(cat(p, "a", "A", 0), cat(p, "b", "B", 1),
            CategoryEntity(providerId = p, kind = Kind.MOVIE, remoteId = "m", name = "M", hidden = true)))
        val a = dao.byRemoteIds(p, Kind.LIVE).single { it.remoteId == "a" }

        dao.setHidden(a.id, true)
        assertEquals(listOf("B"), dao.observeVisible(p, Kind.LIVE).first().map { it.name })
        assertEquals(2, dao.observeAll(p, Kind.LIVE).first().size)

        dao.setHiddenForKind(p, Kind.LIVE, false)
        assertEquals(listOf("A", "B"), dao.observeVisible(p, Kind.LIVE).first().map { it.name })
        assertEquals(emptyList<String>(), dao.observeVisible(p, Kind.MOVIE).first().map { it.name })
    }

    @Test
    fun hideByCountryAcrossKinds() = runTest {
        val p = db.insertTestProvider()
        val dao = db.categoryDao()
        fun c(kind: Kind, r: String, country: String?) =
            CategoryEntity(providerId = p, kind = kind, remoteId = r, name = r, country = country)
        dao.upsert(listOf(c(Kind.LIVE, "l1", "GB"), c(Kind.MOVIE, "m1", "GB"), c(Kind.LIVE, "l2", "US"), c(Kind.SERIES, "s1", null)))

        dao.setHiddenForCountries(p, listOf("GB"), true)
        assertEquals(listOf("l2"), dao.observeVisible(p, Kind.LIVE).first().map { it.name })
        assertEquals(emptyList<String>(), dao.observeVisible(p, Kind.MOVIE).first().map { it.name })

        dao.setHiddenForNoCountry(p, true)
        assertEquals(emptyList<String>(), dao.observeVisible(p, Kind.SERIES).first().map { it.name })
        assertEquals(setOf("GB", "US", null), dao.countries(p).toSet())
    }
}
