package tv.luna.player.ui.vod

import tv.luna.player.data.db.login

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.MutableStateFlow
import tv.luna.player.data.catalogue.cleaned
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.FavouriteEntity
import tv.luna.player.data.db.MovieEntity
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType
import tv.luna.player.data.db.SeriesEntity
import tv.luna.player.data.model.Episode
import tv.luna.player.data.model.Kind
import tv.luna.player.data.source.PlaylistSource
import tv.luna.player.ui.live.RailCategory

/** A movie or a series, as shown on posters and in the hero. */
data class VodItem(
    val id: Long, val kind: Kind, val remoteId: String, val name: String, val poster: String?,
    val year: Int?, val rating: Double?, val categoryId: Long?, val streamUrl: String?,
)

/** Fetched on demand from the provider (Xtream get_vod_info / get_series_info). */
data class VodDetail(
    val backdrop: String?, val plot: String?, val genre: String?, val director: String?, val cast: String?,
    val durationSecs: Int?, val episodes: List<Episode> = emptyList(), val height: Int? = null,
)

fun MovieEntity.item() = VodItem(id, Kind.MOVIE, remoteId, name, poster, year, rating, categoryId, streamUrl)
fun SeriesEntity.item() = VodItem(id, Kind.SERIES, remoteId, name, poster, year, rating, categoryId, null)

/** In-category matches: null for a blank query (normal view), else [search] of the scope (kind, category or null = all) after a 300 ms pause. */
@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
fun vodMatches(query: kotlinx.coroutines.flow.Flow<String>, scope: kotlinx.coroutines.flow.Flow<Pair<Kind, Long?>>,
               version: kotlinx.coroutines.flow.Flow<Long> = flowOf(0L),
               search: suspend (Kind, Long?, String) -> List<VodItem>): kotlinx.coroutines.flow.Flow<List<VodItem>?> =
    combine(query, scope) { q, s -> q to s }
        .debounce { (q, _) -> if (tv.luna.player.data.search.queryWords(q).isEmpty()) 0L else 300L }
        .distinctUntilChanged()
        .combine(version) { qs, _ -> qs } // a catalogue write re-runs the same query
        .mapLatest { (q, s) -> if (tv.luna.player.data.search.queryWords(q).isEmpty()) null else search(s.first, s.second, q) }

/** A new kind or category starts with an empty search bar. */
suspend fun clearQueryOnScopeChange(scope: kotlinx.coroutines.flow.Flow<Pair<Kind, Long?>>, query: MutableStateFlow<String>) =
    scope.distinctUntilChanged().drop(1).collect { query.value = "" }

@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    val player get() = c.playerManager

    private val provider: StateFlow<ProviderEntity?> = c.providerRepository.activeProviderId
        .flatMapLatest { id -> id?.let { c.db.providerDao().observe(it) } ?: flowOf(null) } // an edit applies at once
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // One source per provider row: Xtream checks credentials once per instance; an edited row gets a new one.
    private var source: Pair<ProviderEntity, PlaylistSource>? = null
    private fun source(p: ProviderEntity) = source?.takeIf { it.first == p }?.second
        ?: c.sourceFor(p).also { source = p to it }

    val kind = MutableStateFlow(Kind.MOVIE)
    // Rows key on the id only: the provider row also changes on every sync and name clean-up edit.
    private val providerId = provider.filterNotNull().map { it.id }.distinctUntilChanged()

    private val categoryStrip = provider.map { tv.luna.player.data.catalogue.parseStripList(it?.categoryStrip.orEmpty()) }.distinctUntilChanged()

    val categories: StateFlow<List<RailCategory>> = combine(providerId, kind) { p, k -> p to k }
        .flatMapLatest { (p, k) ->
            val counts = if (k == Kind.MOVIE) c.db.movieDao().observeCounts(p) else c.db.seriesDao().observeCounts(p)
            combine(c.db.categoryDao().observeVisible(p, k), counts, categoryStrip) { cats, n, strip ->
                val byId = n.associate { it.categoryId to it.n }
                cats.map { RailCategory(it.cleaned(strip), byId[it.id] ?: 0) }.filter { it.count > 0 }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favourites: StateFlow<List<VodItem>> = combine(providerId, kind) { p, k -> p to k }
        .flatMapLatest { (p, k) -> c.db.favouriteDao().observe(p, k) }
        .mapLatest { favs -> items(kind.value, favs.map(FavouriteEntity::itemId)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Category shown as a full poster grid (picked from the rail); null = home rows. */
    val gridCategory = MutableStateFlow<CategoryEntity?>(null)
    val grid: StateFlow<List<VodItem>> = gridCategory
        .mapLatest { cat -> cat?.let { inCategory(it.id, GRID_LIMIT) } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The in-category search bar's text; [matches] null = no query, show the normal rows/grid. */
    val query = MutableStateFlow("")
    private val searchScope = combine(kind, gridCategory) { k, g -> k to g?.id }
    val matches: StateFlow<List<VodItem>?> = vodMatches(query, searchScope, c.search.version) { k, cat, q ->
        provider.value?.let { c.search.inCategory(it.id, k, cat, q) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun warmSearch() { viewModelScope.launch { provider.value?.let { c.search.warm(it.id, emptyList(), listOf(kind.value)) } } }

    val focused = MutableStateFlow<VodItem?>(null)
    val details = MutableStateFlow<Map<Long, VodDetail>>(emptyMap())

    init {
        viewModelScope.launch { clearQueryOnScopeChange(searchScope, query) }
        // Leave a grid whose category vanished or belongs to a previously active provider.
        viewModelScope.launch {
            categories.collect { list ->
                gridCategory.value?.let { g -> if (list.none { it.category.id == g.id }) gridCategory.value = null }
            }
        }
    }

    fun setKind(k: Kind) {
        if (kind.value == k) return
        kind.value = k
        gridCategory.value = null
        focused.value = null
    }

    suspend fun inCategory(categoryId: Long, limit: Int = ROW_LIMIT): List<VodItem> =
        if (kind.value == Kind.MOVIE) c.db.movieDao().inCategory(categoryId, limit).map { it.item() }
        else c.db.seriesDao().inCategory(categoryId, limit).map { it.item() }

    private suspend fun items(k: Kind, ids: List<Long>): List<VodItem> {
        if (ids.isEmpty()) return emptyList()
        val byId = if (k == Kind.MOVIE) c.db.movieDao().byIds(ids).associate { it.id to it.item() }
        else c.db.seriesDao().byIds(ids).associate { it.id to it.item() }
        return ids.mapNotNull { byId[it] }
    }

    /** Loads plot/backdrop/episodes once per item; failures are logged and leave the hero without them. */
    fun loadDetail(item: VodItem) {
        if (details.value.containsKey(item.id)) return
        val p = provider.value ?: return
        // M3U has no movie details, and asking would download and hold the whole playlist.
        if (item.kind == Kind.MOVIE && p.type == ProviderType.M3U) return
        viewModelScope.launch {
            runCatching {
                if (item.kind == Kind.MOVIE) source(p).movieDetail(item.remoteId).let {
                    VodDetail(it.backdrop, it.plot, it.genre, it.director, it.cast, it.durationSecs, height = it.height)
                } else source(p).seriesDetail(item.remoteId).let {
                    VodDetail(it.backdrop, it.plot, it.genre, null, null, null, it.episodes)
                }
            }.onSuccess { d -> details.value = details.value + (item.id to d) }
                .onFailure { Log.w("IptvVod", "Detail failed for ${item.name}", it) }
        }
    }

    fun toggleFavourite(item: VodItem) {
        val p = provider.value ?: return
        viewModelScope.launch {
            if (favourites.value.any { it.id == item.id }) c.db.favouriteDao().remove(p.id, item.kind, item.id)
            else c.db.favouriteDao().add(FavouriteEntity(p.id, item.kind, item.id, System.currentTimeMillis()))
        }
    }

    // shortcut: learned heights live in a small prefs file, not Room; move them to the DB if the list grows large.
    private val heightPrefs = app.getSharedPreferences("vod_heights", android.content.Context.MODE_PRIVATE)
    private fun heightKey(item: VodItem) = "${provider.value?.id}:${item.kind}:${item.remoteId}"
    /** Heights read from streams as they played (B1), so the detail page can show them next time. */
    val learnedHeights = MutableStateFlow<Map<String, Int>>(emptyMap())
    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { // a file read: off the main thread
            learnedHeights.value = heightPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap() + learnedHeights.value
        }
    }
    fun learnedHeight(item: VodItem): Int? = learnedHeights.value[heightKey(item)]
    private var learn: kotlinx.coroutines.Job? = null

    /** Plays [url]; for [item] (the movie, or the series an episode belongs to) remembers the picture height once known. */
    fun play(url: String, item: VodItem? = null) {
        player.play(url, provider.value?.userAgent, provider.value?.login(), vod = true)
        learn?.cancel()
        if (item == null) return
        val key = heightKey(item)
        learn = viewModelScope.launch {
            // Only while this stream is still the one playing: a live channel tuned after a failed start mustn't count.
            val h = player.formats.takeWhile { player.playingUrl.value == url }.firstOrNull { f -> f.height != null }?.height ?: return@launch
            if (learnedHeights.value[key] != h) { heightPrefs.edit().putInt(key, h).apply(); learnedHeights.value = learnedHeights.value + (key to h) }
        }
    }

    companion object {
        const val ROW_LIMIT = 30
        const val GRID_LIMIT = 600
    }
}
