package org.imdtv.iptvtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.player.PlayerManager
import androidx.tv.material3.Text
import org.imdtv.iptvtv.ui.live.LiveOverlay
import org.imdtv.iptvtv.ui.live.LiveViewModel

private val OK = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private val PLAY_PAUSE = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause)

/** Full-screen playback. Live ([liveVm] set) adds the info overlay (OK opens, Back closes, 5s auto-hide) and Up/Down zapping. */
@Composable
fun PlayerScreen(player: PlayerManager, title: String, liveVm: LiveViewModel? = null, onGuide: () -> Unit = {}) {
    val live = liveVm != null
    val focused = liveVm?.focused?.collectAsState()?.value
    val name = focused?.displayName(liveVm.strip.collectAsState().value) ?: title
    var error by remember { mutableStateOf<String?>(null) }
    val shownName by androidx.compose.runtime.rememberUpdatedState(if (live) name else title) // the zapped-to channel, not the first
    LaunchedEffect(Unit) { player.errors.collect { error = "Can't play $shownName: ${it.errorCodeName}" } }
    LaunchedEffect(focused?.id) { error = null } // a zap starts a new stream

    var overlay by remember { mutableStateOf(false) }
    var popupOpen by remember { mutableStateOf(false) }
    var lastKey by remember { mutableLongStateOf(0L) }
    val keys = remember { FocusRequester() }
    val paused by player.paused.collectAsState()
    BackHandler(enabled = overlay) { overlay = false }
    LaunchedEffect(overlay, lastKey, popupOpen) {
        // The menu goes away on its own after a few quiet seconds (longer while a list is open).
        if (overlay) { delay(if (popupOpen) 12_000 else 5_000); overlay = false }
        else if (live) { withFrameNanos { }; runCatching { keys.requestFocus() } }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                lastKey = System.nanoTime()
                // The remote's own play/pause keys work whether or not the menu is showing.
                if (live && e.key in PLAY_PAUSE) {
                    if (e.type == KeyEventType.KeyDown) when (e.key) {
                        Key.MediaPlay -> if (paused) player.togglePause()
                        Key.MediaPause -> if (!paused) player.togglePause()
                        else -> player.togglePause()
                    }
                    true
                } else false
            },
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).also {
                it.useController = !live
                if (!live) { insetControls(it); it.setShowSubtitleButton(true); keepFocusWhenControlsHide(it) } // shown beside the cog only when the file has subtitles
                player.attach(it)
                if (live) it.isFocusable = false else it.requestFocus()
            } },
            onRelease = { player.detach(it) },
            modifier = Modifier.fillMaxSize(),
        )
        if (liveVm != null) {
            // Key target while the overlay is closed. OK opens on key-up, so the same press can't also click the TV guide card.
            Box(Modifier.fillMaxSize().focusRequester(keys).focusable().onKeyEvent { e ->
                if (overlay) return@onKeyEvent false
                when {
                    e.key in OK -> { if (e.type == KeyEventType.KeyUp) overlay = true; true }
                    e.key == Key.DirectionUp -> { if (e.type == KeyEventType.KeyDown) liveVm.zapBy(-1); true }
                    e.key == Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) liveVm.zapBy(1); true }
                    else -> false
                }
            })
            // The menu fades in and out rather than snapping.
            AnimatedVisibility(overlay, enter = fadeIn(tween(240)), exit = fadeOut(tween(300))) {
                LiveOverlay(liveVm, onGuide = onGuide, onPick = { liveVm.switchTo(it); overlay = false }, onPopup = { popupOpen = it })
            }
            // Paused with the menu hidden: say so, so a still picture isn't mistaken for a frozen stream.
            if (paused && !overlay) Text("❚❚  Paused", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp).background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 6.dp))
        }
        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000)).padding(24.dp))
        }
    }
}

/** Moves the controller's bottom bar (cog, time) and time bar into the TV-safe area; the dark backdrop stays full-screen. */
private fun insetControls(view: PlayerView) {
    val d = view.resources.displayMetrics.density
    val side = (48 * d).toInt(); val bottom = (27 * d).toInt()
    listOf(androidx.media3.ui.R.id.exo_bottom_bar, androidx.media3.ui.R.id.exo_progress).forEach { id ->
        view.findViewById<android.view.View>(id)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin += side; rightMargin += side; bottomMargin += bottom
        }
    }
}

/** When the controls hide with focus on one of their buttons (subtitles, cog), Android drops focus and the D-pad goes
 *  nowhere; hand it back to the player view so the next key shows the controls again. */
fun keepFocusWhenControlsHide(view: PlayerView) {
    view.isFocusable = true
    view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
        if (v != android.view.View.VISIBLE && !view.isFocused) view.requestFocus()
    })
}
