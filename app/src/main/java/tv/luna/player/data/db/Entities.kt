package tv.luna.player.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tv.luna.player.data.model.Kind
import tv.luna.player.data.model.StreamFormat

enum class ProviderType { XTREAM, M3U }

/** Where a user category sits in the category list (Your categories › Options › Priority). */
enum class GroupPlacement { TOP, NORMAL, BOTTOM }

enum class NameRuleType { BUILTIN, FIND_REPLACE, REGEX }

/** [EpgSourceEntity.origin]: provider/user URLs, or epgshare01 files managed by the app. */
const val ORIGIN_PLAYLIST = "PLAYLIST"
const val ORIGIN_EPGSHARE = "EPGSHARE"

@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ProviderType,
    val url: String,
    val username: String?,
    val password: String?,
    val userAgent: String?,
    val streamFormat: StreamFormat,
    /** Empty = use the source's own EPG URLs. */
    val epgOverrideUrls: List<String>,
    val lastSync: Long?,
    @ColumnInfo(defaultValue = "") val nameStrip: String = "",
    /** Category name clean-up list, same format as [nameStrip]. */
    @ColumnInfo(defaultValue = "") val categoryStrip: String = "",
    /** Xtream user_info.max_connections, refreshed when recording needs it; null = unknown (M3U). */
    val maxConnections: Int? = null,
)

@Entity(
    tableName = "category",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["providerId", "kind", "remoteId"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: Kind,
    val remoteId: String,
    val name: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val pinned: Boolean = false,
    val country: String? = null,
)

@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val customName: String? = null,
    val logo: String?,
    val customLogo: String? = null,
    val number: Int?,
    val customNumber: Int? = null,
    val hidden: Boolean = false,
    val tvgId: String?,
    val epgChannelId: String? = null,
    val epgOffsetMinutes: Int? = null,
    val streamUrl: String,
    /** File path of the logo matched from the user's logo pack. */
    val packLogo: String? = null,
    /** tv-logos path, e.g. `countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png` (not a URL). */
    val builtinLogo: String? = null,
)

@Entity(
    tableName = "movie",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId"), Index(value = ["providerId", "categoryId"])],
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val streamUrl: String?,
    val addedAt: Long = 0,
    // Detail fields, filled when the detail page is opened.
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val durationSecs: Int? = null,
)

@Entity(
    tableName = "series",
    foreignKeys = [
        ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["providerId", "remoteId"], unique = true), Index("categoryId"), Index(value = ["providerId", "categoryId"])],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val categoryId: Long?,
    val remoteId: String,
    val name: String,
    val poster: String?,
    val year: Int?,
    val rating: Double?,
    val addedAt: Long = 0,
    val backdrop: String? = null,
    val plot: String? = null,
    val genre: String? = null,
)

/** Seasons are derived from [season]; there is no season table. */
@Entity(
    tableName = "episode",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["seriesId", "remoteId"], unique = true)],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val remoteId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val thumb: String?,
    val durationSecs: Int?,
    val streamUrl: String,
)

@Entity(
    tableName = "epg_source",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val url: String,
    val priority: Int,
    val offsetMinutes: Int = 0,
    val lastFetched: Long? = null,
    val name: String? = null,
    val lastStatus: String? = null,
    @ColumnInfo(defaultValue = ORIGIN_PLAYLIST) val origin: String = ORIGIN_PLAYLIST,
    /** The programme generation the guide shows; an import writes the next one, then switches (no long write lock). */
    @ColumnInfo(defaultValue = "0") val gen: Int = 0,
)

/** One row per (source, xmltv id, display name): an XMLTV channel can have several display names. */
@Entity(
    tableName = "epg_channel",
    primaryKeys = ["sourceId", "xmltvId", "displayName"],
    foreignKeys = [ForeignKey(entity = EpgSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("xmltvId")],
)
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    val displayName: String,
    val normalisedName: String,
    val icon: String?,
)

@Entity(
    tableName = "programme",
    indices = [Index(value = ["epgChannelKey", "start"]), Index("sourceId")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** `"$sourceId|$xmltvId"` */
    val epgChannelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    val episodeNum: String?,
    val icon: String?,
    /** Import generation; only rows of the source's current [EpgSourceEntity.gen] are shown. */
    @ColumnInfo(defaultValue = "0") val gen: Int = 0,
) {
    companion object {
        fun key(sourceId: Long, xmltvId: String) = "$sourceId|$xmltvId"
    }
}

@Entity(
    tableName = "epg_link",
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
)
data class EpgLinkEntity(
    @PrimaryKey val channelId: Long,
    val sourceId: Long,
    val xmltvId: String,
    val manual: Boolean,
)

@Entity(
    tableName = "name_rule",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class NameRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    @ColumnInfo(name = "ruleOrder") val order: Int,
    val enabled: Boolean,
    val type: NameRuleType,
    /** For BUILTIN: the BuiltinRule name. */
    val pattern: String,
    val replacement: String? = null,
)

@Entity(
    tableName = "hide_rule",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class HideRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val kind: Kind,
    val regex: String,
)

/** [itemId] is the local row id of the channel / movie / series. */
@Entity(tableName = "favourite", primaryKeys = ["providerId", "kind", "itemId"])
data class FavouriteEntity(
    val providerId: Long,
    val kind: Kind,
    val itemId: Long,
    val addedAt: Long,
)

/** [itemId] is the local row id of the movie (kind MOVIE) or the episode (kind SERIES). */
@Entity(tableName = "progress", primaryKeys = ["providerId", "kind", "itemId"])
data class ProgressEntity(
    val providerId: Long,
    val kind: Kind,
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
)

@Entity(
    tableName = "recent_channel",
    primaryKeys = ["providerId", "channelId"],
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("channelId")],
)
data class RecentChannelEntity(
    val providerId: Long,
    val channelId: Long,
    val watchedAt: Long,
)

@Entity(
    tableName = "reminder",
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("channelId")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val programmeStart: Long,
    val title: String,
)

@Entity(
    tableName = "custom_group",
    foreignKeys = [ForeignKey(entity = ProviderEntity::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("providerId")],
)
data class CustomGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "NORMAL") val placement: GroupPlacement = GroupPlacement.NORMAL,
)

@Entity(
    tableName = "custom_group_channel",
    primaryKeys = ["groupId", "channelId"],
    foreignKeys = [
        ForeignKey(entity = CustomGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChannelEntity::class, parentColumns = ["id"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("channelId")],
)
data class CustomGroupChannelEntity(
    val groupId: Long,
    val channelId: Long,
    val sortOrder: Int,
)

/** The Xtream login, for filling the placeholders in saved links; null for M3U or a playlist without one. */
fun ProviderEntity.login(): tv.luna.player.data.security.Login? =
    if (type == ProviderType.XTREAM && username != null && password != null) tv.luna.player.data.security.Login(username, password) else null
