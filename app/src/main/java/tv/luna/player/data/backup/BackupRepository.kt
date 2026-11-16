package tv.luna.player.data.backup

import tv.luna.player.data.security.Login
import tv.luna.player.data.security.m3uBackupUrl
import tv.luna.player.data.security.m3uLogin
import tv.luna.player.data.security.withLogin

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.ORIGIN_PLAYLIST
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.StreamFormat
import tv.luna.player.data.repo.ProviderRepository
import tv.luna.player.data.settings.SettingsRepository

/** [applied]/[skipped] count categories, group members and favourites; skipped = remoteId no longer in the playlist. */
data class RestoreResult(val applied: Int, val skipped: Int)

class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val providers: ProviderRepository,
) {
    suspend fun create(): Backup = Backup(
        playlists = db.providerDao().all().map { export(it) },
        settings = BSettings.of(settings.settings.first()),
    )

    private suspend fun export(p: ProviderEntity): BPlaylist {
        val own = providers.ownEpgUrls(p)
        val m3u = m3uLogin(p.url)
        val login = listOfNotNull(p.username, p.password, m3u?.username, m3u?.password).filter { it.isNotBlank() }
        val sources = db.epgDao().sources(p.id).filter { it.origin == ORIGIN_PLAYLIST }
            .filter { s -> s.url in own || !tv.luna.player.data.security.urlHoldsAny(s.url, login) } // a hand-added URL with the login in it is left out
            .map { BEpgSource(it.url.takeUnless { u -> u in own }, it.name, it.priority, it.offsetMinutes) }
        val favourites = Kind.entries.flatMap { kind ->
            val ids = db.favouriteDao().list(p.id, kind).map { it.itemId } // newest first
            val remote: Map<Long, String> = when (kind) {
                Kind.LIVE -> ids.mapNotNull { db.channelDao().get(it) }.associate { it.id to it.remoteId }
                Kind.MOVIE -> db.movieDao().byIds(ids).associate { it.id to it.remoteId }
                Kind.SERIES -> db.seriesDao().byIds(ids).associate { it.id to it.remoteId }
            }
            ids.mapNotNull { remote[it] }.map { BFavourite(kind.name, it) }
        }
        return BPlaylist(
            // Never a login in a backup: an M3U address carrying one keeps placeholders, filled in on restore.
            name = p.name, type = p.type.name, url = if (p.type == ProviderType.M3U) m3uBackupUrl(p.url) else p.url, userAgent = p.userAgent, streamFormat = p.streamFormat.name,
            nameStrip = p.nameStrip, epgSources = sources,
            categories = db.categoryDao().allKinds(p.id).map { BCategory(it.kind.name, it.remoteId, it.hidden, it.sortOrder, it.pinned) },
            groups = db.customGroupDao().observe(p.id).first().map { BGroup(it.name, it.sortOrder, db.customGroupDao().memberRemoteIds(it.id), it.placement.name) },
            favourites = favourites, categoryStrip = p.categoryStrip,
        )
    }

    /**
     * Finds the playlist by (type, url) or adds it, sets the login and name strip, replaces its own EPG sources,
     * syncs, then applies categories, groups and favourites by remoteId. Throws what the sync throws.
     */
    suspend fun restorePlaylist(b: BPlaylist, username: String?, password: String?, onProgress: (String) -> Unit): RestoreResult {
        val type = ProviderType.entries.find { it.name == b.type } ?: throw BackupException("Unknown playlist type ${b.type}")
        val format = StreamFormat.entries.find { it.name == b.streamFormat } ?: StreamFormat.TS
        val existing = db.providerDao().all().find { it.type == type && (it.url == b.url || m3uBackupUrl(it.url) == b.url) }
        // M3U: the login goes back into the address (it never had separate fields); Xtream keeps it in its fields.
        val m3uLogin = if (type == ProviderType.M3U && username != null && password != null) Login(username, password) else null
        val url = m3uLogin?.let { withLogin(b.url, it) } ?: existing?.url ?: b.url
        val id = if (existing != null) {
            providers.update(existing.copy(name = b.name, url = url,
                username = if (m3uLogin != null) existing.username else username ?: existing.username,
                password = if (m3uLogin != null) existing.password else password ?: existing.password, userAgent = b.userAgent, streamFormat = format))
            existing.id
        } else {
            providers.add(ProviderEntity(name = b.name, type = type, url = url,
                username = username.takeIf { m3uLogin == null }, password = password.takeIf { m3uLogin == null },
                userAgent = b.userAgent, streamFormat = format, epgOverrideUrls = emptyList(), lastSync = null))
        }
        db.providerDao().setNameStrip(id, b.nameStrip)
        db.providerDao().setCategoryStrip(id, b.categoryStrip)
        restoreEpgSources(id, b.epgSources)
        providers.syncNow(id, onProgress)
        return db.withTransaction { applyUserData(id, b) }
    }

    /** Sources kept by URL keep their id (and imported guide); the rest are added or removed. epgshare01 ones are untouched. */
    private suspend fun restoreEpgSources(id: Long, wanted: List<BEpgSource>) {
        val own = providers.ownEpgUrls(db.providerDao().get(id)!!).iterator()
        val resolved = wanted.sortedBy { it.priority }.mapNotNull { s -> (s.url ?: own.takeIf { it.hasNext() }?.next())?.let { it to s } }
        val current = db.epgDao().sources(id).filter { it.origin == ORIGIN_PLAYLIST }
        current.filter { c -> resolved.none { it.first == c.url } }.forEach { providers.removeEpgSource(it.id) }
        resolved.forEach { (url, s) ->
            val c = current.find { it.url == url }
            if (c != null) db.epgDao().updateSource(c.copy(name = s.name, priority = s.priority, offsetMinutes = s.offsetMinutes))
            else db.epgDao().insertSource(EpgSourceEntity(providerId = id, url = url, priority = s.priority, offsetMinutes = s.offsetMinutes, name = s.name))
        }
    }

    private suspend fun applyUserData(id: Long, b: BPlaylist): RestoreResult {
        var applied = 0
        var skipped = 0
        val cats = db.categoryDao().allKinds(id).associateBy { it.kind.name to it.remoteId }
        db.categoryDao().update(b.categories.mapNotNull { bc ->
            cats[bc.kind to bc.remoteId]?.copy(hidden = bc.hidden, sortOrder = bc.sortOrder, pinned = bc.pinned)
        })
        b.categories.forEach { if (cats.containsKey(it.kind to it.remoteId)) applied++ else skipped++ }

        val channels = db.channelDao().byProvider(id).associate { it.remoteId to it.id }
        val groups = db.customGroupDao().observe(id).first().associateBy { it.name }
        b.groups.sortedBy { it.sortOrder }.forEach { g ->
            // Every member gone from the playlist: don't make an empty group (one saved empty is restored).
            if (g.channels.isNotEmpty() && g.channels.none { it in channels } && g.name !in groups) { skipped += g.channels.size; return@forEach }
            val gid = groups[g.name]?.id ?: db.customGroupDao().create(id, g.name)
            tv.luna.player.data.db.GroupPlacement.entries.find { it.name == g.placement }?.let { db.customGroupDao().setPlacement(gid, it) }
            g.channels.forEach { r -> channels[r]?.let { db.customGroupDao().addChannel(gid, it); applied++ } ?: skipped++ }
        }

        val byKind = b.favourites.groupBy { it.kind }
        val now = System.currentTimeMillis()
        var n = 0
        byKind.forEach { (kindName, favs) ->
            val kind = Kind.entries.find { it.name == kindName }
            val ids: Map<String, Long> = when (kind) {
                Kind.LIVE -> channels
                Kind.MOVIE -> db.movieDao().byProvider(id).associate { it.remoteId to it.id }
                Kind.SERIES -> db.seriesDao().byProvider(id).associate { it.remoteId to it.id }
                null -> emptyMap()
            }
            favs.forEach { f ->
                val item = ids[f.remoteId]
                if (kind != null && item != null) { db.favouriteDao().add(FavouriteEntity(id, kind, item, now - n++)); applied++ } else skipped++
            }
        }
        return RestoreResult(applied, skipped)
    }

    suspend fun restoreSettings(s: BSettings) = settings.update { s.toAppSettings(it.lastLiveChannelId).copy(backupFolder = it.backupFolder, recordUsbPath = it.recordUsbPath, smbPassword = it.smbPassword) }
}
