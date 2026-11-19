package tv.luna.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.luna.player.player.PlayerManager
import tv.luna.player.player.TrackOption

private val Accent: Color get() = tv.luna.player.ui.theme.Palette.accent
private val Glow = Color(0xFFEFFBFC)
private val Ink: Color get() = tv.luna.player.ui.theme.Palette.ink
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private enum class Popup { AUDIO, SUBTITLES }

/**
 * Luna's own controls for films, episodes and recordings: title at the top, a progress bar with times, and big round
 * buttons (back 10 s, play/pause, forward 30 s, audio, subtitles). Hidden: OK or Up/Down shows them, Left/Right skips.
 * Back first closes a list, then hides the controls; only with nothing showing does it leave the player.
 */
@Composable
fun VodControls(
    player: PlayerManager, title: String,
    /** The next episode ("S1 · E4"), when there is one. */
    nextLabel: String? = null,
    /** Seconds before the end when the Next episode button shows (0 = never). */
    nextAtSeconds: Int = 45,
    /** At the very end, go on to the next episode by itself. */
    autoNext: Boolean = true,
    onNext: () -> Unit = {},
) {
    val p = player.player
    var visible by remember { mutableStateOf(true) }
    var popup by remember { mutableStateOf<Popup?>(null) }
    var lastKey by remember { mutableLongStateOf(System.nanoTime()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    val paused by player.paused.collectAsState()
    val tracks by player.tracks.collectAsState()
    val catcher = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var nextDismissed by remember { mutableStateOf(false) }
    var nextGone by remember { mutableStateOf(false) } // fired once
    val remaining = if (duration > 0) duration - position else Long.MAX_VALUE
    val showNext = nextLabel != null && nextAtSeconds > 0 && !nextDismissed && remaining <= nextAtSeconds * 1000L
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        while (true) {
            position = p.currentPosition; duration = p.duration.coerceAtLeast(0); buffered = p.bufferedPosition
            delay(400)
        }
    }
    // Quiet for a few seconds while playing: the controls go.
    LaunchedEffect(visible, lastKey, paused, popup) {
        if (visible && !paused) { delay(if (popup != null) 12_000 else 5_000); popup = null; visible = false }
    }
    LaunchedEffect(visible) {
        withFrameNanos { }
        runCatching { if (visible) playFocus.requestFocus() else if (!showNext) catcher.requestFocus() else nextFocus.requestFocus() }
    }
    BackHandler(enabled = visible) { visible = false }
    androidx.compose.runtime.DisposableEffect(Unit) {
        PlayerBack.close = { when { popup != null -> { popup = null; true }; visible -> { visible = false; true }; else -> false } }
        onDispose { PlayerBack.close = null }
    }
    BackHandler(enabled = showNext && !visible) { nextDismissed = true }
    // The button takes focus as it appears, so OK plays the next episode.
    LaunchedEffect(showNext) { if (showNext) { withFrameNanos { }; runCatching { nextFocus.requestFocus() } } }
    // Finished: on to the next episode (if allowed), once.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val d = p.duration
            val ended = p.playbackState == androidx.media3.common.Player.STATE_ENDED || (d > 0 && p.currentPosition >= d - 400)
            if (ended && autoNext && nextLabel != null && !nextGone) { nextGone = true; onNext(); break }
        }
    }
    BackHandler(enabled = popup != null) { popup = null }

    fun seekBy(ms: Long) {
        val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, d)); position = p.currentPosition
    }
    fun show() { visible = true; lastKey = System.nanoTime() }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            lastKey = System.nanoTime()
            // The remote's own play/pause and skip keys always work.
            if (e.type == KeyEventType.KeyDown) when (e.key) {
                Key.MediaPlayPause -> { player.togglePause(); show(); return@onPreviewKeyEvent true }
                Key.MediaPlay -> { if (paused) player.togglePause(); show(); return@onPreviewKeyEvent true }
                Key.MediaPause -> { if (!paused) player.togglePause(); show(); return@onPreviewKeyEvent true }
                Key.MediaFastForward -> { seekBy(30_000); show(); return@onPreviewKeyEvent true }
                Key.MediaRewind -> { seekBy(-10_000); show(); return@onPreviewKeyEvent true }
                else -> Unit
            }
            false
        },
    ) {
        // Key target while the controls are hidden.
        // Only focusable while the controls are hidden, so moving around the buttons can never land on it.
        Box(Modifier.fillMaxSize().focusRequester(catcher).focusable(enabled = !visible).onKeyEvent { e ->
            if (visible || e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { seekBy(-10_000); show(); true }
                Key.DirectionRight -> { seekBy(30_000); show(); true }
                Key.DirectionUp, Key.DirectionDown, in OK_KEYS -> { show(); true }
                else -> false
            }
        })

        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6031A1F)))))
                Text(title, fontSize = 22.sp, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                    modifier = Modifier.padding(start = 48.dp, top = 30.dp, end = 160.dp))
                if (paused) Text("Paused", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 48.dp).clip(RoundedCornerShape(12.dp)).background(Glow)
                        .padding(horizontal = 12.dp, vertical = 4.dp))

                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, bottom = 28.dp)) {
                    SeekBar(position, buffered, duration) { seekBy(it); lastKey = System.nanoTime() }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(clock(position), fontSize = 12.sp, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(if (duration > 0) "-" + clock(duration - position) else "", fontSize = 12.sp, color = Color(0xCCEFFBFC))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        RoundButton(40.dp, onClick = { seekBy(-10_000) }) { c -> SkipIcon(c, back = true) }
                        Spacer(Modifier.width(18.dp))
                        RoundButton(54.dp, Modifier.focusRequester(playFocus), onClick = { player.togglePause() }) { c -> PlayPauseIcon(c, paused) }
                        Spacer(Modifier.width(18.dp))
                        RoundButton(40.dp, onClick = { seekBy(30_000) }) { c -> SkipIcon(c, back = false) }
                        Spacer(Modifier.weight(1f))
                    }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tracks.audio.size > 1) PillButton("Audio") { popup = Popup.AUDIO }
                    if (tracks.text.isNotEmpty()) PillButton("Subtitles") { popup = Popup.SUBTITLES }
                }
            }
        }

        // Starting or buffering: a spinner in the middle, so a dark screen never looks frozen.
        val state by produceStateCompat(p)
        if (state == androidx.media3.common.Player.STATE_BUFFERING || (state == androidx.media3.common.Player.STATE_IDLE && duration == 0L)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 34.dp, color = Color.White)
                Text("Loading…", fontSize = 13.sp, color = Color(0xCCEFFBFC), modifier = Modifier.padding(top = 10.dp))
            }
        }

        // Near the end of an episode: Next episode, bottom right, over the picture (Back hides it).
        AnimatedVisibility(showNext, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (visible) 96.dp else 40.dp),
            enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            FocusBox(onClick = { if (!nextGone) { nextGone = true; onNext() } }, modifier = Modifier.focusRequester(nextFocus)) { f ->
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (f) Glow else Color(0xE606323A))
                    .border(1.dp, if (f) Color.Transparent else Color(0x665FD6DC), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("NEXT EPISODE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = if (f) Ink else Accent)
                        Text(nextLabel.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (f) Ink else Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(if (remaining < Long.MAX_VALUE) "${(remaining / 1000).coerceAtLeast(0)}s" else "", fontSize = 13.sp, color = if (f) Ink else Color(0xCCEFFBFC))
                }
            }
        }

        popup?.let { which ->
            val options: List<Pair<String, Boolean>> = when (which) {
                Popup.AUDIO -> tracks.audio.map { (it.label + (if (it.detail.isNotEmpty()) "  ·  " + it.detail else "")) to it.selected }
                Popup.SUBTITLES -> listOf("Off" to tracks.textOff) + tracks.text.map { it.label to it.selected }
            }
            TrackList(if (which == Popup.AUDIO) "Audio" else "Subtitles", options) { i ->
                when (which) {
                    Popup.AUDIO -> tracks.audio.getOrNull(i)?.let(player::selectAudio)
                    Popup.SUBTITLES -> if (i == 0) player.selectText(null) else tracks.text.getOrNull(i - 1)?.let { o: TrackOption -> player.selectText(o) }
                }
                popup = null
            }
        }
    }
}

/** The player's state (idle, buffering, ready, ended), checked a few times a second. */
@Composable
private fun produceStateCompat(p: androidx.media3.common.Player) = androidx.compose.runtime.produceState(p.playbackState, p) {
    while (true) { value = p.playbackState; delay(300) }
}

@Composable
private fun SeekBar(position: Long, buffered: Long, duration: Long, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buf = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(22.dp).onFocusChanged { focused = it.isFocused }.focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onSeek(-10_000); true }
                    Key.DirectionRight -> { onSeek(10_000); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (focused) 7.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(buf).height(h).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(4.dp)).background(Accent))
        // The knob, at the playing point.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val x = maxWidth * frac
            Box(Modifier.padding(start = (x - 7.dp).coerceAtLeast(0.dp)).size(if (focused) 16.dp else 12.dp).clip(CircleShape)
                .background(if (focused) Glow else Color.White))
        }
    }
}

@Composable
private fun RoundButton(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Box(
            Modifier.size(size).clip(CircleShape).background(if (f) Glow else Color(0x33FFFFFF))
                .border(1.dp, if (f) Color.Transparent else Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon(if (f) Ink else Color.White) }
    }
}

@Composable
private fun PlayPauseIcon(c: Color, paused: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        if (paused) {
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(w * .2f, 0f); lineTo(w, h / 2); lineTo(w * .2f, h); close() }
            drawPath(path, c)
        } else {
            drawRoundRect(c, Offset(w * .12f, 0f), androidx.compose.ui.geometry.Size(w * .28f, h), androidx.compose.ui.geometry.CornerRadius(2f))
            drawRoundRect(c, Offset(w * .6f, 0f), androidx.compose.ui.geometry.Size(w * .28f, h), androidx.compose.ui.geometry.CornerRadius(2f))
        }
    }
}

/** A circular arrow with the seconds in the middle (10 back, 30 forward). */
@Composable
private fun SkipIcon(c: Color, back: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val sw = 2.dp.toPx()
            drawArc(c, if (back) 200f else -20f, 300f, false, style = Stroke(sw, cap = StrokeCap.Round),
                topLeft = Offset(sw, sw), size = androidx.compose.ui.geometry.Size(size.width - 2 * sw, size.height - 2 * sw))
        }
        Text(if (back) "10" else "30", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = c)
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick) { f ->
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (f) Ink else Color.White,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (f) Glow else Color(0x33FFFFFF))
                .border(1.dp, if (f) Color.Transparent else Color(0x40FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 7.dp))
    }
}

@Composable
private fun TrackList(title: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(end = 48.dp).width(300.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF206323A))
                .border(1.dp, tv.luna.player.ui.theme.Palette.navBorder, RoundedCornerShape(18.dp)).padding(14.dp),
        ) {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = Accent,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            val start = options.indexOfFirst { it.second }.coerceAtLeast(0)
            LazyColumn(Modifier.height((options.size.coerceAtMost(7) * 40).dp)) {
                itemsIndexed(options) { i, (label, selected) ->
                    FocusBox(onClick = { onPick(i) }, modifier = if (i == start) Modifier.focusRequester(first) else Modifier) { f ->
                        Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (f) Glow else Color.Transparent)
                            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 13.sp, color = if (f) Ink else Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                            if (selected) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (f) Ink else Accent)
                        }
                    }
                }
            }
        }
    }
}
