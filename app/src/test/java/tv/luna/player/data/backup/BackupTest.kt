package tv.luna.player.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.StreamFormat
import tv.luna.player.data.repo.CatalogueSync
import tv.luna.player.data.repo.FakeSource
import tv.luna.player.data.repo.ProviderRepository
import tv.luna.player.data.repo.channel
import tv.luna.player.data.repo.live
import tv.luna.player.data.settings.SettingsRepository
import tv.luna.player.data.settings.ThemeColour

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dbs = mutableListOf<AppDatabase>()

    @After fun tearDown() { dbs.forEach { it.close() } }

    private val catalogue = FakeSource(
        liveCats = listOf(live("5", "UK| News"), live("6", "UK| Sport")),
        channels = listOf(channel("1", "BBC One", cat = "5"), channel("2", "Sky News", cat = "5"), channel("3", "Sky Sports", cat = "6")),
        epg = listOf("http://h/xmltv.php?username=alice-user&password=s3cret-pw"),
    )

    private class Env(val db: AppDatabase, val providers: ProviderRepository, val settings: SettingsRepository, val backup: BackupRepository)

    private fun TestScope.env(source: FakeSource = catalogue): Env {
        val db = inMemoryDb().also { dbs += it }
        val prefs = PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.newFolder(), "s.preferences_pb") }
        val providers = ProviderRepository(db, CatalogueSync(db), { source }, prefs)
        val settings = SettingsRepository(prefs)
        return Env(db, providers, settings, BackupRepository(db, settings, providers))
    }

    private fun xtream() = ProviderEntity(
        name = "Main", type = ProviderType.XTREAM, url = "http://h", username = "alice-user", password = "s3cret-pw",
        userAgent = "UA", streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = null, nameStrip = "UK|",
    )

    /** Provider synced, News hidden and sorted after Sport, a group, a favourite, a user EPG source, custom settings. */
    private suspend fun Env.customise(): Long {
        val p = providers.add(xtream())
        providers.syncNow(p)
        val cats = db.categoryDao().all(p, Kind.LIVE).associateBy { it.remoteId }
        db.categoryDao().update(cats.getValue("5").copy(hidden = true, sortOrder = 2))
        db.categoryDao().update(cats.getValue("6").copy(sortOrder = 1, pinned = true))
        val ch = db.channelDao().byProvider(p).associateBy { it.remoteId }
        val g = db.customGroupDao().create(p, "Mine")
        db.customGroupDao().addChannel(g, ch.getValue("3").id)
        db.customGroupDao().addChannel(g, ch.getValue("1").id)
        db.customGroupDao().setPlacement(g, tv.luna.player.data.db.GroupPlacement.TOP)
        db.favouriteDao().add(FavouriteEntity(p, Kind.LIVE, ch.getValue("2").id, 1))
        providers.addEpgSource(p, "http://guide/uk.xml", "UK guide")
        db.providerDao().setCategoryStrip(p, "UK|")
        db.epgDao().sources(p).first().let { db.epgDao().setOffset(it.id, 60) }
        settings.update { it.copy(themeColour = ThemeColour.RED, confirmExit = false, epgDaysToKeep = 3) }
        return p
    }

    private suspend fun Env.snapshot(p: Long): List<Any> {
        val ch = db.channelDao().byProvider(p).associate { it.id to it.remoteId }
        val groupId = db.customGroupDao().observe(p).first().single().id
        return listOf(
            db.categoryDao().all(p, Kind.LIVE).map { Triple(it.remoteId, it.hidden, it.sortOrder to it.pinned) },
            db.customGroupDao().observe(p).first().map { it.name to it.placement },
            db.customGroupDao().observeChannels(groupId).first().map { it.remoteId },
            db.favouriteDao().list(p, Kind.LIVE).map { ch[it.itemId] },
            db.epgDao().sources(p).map { Triple(it.url, it.name, it.offsetMinutes) },
            db.providerDao().get(p)!!.nameStrip,
            db.providerDao().get(p)!!.categoryStrip,
            settings.settings.first().copy(lastLiveChannelId = null),
        )
    }

    @Test fun roundTripKeepsEverything() = runTest {
        val a = env()
        val p = a.customise()
        a.settings.update { it.copy(lastLiveChannelId = 99, subtitlesOn = true, subtitleLanguage = "fr") }
        val json = encodeBackup(a.backup.create())

        val b = env()
        val decoded = decodeBackup(json)
        val result = b.backup.restorePlaylist(decoded.playlists.single(), "alice-user", "s3cret-pw") {}
        b.backup.restoreSettings(decoded.settings)

        val q = b.db.providerDao().all().single()
        assertEquals("alice-user", q.username)
        assertEquals(a.snapshot(p), b.snapshot(q.id))
        assertEquals(null, b.settings.settings.first().lastLiveChannelId)
        assertEquals(true, b.settings.settings.first().subtitlesOn)
        assertEquals("fr", b.settings.settings.first().subtitleLanguage)
        assertEquals(0, result.skipped)
        assertTrue(result.applied > 0)
    }

    @Test fun restoreOntoSameProviderUpdatesInPlace() = runTest {
        val a = env()
        val p = a.customise()
        val backup = a.backup.create()
        a.db.categoryDao().setHidden(a.db.categoryDao().all(p, Kind.LIVE).first { it.remoteId == "5" }.id, false)
        a.backup.restorePlaylist(backup.playlists.single(), "alice-user", "s3cret-pw") {}
        assertEquals(1, a.db.providerDao().all().size)
        assertTrue(a.db.categoryDao().all(p, Kind.LIVE).first { it.remoteId == "5" }.hidden)
        assertEquals(1, a.db.customGroupDao().observe(p).first().size)
    }

    @Test fun noCredentialsInJson() = runTest {
        val a = env()
        a.customise()
        val json = encodeBackup(a.backup.create())
        assertFalse(json, "alice-user" in json)
        assertFalse(json, "s3cret-pw" in json)
        assertFalse(json, "\"username\"" in json)
        assertFalse(json, "\"password\"" in json)
    }

    @Test fun m3uLoginStaysOutOfTheBackupAndComesBackOnRestore() = runTest {
        val m3u = "http://h/get.php?username=alice-user&password=s3cret-pw&type=m3u_plus"
        val a = env()
        a.providers.add(xtream().copy(type = ProviderType.M3U, url = m3u, username = null, password = null))
        val json = encodeBackup(a.backup.create())
        assertFalse(json, "alice-user" in json)
        assertFalse(json, "s3cret-pw" in json)
        val pl = a.backup.create().playlists.single()
        assertTrue(tv.luna.player.ui.settings.needsLogin(pl))
        val b = env()
        b.backup.restorePlaylist(pl, "alice-user", "s3cret-pw") {}
        assertEquals(m3u, b.db.providerDao().all().single().url)
    }

    @Test fun newerVersionRejected() {
        val e = assertThrows(BackupException::class.java) {
            decodeBackup("""{"version": ${BACKUP_VERSION + 1}, "playlists": [], "settings": {}}""")
        }
        assertEquals("This backup was made by a newer version of the app", e.message)
    }

    @Test fun garbageRejected() {
        val e = assertThrows(BackupException::class.java) { decodeBackup("not json {") }
        assertEquals("Not a readable backup file", e.message)
        assertThrows(BackupException::class.java) { decodeBackup("""{"hello": 1}""") }
    }

    @Test fun restoreSkipsMissingRemoteIds() = runTest {
        val b = env()
        val playlist = BPlaylist(
            name = "Other", type = "XTREAM", url = "http://h", userAgent = null, streamFormat = "TS", nameStrip = "",
            epgSources = emptyList(),
            categories = listOf(BCategory("LIVE", "gone1", hidden = true, sortOrder = 1, pinned = false),
                BCategory("LIVE", "gone2", hidden = false, sortOrder = 2, pinned = true)),
            groups = listOf(BGroup("G", 0, listOf("gone3"))),
            favourites = listOf(BFavourite("LIVE", "gone4")),
        )
        val result = b.backup.restorePlaylist(playlist, "u", "p") {}
        assertEquals(RestoreResult(applied = 0, skipped = 4), result)
        val p = b.db.providerDao().all().single().id
        assertTrue(b.db.categoryDao().all(p, Kind.LIVE).none { it.hidden })
    }

    @Test fun backupHasRecordingSettingsButNoSmbPassword() {
        val s = tv.luna.player.data.settings.AppSettings(recordingOn = true, smbServer = "nas-box", smbUser = "me", smbPassword = "s3cretpw", endLateMin = 10)
        val json = encodeBackup(Backup(playlists = emptyList(), settings = BSettings.of(s)))
        assertTrue("nas-box" in json); assertFalse("s3cretpw" in json)
        val back = decodeBackup(json).settings.toAppSettings(null)
        assertEquals(s.copy(smbPassword = ""), back)
    }
}
