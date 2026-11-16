package org.imdtv.iptvtv

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import org.imdtv.iptvtv.ui.rootBack
import org.imdtv.iptvtv.ui.RootBack
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.themeOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.ui.AppViewModel
import org.imdtv.iptvtv.ui.PlayerScreen
import org.imdtv.iptvtv.ui.ProviderFormScreen
import org.imdtv.iptvtv.ui.Screen
import org.imdtv.iptvtv.ui.Tab
import org.imdtv.iptvtv.ui.TopNav
import org.imdtv.iptvtv.ui.live.LiveGuideScreen
import org.imdtv.iptvtv.data.settings.ThemeId
import org.imdtv.iptvtv.ui.live.LiveViewModel
import org.imdtv.iptvtv.ui.vod.VodDetailScreen
import org.imdtv.iptvtv.ui.vod.VodHomeScreen
import org.imdtv.iptvtv.ui.vod.VodViewModel
import org.imdtv.iptvtv.ui.settings.SettingsScreen
import org.imdtv.iptvtv.ui.settings.SettingsViewModel
import org.imdtv.iptvtv.work.CatalogueSyncWorker

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()
    private val live: LiveViewModel by viewModels()
    private val vod: VodViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val searchVm: org.imdtv.iptvtv.ui.search.SearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CatalogueSyncWorker.runOnLaunch(this)
        // Audio decoder (spec §4.4): the player rebuilds when idle, else from the next stream.
        lifecycleScope.launch {
            (application as IptvApp).container.settings.settings.map { it.audioDecoder }.distinctUntilChanged()
                .collect { app.player.setAudioDecoder(it) }
        }
        lifecycleScope.launch {
            (application as IptvApp).container.settings.settings.map { it.subtitlesOn to it.subtitleLanguage }.distinctUntilChanged()
                .collect { (on, language) -> app.player.setSubtitles(on, language) }
        }
        setContent {
            val themeId = app.settings.collectAsState().value.theme
            // Luna always uses the teal look; the saved colour choice is ignored.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            CompositionLocalProvider(LocalTheme provides remember(themeId) { themeOf(themeId, org.imdtv.iptvtv.data.settings.ThemeColour.TEAL) }) {
            MaterialTheme(colorScheme = darkColorScheme()) {
            // Celestial Glass and Liquid Glass: every TV Text in Inter (the website's font). Inside MaterialTheme, which
            // otherwise sets its own sans-serif text style over anything provided outside it.
            CompositionLocalProvider(androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(
                fontFamily = if (org.imdtv.iptvtv.ui.theme.usesInter(themeId)) org.imdtv.iptvtv.ui.theme.Inter else androidx.tv.material3.LocalTextStyle.current.fontFamily)) {
                val stack by app.stack.collectAsState()
                val tab by app.tab.collectAsState()
                val grid by vod.gridCategory.collectAsState()
                val top = stack.last()
                val settings by app.settings.collectAsState()
                // Pings run only while shown and the app is in the foreground (collection stops below STARTED).
                val lastPing by remember(settings.showPing) { if (settings.showPing) org.imdtv.iptvtv.data.net.pings() else kotlinx.coroutines.flow.emptyFlow() }
                    .collectAsStateWithLifecycle(null)
                val pingMs = lastPing.takeIf { settings.showPing }
                val startChannel by app.startChannel.collectAsState()
                LaunchedEffect(startChannel) { startChannel?.let { live.startOn(it); app.startChannel.value = null } }
                val menuFocus = remember { FocusRequester() }
                var menuFocused by remember { mutableStateOf(false) }
                var exitHint by remember { mutableStateOf(false) }
                LaunchedEffect(exitHint, menuFocused) {
                    if (!menuFocused) exitHint = false
                    if (exitHint) { delay(3_000); exitHint = false }
                }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var cover by remember { androidx.compose.runtime.mutableFloatStateOf(0f) } // black cover for changes of screen (drawn further down)
                BackHandler {
                    when {
                        top == Screen.Vod && grid != null -> vod.gridCategory.value = null
                        // Leaving live full screen: go black first, so the screen being rebuilt behind is never seen.
                        top is Screen.Player && top.live -> scope.launch {
                            androidx.compose.animation.core.animate(cover, 1f, animationSpec = androidx.compose.animation.core.tween(140)) { v, _ -> cover = v }
                            app.back()
                        }
                        app.back() -> Unit
                        else -> when (rootBack(top, menuFocused, exitHint, settings.confirmExit)) {
                            RootBack.FOCUS_MENU -> runCatching { menuFocus.requestFocus() }
                            RootBack.SHOW_HINT -> exitHint = true
                            RootBack.EXIT -> finish()
                        }
                    }
                }
                val selectTab = { t: Tab ->
                    if (t == Tab.MOVIES) vod.setKind(Kind.MOVIE)
                    if (t == Tab.SERIES) vod.setKind(Kind.SERIES)
                    app.selectTab(t)
                }
                val nav = @androidx.compose.runtime.Composable {
                    TopNav(tab, onSelect = selectTab, Modifier.onFocusChanged { menuFocused = it.hasFocus }, menuFocus,
                        showClock = settings.showDateTime,
                        showDate = false, pingMs = pingMs,
                        tabs = org.imdtv.iptvtv.ui.visibleTabs(settings.recordingOn))
                }
                val themeId = LocalTheme.current.id
                // A channel from search: its category becomes the guide's, so zapping and Back behave as from the guide.
                val searchChannel = { ch: org.imdtv.iptvtv.data.db.ChannelEntity ->
                    live.startOn(ch); app.playFullScreen(ch.displayName(live.strip.value), live = true)
                }
                // Recording switched off while its tab is open: back to Live.
                LaunchedEffect(settings.recordingOn) { if (!settings.recordingOn && tab == Tab.RECORDINGS) selectTab(Tab.LIVE) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val full: (org.imdtv.iptvtv.data.db.ChannelEntity) -> Unit = { ch ->
                        live.preview(ch)
                        live.watched(ch)
                        app.playFullScreen(ch.displayName(live.strip.value), live = true)
                    }
                    val screens = @androidx.compose.runtime.Composable {
                    when (top) {
                        Screen.Loading -> Unit
                        is Screen.Setup -> Box(Modifier.fillMaxSize().background(LocalTheme.current.colors.vodBg)) { ProviderFormScreen(app, top.editId) }
                        is Screen.Settings -> SettingsScreen(settingsVm, onAddPlaylist = app::addPlaylist, onEditPlaylist = app::editPlaylist,
                            onDeletePlaylist = app::deletePlaylist, onUsePlaylist = { live.resetForPlaylistSwitch(); app.usePlaylist(it) }, initialGroup = top.group)
                        Screen.Live, Screen.Vod -> if (top == Screen.Live) LiveGuideScreen(live, nav, full) else VodHomeScreen(vod, nav, onOpen = app::open)
                        Screen.Search -> org.imdtv.iptvtv.ui.search.SearchScreen(searchVm, nav, searchChannel, app::open)
                        Screen.Recordings -> org.imdtv.iptvtv.recording.RecordingsScreen(nav, app::playRecording)
                        is Screen.Detail -> VodDetailScreen(vod, top.item, onPlay = { url, title ->
                            vod.play(url, top.item)
                            app.playFullScreen(title, live = false)
                        }, onOpen = app::open)
                        is Screen.Player -> PlayerScreen(app.player, top.title, live.takeIf { top.live }, onGuide = { app.back() })
                    }
                    }
                    screens()
                    // Going into or out of full-screen playback: the picture moves between the corner preview and the whole
                    // screen, which would show as a glitch. Cover that moment in black and fade it away.
                    val playerOpen = top is Screen.Player
                    var wasPlayerOpen by remember { mutableStateOf(playerOpen) }
                    // Raised the moment the screen changes, and read only while drawing (below), so it is already on the first
                    // frame of the new screen instead of arriving a frame after it.
                    androidx.compose.runtime.SideEffect { if (wasPlayerOpen != playerOpen) { wasPlayerOpen = playerOpen; cover = 1f } }
                    LaunchedEffect(playerOpen) {
                        if (playerOpen) {
                            // Going full screen: a short hold, then fade in.
                            androidx.compose.animation.core.animate(1f, 0f, animationSpec = androidx.compose.animation.core.tween(450, delayMillis = 260)) { v, _ -> cover = v }
                        } else {
                            // Back at the guide: stay black until the corner window has drawn the picture (up to 2 s), then fade in.
                            if (app.player.playingUrl.value != null) {
                                kotlinx.coroutines.withTimeoutOrNull(2_000) { app.player.pictureShowing.first { it } }
                                delay(120)
                            }
                            androidx.compose.animation.core.animate(1f, 0f, animationSpec = androidx.compose.animation.core.tween(380)) { v, _ -> cover = v }
                        }
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(Color.Black))
                    // Connection questions and recording notices, over every screen.
                    org.imdtv.iptvtv.recording.PromptHost((application as IptvApp).container.recordings) {
                        if ((app.stack.value.last() as? Screen.Player)?.live == true) app.back()
                    }
                    org.imdtv.iptvtv.data.update.UpdatePrompt((application as IptvApp).container.updater, (application as IptvApp).container.background)
                    // Opening screen when Luna starts from closed (kept dismissed across rotation/recreation).
                    var splash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
                    if (splash) org.imdtv.iptvtv.ui.LunaSplash { splash = false }
                    if (exitHint) Text(
                        buildAnnotatedString {
                            append("Press ")
                            withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) { append("↩") }
                            append(" to exit application")
                        }, fontSize = 14.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                            .background(Color(0xE6052228), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
            }
            }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep streaming (or holding the provider's connection) after the user leaves the app.
        app.player.toBackground()
    }

    override fun onStart() {
        super.onStart()
        app.player.toForeground()
        // "Check for updates when Luna opens" (on unless turned off in Settings > About): look every time the app comes
        // to the front, and show the pop-up even if "Not now" was pressed last time.
        lifecycleScope.launch {
            val c = (application as IptvApp).container
            if (c.settings.settings.first().checkUpdatesOnOpen) c.updater.check(manual = true)
        }
    }
}
