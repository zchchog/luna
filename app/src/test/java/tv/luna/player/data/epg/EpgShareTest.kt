package tv.luna.player.data.epg

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ORIGIN_EPGSHARE
import tv.luna.player.data.db.ORIGIN_PLAYLIST
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind
import tv.luna.player.data.settings.GuideKind
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class EpgShareTest {
    private val fixture = javaClass.getResource("/epgshare/index.html")!!.readText()
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun src(id: Long, origin: String, priority: Int) = EpgSourceEntity(id = id, providerId = 1, url = "u$id", priority = priority, origin = origin)

    @Test fun parsesIndex() = assertEquals(
        mapOf("UK" to listOf("epg_ripper_UK1.xml.gz", "epg_ripper_UK2.xml.gz"), "US" to listOf("epg_ripper_US1.xml.gz"), "DE" to listOf("epg_ripper_DE1.xml.gz")),
        parseEpgShareIndex(fixture))

    @Test fun codes() {
        assertEquals("UK", epgShareCode("GB")); assertEquals("DE", epgShareCode("DE"))
        listOf(null, "SA", "script:Arab", "lang:Arabic").forEach { assertNull(epgShareCode(it)) }
    }

    @Test fun selectsSourcesPerMode() {
        val p0 = src(1, ORIGIN_PLAYLIST, 0); val p9 = src(2, ORIGIN_PLAYLIST, 9); val e0 = src(3, ORIGIN_EPGSHARE, 0)
        val all = listOf(e0, p9, p0)
        val P = GuideKind.PLAYLIST; val O = GuideKind.OPEN_SOURCE
        assertEquals(listOf(1L, 2L), selectSources(all, listOf(P)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), selectSources(all, listOf(P, O)).map { it.id })
        assertEquals(listOf(3L), selectSources(all, listOf(O)).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), selectSources(all, listOf(O, P)).map { it.id })  // the reverse (user, 2026-11-12)
    }

    private suspend fun category(p: Long, remoteId: String, country: String, hidden: Boolean, kind: Kind = Kind.LIVE) =
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = kind, remoteId = remoteId, name = remoteId, country = country, hidden = hidden)))

    private suspend fun setHidden(p: Long, remoteId: String, hidden: Boolean) =
        db.categoryDao().setHidden(db.categoryDao().allKinds(p).single { it.remoteId == remoteId }.id, hidden)

    private suspend fun shareUrls(p: Long) = db.epgDao().sources(p).filter { it.origin == ORIGIN_EPGSHARE }.map { it.url.removePrefix(EPGSHARE_BASE) }

    @Test fun syncFollowsVisibleCountries() = runTest {
        val p = db.insertTestProvider()
        category(p, "uk", "GB", hidden = false); category(p, "de", "DE", hidden = true); category(p, "sa", "SA", hidden = false)
        val share = EpgShareSources(db) { fixture }
        assertEquals(2, share.sync(p))
        assertEquals(listOf("epg_ripper_UK1.xml.gz", "epg_ripper_UK2.xml.gz"), shareUrls(p))
        val uk1 = db.epgDao().sources(p).first { it.url.endsWith("UK1.xml.gz") }
        assertEquals("Open-source UK1", uk1.name)
        db.programmeDao().insertAll(listOf(ProgrammeEntity(sourceId = uk1.id, epgChannelKey = ProgrammeEntity.key(uk1.id, "x"),
            start = 0, stop = 1, title = "t", desc = null, episodeNum = null, icon = null)))

        setHidden(p, "de", false); setHidden(p, "uk", true)
        assertEquals(1, share.sync(p))
        assertEquals(listOf("epg_ripper_DE1.xml.gz"), shareUrls(p))
        assertTrue(db.programmeDao().window(listOf(ProgrammeEntity.key(uk1.id, "x")), 0, 2).isEmpty())
    }

    /** User 2026-11-11: the TV language's countries decide, not the shown categories. */
    @Test fun languageCountriesReplaceCategories() = runTest {
        val p = db.insertTestProvider()
        category(p, "de", "DE", hidden = false)
        assertEquals(3, EpgShareSources(db, countries = { setOf("GB", "US") }) { fixture }.sync(p))
        assertEquals(listOf("epg_ripper_UK1.xml.gz", "epg_ripper_UK2.xml.gz", "epg_ripper_US1.xml.gz"), shareUrls(p))
    }

    @Test fun visibleMovieCategoryCountsToo() = runTest {
        val p = db.insertTestProvider()
        category(p, "de-movies", "DE", hidden = false, kind = Kind.MOVIE)
        EpgShareSources(db) { fixture }.sync(p)
        assertEquals(listOf("epg_ripper_DE1.xml.gz"), shareUrls(p))
    }

    @Test fun fetchFailureChangesNothing() = runTest {
        val p = db.insertTestProvider()
        category(p, "uk", "GB", hidden = false)
        EpgShareSources(db) { fixture }.sync(p)
        setHidden(p, "uk", true)
        val failed = runCatching { EpgShareSources(db) { throw IOException("offline") }.sync(p) }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertEquals(listOf("epg_ripper_UK1.xml.gz", "epg_ripper_UK2.xml.gz"), shareUrls(p))
    }

    @Test fun playlistSourcesUntouched() = runTest {
        val p = db.insertTestProvider()
        db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "http://own/epg.xml", priority = 0))
        EpgShareSources(db) { fixture }.sync(p)
        assertEquals(listOf("http://own/epg.xml"), db.epgDao().sources(p).map { it.url })
    }
}
