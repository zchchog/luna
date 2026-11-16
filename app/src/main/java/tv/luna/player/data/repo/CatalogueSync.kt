package tv.luna.player.data.repo

import androidx.room.withTransaction
import tv.luna.player.data.db.ChannelFromProvider
import tv.luna.player.data.db.MovieFromProvider
import tv.luna.player.data.db.SeriesFromProvider
import tv.luna.player.data.catalogue.detectCountry
import tv.luna.player.data.catalogue.matchesLanguage
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.MovieEntity
import tv.luna.player.data.db.SeriesEntity
import tv.luna.player.data.model.Category
import tv.luna.player.data.model.Kind
import tv.luna.player.data.source.PlaylistSource

data class SyncResult(
    val categories: Int,
    val channels: Int,
    val movies: Int,
    val series: Int,
    val newlyHidden: Int,
    /** Malformed source entries skipped (spec §7). */
    val skipped: Int = 0,
) {
    fun summary() = "$categories categories, $channels channels, $movies movies, $series series, $newlyHidden hidden, $skipped skipped."
}

/**
 * Writes a source's catalogue into Room (spec §4.2/§4.3): upsert by (providerId, remoteId), keep user
 * fields, delete what disappeared (with its favourites/progress), hide new categories matching a hide rule.
 * Everything is fetched before any write, so a network failure leaves the cached catalogue untouched.
 */
class CatalogueSync(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    /** "Match TV language": the language new categories must have to start shown, or null when off. */
    private val keepLanguage: suspend () -> String? = { null },
) {
    companion object {
        /** remoteId of the synthetic per-kind category for items with no (known) category. */
        const val UNCATEGORISED = ""
    }


    /**
     * Kinds are fetched and written one after another to bound peak memory on low-RAM TV boxes.
     * Each kind is fully fetched before its transaction, so a failure keeps that kind's cached data.
     */
    suspend fun sync(providerId: Long, source: PlaylistSource, onProgress: (String) -> Unit = {}): SyncResult {
        onProgress("Loading live TV…")
        val live = syncLive(providerId, source.liveCategories(), source.liveChannels())
        onProgress("${live.items} channels saved · loading movies…")
        val vod = syncMovies(providerId, source.vodCategories(), source.movies())
        onProgress("${live.items} channels, ${vod.items} movies saved · loading series…")
        val ser = syncSeries(providerId, source.seriesCategories(), source.series())
        return SyncResult(
            categories = live.categories + vod.categories + ser.categories,
            channels = live.items, movies = vod.items, series = ser.items,
            newlyHidden = live.newlyHidden + vod.newlyHidden + ser.newlyHidden,
            skipped = source.skipped(),
        )
    }

    /** Items with no category, or one the provider didn't list, go to a synthetic "Uncategorised" category. */
    private fun withUncategorised(cats: List<Category>, kind: Kind, itemCats: List<String?>): Pair<List<Category>, (String?) -> String> {
        val known = cats.mapTo(HashSet()) { it.remoteId }
        val resolve = { r: String? -> if (r != null && r in known) r else UNCATEGORISED }
        val needed = itemCats.any { it == null || it !in known }
        return (if (needed) cats + Category(UNCATEGORISED, "Uncategorised", kind) else cats) to resolve
    }

    private data class KindResult(val categories: Int, val items: Int, val newlyHidden: Int)
    private data class CatResult(val idByRemote: Map<String, Long>, val newlyHidden: Int)

    private suspend fun syncCategories(providerId: Long, kind: Kind, incoming: List<Category>): CatResult {
        val dao = db.categoryDao()
        val hideRules = db.ruleDao().hideRules(providerId, kind).mapNotNull {
            runCatching { Regex(it.regex, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        val existing = dao.byRemoteIds(providerId, kind).associateBy { it.remoteId }
        val language = keepLanguage()
        var nextOrder = (existing.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var newlyHidden = 0
        val unique = incoming.distinctBy { it.remoteId }
        val rows = unique.map { c ->
            existing[c.remoteId]?.copy(name = c.name, country = detectCountry(c.name)) ?: run {
                val country = detectCountry(c.name)
                val hidden = hideRules.any { it.containsMatchIn(c.name) } || (language != null && !matchesLanguage(country, language))
                if (hidden) newlyHidden++
                CategoryEntity(providerId = providerId, kind = kind, remoteId = c.remoteId, name = c.name,
                    sortOrder = nextOrder++, hidden = hidden, country = country)
            }
        }
        dao.upsert(rows)
        dao.deleteMissing(providerId, kind, unique.map { it.remoteId })
        return CatResult(dao.byRemoteIds(providerId, kind).associate { it.remoteId to it.id }, newlyHidden)
    }

    // No transaction around a whole kind (46k channels, 185k movies): new rows go in chunks, rows the provider changed
    // get a provider-columns-only update, unchanged rows aren't written at all, so user writes never wait long.
    private suspend fun syncLive(providerId: Long, cats: List<Category>, channels: List<tv.luna.player.data.model.Channel>): KindResult {
        val unique = channels.distinctBy { it.remoteId }
        val (allCats, resolve) = withUncategorised(cats, Kind.LIVE, unique.map { it.categoryRemoteId })
        val c = db.withTransaction { syncCategories(providerId, Kind.LIVE, allCats) }
        val dao = db.channelDao()
        val existing = dao.byProvider(providerId).associateBy { it.remoteId }
        val fresh = ArrayList<ChannelEntity>(); val changed = ArrayList<ChannelFromProvider>()
        unique.forEach { ch ->
            val catId = c.idByRemote[resolve(ch.categoryRemoteId)]
            val old = existing[ch.remoteId]
            if (old == null) fresh += ChannelEntity(providerId = providerId, categoryId = catId, remoteId = ch.remoteId, name = ch.name,
                logo = ch.logo, number = ch.number, tvgId = ch.tvgId, streamUrl = ch.streamUrl)
            else ChannelFromProvider(old.id, ch.name, ch.logo, ch.number, ch.tvgId, ch.streamUrl, catId).let { n ->
                if (n != ChannelFromProvider(old.id, old.name, old.logo, old.number, old.tvgId, old.streamUrl, old.categoryId)) changed += n
            }
        }
        fresh.chunked(WRITE_CHUNK).forEach { dao.upsert(it) }
        changed.chunked(WRITE_CHUNK).forEach { dao.updateFromProvider(it) }
        val keep = unique.mapTo(HashSet()) { it.remoteId }
        existing.values.filter { it.remoteId !in keep }.map { it.id }.chunked(SQL_DELETE_CHUNK).forEach { ids ->
            db.withTransaction { dao.deleteIds(ids); db.favouriteDao().deleteForItems(providerId, Kind.LIVE, ids) }
        }
        return KindResult(c.idByRemote.size, unique.size, c.newlyHidden)
    }

    private suspend fun syncMovies(providerId: Long, cats: List<Category>, movies: List<tv.luna.player.data.model.Movie>): KindResult {
        val unique = movies.distinctBy { it.remoteId }
        val (allCats, resolve) = withUncategorised(cats, Kind.MOVIE, unique.map { it.categoryRemoteId })
        val c = db.withTransaction { syncCategories(providerId, Kind.MOVIE, allCats) }
        val dao = db.movieDao()
        val existing = dao.byProvider(providerId).associateBy { it.remoteId }
        val t = now()
        val fresh = ArrayList<MovieEntity>(); val changed = ArrayList<MovieFromProvider>()
        unique.forEach { m ->
            val catId = c.idByRemote[resolve(m.categoryRemoteId)]
            val old = existing[m.remoteId]
            if (old == null) fresh += MovieEntity(providerId = providerId, categoryId = catId, remoteId = m.remoteId, name = m.name,
                poster = m.poster, year = m.year, rating = m.rating, streamUrl = m.streamUrl, addedAt = t)
            else MovieFromProvider(old.id, m.name, m.poster, m.year, m.rating, m.streamUrl, catId).let { n ->
                if (n != MovieFromProvider(old.id, old.name, old.poster, old.year, old.rating, old.streamUrl, old.categoryId)) changed += n
            }
        }
        fresh.chunked(WRITE_CHUNK).forEach { dao.upsert(it) }
        changed.chunked(WRITE_CHUNK).forEach { dao.updateFromProvider(it) }
        val keep = unique.mapTo(HashSet()) { it.remoteId }
        existing.values.filter { it.remoteId !in keep }.map { it.id }.chunked(SQL_DELETE_CHUNK).forEach { ids ->
            db.withTransaction {
                dao.deleteIds(ids)
                db.favouriteDao().deleteForItems(providerId, Kind.MOVIE, ids)
                db.progressDao().deleteForItems(providerId, Kind.MOVIE, ids)
            }
        }
        return KindResult(c.idByRemote.size, unique.size, c.newlyHidden)
    }

    private suspend fun syncSeries(providerId: Long, cats: List<Category>, series: List<tv.luna.player.data.model.Series>): KindResult {
        val unique = series.distinctBy { it.remoteId }
        val (allCats, resolve) = withUncategorised(cats, Kind.SERIES, unique.map { it.categoryRemoteId })
        val c = db.withTransaction { syncCategories(providerId, Kind.SERIES, allCats) }
        val dao = db.seriesDao()
        val existing = dao.byProvider(providerId).associateBy { it.remoteId }
        val t = now()
        val fresh = ArrayList<SeriesEntity>(); val changed = ArrayList<SeriesFromProvider>()
        unique.forEach { s ->
            val catId = c.idByRemote[resolve(s.categoryRemoteId)]
            val old = existing[s.remoteId]
            if (old == null) fresh += SeriesEntity(providerId = providerId, categoryId = catId, remoteId = s.remoteId, name = s.name,
                poster = s.poster, year = s.year, rating = s.rating, addedAt = t)
            else SeriesFromProvider(old.id, s.name, s.poster, s.year, s.rating, catId).let { n ->
                if (n != SeriesFromProvider(old.id, old.name, old.poster, old.year, old.rating, old.categoryId)) changed += n
            }
        }
        fresh.chunked(WRITE_CHUNK).forEach { dao.upsert(it) }
        changed.chunked(WRITE_CHUNK).forEach { dao.updateFromProvider(it) }
        val keep = unique.mapTo(HashSet()) { it.remoteId }
        existing.values.filter { it.remoteId !in keep }.map { it.id }.chunked(SQL_DELETE_CHUNK).forEach { ids ->
            db.withTransaction {
                // Series progress is per episode (ProgressEntity.itemId = episode id).
                val goneEpisodes = dao.episodeIds(ids)
                dao.deleteIds(ids)
                db.favouriteDao().deleteForItems(providerId, Kind.SERIES, ids)
                db.progressDao().deleteForItems(providerId, Kind.SERIES, goneEpisodes)
            }
        }
        return KindResult(c.idByRemote.size, unique.size, c.newlyHidden)
    }
}

private const val WRITE_CHUNK = 2_000
private const val SQL_DELETE_CHUNK = 900 // SQLite's bound-variable limit
