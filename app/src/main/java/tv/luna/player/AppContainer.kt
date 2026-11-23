package tv.luna.player

import android.content.Context
import kotlinx.coroutines.launch
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.epg.EpgImporter
import tv.luna.player.data.epg.EpgMatcher
import tv.luna.player.data.epg.EpgShareSources
import tv.luna.player.data.epg.XmltvParser
import tv.luna.player.data.repo.CatalogueSync
import tv.luna.player.data.catalogue.tvLanguage
import tv.luna.player.data.catalogue.countriesOfLanguage
import kotlinx.coroutines.flow.first
import tv.luna.player.data.repo.ProviderRepository
import tv.luna.player.data.logos.LogoRepository
import tv.luna.player.data.logos.LogoPackRepository
import tv.luna.player.data.settings.LogoKind
import tv.luna.player.data.settings.SettingsRepository
import tv.luna.player.data.source.M3uSource
import tv.luna.player.data.source.PlaylistSource
import tv.luna.player.data.source.XtreamSource
import tv.luna.player.data.source.xtreamJson
import tv.luna.player.player.PlayerManager
import java.util.concurrent.TimeUnit

/** Manual dependency wiring. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    val json: Json = xtreamJson()
    val prefs: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("settings") }
    }

    /** A fresh source per call: M3U caches its parse per instance, i.e. per sync. */
    fun sourceFor(p: ProviderEntity): PlaylistSource = when (p.type) {
        ProviderType.XTREAM -> XtreamSource(
            p.url, p.username.orEmpty(), p.password.orEmpty(), p.streamFormat, http, json, p.userAgent,
        )
        ProviderType.M3U -> M3uSource(p.url, p.epgOverrideUrls, http, p.userAgent)
    }

    val catalogueSync: CatalogueSync by lazy {
        CatalogueSync(db, keepLanguage = { if (settings.settings.first().matchTvLanguage) tvLanguage() else null })
    }
    val providerRepository: ProviderRepository by lazy {
        // Logo matching runs after the sync result is shown, and one step failing doesn't skip the others.
        ProviderRepository(db, catalogueSync, ::sourceFor, prefs, afterSync = { id ->
            background.launch {
                fun failed(what: String, e: Exception) = android.util.Log.w("AppContainer", "$what after sync failed: $e")
                try { logoPack.rematch(id) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed("pack rematch", e) }
                try { if (LogoKind.OPEN_SOURCE in settings.settings.first().logoOrder) logos.refreshBuiltin(id) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e; failed("tv-logos match", e)
                }
                try { logos.pruneCustom() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed("logo clean-up", e) }
            }
        })
    }
    /** App-lifetime work that must not hold up the caller (logo matching after a sync). */
    val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    /** tv-logos list, packs in `<providerId>/` and hand-picked logos in `custom/` share this folder. */
    private val logoDir by lazy { java.io.File(appContext.filesDir, "logos") }
    val logos: LogoRepository by lazy { LogoRepository(db, http, logoDir) }
    val logoPack: LogoPackRepository by lazy { LogoPackRepository(db, logoDir) }
    val settings: SettingsRepository by lazy { SettingsRepository(prefs) }
    /** First-run registration code. */
    val activation: tv.luna.player.data.security.Activation by lazy { tv.luna.player.data.security.Activation(prefs) }
    /** TV guide reminders (pop-up or notification a minute before). */
    val reminders: tv.luna.player.reminders.Reminders by lazy { tv.luna.player.reminders.Reminders(appContext, db, recordings.prompt) }
    val epgImporter: EpgImporter by lazy { EpgImporter(db, http, XmltvParser()) }
    val epgMatcher: EpgMatcher by lazy { EpgMatcher(db, settings = { settings.settings.first() }) }
    val epgShare: EpgShareSources by lazy {
        // The TV language's countries (user, 2026-11-11): English → UK, US, CA, AU, IE, NZ (~22 MB), not every shown country (~32 MB).
        EpgShareSources(db, countries = { countriesOfLanguage(tvLanguage()) }) { url ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("epgshare01: HTTP ${r.code}")
                    r.body!!.string()
                }
            }
        }
    }

    val backup: tv.luna.player.data.backup.BackupRepository by lazy {
        tv.luna.player.data.backup.BackupRepository(db, settings, providerRepository)
    }

    val updater: tv.luna.player.data.update.Updater by lazy { tv.luna.player.data.update.Updater(appContext, http, json, prefs) }

    val search: tv.luna.player.data.search.SearchRepository by lazy { tv.luna.player.data.search.SearchRepository(db) }

    private val player = lazy {
        PlayerManager(appContext).also { p ->
            p.guard = { retry -> recordings.blocksWatching { main.post(retry) } }
            p.smbSettings = { smbSettings(kotlinx.coroutines.runBlocking { settings.settings.first() }) } // on the player's loader thread
        }
    }
    /** First access must be on the main thread. */
    val playerManager: PlayerManager by player

    /** The player is showing a provider stream (read from any thread; false before the player exists). */
    fun isWatching(): Boolean = player.isInitialized() && playerManager.playingUrl.value?.let { !tv.luna.player.recording.isRecordingLocation(it) } == true

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Last known stream limit per playlist (Xtream max_connections), for the player guard. */
    private val limits = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    @Volatile private var activeId: Long? = null

    val recordingScheduler: tv.luna.player.recording.RecordingScheduler by lazy {
        tv.luna.player.recording.RecordingScheduler(appContext, db.recordingDao(), startNow = { recordings.onStart(it) }) // through the gate, as the alarm would
    }

    val recordings: tv.luna.player.recording.RecordingRepository by lazy {
        background.launch {
            db.providerDao().all().forEach { p -> p.maxConnections?.let { limits[p.id] = it } }
            providerRepository.activeProviderId.collect { activeId = it }
        }
        tv.luna.player.recording.RecordingRepository(
            db, settings = { settings.settings.first() }, arm = recordingScheduler::arm, disarm = recordingScheduler::cancel,
            limitOf = ::refreshLimit, storedLimit = { limits[it] }, activeProviderId = { activeId },
            startService = { tv.luna.player.recording.RecordingService.start(appContext, it) },
            stopService = { tv.luna.player.recording.RecordingService.stop(appContext, it) },
            stopPlayer = { main.post { if (player.isInitialized()) playerManager.stop() } },
            watching = ::isWatching, scope = background,
            showings = { ch ->
                val t = System.currentTimeMillis()
                tv.luna.player.data.repo.GuideRepository(db).programmes(ch.providerId, listOf(ch), t, t + 7 * 24 * 3_600_000L)[ch.id].orEmpty()
            },
        )
    }

    /** Xtream: asks the provider for the login's stream limit and keeps it; falls back to the last known one. */
    private suspend fun refreshLimit(providerId: Long): Int? {
        val p = db.providerDao().get(providerId) ?: return null
        val source = sourceFor(p) as? XtreamSource ?: return null
        val fresh = source.maxConnections()
        if (fresh != null && fresh != p.maxConnections) db.providerDao().setMaxConnections(providerId, fresh)
        return (fresh ?: p.maxConnections)?.also { limits[providerId] = it }
    }

    /** Where Save to points now; null = USB chosen but no drive is plugged in. */
    fun recordingStore(s: tv.luna.player.data.settings.AppSettings): tv.luna.player.recording.RecordingStore? = when (s.recordTo) {
        tv.luna.player.recording.RecStorage.INTERNAL -> tv.luna.player.recording.FileStore(s.recordTo, tv.luna.player.recording.internalDir(appContext))
        tv.luna.player.recording.RecStorage.USB -> tv.luna.player.recording.usbDirs(appContext)
            .let { dirs -> dirs.firstOrNull { it.path == s.recordUsbPath } ?: dirs.firstOrNull() }
            ?.let { tv.luna.player.recording.FileStore(s.recordTo, it) }
        tv.luna.player.recording.RecStorage.SMB -> tv.luna.player.recording.SmbStore(smbSettings(s))
    }

    /** Removes a recording's file wherever it is (box, USB drive or network share). */
    suspend fun deleteRecordingFile(r: tv.luna.player.recording.RecordingEntity) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loc = r.location ?: return@withContext
        if (loc.startsWith("smb://")) tv.luna.player.recording.SmbStore(smbSettings(settings.settings.first())).delete(loc) else java.io.File(loc).delete()
    }

    fun smbSettings(s: tv.luna.player.data.settings.AppSettings) =
        tv.luna.player.recording.SmbSettings(s.smbServer.trim(), s.smbShare.trim(), s.smbFolder.trim(), s.smbUser.trim(), s.smbPassword)
}
