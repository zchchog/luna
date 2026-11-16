package tv.luna.player.ui.settings

import androidx.room.withTransaction
import android.app.Application
import android.net.Uri
import tv.luna.player.data.files.SharedFile
import tv.luna.player.data.files.listSharedFiles
import tv.luna.player.data.logos.PackException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.data.backup.BPlaylist
import tv.luna.player.data.backup.Backup
import tv.luna.player.data.backup.BackupException
import tv.luna.player.data.backup.decodeBackup
import tv.luna.player.data.backup.encodeBackup
import tv.luna.player.data.backup.listBackups
import tv.luna.player.data.backup.readBackup
import tv.luna.player.data.backup.writeBackup
import tv.luna.player.data.catalogue.countryLabel
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.catalogue.languageOf
import tv.luna.player.data.catalogue.parseStripList
import tv.luna.player.data.catalogue.cleaned
import tv.luna.player.data.catalogue.tvLanguage
import tv.luna.player.data.db.CategoryCount
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.CustomGroupEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.epg.clearGuide
import tv.luna.player.data.model.Kind
import tv.luna.player.data.repo.CategoryRepository
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.LogoKind
import tv.luna.player.data.settings.GuideKind
import tv.luna.player.work.EpgRefreshWorker

data class CategoryRow(val category: CategoryEntity, val count: Int)
data class CountryRow(val country: String?, val label: String, val shown: Boolean)
data class LanguageRow(val language: String, val countries: List<CountryRow>)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    private val categoryRepo = CategoryRepository(c.db)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(change: (AppSettings) -> AppSettings) { viewModelScope.launch { c.settings.update(change) } }

    // --- Recording (Settings › Playback › Recording) ---
    fun recordingOff() { viewModelScope.launch { c.settings.update { it.copy(recordingOn = false) }; c.recordings.turnOff() } }
    suspend fun recordingFreeBytes(s: AppSettings): Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.recordingStore(s)?.freeBytes() }
    suspend fun testShare(s: AppSettings): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tv.luna.player.recording.testShare(c.smbSettings(s)) }
    fun notice(text: String) { c.recordings.prompt.value = tv.luna.player.recording.Prompt.Notice(text) }

    val guideUpdating: StateFlow<Boolean> = EpgRefreshWorker.updating(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** A second press while one runs used to REPLACE (cancel and restart) it. */
    fun refreshGuide() { if (!guideUpdating.value) EpgRefreshWorker.runNow(getApplication()) }
    /** Re-links only; nothing is downloaded. */
    fun setBorrowSiblingGuide(on: Boolean) = launchFor { p ->
        c.settings.update { it.copy(borrowSiblingGuide = on) }
        val m = c.epgMatcher.rematch(p, borrow = on)
        android.util.Log.i("IptvEpg", "Provider $p: ${m.matched} matched (${m.borrowed} borrowed), ${m.unmatched} unmatched")
    }
    fun setGuideOrder(order: List<GuideKind>) {
        viewModelScope.launch { c.settings.update { it.copy(guideOrder = order) }; EpgRefreshWorker.runNow(getApplication()) }
    }

    // --- Playlists and TV guide ---

    val providers: StateFlow<List<ProviderEntity>> = c.db.providerDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sources(providerId: Long): Flow<List<EpgSourceEntity>> = c.db.epgDao().observeSources(providerId)

    /** The active playlist's EPG sources (TV guide page). */
    val activeSources: StateFlow<List<EpgSourceEntity>> by lazy { perProvider(emptyList()) { sources(it) } } // providerId is declared below

    fun renameSource(id: Long, name: String) { viewModelScope.launch { c.db.epgDao().renameSource(id, name.trim().ifEmpty { null }) } }
    fun setOffset(id: Long, minutes: Int) { viewModelScope.launch { c.db.epgDao().setOffset(id, minutes) } }
    fun addSource(providerId: Long, url: String) {
        viewModelScope.launch { c.providerRepository.addEpgSource(providerId, url.trim(), null); EpgRefreshWorker.runNow(getApplication()) }
    }
    fun removeSource(providerId: Long, id: Long) {
        viewModelScope.launch { c.providerRepository.removeEpgSource(id); c.epgMatcher.rematch(providerId) }
    }

    fun clearGuideData() { viewModelScope.launch { clearGuide(c.db) } }

    /** The playlist page Edit was opened from; Settings reopens it when it comes back. */
    var playlistToReopen: Long? = null

    /** Progress, then the result line, of "Update playlist now", per playlist. */
    val playlistUpdate = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val updating = mutableSetOf<Long>()

    fun updatePlaylist(id: Long) {
        if (!updating.add(id)) return // a second OK while it runs would start a second full sync
        fun show(text: String) { playlistUpdate.value = playlistUpdate.value + (id to text) }
        show("Updating…")
        viewModelScope.launch {
            try {
                val r = c.providerRepository.syncNow(id) { show(it) }
                c.epgMatcher.rematch(id)
                if (settings.value.epgUpdateOnPlaylistChange) EpgRefreshWorker.runNow(getApplication())
                show(r.summary())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show("Update failed: ${e.message ?: e::class.simpleName}")
            } finally {
                updating.remove(id)
            }
        }
    }

    // --- Channels & categories ---

    val providerId: StateFlow<Long?> = c.providerRepository.activeProviderId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private fun <T> perProvider(empty: T, f: (Long) -> Flow<T>): StateFlow<T> =
        providerId.flatMapLatest { it?.let(f) ?: flowOf(empty) }.stateIn(viewModelScope, SharingStarted.Eagerly, empty)
    private fun launchFor(block: suspend (Long) -> Unit) {
        viewModelScope.launch { block(providerId.filterNotNull().first()) }
    }

    val categoryKind = MutableStateFlow(Kind.LIVE)

    val categoryRows: StateFlow<List<CategoryRow>> = perProvider(emptyList()) { p ->
        categoryKind.flatMapLatest { k ->
            val counts: Flow<List<CategoryCount>> = when (k) {
                Kind.LIVE -> c.db.channelDao().observeCounts(p)
                Kind.MOVIE -> c.db.movieDao().observeCounts(p)
                Kind.SERIES -> c.db.seriesDao().observeCounts(p)
            }
            val strip = c.db.providerDao().observe(p).map { parseStripList(it?.categoryStrip.orEmpty()) }
            combine(c.db.categoryDao().observeAll(p, k), counts, strip) { cats, n, s ->
                val byId = n.associate { it.categoryId to it.n }
                cats.map { CategoryRow(it.cleaned(s), byId[it.id] ?: 0) }
            }
        }
    }

    fun setHidden(cat: CategoryEntity, hidden: Boolean) { viewModelScope.launch { c.db.categoryDao().setHidden(cat.id, hidden) } }
    fun setAllHidden(hidden: Boolean) = launchFor { c.db.categoryDao().setHiddenForKind(it, categoryKind.value, hidden) }
    fun move(cat: CategoryEntity, delta: Int) = launchFor { categoryRepo.move(it, cat.kind, cat.id, delta) }

    /** Languages A–Z ("Other" last), each with its countries; a country is shown when any of its categories is. */
    val languages: StateFlow<List<LanguageRow>> = perProvider(emptyList()) { p ->
        c.db.categoryDao().observeAllKinds(p).map { cats ->
            cats.groupBy { it.country }
                .map { (code, list) -> CountryRow(code, countryLabel(code), list.any { !it.hidden }) }
                .groupBy { languageOf(it.country) }
                .map { (lang, list) -> LanguageRow(lang, list.sortedBy { it.label }) }
                .sortedWith(compareBy({ it.language == "Other" }, { it.language }))
        }
    }

    val tvLanguage: String = tvLanguage()

    /** On: show only the TV's language (and categories with none detected); new categories at sync follow it. Off: show all. */
    fun setMatchTvLanguage(on: Boolean) = launchFor { p ->
        c.settings.update { it.copy(matchTvLanguage = on) }
        categoryRepo.matchLanguage(p, if (on) tvLanguage else null)
    }

    fun setCountriesShown(countries: List<String?>, shown: Boolean) = launchFor { p ->
        val dao = c.db.categoryDao()
        countries.filterNotNull().takeIf { it.isNotEmpty() }?.let { dao.setHiddenForCountries(p, it, !shown) }
        if (null in countries) dao.setHiddenForNoCountry(p, !shown)
    }

    val nameStrip: StateFlow<String> = perProvider("") { p -> c.db.providerDao().observe(p).map { it?.nameStrip.orEmpty() } }

    private var logoMatch: kotlinx.coroutines.Job? = null
    /** True while open-source logos are being matched (spinner on the Channel logos rows). */
    val logoMatching = MutableStateFlow(false)
    fun setLogoOrder(order: List<LogoKind>) {
        val had = LogoKind.OPEN_SOURCE in settings.value.logoOrder
        update { it.copy(logoOrder = order) }
        // builtinLogo doesn't depend on the order: match only when open-source logos are newly in use.
        if (LogoKind.OPEN_SOURCE in order && !had && logoMatch?.isActive != true) logoMatch = viewModelScope.launch {
            logoMatching.value = true
            try { providerId.filterNotNull().first().let { c.logos.refreshBuiltin(it) } } finally { logoMatching.value = false }
        }
    }

    // Logo pack (Task 21)
    val packCount: StateFlow<Int> by lazy { perProvider(0) { c.logoPack.observeCount(it) } }
    val packStatus = MutableStateFlow<String?>(null)
    val zipFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    fun refreshZips() { viewModelScope.launch { zipFiles.value = listSharedFiles(getApplication(), setOf("zip")) } }
    fun importPack(uri: Uri, name: String?) = launchFor { p ->
        if (packStatus.value == "Importing…") return@launchFor  // a second OK while one runs
        if (name?.endsWith(".zip", ignoreCase = true) != true) { packStatus.value = "That isn't a .zip file"; return@launchFor }
        packStatus.value = "Importing…"
        packStatus.value = try {
            val r = c.logoPack.import(p) { getApplication<Application>().contentResolver.openInputStream(uri) ?: throw PackException("This zip could not be read") }
            "Matched ${r.matched} of ${r.images} images"
        } catch (e: PackException) {
            e.message
        } catch (e: java.io.IOException) {
            "This zip could not be read"
        }
    }
    fun removePack() = launchFor { c.logoPack.remove(it); packStatus.value = null }

    fun setNameStrip(s: String) = launchFor { c.db.providerDao().setNameStrip(it, s) }

    /** The clean-up text being typed (null when not editing), so the preview follows it before Save. */
    val stripDraft = MutableStateFlow<String?>(null)

    /** First 8 channels whose shown name the clean-up list changes, as before → after. */
    val stripPreview: StateFlow<List<Pair<String, String>>> = perProvider(emptyList()) { p ->
        val names = flow { emit(c.db.channelDao().byProvider(p).filter { it.customName == null }.map { it.name }) }
        combine(names, nameStrip, stripDraft) { all, saved, draft ->
            val s = draft ?: saved
            val strip = parseStripList(s)
            if (strip.isEmpty()) emptyList()
            else all.asSequence().map { it to displayName(it, null, strip) }.filter { it.first != it.second }.take(8).toList()
        }.flowOn(Dispatchers.Default)
    }

    // --- Category name clean-up (same rules and page as the channel one) ---

    val categoryStrip: StateFlow<String> = perProvider("") { p -> c.db.providerDao().observe(p).map { it?.categoryStrip.orEmpty() } }
    fun setCategoryStrip(s: String) = launchFor { c.db.providerDao().setCategoryStrip(it, s) }
    val categoryStripDraft = MutableStateFlow<String?>(null)

    /** First 8 category names (every kind) the clean-up list changes, as before → after. */
    val categoryStripPreview: StateFlow<List<Pair<String, String>>> = perProvider(emptyList()) { p ->
        val names = flow { emit(c.db.categoryDao().allKinds(p).map { it.name }.distinct()) }
        combine(names, categoryStrip, categoryStripDraft) { all, saved, draft ->
            val strip = parseStripList(draft ?: saved)
            if (strip.isEmpty()) emptyList()
            else all.asSequence().map { it to displayName(it, null, strip) }.filter { it.first != it.second }.take(8).toList()
        }.flowOn(Dispatchers.Default)
    }

    val groups: StateFlow<List<CustomGroupEntity>> = perProvider(emptyList()) { c.db.customGroupDao().observe(it) }

    fun createGroup(name: String) = launchFor { c.db.customGroupDao().create(it, name.trim()) }
    fun renameGroup(g: CustomGroupEntity, name: String) { viewModelScope.launch { c.db.customGroupDao().rename(g.id, name.trim()) } }
    fun deleteGroup(g: CustomGroupEntity) { viewModelScope.launch { c.db.customGroupDao().delete(g.id) } }
    /** Saves your categories' order as shown (their sortOrder becomes the position). */
    fun saveGroupOrder(order: List<CustomGroupEntity>) {
        viewModelScope.launch { c.db.withTransaction { order.forEachIndexed { n, it -> c.db.customGroupDao().setSortOrder(it.id, n) } } }
    }

    fun setGroupPlacement(g: CustomGroupEntity, p: tv.luna.player.data.db.GroupPlacement) {
        viewModelScope.launch { c.db.customGroupDao().setPlacement(g.id, p) }
    }

    // --- Backup & restore (spec §7–8) ---

    val backupStatus = MutableStateFlow<String?>(null)
    val backupFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    /** The chosen backup, decoded, while logins are asked for; null otherwise. */
    val pendingRestore = MutableStateFlow<Backup?>(null)
    val restoreRunning = MutableStateFlow(false)

    fun backUpNow() {
        if (restoreRunning.value) return
        backupStatus.value = "Saving…"
        viewModelScope.launch {
            backupStatus.value = try { writeBackup(getApplication(), kotlinx.coroutines.withContext(Dispatchers.Default) { encodeBackup(c.backup.create()) }, settings.value.backupFolder) }
            catch (e: Exception) { "Couldn't save the backup: ${e.message ?: e::class.simpleName}" }
        }
    }

    fun refreshBackups() { viewModelScope.launch { backupFiles.value = listBackups(getApplication(), settings.value.backupFolder) } }
    fun setBackupFolder(path: String?) = update { it.copy(backupFolder = path) }

    /** An unreadable or newer file only shows its message; nothing changes. */
    fun openBackup(f: SharedFile) {
        viewModelScope.launch {
            try {
                val text = readBackup(getApplication(), f.uri)
                pendingRestore.value = kotlinx.coroutines.withContext(Dispatchers.Default) { decodeBackup(text) }
                backupStatus.value = null
            } catch (e: BackupException) {
                backupStatus.value = e.message
            } catch (e: java.io.IOException) {
                backupStatus.value = "Not a readable backup file"
            }
        }
    }

    /** Already on this box with a login: restore keeps that login instead of asking again. */
    fun hasSavedLogin(p: BPlaylist) = providers.value.any {
        it.type.name == p.type && when (it.type) {
            tv.luna.player.data.db.ProviderType.XTREAM -> it.url == p.url && !it.username.isNullOrBlank()
            tv.luna.player.data.db.ProviderType.M3U -> tv.luna.player.data.security.m3uBackupUrl(it.url) == p.url // same playlist, login in its address
        }
    }

    fun cancelRestore() { if (!restoreRunning.value) pendingRestore.value = null }

    /** [logins] by playlist index; a playlist that needs a login and has none here was skipped by the user. */
    fun restore(b: Backup, logins: Map<Int, Pair<String, String>>) {
        if (restoreRunning.value) return
        restoreRunning.value = true
        viewModelScope.launch {
          try {
            var applied = 0
            var skipped = 0
            val failed = mutableListOf<String>()
            b.playlists.forEachIndexed { i, pl ->
                val login = logins[i]
                if (needsLogin(pl) && login == null && !hasSavedLogin(pl)) return@forEachIndexed
                backupStatus.value = "Restoring ${pl.name}…"
                try {
                    val r = c.backup.restorePlaylist(pl, login?.first, login?.second) { backupStatus.value = "${pl.name}: $it" }
                    c.db.providerDao().all().find { it.url == pl.url && it.type.name == pl.type }?.let { c.epgMatcher.rematch(it.id) }
                    applied += r.applied; skipped += r.skipped
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failed += "${pl.name} (${e.message ?: e::class.simpleName})"
                }
            }
            c.backup.restoreSettings(b.settings)
            EpgRefreshWorker.runNow(getApplication())
            backupStatus.value = "Restored $applied settings, skipped $skipped that no longer exist" +
                (if (failed.isEmpty()) "" else ". Not restored: ${failed.joinToString()}")
            pendingRestore.value = null
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            backupStatus.value = "Restore failed: ${e.message ?: e::class.simpleName}"
          } finally {
            restoreRunning.value = false // else Restore stays blocked until the app restarts
          }
        }
    }
}

/** Xtream always; M3U when its backed-up address had its login taken out. */
fun needsLogin(p: BPlaylist) = p.type == tv.luna.player.data.db.ProviderType.XTREAM.name ||
    (p.type == tv.luna.player.data.db.ProviderType.M3U.name && tv.luna.player.data.security.hasLoginPlaceholders(p.url))
