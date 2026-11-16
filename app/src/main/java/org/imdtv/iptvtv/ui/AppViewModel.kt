package org.imdtv.iptvtv.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.IptvApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.catalogue.parseStripList
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.db.ProviderType
import org.imdtv.iptvtv.data.model.StreamFormat
import org.imdtv.iptvtv.data.source.XtreamAuthException
import org.imdtv.iptvtv.ui.vod.VodItem
import org.imdtv.iptvtv.work.EpgRefreshWorker

sealed interface Screen {
    data object Loading : Screen
    /** Add a playlist, or edit [editId]. */
    data class Setup(val editId: Long? = null) : Screen
    data class Settings(val group: org.imdtv.iptvtv.ui.settings.SettingsGroup = org.imdtv.iptvtv.ui.settings.SettingsGroup.PLAYLISTS) : Screen
    data object Live : Screen
    data object Vod : Screen
    data object Search : Screen
    data class Detail(val item: VodItem) : Screen
    /** [recordingId]: a recording is playing; its position is saved on Back. */
    data class Player(val title: String, val live: Boolean, val recordingId: Long? = null) : Screen
    data object Recordings : Screen
}

enum class RootBack { FOCUS_MENU, SHOW_HINT, EXIT }

/**
 * Back at a root screen: first press jumps to the top menu, the next shows the exit prompt, the one after exits.
 * With [confirmExit] off, Back at a root exits at once.
 */
fun rootBack(top: Screen, menuFocused: Boolean, hintShowing: Boolean, confirmExit: Boolean) = when {
    !confirmExit || (top != Screen.Live && top != Screen.Vod && top != Screen.Search && top != Screen.Recordings) -> RootBack.EXIT
    !menuFocused -> RootBack.FOCUS_MENU
    !hintShowing -> RootBack.SHOW_HINT
    else -> RootBack.EXIT
}

/** The screens at launch: the main page (the live guide), or playlist setup when there is no playlist yet. It never opens a channel. */
fun startStack(hasProvider: Boolean): List<Screen> = if (hasProvider) listOf(Screen.Live) else listOf(Screen.Setup())

data class FormState(val busy: Boolean = false, val error: String? = null, val result: String? = null, val progress: String? = null)

/** Navigation stack, provider setup form and the shared player. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IptvApp).container
    val player get() = c.playerManager

    val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Loading))
    val tab = MutableStateFlow(Tab.LIVE)
    val form = MutableStateFlow(FormState())
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    /** Set when a channel from search should start playing; the activity hands it to LiveViewModel. */
    val startChannel = MutableStateFlow<ChannelEntity?>(null)
    private var hasProvider = false

    init {
        // EPG schedule follows the interval setting (re-enqueued with UPDATE when it changes).
        viewModelScope.launch {
            c.settings.settings.map { it.epgUpdateIntervalHours }.distinctUntilChanged()
                .collect { EpgRefreshWorker.schedule(getApplication(), it) }
        }
        viewModelScope.launch {
            if (c.settings.settings.first().epgUpdateOnStart) EpgRefreshWorker.runNow(getApplication(), automatic = true)
        }
        viewModelScope.launch {
            val active = c.providerRepository.activeProviderId.first()
            hasProvider = active != null
            stack.value = startStack(hasProvider)
        }
    }

    private fun push(s: Screen) { stack.value = stack.value + s }

    fun selectTab(t: Tab) {
        if (t == Tab.SETTINGS) { push(Screen.Settings()); return }
        if (t != Tab.LIVE) player.stop() // the live preview shouldn't keep playing behind Movies/Series
        tab.value = t
        stack.value = listOf(when (t) { Tab.LIVE -> Screen.Live; Tab.SEARCH -> Screen.Search; Tab.RECORDINGS -> Screen.Recordings; else -> Screen.Vod })
    }

    fun addPlaylist() { form.value = FormState(); push(Screen.Setup()) }

    fun editPlaylist(id: Long) { form.value = FormState(); push(Screen.Setup(id)) }

    suspend fun provider(id: Long): ProviderEntity? = c.db.providerDao().get(id)

    fun open(item: VodItem) = push(Screen.Detail(item))

    /** Gamestation: an XMB Settings item opens Settings on that group. */
    fun openSettings(group: org.imdtv.iptvtv.ui.settings.SettingsGroup) = push(Screen.Settings(group))

    fun playFullScreen(title: String, live: Boolean) = push(Screen.Player(title, live))

    /** A finished (or stopped) recording, from where it was left. */
    fun playRecording(r: org.imdtv.iptvtv.recording.RecordingEntity) {
        val location = r.location ?: return
        player.playRecording(location, r.positionMs)
        push(Screen.Player(r.title, live = false, recordingId = r.id))
    }

    /** Returns false at a root screen, so the activity can finish. */
    fun back(): Boolean {
        val top = stack.value.last()
        if (stack.value.size == 1) return false
        // Live full screen returns to the guide with the same stream still in the preview.
        if (top is Screen.Player && top.recordingId != null) {
            val p = player.player
            val at = p.currentPosition.takeIf { p.duration <= 0 || it < p.duration - 30_000 } ?: 0 // watched to the end: start over next time
            viewModelScope.launch { c.recordings.setPosition(top.recordingId, at) }
        }
        if (top is Screen.Player && !top.live) player.stop()
        stack.value = stack.value.dropLast(1)
        // A channel played from search: there is no preview to return to.
        if (top is Screen.Player && stack.value.last() == Screen.Search) player.stop()
        return true
    }

    fun save(type: ProviderType, url: String, username: String, password: String, epgUrls: String, userAgent: String, editing: ProviderEntity? = null) {
        val trimmed = url.trim().trimEnd('/')
        if (Uri.parse(trimmed).host.isNullOrEmpty()) {
            form.value = FormState(error = "Enter a full URL, e.g. http://example.com:8080")
            return
        }
        form.value = FormState(busy = true)
        viewModelScope.launch {
            val login = username.trim().ifEmpty { null }
            val pass = password.ifEmpty { null }
            val agent = userAgent.trim().ifEmpty { null }
            if (editing != null) { saveEdit(editing, editing.copy(name = Uri.parse(trimmed).host ?: editing.name, url = trimmed, username = login, password = pass, userAgent = agent)); return@launch }
            val entity = ProviderEntity(
                name = Uri.parse(trimmed).host!!, type = type, url = trimmed,
                username = login, password = pass,
                userAgent = agent, streamFormat = StreamFormat.TS,
                epgOverrideUrls = epgUrls.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                lastSync = null,
            )
            var id: Long? = null
            try {
                id = c.providerRepository.add(entity)
                val r = c.providerRepository.syncNow(id) { step -> form.value = form.value.copy(progress = step) }
                c.providerRepository.setActive(id)
                hasProvider = true
                EpgRefreshWorker.runNow(getApplication())
                form.value = FormState(result = r.summary() + " The guide is loading in the background.")
            } catch (e: Exception) {
                // Don't leave a half-added provider behind; the user will retry with corrected details.
                id?.let { c.providerRepository.remove(it) }
                form.value = FormState(error = loadError(e))
            }
        }
    }

    /** Saves the edit and re-syncs with it; if the playlist no longer loads, the old details are put back. */
    private suspend fun saveEdit(old: ProviderEntity, new: ProviderEntity) {
        c.providerRepository.update(new)
        try {
            val r = c.providerRepository.syncNow(new.id) { step -> form.value = form.value.copy(progress = step) }
            c.epgMatcher.rematch(new.id)
            if (settings.value.epgUpdateOnPlaylistChange) EpgRefreshWorker.runNow(getApplication())
            form.value = FormState(result = r.summary())
        } catch (e: Exception) {
            c.providerRepository.update(old)
            form.value = FormState(error = loadError(e) + " Your changes were not saved.")
        }
    }

    private fun loadError(e: Exception) = when (e) {
        is XtreamAuthException -> "Login failed: ${e.message}"
        else -> "Couldn't load the playlist: ${e.message ?: e::class.simpleName}"
    }

    fun refreshGuide() = EpgRefreshWorker.runNow(getApplication())

    /** Deleting the active playlist restarts at Live on the next one, or at first-run setup if none is left. */
    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            val wasActive = c.providerRepository.activeProviderId.first() == id
            if (wasActive) player.stop()
            c.providerRepository.remove(id)
            c.logoPack.remove(id) // its unpacked pack folder
            c.logos.pruneCustom() // hand-picked logos of its channels
            hasProvider = c.providerRepository.activeProviderId.first() != null
            if (wasActive) {
                form.value = FormState() // first-run setup must not show the last add/edit result
                tab.value = Tab.LIVE
                stack.value = startStack(hasProvider)
            }
        }
    }

    /** Switches the active playlist and restarts at its Live guide (the Live/VOD/search screens follow the active id). */
    fun usePlaylist(id: Long) {
        viewModelScope.launch {
            player.stop()
            c.providerRepository.setActive(id)
            tab.value = Tab.LIVE
            stack.value = listOf(Screen.Live)
        }
    }

    fun done() { form.value = FormState(); tab.value = Tab.LIVE; stack.value = listOf(Screen.Live) }

    val canLeaveSetup get() = hasProvider
}
