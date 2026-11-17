package tv.luna.player.ui.ondemand

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.data.addons.Catalog
import tv.luna.player.data.addons.Installed
import tv.luna.player.data.addons.Meta
import tv.luna.player.data.addons.MetaPreview
import tv.luna.player.data.addons.NowPlaying
import tv.luna.player.data.addons.Progress
import tv.luna.player.data.addons.Stream
import tv.luna.player.data.source.withStreamHeaders

/** One thing to play: a film, or one episode of a series. */
data class SourceRequest(
    val type: String, val metaId: String, val videoId: String, val imdbId: String?,
    val name: String, val subtitle: String?, val poster: String?, val background: String?, val resumeMs: Long = 0,
    /** Start the best source straight away (the player's Next episode). */
    val autoPlay: Boolean = false,
)

data class SourcesState(val key: String, val loading: Boolean, val streams: List<Stream>)

class OnDemandViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    private val repo get() = c.addons
    val player get() = c.playerManager

    val installed = repo.installed
    val progress: StateFlow<List<Progress>> = repo.progress.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val focused = MutableStateFlow<MetaPreview?>(null)

    /** Per page ("home", "f:<folder>"): the item last opened there, to focus again on coming back. */
    val restore = mutableMapOf<String, String>()
    private val lists = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    /** Scroll positions that outlive the screen, so Back returns to the same place. */
    fun listState(key: String) = lists.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }

    fun homeRows(): List<Pair<Installed, Catalog>> = repo.homeCatalogs()

    val collections = repo.collections

    /** A collection folder's catalogs that the installed add-ons can supply, with any genre filter. */
    fun folderRows(f: tv.luna.player.data.addons.CFolder): List<Pair<Pair<Installed, Catalog>, String?>> {
        val list = installed.value.orEmpty()
        return f.sources.mapNotNull { s -> tv.luna.player.data.addons.resolveSource(list, s)?.let { it to s.genre } }
            .distinctBy { (src, genre) -> src.first.url + src.second.type + src.second.id + genre }
    }

    private val rows = mutableMapOf<String, List<MetaPreview>>()
    suspend fun row(addon: Installed, catalog: Catalog, genre: String? = null): List<MetaPreview> {
        val key = addon.url + "|" + catalog.type + "|" + catalog.id + "|" + genre
        rows[key]?.let { return it }
        val items = runCatching { repo.catalog(addon, catalog, genre = genre) }.getOrDefault(emptyList())
        if (items.isNotEmpty()) rows[key] = items
        return items
    }

    /** Full details by id; a key with null means they couldn't be loaded. */
    val metas = MutableStateFlow<Map<String, Meta?>>(emptyMap())
    fun loadMeta(p: MetaPreview) {
        if (metas.value[p.id] != null) return
        viewModelScope.launch {
            val m = runCatching { repo.meta(p.type, p.id) }.getOrNull()
            metas.value = metas.value + (p.id to m)
        }
    }

    val sources = MutableStateFlow<SourcesState?>(null)
    private var sourcesJob: Job? = null
    fun loadSources(r: SourceRequest) {
        val key = r.type + "|" + r.videoId
        if (sources.value?.key == key && sources.value?.loading == false && sources.value?.streams?.isNotEmpty() == true) return
        sourcesJob?.cancel()
        sources.value = SourcesState(key, true, emptyList())
        sourcesJob = viewModelScope.launch {
            val list = runCatching { repo.streams(r.type, r.videoId, r.imdbId) }.getOrDefault(emptyList())
            sources.value = SourcesState(key, false, list)
        }
    }

    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<Pair<String, List<MetaPreview>>>?>(null)
    private var searchJob: Job? = null
    fun search(q: String) {
        query.value = q
        searchJob?.cancel()
        if (q.isBlank()) { results.value = null; return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            results.value = runCatching { repo.search(q.trim()) }.getOrDefault(emptyList())
        }
    }

    /** Plays [s] for [r], from where it was left; the place is saved when the player closes. */
    val myList = repo.myList.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun toggleMyList(m: MetaPreview) = viewModelScope.launch { repo.toggleMyList(m) }
    val preferredQuality = repo.preferredQuality.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The episode after the one playing, for the player's Next episode button (null: none, or not a series). */
    val upNext = MutableStateFlow<Progress?>(null)

    /** The player's Next episode: the request that plays it. */
    fun nextRequest(): SourceRequest? = upNext.value?.let { n ->
        SourceRequest(n.type, n.metaId, n.videoId, n.imdbId, n.name, n.subtitle?.removePrefix("Up next · "), n.poster, n.background, 0, autoPlay = true)
    }

    /** Next episode pressed: this one counts as watched, so Continue watching moves on. */
    fun markWatched() { repo.nowPlaying?.watched = true }

    fun play(r: SourceRequest, s: Stream) {
        upNext.value = null
        val now = NowPlaying(Progress(r.metaId, r.type, r.videoId, r.name, r.subtitle, r.poster, r.background, 0, 0, System.currentTimeMillis(), r.imdbId))
        repo.nowPlaying = now
        // A series: find the episode after this one, for Up next.
        if (r.type == "series") viewModelScope.launch {
            val meta = metas.value[r.metaId] ?: runCatching { repo.meta(r.type, r.metaId) }.getOrNull() ?: return@launch
            val eps = meta.videos.filter { (it.season ?: 1) > 0 }
            val i = eps.indexOfFirst { it.id == r.videoId }
            val n = eps.getOrNull(i + 1)?.takeIf { i >= 0 } ?: return@launch
            val label = listOfNotNull(n.season?.let { "S$it" }, n.episode?.let { "E$it" }).joinToString(" · ")
            now.next = Progress(r.metaId, r.type, n.id, r.name, "Up next · $label", r.poster, r.background, 0, 0, 0, meta.imdbId ?: r.imdbId)
            upNext.value = now.next
        }
        val agent = s.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        player.play(withStreamHeaders(s.url, s.headers.filterKeys { !it.equals("User-Agent", true) }), agent, null, vod = true)
        if (r.resumeMs > 0) player.player.seekTo(r.resumeMs)
    }

    fun forget(metaId: String) = viewModelScope.launch { repo.forget(metaId) }
}
