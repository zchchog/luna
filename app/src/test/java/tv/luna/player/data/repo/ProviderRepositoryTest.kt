package tv.luna.player.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.ProgressEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.StreamFormat

@RunWith(RobolectricTestRunner::class)
class ProviderRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun TestScope.repo(afterSync: suspend (Long) -> Unit = {}) = ProviderRepository(
        db = db, sync = CatalogueSync(db), sourceFor = { p -> FakeSource(epg = listOf("http://h/xmltv.php?password=${p.password}")) },
        prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("prefs.preferences_pb") },
        afterSync = afterSync,
    )

    @Test fun syncSucceedsWhenAfterSyncThrows() = runTest {
        val r = repo(afterSync = { throw java.io.IOException("github down") })
        val p = r.add(provider("a", "http://a/e.xml"))
        val result = r.syncNow(p)
        assertEquals(0, result.channels)
        assertTrue(db.providerDao().get(p)!!.lastSync != null)
    }

    private fun provider(name: String, epg: String) = ProviderEntity(
        name = name, type = ProviderType.M3U, url = "http://$name/list.m3u", username = null, password = null,
        userAgent = null, streamFormat = StreamFormat.TS, epgOverrideUrls = listOf(epg), lastSync = null,
    )

    /** One channel, a favourite, a progress row and a programme on the provider's first EPG source. */
    private suspend fun fill(p: Long) {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = null, remoteId = "1", name = "C$p",
            logo = null, number = null, tvgId = null, streamUrl = "http://h/$p")))
        val ch = db.channelDao().byProvider(p).single().id
        db.favouriteDao().add(FavouriteEntity(p, Kind.LIVE, ch, 1))
        db.progressDao().upsert(ProgressEntity(p, Kind.MOVIE, 9, 1, 2, 3, false))
        val s = db.epgDao().sources(p).first().id
        db.programmeDao().insertAll(listOf(ProgrammeEntity(sourceId = s, epgChannelKey = ProgrammeEntity.key(s, "x"),
            start = 0, stop = 1, title = "t", desc = null, episodeNum = null, icon = null)))
    }

    private suspend fun programmesOf(p: Long) =
        db.epgDao().sources(p).sumOf { s -> db.programmeDao().window(listOf(ProgrammeEntity.key(s.id, "x")), 0, 2).size }

    @Test
    fun deleteRemovesUnlinkedRowsAndSwitchesActive() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        val b = repo.add(provider("b", "http://e/b.xml"))
        fill(a); fill(b)
        val aSource = db.epgDao().sources(a).single().id
        assertEquals(a, repo.activeProviderId.first())

        repo.remove(a)

        assertTrue(db.favouriteDao().list(a, Kind.LIVE).isEmpty())
        assertTrue(db.progressDao().all(a).isEmpty())
        assertTrue(db.channelDao().byProvider(a).isEmpty())
        assertEquals(0, db.programmeDao().window(listOf(ProgrammeEntity.key(aSource, "x")), 0, 2).size)
        assertEquals(1, db.favouriteDao().list(b, Kind.LIVE).size)
        assertEquals(1, db.progressDao().all(b).size)
        assertEquals(1, db.channelDao().byProvider(b).size)
        assertEquals(1, programmesOf(b))
        assertEquals(b, repo.activeProviderId.first())
    }

    @Test
    fun deletingLastProviderClearsActive() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        repo.remove(a)
        assertNull(repo.activeProviderId.first())
        assertNull(db.providerDao().get(a))
    }

    @Test
    fun updateKeepsIdAndCatalogue() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        fill(a)
        val p = db.providerDao().get(a)!!
        repo.update(p.copy(url = "http://new/list.m3u", username = "u", password = "pw", userAgent = "VLC"))
        val after = db.providerDao().get(a)!!
        assertEquals("http://new/list.m3u", after.url)
        assertEquals("VLC", after.userAgent)
        assertEquals("u", after.username)
        assertEquals(1, db.channelDao().byProvider(a).size)
        assertEquals(1, db.favouriteDao().list(a, Kind.LIVE).size)
    }

    @Test
    fun editKeepsProviderEpgSourceOnNewLogin() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml").copy(epgOverrideUrls = emptyList(), password = "old"))
        repo.addEpgSource(a, "http://own/guide.xml", null)
        val before = db.epgDao().sources(a)
        db.epgDao().setOffset(before[0].id, 60)

        repo.update(db.providerDao().get(a)!!.copy(password = "new"))

        val after = db.epgDao().sources(a)
        assertEquals(listOf("http://h/xmltv.php?password=new", "http://own/guide.xml"), after.map { it.url })
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(60, after[0].offsetMinutes)
    }

    @Test
    fun removeEpgSourceDeletesItsProgrammes() = runTest {
        val repo = repo()
        val a = repo.add(provider("a", "http://e/a.xml"))
        fill(a)
        repo.addEpgSource(a, "http://e/second.xml", "Second")
        val (first, second) = db.epgDao().sources(a)
        assertEquals(1, second.priority)
        assertEquals("Second", second.name)

        db.epgDao().upsertLinks(listOf(EpgLinkEntity(db.channelDao().byProvider(a).single().id, first.id, "x", manual = true)))

        repo.removeEpgSource(first.id)

        assertEquals(listOf(second.id), db.epgDao().sources(a).map { it.id })
        assertEquals(0, db.programmeDao().window(listOf(ProgrammeEntity.key(first.id, "x")), 0, 2).size)
        assertTrue(db.epgDao().links(a).isEmpty())
    }
}
