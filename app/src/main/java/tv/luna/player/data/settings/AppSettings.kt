package tv.luna.player.data.settings

enum class AudioDecoder { HARDWARE, SOFTWARE }

/** Where a channel logo can come from; the setting is an order of these (first available wins). */
enum class LogoKind(val label: String) { PLAYLIST("Playlist"), OPEN_SOURCE("Open-source logos"), PACK("My logo pack") }

/** Where the guide can come from; the setting is an order of these (spec §5.1, reversible since 2026-11-12). */
enum class GuideKind(val label: String) { PLAYLIST("Playlist"), OPEN_SOURCE("Open-source guide") }

val DEFAULT_LOGO_ORDER = listOf(LogoKind.PLAYLIST, LogoKind.OPEN_SOURCE)
val DEFAULT_GUIDE_ORDER = listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE)

val EPG_INTERVALS = listOf(6, 12, 24, 48)
val START_EARLY = listOf(0, 1, 2, 5, 10)
val END_LATE = listOf(0, 2, 5, 10, 15, 30)

/** App-wide settings (spec §2.1). Per-playlist data lives in Room. */
data class AppSettings(
    val autoplayOnScroll: Boolean = false,
    val audioDecoder: AudioDecoder = AudioDecoder.SOFTWARE,
    val confirmExit: Boolean = true,
    /** Look for a new version every time the app opens, and show the update pop-up when there is one. */
    val checkUpdatesOnOpen: Boolean = true,
    val startOnBoot: Boolean = false,
    val epgUpdateIntervalHours: Int = 12,
    val epgUpdateOnStart: Boolean = false,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val epgDaysToKeep: Int = 7,
    val epgPastDays: Int = 1,
    val lastLiveChannelId: Long? = null,
    val matchTvLanguage: Boolean = false,
    val logoOrder: List<LogoKind> = DEFAULT_LOGO_ORDER,
    val guideOrder: List<GuideKind> = DEFAULT_GUIDE_ORDER,
    val borrowSiblingGuide: Boolean = true,
    val hideHeaderChannels: Boolean = false,
    val showDateTime: Boolean = true,
    val showPing: Boolean = false,
    /** Movies and series start with subtitles in [subtitleLanguage] when the file has that language. */
    val subtitlesOn: Boolean = false,
    /** ISO 639-1 code; null = the TV's language. */
    val subtitleLanguage: String? = null,
    /** Folder backups are saved to (absolute path; needs all files access). Null = Downloads. Not backed up: per box. */
    val backupFolder: String? = null,
    val recordingOn: Boolean = false,
    val recordTo: tv.luna.player.recording.RecStorage = tv.luna.player.recording.RecStorage.INTERNAL,
    /** The chosen USB drive's recordings folder; null = the first drive found. Not backed up: per box. */
    val recordUsbPath: String? = null,
    val startEarlyMin: Int = 2,
    val endLateMin: Int = 5,
    val keepAwake: Boolean = true,
    val smbServer: String = "",
    val smbShare: String = "",
    val smbFolder: String = "",
    val smbUser: String = "",
    /** Stored encrypted (Secrets); never backed up. */
    val smbPassword: String = "",
) {
    fun clamped() = copy(
        epgUpdateIntervalHours = epgUpdateIntervalHours.takeIf { it in EPG_INTERVALS } ?: 12,
        epgDaysToKeep = epgDaysToKeep.coerceIn(1, 14),
        epgPastDays = epgPastDays.coerceIn(0, 7),
        startEarlyMin = startEarlyMin.takeIf { it in START_EARLY } ?: 2,
        endLateMin = endLateMin.takeIf { it in END_LATE } ?: 5,
    )
}

/** The Subtitle language choices besides "TV language" (ISO 639-1); shown sorted by display name. */
val SUBTITLE_LANGUAGES = listOf("ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr",
    "hu", "id", "it", "ja", "ko", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sv", "th", "tr")
