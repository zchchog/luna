package tv.luna.player.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.InvalidationTracker
import kotlinx.coroutines.flow.update
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.catalogue.isHeaderChannel
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.SearchRow
import tv.luna.player.data.model.Kind
import tv.luna.player.ui.vod.VodItem
import tv.luna.player.ui.vod.item

data class Section<T>(val items: List<T>, val total: Int)

data class SearchResults(val channels: Section<ChannelEntity>, val movies: Section<VodItem>, val series: Section<VodItem>) {
    companion object { val EMPTY = SearchResults(Section(emptyList(), 0), Section(emptyList(), 0), Section(emptyList(), 0)) }
}

/**
 * Name search over the active playlist's visible items (search spec). The folded names are cached per
 * (provider, kind) and rebuilt after any write to the tables they come from (Room's invalidation tracker: a sync,
 * hide/show, rename) or a clean-up list change, so a query is a scan of folded strings in memory, off the main thread.
 */
class SearchRepository(private val db: AppDatabase) {
    /** Bumped by every write that could change results; screens re-run their query on it. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Writes per table, so a channel write (logo/guide rematch) rebuilds only the channel index. */
    private val writes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("provider", "category", "channel", "movie", "series")) {
            override fun onInvalidated(tables: Set<String>) {
                tables.forEach { t -> writes.merge(t, 1L, Long::plus) }
                version.update { it + 1 }
            }
        })
    }

    private fun tableOf(kind: Kind) = when (kind) { Kind.LIVE -> "channel"; Kind.MOVIE -> "movie"; Kind.SERIES -> "series" }

    private class Entry(val id: Long, val folded: String, val number: Int?, val categoryId: Long?)
    private class Index(val key: String, val entries: List<Entry>)

    private val lock = Mutex()
    private val cache = HashMap<Pair<Long, Kind>, Index>()

    /** Builds the indexes ahead of the first query (about 4 s for the whole catalogue on a Chromecast). */
    suspend fun warm(providerId: Long, strip: List<String>, kinds: List<Kind> = Kind.entries) =
        withContext(Dispatchers.Default) { kinds.forEach { index(providerId, it, strip) } }

    suspend fun universal(providerId: Long, strip: List<String>, query: String, cap: Int = 50): SearchResults = withContext(Dispatchers.Default) {
        val words = queryWords(query)
        if (words.isEmpty()) return@withContext SearchResults.EMPTY
        val ch = find(providerId, Kind.LIVE, strip, words, null)
        val mv = find(providerId, Kind.MOVIE, strip, words, null)
        val se = find(providerId, Kind.SERIES, strip, words, null)
        SearchResults(
            Section(load(ch.take(cap)) { db.channelDao().byIds(it).associateBy { c -> c.id } }, ch.size),
            Section(load(mv.take(cap)) { db.movieDao().byIds(it).associate { m -> m.id to m.item() } }, mv.size),
            Section(load(se.take(cap)) { db.seriesDao().byIds(it).associate { s -> s.id to s.item() } }, se.size),
        )
    }

    /** [categoryId] null = every visible item of [kind]. No cap. */
    suspend fun inCategory(providerId: Long, kind: Kind, categoryId: Long?, query: String): List<VodItem> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        val ids = find(providerId, kind, emptyList(), words, categoryId)
        return withContext(Dispatchers.Default) { ids.chunked(500).flatMap { part ->
            load(part) { if (kind == Kind.MOVIE) db.movieDao().byIds(it).associate { m -> m.id to m.item() } else db.seriesDao().byIds(it).associate { s -> s.id to s.item() } }
        } }
    }

    /** Ranked ids of the matches. */
    private suspend fun find(providerId: Long, kind: Kind, strip: List<String>, words: List<String>, categoryId: Long?): List<Long> {
        val entries = index(providerId, kind, strip)
        return withContext(Dispatchers.Default) {
            val hits = entries.filter { (categoryId == null || it.categoryId == categoryId) && matchesAll(it.folded, words) }
            rank(hits.map { Hit(it.id, it.folded, it.number) }, words)
        }
    }

    private suspend fun index(providerId: Long, kind: Kind, strip: List<String>): List<Entry> = lock.withLock {
        // Read before loading: a write during the build bumps it again, so the next query rebuilds.
        val key = listOf("provider", "category", tableOf(kind)).joinToString(",") { "${writes[it] ?: 0}" } + "|" + (if (kind == Kind.LIVE) strip.joinToString("\u001f") else "")
        cache[providerId to kind]?.takeIf { it.key == key }?.let { return it.entries }
        val rows: List<SearchRow> = when (kind) {
            Kind.LIVE -> db.channelDao().searchable(providerId)
            Kind.MOVIE -> db.movieDao().searchable(providerId)
            Kind.SERIES -> db.seriesDao().searchable(providerId)
        }
        val entries = withContext(Dispatchers.Default) {
            rows.mapNotNull { r ->
                when {
                    kind != Kind.LIVE -> Entry(r.id, foldForSearch(r.name), null, r.categoryId)
                    isHeaderChannel(r.name) -> null // "#### SKY CINEMA ####" divider rows are not channels
                    // Shown name first (ranking reads its start), then the provider name so a stripped tag still matches.
                    else -> Entry(r.id, foldForSearch(displayName(r.name, r.customName, strip)) + " " + foldForSearch(r.name), r.number, r.categoryId)
                }
            }
        }
        cache[providerId to kind] = Index(key, entries)
        entries
    }

    /** Full rows for [ids], kept in [ids] order. */
    private suspend fun <T> load(ids: List<Long>, fetch: suspend (List<Long>) -> Map<Long, T>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val byId = fetch(ids)
        return ids.mapNotNull { byId[it] }
    }
}
