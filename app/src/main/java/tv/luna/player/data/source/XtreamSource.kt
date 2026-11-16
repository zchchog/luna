package tv.luna.player.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import okio.BufferedSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import tv.luna.player.data.security.PASS_TOKEN
import tv.luna.player.data.security.USER_TOKEN
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
import tv.luna.player.data.model.StreamFormat
import java.io.IOException

class XtreamAuthException(message: String) : Exception(message)

/** Xtream Codes `player_api.php` source (spec §4.1). Credentials are checked once, on first use. */
class XtreamSource(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val streamFormat: StreamFormat,
    private val http: OkHttpClient,
    private val json: Json,
    private val userAgent: String?,
) : PlaylistSource {

    private val base = baseUrl.trim().trimEnd('/')
    private val baseHttpUrl by lazy { base.toHttpUrl() }

    /** `$base/<kind>/<user>/<pass>/<file>` with every segment percent-encoded. */
    private fun streamUrl(kind: String, file: String): String =
        // Saved without the login (placeholders); the player fills it in, so the database never holds it.
        baseHttpUrl.newBuilder().addPathSegment(kind).addPathSegment(USER_TOKEN).addPathSegment(PASS_TOKEN)
            .addPathSegment(file).build().toString()
    private val authMutex = Mutex()
    private var authenticated = false

    private suspend fun get(action: String?, vararg params: Pair<String, String>): String =
        withBody(action, *params) { it.readUtf8() }

    /** Runs [read] on the response body stream, so large lists are decoded without a full String copy. */
    private suspend fun <T> withBody(action: String?, vararg params: Pair<String, String>, read: (BufferedSource) -> T): T =
        withContext(Dispatchers.IO) {
            val url = "$base/player_api.php".toHttpUrl().newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .apply {
                    action?.let { addQueryParameter("action", it) }
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
            val req = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw XtreamAuthException("Login rejected by server (HTTP ${resp.code})")
                }
                if (!resp.isSuccessful) throw IOException("Xtream request failed: HTTP ${resp.code} ${resp.message}")
                read(resp.body!!.source())
            }
        }

    private suspend fun ensureAuth() = authMutex.withLock {
        if (authenticated) return@withLock
        val info = json.decodeFromString(XAuthResponse.serializer(), get(null)).userInfo
        if (info?.auth != 1) {
            throw XtreamAuthException(info?.message ?: info?.status?.let { "Account $it" } ?: "Invalid username or password")
        }
        authenticated = true
    }

    /** The login's stream limit (user_info.max_connections); null when unknown, 0 or the call fails. */
    suspend fun maxConnections(): Int? = try {
        json.decodeFromString(XAuthResponse.serializer(), get(null)).userInfo?.maxConnections?.takeIf { it > 0 }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    private suspend fun <T> call(action: String, strategy: DeserializationStrategy<T>, vararg params: Pair<String, String>): T {
        ensureAuth()
        return json.decodeFromString(strategy, get(action, *params))
    }

    /** List endpoints sometimes return `{}` or `null` instead of `[]`. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> list(action: String, item: kotlinx.serialization.KSerializer<T>): List<T> {
        ensureAuth()
        return withBody(action) { body ->
            if (firstNonBlankByte(body) == '['.code.toByte()) {
                json.decodeFromStream(ListSerializer(item), body.inputStream())
            } else {
                emptyList()
            }
        }
    }

    /** Peeks past leading whitespace without consuming the body. */
    private fun firstNonBlankByte(body: BufferedSource): Byte? {
        val peek = body.peek()
        while (!peek.exhausted()) {
            val b = peek.readByte()
            if (!b.toInt().toChar().isWhitespace()) return b
        }
        return null
    }

    private suspend fun categories(action: String, kind: Kind) =
        list(action, XCategory.serializer()).mapNotNull { c ->
            c.categoryId?.let { Category(it, c.categoryName ?: it, kind) }
        }

    override suspend fun liveCategories() = categories("get_live_categories", Kind.LIVE)
    override suspend fun vodCategories() = categories("get_vod_categories", Kind.MOVIE)
    override suspend fun seriesCategories() = categories("get_series_categories", Kind.SERIES)

    private val liveExt get() = if (streamFormat == StreamFormat.HLS) "m3u8" else "ts"

    override suspend fun liveChannels() =
        list("get_live_streams", XLiveStream.serializer()).mapNotNull { s ->
            val id = s.streamId ?: return@mapNotNull null
            Channel(
                remoteId = id,
                categoryRemoteId = s.categoryId,
                name = s.name ?: id,
                logo = s.streamIcon,
                number = s.num,
                tvgId = s.epgChannelId,
                streamUrl = streamUrl("live", "$id.$liveExt"),
            )
        }

    private fun XVodStream.toMovie(): Movie? {
        val id = streamId ?: return null
        return Movie(
            remoteId = id,
            categoryRemoteId = categoryId,
            name = name ?: id,
            poster = streamIcon,
            year = year,
            rating = rating,
            streamUrl = streamUrl("movie", "$id.${containerExtension ?: "mp4"}"),
        )
    }

    override suspend fun movies() = list("get_vod_streams", XVodStream.serializer()).mapNotNull { it.toMovie() }

    override suspend fun movieDetail(id: String): MovieDetail {
        val r = call("get_vod_info", XVodInfo.serializer(), "vod_id" to id)
        val info = r.info.asObjectOrNull()?.let { json.decodeFromJsonElement(XVodInfoInfo.serializer(), it) }
        val movie = (r.movieData ?: XVodStream(streamId = id)).let { it.copy(streamId = it.streamId ?: id) }.toMovie()!!
            .let { m ->
                m.copy(
                    name = if (m.name == id) info?.name ?: m.name else m.name,
                    poster = m.poster ?: info?.movieImage,
                    year = m.year ?: yearOf(info?.releasedate),
                    rating = m.rating ?: info?.rating,
                )
            }
        return MovieDetail(
            movie = movie,
            backdrop = firstUrl(info?.backdropPath),
            plot = info?.plot,
            genre = info?.genre,
            director = info?.director,
            cast = info?.cast ?: info?.actors,
            durationSecs = info?.durationSecs,
            height = videoHeight(info?.video),
        )
    }

    override suspend fun series() =
        list("get_series", XSeries.serializer()).mapNotNull { s ->
            val id = s.seriesId ?: return@mapNotNull null
            Series(id, s.categoryId, s.name ?: id, s.cover, yearOf(s.releaseDate), s.rating)
        }

    override suspend fun seriesDetail(id: String): SeriesDetail {
        val r = call("get_series_info", XSeriesInfo.serializer(), "series_id" to id)
        val info = r.info.asObjectOrNull()?.let { json.decodeFromJsonElement(XSeriesInfoInfo.serializer(), it) }
        // `episodes` is normally {"1":[…],"2":[…]} but may be a flat array or [] when empty.
        val groups: List<Pair<Int?, JsonElement>> = when (val e = r.episodes) {
            is JsonObject -> e.entries.map { (season, eps) -> season.toIntOrNull() to eps }
            is JsonArray -> listOf(null to e)
            else -> emptyList()
        }
        val episodes = groups.flatMap { (seasonKey, eps) ->
            (eps as? JsonArray).orEmpty().mapNotNull { el ->
                val ep = (el as? JsonObject)?.let { json.decodeFromJsonElement(XEpisode.serializer(), it) } ?: return@mapNotNull null
                val epId = ep.id ?: return@mapNotNull null
                val epInfo = ep.info.asObjectOrNull()?.let { json.decodeFromJsonElement(XEpisodeInfo.serializer(), it) }
                Episode(
                    remoteId = epId,
                    season = ep.season ?: seasonKey ?: 1,
                    number = ep.episodeNum ?: 0,
                    title = ep.title ?: "Episode ${ep.episodeNum ?: ""}".trim(),
                    plot = epInfo?.plot,
                    thumb = epInfo?.movieImage,
                    durationSecs = epInfo?.durationSecs,
                    streamUrl = streamUrl("series", "$epId.${ep.containerExtension ?: "mp4"}"),
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.number }))
        val series = Series(
            remoteId = id,
            categoryRemoteId = info?.categoryId,
            name = info?.name ?: id,
            poster = info?.cover,
            year = yearOf(info?.releaseDate),
            rating = info?.rating,
        )
        // Providers sometimes repeat an episode id; duplicate list keys crash the detail screen.
        return SeriesDetail(series, firstUrl(info?.backdropPath), info?.plot, info?.genre, episodes.distinctBy { it.remoteId })
    }

    override fun epgUrls() = listOf(
        baseHttpUrl.newBuilder().addPathSegment("xmltv.php")
            .addQueryParameter("username", USER_TOKEN).addQueryParameter("password", PASS_TOKEN).build().toString()
    )
}
