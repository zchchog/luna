package tv.luna.player.data.source

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun xtreamJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** Primitive content of [e] as text; null for JSON null, empty strings, objects and arrays. */
private fun lenientText(e: JsonElement): String? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> e.content.trim().takeIf { it.isNotEmpty() && it != "null" }
    else -> null
}

private abstract class LenientSerializer<T : Any>(name: String, kind: PrimitiveKind) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, kind)
    abstract fun convert(text: String): T?
    override fun deserialize(decoder: Decoder): T? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return lenientText(element)?.let(::convert)
    }
    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}

/** Accepts number, numeric string, "", null. */
object LenientIntSerializer : KSerializer<Int?> by object : LenientSerializer<Int>("LenientInt", PrimitiveKind.INT) {
    override fun convert(text: String) = text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
}

object LenientDoubleSerializer : KSerializer<Double?> by object : LenientSerializer<Double>("LenientDouble", PrimitiveKind.DOUBLE) {
    override fun convert(text: String) = text.toDoubleOrNull()
}

/** Accepts string or number; "" and null become null. */
object LenientStringSerializer : KSerializer<String?> by object : LenientSerializer<String>("LenientString", PrimitiveKind.STRING) {
    override fun convert(text: String) = text
}

typealias LInt = @Serializable(with = LenientIntSerializer::class) Int?
typealias LDouble = @Serializable(with = LenientDoubleSerializer::class) Double?
typealias LString = @Serializable(with = LenientStringSerializer::class) String?

@Serializable
data class XAuthResponse(@SerialName("user_info") val userInfo: XUserInfo? = null)

@Serializable
data class XUserInfo(val auth: LInt = null, val status: LString = null, val message: LString = null,
                     @SerialName("max_connections") val maxConnections: LInt = null)

@Serializable
data class XCategory(
    @SerialName("category_id") val categoryId: LString = null,
    @SerialName("category_name") val categoryName: LString = null,
)

@Serializable
data class XLiveStream(
    val num: LInt = null,
    val name: LString = null,
    @SerialName("stream_id") val streamId: LString = null,
    @SerialName("stream_icon") val streamIcon: LString = null,
    @SerialName("epg_channel_id") val epgChannelId: LString = null,
    @SerialName("category_id") val categoryId: LString = null,
)

@Serializable
data class XVodStream(
    val name: LString = null,
    @SerialName("stream_id") val streamId: LString = null,
    @SerialName("stream_icon") val streamIcon: LString = null,
    val rating: LDouble = null,
    val year: LInt = null,
    @SerialName("category_id") val categoryId: LString = null,
    @SerialName("container_extension") val containerExtension: LString = null,
)

@Serializable
data class XSeries(
    @SerialName("series_id") val seriesId: LString = null,
    val name: LString = null,
    val cover: LString = null,
    val rating: LDouble = null,
    val releaseDate: LString = null,
    @SerialName("category_id") val categoryId: LString = null,
)

/** Free-form sections (`info`, `episodes`) are kept as JSON: providers send `[]` where an object is expected. */
@Serializable
data class XSeriesInfo(
    val info: JsonElement? = null,
    val episodes: JsonElement? = null,
)

@Serializable
data class XSeriesInfoInfo(
    val name: LString = null,
    val cover: LString = null,
    val plot: LString = null,
    val genre: LString = null,
    val releaseDate: LString = null,
    val rating: LDouble = null,
    @SerialName("backdrop_path") val backdropPath: JsonElement? = null,
    @SerialName("category_id") val categoryId: LString = null,
)

@Serializable
data class XEpisode(
    val id: LString = null,
    @SerialName("episode_num") val episodeNum: LInt = null,
    val title: LString = null,
    @SerialName("container_extension") val containerExtension: LString = null,
    val season: LInt = null,
    val info: JsonElement? = null,
)

@Serializable
data class XEpisodeInfo(
    @SerialName("duration_secs") val durationSecs: LInt = null,
    val plot: LString = null,
    @SerialName("movie_image") val movieImage: LString = null,
)

@Serializable
data class XVodInfo(val info: JsonElement? = null, @SerialName("movie_data") val movieData: XVodStream? = null)

@Serializable
data class XVodInfoInfo(
    val name: LString = null,
    @SerialName("movie_image") val movieImage: LString = null,
    @SerialName("backdrop_path") val backdropPath: JsonElement? = null,
    val plot: LString = null,
    val genre: LString = null,
    val director: LString = null,
    val cast: LString = null,
    val actors: LString = null,
    @SerialName("duration_secs") val durationSecs: LInt = null,
    val releasedate: LString = null,
    val rating: LDouble = null,
    val video: JsonElement? = null,
)

/** `video.height` from get_vod_info (some panels send ffprobe data there), when it is a positive number. */
internal fun videoHeight(video: JsonElement?): Int? =
    video.asObjectOrNull()?.get("height")?.let(::lenientText)?.toIntOrNull()?.takeIf { it > 0 }

/** First URL from a `backdrop_path` that may be a string, an array of strings or null. */
internal fun firstUrl(e: JsonElement?): String? = when (e) {
    is JsonArray -> e.firstNotNullOfOrNull { lenientText(it) }
    null -> null
    else -> lenientText(e)
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

/** Leading 4-digit year of "2019-04-01" / "2019". */
internal fun yearOf(s: String?): Int? = s?.let { Regex("""^\s*(\d{4})""").find(it)?.groupValues?.get(1)?.toInt() }
