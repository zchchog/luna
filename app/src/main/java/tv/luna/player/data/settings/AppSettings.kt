package tv.luna.player.data.settings

/** Layout themes (spec §6A); each sub-stage appends its entry, so the drop-down lists only built themes. */
enum class ThemeId(val label: String) { LUNA("Luna") }

/** A stored theme name this build doesn't know (old SKY_GLASS/MIDNIGHT…, or a newer build's theme) reads as the default. */
fun themeIdOf(stored: String?): ThemeId = ThemeId.entries.find { it.name == stored } ?: ThemeId.LUNA

/**
 * Background colour for Luna and Liquid Glass (Appearance › Theme colour). ARGB stops: [glass] is Luna
 * Glass's dark→light gradient, [liquid] Liquid Glass's backdrop gradient. DEFAULT keeps each theme's own colours.
 */
enum class ThemeColour(val label: String, val glass: List<Long>, val liquid: List<Long>) {
    DEFAULT("Default", listOf(0xFF0A1753, 0xFF1A39A6, 0xFF3459CA), listOf(0xFF0FB5BA, 0xFF7A3CF0)),
    PURPLE("Purple", listOf(0xFF22104F, 0xFF4B21A0, 0xFF6E45C8), listOf(0xFF9B3CF0, 0xFFF03C9B)),
    TEAL("Teal", listOf(0xFF06323A, 0xFF0D6F7A, 0xFF1C9AA0), listOf(0xFF0FB5BA, 0xFF0F5FBA)),
    GREEN("Green", listOf(0xFF0A3318, 0xFF1A6E35, 0xFF2F9752), listOf(0xFF2FBF71, 0xFF0F9AB5)),
    RED("Red", listOf(0xFF420A12, 0xFF8C1A2A, 0xFFB43544), listOf(0xFFF0563C, 0xFFB03CF0)),
    GRAPHITE("Graphite", listOf(0xFF12151C, 0xFF29303B, 0xFF444E5E), listOf(0xFF5A6478, 0xFF262B38)),
}

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
    val theme: ThemeId = ThemeId.LUNA,
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
    val themeColour: ThemeColour = ThemeColour.DEFAULT,
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
    // New fields go last, so positional uses of the constructor keep working.
    /** On Demand: start the top source at once instead of showing the list. */
    val autoPlayBestSource: Boolean = false,
    /** On Demand: how many seconds before an episode ends the "Next episode" button shows (0 = never). */
    val nextEpisodeSeconds: Int = 45,
    /** On Demand: when an episode ends, start the next one by itself. */
    val autoNextEpisode: Boolean = true,
    /** On Demand: the best quality to go for: "Best" (highest available), "1080p" or "720p" (for slower internet). */
    val maxQuality: String = "Best",
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
