package tv.luna.player.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.luna.player.data.model.Category
import tv.luna.player.data.model.Channel
import tv.luna.player.data.model.Episode
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.Movie
import tv.luna.player.data.model.MovieDetail
import tv.luna.player.data.model.Series
import tv.luna.player.data.model.SeriesDetail
import java.io.IOException

/**
 * M3U playlist source. The playlist is fetched once per instance (one instance per sync).
 * M3U has no ids, so an entry's URL is its remoteId and a group-title is its category's remoteId.
 */
class M3uSource(
    private val url: String,
    private val epgUrls: List<String>,
    private val http: OkHttpClient,
    private val userAgent: String?,
) : PlaylistSource {

    private class Catalogue(
        val live: List<M3uEntry>,
        val movies: List<M3uEntry>,
        val series: Map<String, SeriesDetail>,
        val skipped: Int,
    )

    private val mutex = Mutex()
    private var cached: Catalogue? = null

    /** Malformed lines skipped by the last fetch. */
    override suspend fun skipped(): Int = catalogue().skipped

    private suspend fun catalogue(): Catalogue = mutex.withLock {
        cached ?: build(fetch()).also { cached = it }
    }

    private suspend fun fetch(): M3uParseResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Playlist request failed: HTTP ${resp.code} ${resp.message}")
            M3uParser.parse(resp.body!!.byteStream())
        }
    }

    private fun build(parsed: M3uParseResult): Catalogue {
        val live = ArrayList<M3uEntry>()
        val movies = ArrayList<M3uEntry>()
        val seriesEps = LinkedHashMap<String, MutableList<M3uEntry>>()
        for (e in parsed.entries) {
            val kind = M3uParser.classify(e)
            if (kind == Kind.LIVE) { live += e; continue }
            val title = M3uParser.seriesTitle(e.name)
            when {
                title != null -> seriesEps.getOrPut(seriesId(title)) { ArrayList() } += e
                kind == Kind.SERIES -> seriesEps.getOrPut(seriesId(e.name)) { ArrayList() } += e
                else -> movies += e
            }
        }
        val series = seriesEps.mapValues { (id, eps) ->
            val first = eps.first()
            val s = Series(
                remoteId = id,
                categoryRemoteId = first.attrs["group-title"],
                name = M3uParser.seriesTitle(first.name) ?: first.name,
                poster = first.attrs["tvg-logo"],
                year = null, rating = null,
            )
            val episodes = eps.distinctBy { it.key }.mapIndexed { i, e ->
                val (season, number) = M3uParser.seasonEpisode(e.name) ?: (1 to i + 1)
                Episode(e.key, season, number, e.name, null, e.attrs["tvg-logo"], null, e.url)
            }.sortedWith(compareBy({ it.season }, { it.number }))
            SeriesDetail(s, backdrop = null, plot = null, genre = null, episodes = episodes)
        }
        return Catalogue(live, movies, series, parsed.skipped)
    }

    private fun seriesId(title: String) = "series:" + title.trim().lowercase()

    private fun categories(entries: List<M3uEntry>, kind: Kind) =
        entries.mapNotNull { it.attrs["group-title"]?.takeIf(String::isNotBlank) }
            .distinct().map { Category(it, it, kind) }

    /** The link without its "|Header=…" suffix: the sync key, so a header change doesn't re-key the user's data. */
    private val M3uEntry.key get() = splitStreamHeaders(url).first

    private fun M3uEntry.toMovie() = Movie(key, attrs["group-title"], name, attrs["tvg-logo"], null, null, url)

    override suspend fun liveCategories() = categories(catalogue().live, Kind.LIVE)

    override suspend fun liveChannels() = catalogue().live.map {
        Channel(
            remoteId = it.key,
            categoryRemoteId = it.attrs["group-title"]?.takeIf(String::isNotBlank),
            name = it.name,
            logo = it.attrs["tvg-logo"]?.takeIf(String::isNotBlank),
            number = it.attrs["tvg-chno"]?.toIntOrNull(),
            tvgId = it.attrs["tvg-id"]?.takeIf(String::isNotBlank),
            streamUrl = it.url,
        )
    }

    override suspend fun vodCategories() = categories(catalogue().movies, Kind.MOVIE)
    override suspend fun movies() = catalogue().movies.map { it.toMovie() }

    override suspend fun movieDetail(id: String): MovieDetail {
        val e = catalogue().movies.firstOrNull { it.key == id } ?: throw NoSuchElementException("No movie $id")
        return MovieDetail(e.toMovie(), null, null, null, null, null, null)
    }

    override suspend fun seriesCategories() =
        catalogue().series.values.mapNotNull { it.series.categoryRemoteId?.takeIf(String::isNotBlank) }
            .distinct().map { Category(it, it, Kind.SERIES) }

    override suspend fun series() = catalogue().series.values.map { it.series }

    override suspend fun seriesDetail(id: String) =
        catalogue().series[id] ?: throw NoSuchElementException("No series $id")

    override fun epgUrls() = epgUrls
}
