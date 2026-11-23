package tv.luna.player.ui.live

import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.db.login

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.data.catalogue.parseStripList
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.catalogue.isHeaderChannel
import tv.luna.player.data.epg.guideDonors
import tv.luna.player.data.epg.guideSiblings
import tv.luna.player.data.db.CustomGroupEntity
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.model.Kind
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.data.repo.CategoryRepository
import tv.luna.player.data.repo.GuideRepository
import tv.luna.player.data.repo.RailEntry
import tv.luna.player.data.repo.RailItem
import tv.luna.player.data.repo.RailRepository
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.LogoKind

data class RailCategory(val category: CategoryEntity, val count: Int)

/** Where the guide list was scrolled when it was left, so coming back from full screen doesn't move it. */
data class GuidePos(val channelId: Long?, val index: Int, val offset: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    private val guide = GuideRepository(c.db)
    private val railRepo = RailRepository(c.db)
    val player get() = c.playerManager

    private val provider: StateFlow<ProviderEntity?> = c.providerRepository.activeProviderId
        .flatMapLatest { id -> id?.let { c.db.providerDao().observe(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Name clean-up list of the active playlist; edits in Settings show up in the guide at once. */
    val strip: StateFlow<List<String>> = provider.map { parseStripList(it?.nameStrip.orEmpty()) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Keyed on the id only: the provider row also changes on every sync (lastSync) and name-strip edit.
    private val providerId = provider.map { it?.id }.distinctUntilChanged().filterNotNull()

    // Counts are observed too: a background re-sync can add categories while the guide is open.
    /** Null until first loaded, so the guide can tell "everything hidden" from "not loaded yet". */
    val rail: StateFlow<List<RailItem>?> = providerId.flatMapLatest { railRepo.rail(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val entry = MutableStateFlow<RailEntry?>(null)

    /** The selected entry's channels tagged with its key, so the guide can tell a stale list from the new one. */
    // Keyed on the provider too: "fav" is the same key in every playlist.
    private val hideHeaders = c.settings.settings.map { it.hideHeaderChannels }.distinctUntilChanged()

    val listing: StateFlow<Pair<String?, List<ChannelEntity>>> = combine(providerId, entry) { p, e -> p to e }
        .distinctUntilChangedBy { (p, e) -> p to e?.key }
        .flatMapLatest { (p, e) ->
            if (e == null) flowOf(null to emptyList())
            else combine(railRepo.channels(p, e), hideHeaders) { list, hide -> e.key to if (hide) list.filterNot { isHeaderChannel(it.name) } else list }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null to emptyList())

    val channels: StateFlow<List<ChannelEntity>> = listing.map { it.second }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ticks every 30s for the "now" line and progress bars. */
    val now: StateFlow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /** Half-hours the guide is scrolled forward from the current half-hour. */
    val timeShift = MutableStateFlow(0)

    val windowStart: StateFlow<Long> = combine(now.map { Timeline.windowStart(it) }.distinctUntilChanged(), timeShift) { w, s ->
        w + s * HALF_HOUR
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Timeline.windowStart(System.currentTimeMillis()))

    /** Covers the visible window and "now" (for the hero) even when scrolled forward. */
    // observeGuideVersion counts links; replacing one channel's link doesn't change it, so Use guide from… bumps this.
    private val guideBump = MutableStateFlow(0)

    val programmes: StateFlow<Map<Long, List<GuideProgramme>>> =
        combine(channels, windowStart, c.db.epgDao().observeGuideVersion(), guideBump) { ch, w, _, _ -> ch to w } // reload after an EPG import
        .mapLatest { (ch, w) ->
            val p = provider.value ?: return@mapLatest emptyMap()
            val from = minOf(w, System.currentTimeMillis())
            guide.programmes(p.id, ch, from, w + WINDOW)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val focused = MutableStateFlow<ChannelEntity?>(null)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val guideUpdating: StateFlow<Boolean> = tv.luna.player.work.EpgRefreshWorker.updating(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val logoSource: StateFlow<List<LogoKind>> = c.settings.settings.map { it.logoOrder }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings().logoOrder)

    private val previewed = MutableStateFlow<ChannelEntity?>(null)

    /** The channel the player is showing; cleared when the player stops or plays something else (a movie). */
    val previewingId: StateFlow<Long?> = combine(previewed, player.playingUrl) { ch, url -> ch?.id?.takeIf { ch.streamUrl == url } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var guidePos: GuidePos? = null

    init {
        viewModelScope.launch {
            // A recreated activity (e.g. reopened after Back-exit) gets a fresh view model while the process-wide
            // player resumes the old stream: open the guide on that channel, or the preview would jump to the top row.
            val p = provider.filterNotNull().first()
            val playing = player.currentUrl?.let { c.db.channelDao().byStreamUrl(p.id, it) }
            if (playing != null) { focused.value = playing; previewed.value = playing }
            // Also moves off an entry that vanished or belongs to a previously active provider.
            rail.filterNotNull().collect { list ->
                val entries = list.map { it.entry }
                entry.value = entries.firstOrNull { it.key == entry.value?.key }
                    ?: entries.firstOrNull { it is RailEntry.Provider && it.category.id == playing?.categoryId }
                    ?: entries.firstOrNull()
            }
        }
    }

    /** Before switching playlists: forget the old one's position so the guide opens the new one from the top. */
    fun resetForPlaylistSwitch() {
        // Entry null until the new rail arrives: keys such as "recent" exist in every playlist and would keep the old list.
        entry.value = null
        focused.value = null
        previewed.value = null
        guidePos = null
        versionIndex = null
        timeShift.value = 0
        focusAt.value = null
    }

    fun select(e: RailEntry) {
        entry.value = e
        timeShift.value = 0
        focusAt.value = null
    }

    /** The moment the highlight is on, once the viewer has moved along the guide; null = now. */
    val focusAt = MutableStateFlow<Long?>(null)

    /** Scroll the window so it starts at the half-hour that holds [t]. */
    private fun showTime(t: Long) {
        val base = Timeline.windowStart(now.value)
        timeShift.value = ((Timeline.windowStart(t) - base) / HALF_HOUR).toInt().coerceIn(0, MAX_SHIFT)
    }

    /**
     * Right on a guide row: the highlight moves to the channel's next programme. The window only scrolls when that
     * programme would sit in its last half hour, and a row with no guide just moves half an hour on. Returns false at the end.
     */
    fun stepForward(progs: List<GuideProgramme>): Boolean {
        val at = focusAt.value ?: now.value
        val next = progs.firstOrNull { it.start > at }
        if (next == null) {
            if (!shiftTime(1)) return false
            focusAt.value = maxOf(at, windowStart.value)
            return true
        }
        val end = windowStart.value + WINDOW
        focusAt.value = next.start
        if (next.start >= end - HALF_HOUR) showTime(next.start)
        return true
    }

    /**
     * Left on a guide row: the highlight moves to the previous programme, back as far as now. Returns false when it is
     * already on now, which is where Left opens the category list.
     */
    fun stepBack(progs: List<GuideProgramme>): Boolean {
        val at = focusAt.value ?: return false
        val here = progs.firstOrNull { it.start <= at && at < it.stop }
        val before = progs.lastOrNull { it.start < (here?.start ?: at) }
        if (before == null || before.stop <= now.value || before.start <= now.value) {
            focusAt.value = null
            timeShift.value = 0
        } else {
            focusAt.value = before.start
            if (before.start < windowStart.value) showTime(before.start)
        }
        return true
    }

    fun shiftTime(by: Int): Boolean {
        val next = timeShift.value + by
        if (next < 0 || next > MAX_SHIFT) return false
        timeShift.value = next
        return true
    }

    fun preview(ch: ChannelEntity) {
        previewed.value = ch
        player.play(ch.streamUrl, provider.value?.userAgent, provider.value?.login())
        viewModelScope.launch { c.settings.update { it.copy(lastLiveChannelId = ch.id) } }
    }

    /** Recently watched channels of the active playlist, newest first (spec §4.2). */
    val recents: StateFlow<List<ChannelEntity>> = providerId.flatMapLatest { c.db.recentDao().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A channel started full screen (or zapped to): Recents and the last channel. Previews only set the last channel. */
    fun watched(ch: ChannelEntity) {
        viewModelScope.launch {
            c.db.recentDao().record(ch.providerId, ch.id, System.currentTimeMillis())
            c.settings.update { it.copy(lastLiveChannelId = ch.id) }
        }
    }

    /** Now and next for the overlay's Recents cards, which may be outside the selected category. */
    val recentProgrammes: StateFlow<Map<Long, List<GuideProgramme>>> =
        combine(recents, now, c.db.epgDao().observeGuideVersion()) { r, n, _ -> r to n }
            .mapLatest { (r, n) -> provider.value?.let { guide.programmes(it.id, r, n, n + 12 * 3_600_000L) }.orEmpty() } // now and next
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Full screen Up/Down (spec §4.2): the next/previous channel of the selected category, wrapping. */
    fun zapBy(delta: Int) {
        if (listing.value.first != entry.value?.key) return // a just-switched category's list hasn't loaded: don't zap in the old one
        val list = channels.value
        val id = zap(list.map { it.id }, focused.value?.id ?: return, delta) ?: return
        val ch = list.first { it.id == id }
        if (ch.id == focused.value?.id) return // a category of one zaps to itself
        focused.value = ch
        preview(ch) // also writes lastLiveChannelId
        if (zapRecordsRecent(entry.value)) watched(ch)
    }

    /** A Recents card: play it and select its category, so Up/Down then zaps there and Back lands on it in the guide. */
    fun switchTo(ch: ChannelEntity) {
        rail.value?.map { it.entry }?.find { it is RailEntry.Provider && it.category.id == ch.categoryId }?.let { select(it) }
        focused.value = ch
        preview(ch)
        watched(ch)
    }

    private var versionIndex: tv.luna.player.data.catalogue.VersionIndex? = null
    private var versionIndexFor: Long? = null

    /** The other versions of [ch] in its playlist (HD, 4K, +1...), best first; empty when there are none. Built once per playlist. */
    suspend fun versionsOf(ch: ChannelEntity): List<ChannelEntity> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (versionIndexFor != ch.providerId || versionIndex == null) {
            versionIndex = tv.luna.player.data.catalogue.VersionIndex(c.db.channelDao().byProvider(ch.providerId).filter { !it.hidden })
            versionIndexFor = ch.providerId
        }
        versionIndex!!.versionsOf(ch)
    }

    /** Launch on the last channel full screen: select its category and focus it so Back lands on it in the guide. */
    fun startOn(ch: ChannelEntity) {
        focused.value = ch
        preview(ch)
        watched(ch)
        viewModelScope.launch {
            rail.filterNotNull().first { it.isNotEmpty() }.map { it.entry }
                .find { it is RailEntry.Provider && it.category.id == ch.categoryId }?.let { select(it) }
        }
    }

    // --- Long-press menu actions (spec §4.3) ---

    val favouriteIds: StateFlow<Set<Long>> = providerId
        .flatMapLatest { p -> c.db.favouriteDao().observe(p, Kind.LIVE).map { f -> f.mapTo(HashSet()) { it.itemId } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val groups: StateFlow<List<CustomGroupEntity>> = providerId.flatMapLatest { c.db.customGroupDao().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggleFavourite(ch: ChannelEntity) {
        viewModelScope.launch {
            val dao = c.db.favouriteDao()
            if (ch.id in favouriteIds.value) dao.remove(ch.providerId, Kind.LIVE, ch.id)
            else dao.add(FavouriteEntity(ch.providerId, Kind.LIVE, ch.id, System.currentTimeMillis()))
        }
    }

    fun addToGroup(ch: ChannelEntity, groupId: Long) { viewModelScope.launch { c.db.customGroupDao().addChannel(groupId, ch.id) } }

    fun removeFromGroup(ch: ChannelEntity, groupId: Long) { viewModelScope.launch { c.db.customGroupDao().removeChannel(groupId, ch.id) } }

    fun addToNewGroup(ch: ChannelEntity, name: String) {
        viewModelScope.launch { c.db.customGroupDao().run { addChannel(create(ch.providerId, name), ch.id) } }
    }

    /** Blank resets to the provider's name. */
    fun rename(ch: ChannelEntity, name: String?) {
        viewModelScope.launch { c.db.channelDao().setCustomName(ch.id, name?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    // Change logo… (spec §4.3)
    suspend fun logoCandidates(ch: ChannelEntity): List<String> = c.logos.candidatesFor(ch)
    fun setCustomLogoUrl(ch: ChannelEntity, url: String) { viewModelScope.launch { c.logos.setCustomUrl(ch.id, url) } }
    fun setCustomLogoFile(ch: ChannelEntity, uri: android.net.Uri, ext: String) {
        viewModelScope.launch {
            runCatching { c.logos.setCustomFromFile(ch.id, { getApplication<android.app.Application>().contentResolver.openInputStream(uri)!! }, ext) }
                .onFailure { android.util.Log.w("LiveViewModel", "custom logo: $it") }
        }
    }
    fun useAutomaticLogo(ch: ChannelEntity) { viewModelScope.launch { c.logos.clearCustom(ch.id) } }

    // Use guide from… (spec §5.4)
    /** Donors with their category name, for "<name> · <category>". */
    suspend fun donors(ch: ChannelEntity, query: String): List<Pair<ChannelEntity, String?>> {
        // The channel list and its same-name siblings (~3 s on a TV box) are worked out once per channel, not per search.
        val pool = donorPool?.takeIf { it.channelId == ch.id } ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val list = c.db.channelDao().withGuide(ch.providerId)
            DonorPool(ch.id, list, guideSiblings(ch, list), c.db.categoryDao().allKinds(ch.providerId).associate { it.id to it.name })
        }.also { donorPool = it }
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { guideDonors(ch, pool.list, query, siblings = pool.siblings) }
        return found.map { it to it.categoryId?.let(pool.cats::get) }
    }
    private class DonorPool(val channelId: Long, val list: List<ChannelEntity>, val siblings: List<ChannelEntity>, val cats: Map<Long, String>)
    private var donorPool: DonorPool? = null
    /** The picker closed: links may change before it opens again. */
    fun clearDonors() { donorPool = null }
    suspend fun hasManualGuide(ch: ChannelEntity): Boolean = c.db.epgDao().link(ch.id)?.manual == true
    fun useGuideFrom(ch: ChannelEntity, donor: ChannelEntity) {
        viewModelScope.launch {
            val l = c.db.epgDao().link(donor.id) ?: return@launch
            c.db.epgDao().upsertLinks(listOf(EpgLinkEntity(ch.id, l.sourceId, l.xmltvId, manual = true)))
            guideBump.value++
        }
    }
    fun useAutomaticGuide(ch: ChannelEntity) {
        viewModelScope.launch {
            c.db.epgDao().deleteLink(ch.id)
            c.epgMatcher.rematch(ch.providerId)  // follows Guide source and Borrow settings
            guideBump.value++
        }
    }

    // --- Recording (channel menu) ---

    val recordingOn: StateFlow<Boolean> = c.settings.settings.map { it.recordingOn }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** (channelId, programme start) of scheduled/recording programmes: the guide's dot and the menu's mark. */
    val marks: StateFlow<Map<Pair<Long, Long>, Boolean>> = c.recordings.marks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val seriesRules: StateFlow<List<tv.luna.player.recording.SeriesRuleEntity>> = c.recordings.rules().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun recordSeries(ch: ChannelEntity, p: GuideProgramme) { viewModelScope.launch { c.recordings.recordSeries(ch, ch.displayName(strip.value), p) } }
    fun stopSeries(id: Long, deleteRecorded: Boolean) { viewModelScope.launch { c.recordings.stopSeries(id, deleteRecorded, c::deleteRecordingFile) } }
    /** Channel id → the recording running on it. */
    val recordingOnChannel: StateFlow<Map<Long, Long>> = c.recordings.running
        .map { m -> m.values.mapNotNull { r -> r.channelId?.let { it to r.id } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Now and the next programmes (up to 8, next 24 h) for "Record a later programme…". */
    suspend fun upcoming(ch: ChannelEntity): List<GuideProgramme> {
        val t = System.currentTimeMillis()
        return guide.programmes(ch.providerId, listOf(ch), t, t + 24 * 3_600_000L)[ch.id].orEmpty().take(8)
    }

    fun recordNow(ch: ChannelEntity) {
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            c.recordings.recordNow(ch, ch.displayName(strip.value), upcoming(ch).firstOrNull { it.start <= t && t < it.stop })
        }
    }

    fun schedule(ch: ChannelEntity, p: GuideProgramme) { viewModelScope.launch { c.recordings.schedule(ch, ch.displayName(strip.value), p) } }

    /** The scheduled (or running) recording of [p] on [ch]: cancelled, or stopped if it has started. */
    fun cancelRecording(ch: ChannelEntity, p: GuideProgramme) {
        viewModelScope.launch {
            val r = c.db.recordingDao().onChannel(ch.id, listOf(tv.luna.player.recording.RecStatus.SCHEDULED, tv.luna.player.recording.RecStatus.RECORDING))
                .firstOrNull { it.programmeStart == p.start } ?: return@launch
            if (r.status == tv.luna.player.recording.RecStatus.RECORDING) c.recordings.stop(r.id) else c.recordings.cancel(r.id)
        }
    }

    fun stopRecording(id: Long) = c.recordings.stop(id)

    // --- Where recordings go (asked the first time) ---

    /** "this box", "the USB drive"… for the channel menu. */
    val recordPlace: StateFlow<String> = c.settings.settings.map { s ->
        when (s.recordTo) {
            tv.luna.player.recording.RecStorage.INTERNAL -> "this box"
            tv.luna.player.recording.RecStorage.USB -> "the USB drive"
            tv.luna.player.recording.RecStorage.SMB -> "the network share"
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "this box")

    /** USB drives plugged in now, with their names. */
    fun usbDrives(): List<Pair<String, String>> = tv.luna.player.recording.usbDirs(getApplication()).map { dir ->
        val name = runCatching {
            getApplication<android.app.Application>().getSystemService(android.os.storage.StorageManager::class.java)
                .getStorageVolume(dir)?.getDescription(getApplication())
        }.getOrNull() ?: "USB drive"
        name to dir.path
    }

    /** Whether a network share has been set up in Settings. */
    suspend fun shareSetUp() = c.settings.settings.first().smbServer.isNotBlank()

    /** Turns recording on, saving to [to] ([usbPath]: which USB drive). */
    suspend fun setRecordPlace(to: tv.luna.player.recording.RecStorage, usbPath: String? = null) {
        c.settings.update { it.copy(recordingOn = true, recordTo = to, recordUsbPath = usbPath ?: it.recordUsbPath) }
    }

    // --- Reminders (channel menu) ---

    /** (channelId, programme start) with a reminder set. */
    val reminderMarks: StateFlow<Set<Pair<Long, Long>>> = c.reminders.marks.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun remind(ch: ChannelEntity, p: GuideProgramme) { viewModelScope.launch { c.reminders.add(ch, p) } }
    fun cancelReminder(ch: ChannelEntity, p: GuideProgramme) { viewModelScope.launch { c.reminders.cancel(ch, p) } }

    fun hide(ch: ChannelEntity) { viewModelScope.launch { c.db.channelDao().setHidden(ch.id, true) } }

    fun hideCategory(cat: CategoryEntity) { viewModelScope.launch { c.db.categoryDao().setHidden(cat.id, true) } }

    /** Moves [delta] places among the categories the rail shows, so a step never vanishes behind a hidden one. */
    fun moveCategory(cat: CategoryEntity, delta: Int) {
        viewModelScope.launch {
            val all = c.db.categoryDao().all(cat.providerId, Kind.LIVE)
            val visible = all.filter { !it.hidden || it.id == cat.id }
            val i = visible.indexOfFirst { it.id == cat.id }.takeIf { it >= 0 } ?: return@launch
            val target = visible[(i + delta).coerceIn(0, visible.lastIndex)]
            CategoryRepository(c.db).move(cat.providerId, Kind.LIVE, cat.id,
                all.indexOfFirst { it.id == target.id } - all.indexOfFirst { it.id == cat.id })
        }
    }

    companion object {
        const val HALF_HOUR = 30 * 60_000L
        const val WINDOW = 4 * HALF_HOUR
        const val MAX_SHIFT = 7 * 48 // 7 days of half-hours
    }
}
