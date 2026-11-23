package tv.luna.player.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.AudioDecoder
import tv.luna.player.data.settings.GuideKind
import tv.luna.player.data.settings.LogoKind

const val BACKUP_VERSION = 1

class BackupException(message: String) : Exception(message)

/** Backup file (spec §7). Never holds a username or password; enums are stored by name so unknown ones read as defaults. */
@Serializable
data class Backup(val version: Int = BACKUP_VERSION, val playlists: List<BPlaylist>, val settings: BSettings)

@Serializable
data class BPlaylist(
    val name: String,
    val type: String,
    val url: String,
    val userAgent: String?,
    val streamFormat: String,
    val nameStrip: String,
    val epgSources: List<BEpgSource>,
    val categories: List<BCategory>,
    val groups: List<BGroup>,
    val favourites: List<BFavourite>,
    val categoryStrip: String = "",
)

/** [url] null = the playlist's own guide, whose URL carries the login: restored from the new login. */
@Serializable
data class BEpgSource(val url: String?, val name: String?, val priority: Int, val offsetMinutes: Int)

@Serializable
data class BCategory(val kind: String, val remoteId: String, val hidden: Boolean, val sortOrder: Int, val pinned: Boolean)

/** [channels] are channel remoteIds in group order. */
@Serializable
data class BGroup(val name: String, val sortOrder: Int, val channels: List<String>, val placement: String = "NORMAL")

@Serializable
data class BFavourite(val kind: String, val remoteId: String)

/** [AppSettings] minus lastLiveChannelId (a row id, meaningless after restore). */
@Serializable
data class BSettings(
    val autoplayOnScroll: Boolean = AppSettings().autoplayOnScroll,
    val audioDecoder: String = AppSettings().audioDecoder.name,
    val confirmExit: Boolean = AppSettings().confirmExit,
    val startOnBoot: Boolean = AppSettings().startOnBoot,
    val epgUpdateIntervalHours: Int = AppSettings().epgUpdateIntervalHours,
    val epgUpdateOnStart: Boolean = AppSettings().epgUpdateOnStart,
    val epgUpdateOnPlaylistChange: Boolean = AppSettings().epgUpdateOnPlaylistChange,
    val epgDaysToKeep: Int = AppSettings().epgDaysToKeep,
    val epgPastDays: Int = AppSettings().epgPastDays,
    val matchTvLanguage: Boolean = AppSettings().matchTvLanguage,
    val logoOrder: List<String> = AppSettings().logoOrder.map { it.name },
    val guideOrder: List<String> = AppSettings().guideOrder.map { it.name },
    val borrowSiblingGuide: Boolean = AppSettings().borrowSiblingGuide,
    val hideHeaderChannels: Boolean = AppSettings().hideHeaderChannels,
    val showDateTime: Boolean = AppSettings().showDateTime,
    val showPing: Boolean = AppSettings().showPing,
    val subtitlesOn: Boolean = AppSettings().subtitlesOn,
    val subtitleLanguage: String? = AppSettings().subtitleLanguage,
    val recordingOn: Boolean = AppSettings().recordingOn,
    val recordTo: String = AppSettings().recordTo.name,
    val startEarlyMin: Int = AppSettings().startEarlyMin,
    val endLateMin: Int = AppSettings().endLateMin,
    val keepAwake: Boolean = AppSettings().keepAwake,
    val smbServer: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
) {
    fun toAppSettings(lastLiveChannelId: Long?): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoplayOnScroll = autoplayOnScroll,
            audioDecoder = AudioDecoder.entries.find { it.name == audioDecoder } ?: d.audioDecoder,
            confirmExit = confirmExit,
            startOnBoot = startOnBoot,
            epgUpdateIntervalHours = epgUpdateIntervalHours,
            epgUpdateOnStart = epgUpdateOnStart,
            epgUpdateOnPlaylistChange = epgUpdateOnPlaylistChange,
            epgDaysToKeep = epgDaysToKeep,
            epgPastDays = epgPastDays,
            lastLiveChannelId = lastLiveChannelId,
            matchTvLanguage = matchTvLanguage,
            logoOrder = logoOrder.mapNotNull { n -> LogoKind.entries.find { it.name == n } }.distinct().ifEmpty { d.logoOrder },
            guideOrder = guideOrder.mapNotNull { n -> GuideKind.entries.find { it.name == n } }.distinct().ifEmpty { d.guideOrder },
            borrowSiblingGuide = borrowSiblingGuide,
            hideHeaderChannels = hideHeaderChannels,
            showDateTime = showDateTime,
            showPing = showPing,
            subtitlesOn = subtitlesOn,
            subtitleLanguage = subtitleLanguage,
            recordingOn = recordingOn,
            recordTo = tv.luna.player.recording.RecStorage.entries.find { it.name == recordTo } ?: d.recordTo,
            startEarlyMin = startEarlyMin,
            endLateMin = endLateMin,
            keepAwake = keepAwake,
            smbServer = smbServer, smbShare = smbShare, smbFolder = smbFolder, smbUser = smbUser,
        ).clamped()
    }

    companion object {
        fun of(s: AppSettings) = BSettings(
            s.autoplayOnScroll, s.audioDecoder.name, s.confirmExit,
            s.startOnBoot, s.epgUpdateIntervalHours, s.epgUpdateOnStart, s.epgUpdateOnPlaylistChange, s.epgDaysToKeep,
            s.epgPastDays, s.matchTvLanguage, s.logoOrder.map { it.name }, s.guideOrder.map { it.name },
            s.borrowSiblingGuide, s.hideHeaderChannels, s.showDateTime, s.showPing, s.subtitlesOn, s.subtitleLanguage,
            recordingOn = s.recordingOn, recordTo = s.recordTo.name, startEarlyMin = s.startEarlyMin, endLateMin = s.endLateMin,
            keepAwake = s.keepAwake, smbServer = s.smbServer, smbShare = s.smbShare, smbFolder = s.smbFolder, smbUser = s.smbUser,
        )
    }
}

private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

fun encodeBackup(b: Backup): String = backupJson.encodeToString(Backup.serializer(), b)

/** The version is read before the body, so a newer file is named as such even when its shape has changed. */
fun decodeBackup(s: String): Backup {
    val tree = try {
        backupJson.parseToJsonElement(s).jsonObject
    } catch (e: SerializationException) {
        throw BackupException("Not a readable backup file")
    } catch (e: IllegalArgumentException) {
        throw BackupException("Not a readable backup file")
    }
    val version = (tree["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException("Not a readable backup file")
    if (version > BACKUP_VERSION) throw BackupException("This backup was made by a newer version of the app")
    return try {
        backupJson.decodeFromJsonElement(Backup.serializer(), tree)
    } catch (e: SerializationException) {
        throw BackupException("Not a readable backup file")
    } catch (e: IllegalArgumentException) {
        throw BackupException("Not a readable backup file")
    }
}
