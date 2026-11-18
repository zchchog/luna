package tv.luna.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** AppSettings in the app's DataStore, alongside ProviderRepository's "active_provider_id". */
class SettingsRepository(private val prefs: DataStore<Preferences>) {
    private val theme = stringPreferencesKey("theme")
    private val themeColour = stringPreferencesKey("theme_colour")
    private val autoplay = booleanPreferencesKey("autoplay_on_scroll")
    private val decoder = stringPreferencesKey("audio_decoder")
    private val confirmExit = booleanPreferencesKey("confirm_exit")
    private val checkUpdatesOnOpen = booleanPreferencesKey("check_updates_on_open")
    private val startOnBoot = booleanPreferencesKey("start_on_boot")
    private val interval = intPreferencesKey("epg_update_interval_hours")
    private val epgOnStart = booleanPreferencesKey("epg_update_on_start")
    private val epgOnPlaylist = booleanPreferencesKey("epg_update_on_playlist_change")
    private val daysToKeep = intPreferencesKey("epg_days_to_keep")
    private val pastDays = intPreferencesKey("epg_past_days")
    private val lastChannel = longPreferencesKey("last_live_channel_id")
    private val matchLanguage = booleanPreferencesKey("match_tv_language")
    private val logoOrder = stringPreferencesKey("logo_order")
    private val guideOrder = stringPreferencesKey("guide_order")
    // Single-choice settings before 2026-11-12, read once to seed the orders.
    private val oldLogoSource = stringPreferencesKey("logo_source")
    private val oldGuideSource = stringPreferencesKey("guide_source")
    private val borrowSibling = booleanPreferencesKey("borrow_sibling_guide")
    private val hideHeaders = booleanPreferencesKey("hide_header_channels")
    private val showDateTime = booleanPreferencesKey("show_date_time")
    private val showPing = booleanPreferencesKey("show_ping")
    private val subtitlesOn = booleanPreferencesKey("subtitles_on")
    private val subtitleLanguage = stringPreferencesKey("subtitle_language")
    private val backupFolder = stringPreferencesKey("backup_folder")
    private val recordingOn = booleanPreferencesKey("recording_on")
    private val recordTo = stringPreferencesKey("record_to")
    private val recordUsbPath = stringPreferencesKey("record_usb_path")
    private val startEarly = intPreferencesKey("start_early_min")
    private val endLate = intPreferencesKey("end_late_min")
    private val keepAwake = booleanPreferencesKey("keep_awake")
    private val smbServer = stringPreferencesKey("smb_server")
    private val smbShare = stringPreferencesKey("smb_share")
    private val smbFolder = stringPreferencesKey("smb_folder")
    private val smbUser = stringPreferencesKey("smb_user")
    private val smbPassword = stringPreferencesKey("smb_password")

    val settings: Flow<AppSettings> = prefs.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(change: (AppSettings) -> AppSettings) {
        prefs.edit { p -> p.write(change(p.toSettings()).clamped()) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = themeIdOf(this[theme]),
            themeColour = ThemeColour.entries.find { it.name == this[themeColour] } ?: d.themeColour,
            autoplayOnScroll = this[autoplay] ?: d.autoplayOnScroll,
            audioDecoder = this[decoder]?.let { n -> AudioDecoder.entries.find { it.name == n } } ?: d.audioDecoder,
            confirmExit = this[confirmExit] ?: d.confirmExit,
            checkUpdatesOnOpen = this[checkUpdatesOnOpen] ?: d.checkUpdatesOnOpen,
            startOnBoot = this[startOnBoot] ?: d.startOnBoot,
            epgUpdateIntervalHours = this[interval] ?: d.epgUpdateIntervalHours,
            epgUpdateOnStart = this[epgOnStart] ?: d.epgUpdateOnStart,
            epgUpdateOnPlaylistChange = this[epgOnPlaylist] ?: d.epgUpdateOnPlaylistChange,
            epgDaysToKeep = this[daysToKeep] ?: d.epgDaysToKeep,
            epgPastDays = this[pastDays] ?: d.epgPastDays,
            lastLiveChannelId = this[lastChannel],
            matchTvLanguage = this[matchLanguage] ?: d.matchTvLanguage,
            logoOrder = order(this[logoOrder] ?: this[oldLogoSource]?.let(OLD_LOGO::get), LogoKind.entries) ?: d.logoOrder,
            guideOrder = order(this[guideOrder] ?: this[oldGuideSource]?.let(OLD_GUIDE::get), GuideKind.entries) ?: d.guideOrder,
            borrowSiblingGuide = this[borrowSibling] ?: d.borrowSiblingGuide,
            hideHeaderChannels = this[hideHeaders] ?: d.hideHeaderChannels,
            showDateTime = this[showDateTime] ?: d.showDateTime,
            showPing = this[showPing] ?: d.showPing,
            subtitlesOn = this[subtitlesOn] ?: d.subtitlesOn,
            subtitleLanguage = this[subtitleLanguage],
            backupFolder = this[backupFolder],
            recordingOn = this[recordingOn] ?: d.recordingOn,
            recordTo = tv.luna.player.recording.RecStorage.entries.find { it.name == this[recordTo] } ?: d.recordTo,
            recordUsbPath = this[recordUsbPath],
            startEarlyMin = this[startEarly] ?: d.startEarlyMin,
            endLateMin = this[endLate] ?: d.endLateMin,
            keepAwake = this[keepAwake] ?: d.keepAwake,
            smbServer = this[smbServer] ?: "",
            smbShare = this[smbShare] ?: "",
            smbFolder = this[smbFolder] ?: "",
            smbUser = this[smbUser] ?: "",
            smbPassword = tv.luna.player.data.security.Secrets.decrypt(this[smbPassword]) ?: "",
        ).clamped()
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[theme] = s.theme.name
        this[themeColour] = s.themeColour.name
        this[autoplay] = s.autoplayOnScroll
        this[decoder] = s.audioDecoder.name
        this[confirmExit] = s.confirmExit
        this[checkUpdatesOnOpen] = s.checkUpdatesOnOpen
        this[startOnBoot] = s.startOnBoot
        this[interval] = s.epgUpdateIntervalHours
        this[epgOnStart] = s.epgUpdateOnStart
        this[epgOnPlaylist] = s.epgUpdateOnPlaylistChange
        this[daysToKeep] = s.epgDaysToKeep
        this[pastDays] = s.epgPastDays
        this[matchLanguage] = s.matchTvLanguage
        this[logoOrder] = s.logoOrder.joinToString(",") { it.name }
        this[guideOrder] = s.guideOrder.joinToString(",") { it.name }
        this[borrowSibling] = s.borrowSiblingGuide
        this[hideHeaders] = s.hideHeaderChannels
        this[showDateTime] = s.showDateTime
        this[showPing] = s.showPing
        this[subtitlesOn] = s.subtitlesOn
        s.subtitleLanguage?.let { this[subtitleLanguage] = it } ?: remove(subtitleLanguage)
        s.backupFolder?.let { this[backupFolder] = it } ?: remove(backupFolder)
        s.lastLiveChannelId?.let { this[lastChannel] = it } ?: remove(lastChannel)
        this[recordingOn] = s.recordingOn
        this[recordTo] = s.recordTo.name
        s.recordUsbPath?.let { this[recordUsbPath] = it } ?: remove(recordUsbPath)
        this[startEarly] = s.startEarlyMin
        this[endLate] = s.endLateMin
        this[keepAwake] = s.keepAwake
        this[smbServer] = s.smbServer
        this[smbShare] = s.smbShare
        this[smbFolder] = s.smbFolder
        this[smbUser] = s.smbUser
        // Re-encrypt only when changed: Secrets.encrypt of the same text gives a new ciphertext each time.
        if (tv.luna.player.data.security.Secrets.decrypt(this[smbPassword]).orEmpty() != s.smbPassword) {
            this[smbPassword] = tv.luna.player.data.security.Secrets.encrypt(s.smbPassword).orEmpty()
        }
    }

    private companion object {
        val OLD_LOGO = mapOf("PLAYLIST" to "PLAYLIST", "PLAYLIST_FILLED" to "PLAYLIST,OPEN_SOURCE", "TV_LOGOS" to "OPEN_SOURCE",
            "MY_PACK" to "PACK,PLAYLIST", "MY_PACK_FILLED" to "PACK,OPEN_SOURCE,PLAYLIST")
        val OLD_GUIDE = mapOf("PLAYLIST" to "PLAYLIST", "PLAYLIST_FILLED" to "PLAYLIST,OPEN_SOURCE", "EPGSHARE" to "OPEN_SOURCE")

        /** Known names in order, no repeats; null when nothing usable is left (the first priority can't be empty). */
        fun <T : Enum<T>> order(s: String?, all: List<T>): List<T>? =
            s?.split(',')?.mapNotNull { n -> all.find { it.name == n.trim() } }?.distinct()?.takeIf { it.isNotEmpty() }
    }
}
