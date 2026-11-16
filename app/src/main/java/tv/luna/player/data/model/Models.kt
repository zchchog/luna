package tv.luna.player.data.model

/** Source-level models produced by a [tv.luna.player.data.source.PlaylistSource]. */

enum class Kind { LIVE, MOVIE, SERIES }

enum class StreamFormat { TS, HLS }

data class Category(val remoteId: String, val name: String, val kind: Kind)

data class Channel(
    val remoteId: String,
    val categoryRemoteId: String?,
    val name: String,
    val logo: String?,
    val number: Int?,
    val tvgId: String?,
    val streamUrl: String,
)

data class Movie(
    val remoteId: String,
    val categoryRemoteId: String?,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val streamUrl: String?,
)

data class Series(
    val remoteId: String,
    val categoryRemoteId: String?,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
)

data class Episode(
    val remoteId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val thumb: String?,
    val durationSecs: Int?,
    val streamUrl: String,
)

data class SeriesDetail(
    val series: Series,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val episodes: List<Episode>,
)

data class MovieDetail(
    val movie: Movie,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val durationSecs: Int?,
    /** Picture height from the provider's info, when it gives one. */
    val height: Int? = null,
)
