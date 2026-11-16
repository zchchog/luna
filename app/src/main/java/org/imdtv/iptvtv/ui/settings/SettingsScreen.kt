package org.imdtv.iptvtv.ui.settings

import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.guideBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.settings.AudioDecoder
import org.imdtv.iptvtv.ui.AppLogo
import org.imdtv.iptvtv.ui.FocusBox

enum class SettingsGroup(val title: String) {
    PLAYLISTS("Playlists"), CHANNELS("Channels & categories"), EPG("TV guide (EPG)"), PLAYBACK("Playback"),
    APPEARANCE("Appearance"), BEHAVIOUR("App behaviour"), BACKUP("Backup & restore"), ABOUT("About"),
}

@OptIn(ExperimentalComposeUiApi::class)
/** Left: groups. Right: the focused group's rows. Back in the rows returns to the groups; Back there pops the screen. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onAddPlaylist: () -> Unit, onEditPlaylist: (Long) -> Unit, onDeletePlaylist: (Long) -> Unit, onUsePlaylist: (Long) -> Unit,
                   initialGroup: SettingsGroup = SettingsGroup.PLAYLISTS) {
    val s by vm.settings.collectAsState()
    // Back from Edit playlist (Settings left composition meanwhile): reopen that playlist's page.
    val editedPlaylist = remember { vm.playlistToReopen.also { vm.playlistToReopen = null } }
    var group by remember { mutableStateOf(if (editedPlaylist != null) SettingsGroup.PLAYLISTS else initialGroup) }
    var inRows by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<ChannelPage?>(null) }
    var reopen by remember { mutableStateOf<ChannelPage?>(null) }
    val groupFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    var playlist by remember { mutableStateOf(editedPlaylist) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var recordingOpen by remember { mutableStateOf(false) }
    var reopenRecording by remember { mutableStateOf(false) }
    val recordingOpener = remember { FocusRequester() }
    var reopenSources by remember { mutableStateOf(false) }
    val sourcesOpener = remember { FocusRequester() }
    var reopenPlaylist by remember { mutableStateOf<Long?>(null) }
    val openers = remember { ChannelPage.entries.associateWith { FocusRequester() } }
    /** Where focus goes when it next enters the rows pane (null = its first row). Not state: read only by `enter`. */
    val enterTarget = remember { object { var value: FocusRequester? = null } }
    val playlistOpeners = remember { mutableMapOf<Long, FocusRequester>() }
    // Closing a page puts focus back on the row that opened it.
    LaunchedEffect(page) {
        val back = reopen?.takeIf { page == it.parent } ?: return@LaunchedEffect
        // The opener row may take a few frames to appear (the parent page is rebuilt); until then requestFocus throws.
        // Focus enters the pane from the group list, and the pane's `enter` rule would send it to the first row.
        enterTarget.value = openers.getValue(back)
        for (i in 0 until 10) { withFrameNanos { }; if (runCatching { openers.getValue(back).requestFocus() }.isSuccess) break }
        enterTarget.value = null
        reopen = null
    }
    LaunchedEffect(sourcesOpen) {
        if (sourcesOpen || !reopenSources) return@LaunchedEffect
        enterTarget.value = sourcesOpener
        withFrameNanos { }
        runCatching { sourcesOpener.requestFocus() }
        enterTarget.value = null
        reopenSources = false
    }
    LaunchedEffect(recordingOpen) {
        if (recordingOpen || !reopenRecording) return@LaunchedEffect
        enterTarget.value = recordingOpener
        withFrameNanos { }
        runCatching { recordingOpener.requestFocus() }
        enterTarget.value = null
        reopenRecording = false
    }
    LaunchedEffect(playlist) {
        val back = reopenPlaylist?.takeIf { playlist == null } ?: return@LaunchedEffect
        withFrameNanos { }
        val exists = vm.providers.value.any { it.id == back }
        val target = if (exists) playlistOpeners.getValue(back) else rowsFocus // a deleted playlist has no row
        enterTarget.value = target
        runCatching { target.requestFocus() }
        enterTarget.value = null
        reopenPlaylist = null
    }
    // Opened on a group (Gamestation XMB): focus its rows; otherwise the group list.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { if (initialGroup != SettingsGroup.PLAYLISTS || editedPlaylist != null) rowsFocus.requestFocus() else groupFocus.requestFocus() } }
    BackHandler(enabled = inRows) { runCatching { groupFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().guideBackground()) {
        Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))
                // Appearance is hidden: the app always uses the teal look (MainActivity).
                SettingsGroup.entries.filter { it != SettingsGroup.APPEARANCE }.forEach { g ->
                    FocusBox(
                        onClick = { runCatching { rowsFocus.requestFocus() } },
                        modifier = if (g == group) Modifier.focusRequester(groupFocus) else Modifier,
                        onFocused = { if (g != group) { page = null; playlist = null; sourcesOpen = false; recordingOpen = false }; group = g },
                    ) { f ->
                        Text(
                            g.title, fontSize = 15.sp, color = if (f) Color.Black else if (g == group) Color.White else LocalTheme.current.colors.muted,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (f) Color.White else if (g == group) Color(0x1FFFFFFF) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            val pane = Modifier.weight(1f).onFocusChanged { inRows = it.hasFocus }
                    // Enter on the first row; Up/Down past the ends stay put instead of jumping into the group list.
                    .focusProperties {
                        enter = { enterTarget.value ?: rowsFocus }
                        exit = {
                            when (it) {
                                FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                                FocusDirection.Left -> groupFocus
                                else -> FocusRequester.Default // Back's requestFocus must still leave
                            }
                        }
                    }
                    .focusGroup()
            // Removing the focused node hands focus to the first focusable (Playlists), so park it on this group first.
            fun park() = runCatching { groupFocus.requestFocus() }
            page?.let { p ->
                key(p) {
                    ChannelPageView(p, vm, pane, first = rowsFocus, openers, restoring = reopen?.parent == p,
                        onOpen = { park(); reopen = null; page = it }) { park(); reopen = p; page = p.parent }
                }
            }
            playlist?.let { id ->
                PlaylistPage(vm, id, pane, rowsFocus, { vm.playlistToReopen = it; onEditPlaylist(it) }, onDeletePlaylist, onUsePlaylist) { park(); reopenPlaylist = id; playlist = null }
            }
            if (sourcesOpen) EpgSourcesPage(vm, pane, rowsFocus) { park(); reopenSources = true; sourcesOpen = false }
            if (recordingOpen) org.imdtv.iptvtv.recording.RecordingPage(vm, pane, rowsFocus) { park(); reopenRecording = true; recordingOpen = false }
            if (page == null && playlist == null && !sourcesOpen && !recordingOpen) Column(pane.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.title, fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                // The first row of a group takes rowsFocus, so OK/Right on a group lands on it.
                val first = Modifier.focusRequester(rowsFocus)
                when (group) {
                    SettingsGroup.PLAYLISTS -> PlaylistGroupRows(vm, first, playlistOpeners, onAddPlaylist) { park(); playlist = it }
                    SettingsGroup.EPG -> EpgGroupRows(vm, first, Modifier.focusRequester(sourcesOpener)) { park(); sourcesOpen = true }
                    SettingsGroup.CHANNELS -> ChannelGroupRows(vm, first, openers) { park(); page = it }
                    SettingsGroup.PLAYBACK -> {
                        SwitchRow("Autoplay while scrolling", "Preview the focused channel after a short pause; when off, press OK to preview",
                            s.autoplayOnScroll, first) { on -> vm.update { it.copy(autoplayOnScroll = on) } }
                        ChoiceRow("Audio decoder", "Software (FFmpeg) plays Dolby/DTS on any box; Hardware passes it to the TV",
                            listOf(AudioDecoder.SOFTWARE, AudioDecoder.HARDWARE), s.audioDecoder,
                            { if (it == AudioDecoder.HARDWARE) "Hardware" else "Software" }) { d -> vm.update { it.copy(audioDecoder = d) } }
                        SettingRow("Recording", "Record live TV to the box, a USB drive or a network share",
                            if (s.recordingOn) "On ›" else "Off ›", Modifier.focusRequester(recordingOpener)) { park(); recordingOpen = true }
                        SwitchRow("Subtitles", "Movies and series that have them",
                            s.subtitlesOn) { on -> vm.update { it.copy(subtitlesOn = on) } }
                        if (s.subtitlesOn) {
                            val tv = java.util.Locale.getDefault().displayLanguage
                            ChoiceRow("Subtitle language", "Subtitles show only when the film has this language",
                                listOf<String?>(null) + org.imdtv.iptvtv.data.settings.SUBTITLE_LANGUAGES.sortedBy { java.util.Locale(it).displayLanguage },
                                s.subtitleLanguage, { c -> c?.let { java.util.Locale(it).displayLanguage } ?: "TV language ($tv)" }) { l ->
                                vm.update { it.copy(subtitleLanguage = l) }
                            }
                        }
                    }
                    SettingsGroup.APPEARANCE -> AppearanceRows(s, first, onTheme = { t -> vm.update { it.copy(theme = t) } }, onColour = { c -> vm.update { it.copy(themeColour = c) } })
                    SettingsGroup.BEHAVIOUR -> {
                        SwitchRow("Confirm exit", "Press Back twice at the top menu to leave the app", s.confirmExit, first) { on ->
                            vm.update { it.copy(confirmExit = on) }
                        }
                        StartOnBootRow(s.startOnBoot) { on -> vm.update { it.copy(startOnBoot = on) } }
                        SwitchRow("Show date and time", "The clock (and date) at the top of the screen", s.showDateTime) { on ->
                            vm.update { it.copy(showDateTime = on) }
                        }
                        SwitchRow("Show ping", "Connection time to Cloudflare (1.1.1.1) in the top bar, checked every 30 seconds", s.showPing) { on ->
                            vm.update { it.copy(showPing = on) }
                        }
                    }
                    SettingsGroup.BACKUP -> BackupRows(vm, first)
                    SettingsGroup.ABOUT -> AboutRows(s, first) { on -> vm.update { it.copy(checkUpdatesOnOpen = on) } }
                }
            }
        }
        AppLogo(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 48.dp).size(64.dp))
    }
}

/** A setting: name, one-line grey explanation, and its current value on the right. */
@Composable
fun SettingRow(
    title: String, explanation: String, value: String?, modifier: Modifier = Modifier, busy: Boolean = false,
    /** Non-null: a slider switch in that state instead of [value]. */
    toggle: Boolean? = null, onClick: () -> Unit,
) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (f) Color.White else Color(0x14FFFFFF))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, color = if (f) Color.Black else Color.White)
                Text(explanation, fontSize = 12.sp, color = if (f) Color(0xFF555555) else LocalTheme.current.colors.muted)
            }
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                // One line, capped: a long value (e.g. a clean-up list) must not squeeze the title to nothing.
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (f) Color.Black else Color.White,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
            }
            if (toggle != null) { Spacer(Modifier.width(16.dp)); Toggle(toggle, f) }
            if (busy) { Spacer(Modifier.width(16.dp)); org.imdtv.iptvtv.ui.Spinner(size = 20.dp, color = if (f) Color.Black else Color.White) }
        }
    }
}

@Composable
fun SwitchRow(title: String, explanation: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) =
    SettingRow(title, explanation, null, modifier, toggle = checked) { onToggle(!checked) }

/** Slider switch (TiviMate style): a deep blue fills the track when on; the white knob sits at that end. */
@Composable
fun Toggle(on: Boolean, focused: Boolean) {
    val off = if (focused) Color(0xFFB0B0B0) else Color(0x40FFFFFF)
    Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF1C9AA0) else off).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
    }
}

/** On without "Display over other apps", Android drops the boot start: the row says so and OK opens that setting. */
@Composable
private fun StartOnBootRow(on: Boolean, onToggle: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowed by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val switchFocus = remember { FocusRequester() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val now = android.provider.Settings.canDrawOverlays(context)
            // The Allow row is about to go: move focus to the switch first, or it falls to the group list.
            if (now && !allowed) runCatching { switchFocus.requestFocus() }
            allowed = now
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    SwitchRow("Start on boot", "Open the app when the TV box starts", on, Modifier.focusRequester(switchFocus), onToggle = onToggle)
    if (on && !allowed) {
        // A row of its own, so the switch above can still be turned off.
        SettingRow("Allow Display over other apps", "Android blocks this until you allow Display over other apps — OK to open that setting", null) {
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(i) }
        }
    }
}
