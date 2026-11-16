package tv.luna.player.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.luna.player.data.db.login
import tv.luna.player.data.security.PASS_TOKEN
import tv.luna.player.data.security.USER_TOKEN
import tv.luna.player.data.security.withoutLogin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.NameRuleEntity
import tv.luna.player.data.db.NameRuleType
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.epg.BuiltinRule
import tv.luna.player.data.source.PlaylistSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class ProviderRepository(
    private val db: AppDatabase,
    private val sync: CatalogueSync,
    private val sourceFor: (ProviderEntity) -> PlaylistSource,
    private val prefs: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
    /** Extra work after each catalogue sync (tv-logos matching); a failure here never fails the sync. */
    private val afterSync: suspend (Long) -> Unit = {},
) {
    private val activeKey = longPreferencesKey("active_provider_id")

    /** A stored id whose playlist is gone (a crash between delete and the switch) reads as the first playlist left. */
    val activeProviderId: Flow<Long?> = prefs.data.map { it[activeKey] }.distinctUntilChanged().map { id ->
        val ids = db.providerDao().ids()
        id?.takeIf { it in ids } ?: ids.firstOrNull()
    }

    suspend fun setActive(id: Long) {
        prefs.edit { it[activeKey] = id }
    }

    /**
     * Deletes the provider and the rows that have no FK to it (programmes, favourites, progress);
     * the cascade takes the rest. If it was active, the first remaining provider becomes active.
     */
    suspend fun remove(id: Long) {
        db.withTransaction {
            db.epgDao().sources(id).forEach { db.programmeDao().deleteForSource(it.id) }
            db.favouriteDao().deleteForProvider(id)
            db.progressDao().deleteForProvider(id)
            db.providerDao().get(id)?.let { db.providerDao().delete(it) }
        }
        val next = db.providerDao().all().firstOrNull()?.id
        prefs.edit { if (it[activeKey] == id) { if (next != null) it[activeKey] = next else it.remove(activeKey) } }
    }

    /**
     * Saves edited URL/login/user agent; the catalogue is kept until the next sync. EPG sources the provider
     * itself supplied (Xtream's xmltv.php carries the login) move to the new URLs, keeping id, name and offset.
     */
    suspend fun update(p: ProviderEntity) = db.withTransaction {
        val old = db.providerDao().get(p.id) ?: return@withTransaction
        db.providerDao().update(p)
        if (p.epgOverrideUrls.isNotEmpty()) return@withTransaction
        val moved = sourceFor(old).epgUrls().zip(sourceFor(p).epgUrls()).toMap()
        db.epgDao().sources(p.id).forEach { s -> moved[s.url]?.takeIf { it != s.url }?.let { db.epgDao().setUrl(s.id, it) } }
    }

    /**
     * Once, for playlists saved before encryption: their login comes out of saved stream and guide links (placeholders,
     * filled in at play/download time) and the URL, username and password are re-saved encrypted.
     */
    suspend fun sealOldRows() = db.withTransaction {
        val dao = db.providerDao()
        dao.unsealed().forEach { p ->
            p.login()?.let { login ->
                val from = "/" + segment(login.username) + "/" + segment(login.password) + "/"
                val to = "/$USER_TOKEN/$PASS_TOKEN/"
                dao.replaceInChannelUrls(p.id, from, to); dao.replaceInMovieUrls(p.id, from, to); dao.replaceInEpisodeUrls(p.id, from, to)
                db.epgDao().sources(p.id).forEach { s -> withoutLogin(s.url, login).takeIf { it != s.url }?.let { db.epgDao().setUrl(s.id, it) } }
            }
            dao.update(p)
        }
    }

    /** [s] percent-encoded as a URL path segment, the way the saved links were built. */
    private fun segment(s: String) = "http://x/".toHttpUrl().newBuilder().addPathSegment(s).build().encodedPath.removePrefix("/")

    suspend fun addEpgSource(providerId: Long, url: String, name: String?) {
        val priority = (db.epgDao().sources(providerId).maxOfOrNull { it.priority } ?: -1) + 1
        db.epgDao().insertSource(EpgSourceEntity(providerId = providerId, url = url, priority = priority, name = name))
    }

    /** XMLTV channels go by cascade; programmes and links have no FK to the source. */
    suspend fun removeEpgSource(id: Long) = db.withTransaction {
        db.programmeDao().deleteForSource(id)
        db.epgDao().deleteLinksForSource(id)
        db.epgDao().deleteSource(id)
    }

    /** The guide URLs the playlist itself supplies (Xtream's carry the login). */
    fun ownEpgUrls(p: ProviderEntity): List<String> = sourceFor(p).epgUrls()

    fun observeAll(): Flow<List<ProviderEntity>> = db.providerDao().observeAll()

    /** Inserts the provider with its EPG sources and default name rules; becomes active if none is. */
    suspend fun add(p: ProviderEntity): Long {
        val epgUrls = p.epgOverrideUrls.ifEmpty { sourceFor(p).epgUrls() }
        val id = db.withTransaction {
            val id = db.providerDao().insert(p.copy(id = 0))
            epgUrls.forEachIndexed { i, url ->
                db.epgDao().insertSource(EpgSourceEntity(providerId = id, url = url, priority = i, offsetMinutes = 0))
            }
            db.ruleDao().insertNameRules(BuiltinRule.entries.mapIndexed { i, r ->
                NameRuleEntity(providerId = id, order = i, enabled = true, type = NameRuleType.BUILTIN, pattern = r.name)
            })
            id
        }
        if (prefs.data.first()[activeKey] == null) setActive(id)
        return id
    }

    suspend fun syncIfStale(providerId: Long, maxAge: Duration = 24.hours): SyncResult? {
        val p = db.providerDao().get(providerId) ?: return null
        val last = p.lastSync
        return if (last == null || now() - last >= maxAge.inWholeMilliseconds) syncNow(providerId) else null
    }

    suspend fun syncNow(providerId: Long, onProgress: (String) -> Unit = {}): SyncResult {
        val p = db.providerDao().get(providerId) ?: throw NoSuchElementException("No provider $providerId")
        val result = sync.sync(providerId, sourceFor(p), onProgress)
        db.providerDao().setLastSync(providerId, now())
        try {
            afterSync(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ProviderRepository", "after-sync work failed: $e")
        }
        return result
    }
}
