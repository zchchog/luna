package tv.luna.player.data.repo

import tv.luna.player.data.model.Category
import tv.luna.player.data.model.Channel
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.Movie
import tv.luna.player.data.model.MovieDetail
import tv.luna.player.data.model.Series
import tv.luna.player.data.model.SeriesDetail
import tv.luna.player.data.source.PlaylistSource

class FakeSource(
    var liveCats: List<Category> = emptyList(),
    var channels: List<Channel> = emptyList(),
    var vodCats: List<Category> = emptyList(),
    var movieList: List<Movie> = emptyList(),
    var seriesCats: List<Category> = emptyList(),
    var seriesList: List<Series> = emptyList(),
    var epg: List<String> = emptyList(),
    var skippedCount: Int = 0,
) : PlaylistSource {
    override suspend fun skipped() = skippedCount
    override suspend fun liveCategories() = liveCats
    override suspend fun liveChannels() = channels
    override suspend fun vodCategories() = vodCats
    override suspend fun movies() = movieList
    override suspend fun movieDetail(id: String): MovieDetail = error("unused")
    override suspend fun seriesCategories() = seriesCats
    override suspend fun series() = seriesList
    override suspend fun seriesDetail(id: String): SeriesDetail = error("unused")
    override fun epgUrls() = epg
}

fun live(id: String, name: String) = Category(id, name, Kind.LIVE)

fun channel(id: String, name: String, cat: String? = "5", number: Int? = null, logo: String? = null) =
    Channel(id, cat, name, logo = logo, number = number, tvgId = null, streamUrl = "http://h/live/u/p/$id.ts")
