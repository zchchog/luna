package tv.luna.player.ui.live

import kotlinx.coroutines.launch

import tv.luna.player.ui.theme.smokedGlass
import tv.luna.player.ui.theme.LocalTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import tv.luna.player.player.PictureSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.luna.player.data.settings.LogoKind
import tv.luna.player.ui.ChannelLogo
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.player.badges
import tv.luna.player.ui.FocusBox
import tv.luna.player.ui.clockTime

/** What the viewer is adjusting in a popup. */
private enum class Popup { AUDIO, SUBTITLES, QUALITY, PICTURE, DELAY, VERSIONS, RECORD_WHERE }

/**
 * The info and controls over live full screen. Focus starts on Pause/Play; the row of controls opens popups for audio,
 * subtitles, quality, picture size and audio delay. Down shows the TV guide and recent channels underneath.
 * [onPopup] tells the player screen a popup is open, so the menu stays up while the viewer reads it.
 */
@Composable
fun LiveOverlay(vm: LiveViewModel, onGuide: () -> Unit, onPick: (ChannelEntity) -> Unit, onPopup: (Boolean) -> Unit = {}) {
    val entry by vm.entry.collectAsState()
    val now by vm.now.collectAsState()
    val focused by vm.focused.collectAsState()
    val channels by vm.channels.collectAsState()
    val programmes by vm.programmes.collectAsState()
    val recents by vm.recents.collectAsState()
    val recentProgs by vm.recentProgrammes.collectAsState()
    val strip by vm.strip.collectAsState()
    val logoSource by vm.logoSource.collectAsState()
    val formats by vm.player.formats.collectAsState()
    val ch = channels.find { it.id == focused?.id } ?: focused ?: return
    val progs = programmes[ch.id] ?: recentProgs[ch.id].orEmpty()
    val cur = progs.firstOrNull { it.start <= now && now < it.stop }
    val next = progs.firstOrNull { it.start >= (cur?.stop ?: now) }
    val progress = cur?.let { ((now - it.start).toFloat() / (it.stop - it.start)).coerceIn(0f, 1f) }

    val player = vm.player
    val paused by player.paused.collectAsState()
    val tracks by player.tracks.collectAsState()
    val pictureSize by player.pictureSize.collectAsState()
    val delayMs by player.audioDelayMs.collectAsState()
    val recordingOn by vm.recordingOn.collectAsState()
    val recordingOnChannel by vm.recordingOnChannel.collectAsState()
    val recordingId = recordingOnChannel[ch.id]
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The same channel offered in other qualities (HD, 4K...), found in the playlist.
    val versions by androidx.compose.runtime.produceState(emptyList<ChannelEntity>(), ch.id) { value = vm.versionsOf(ch) }
    var popup by remember { mutableStateOf<Popup?>(null) }
    var lastPopup by remember { mutableStateOf<Popup?>(null) }
    var showStrip by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pauseFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val buttonFocus = remember { Popup.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { pauseFocus.requestFocus() } }
    LaunchedEffect(popup) {
        onPopup(popup != null)
        // Closing a popup puts the highlight back on the button that opened it.
        if (popup == null) lastPopup?.let { withFrameNanos { }; runCatching { buttonFocus.getValue(it).requestFocus() } }
    }
    // The settings row slides in under the buttons and takes the highlight.
    LaunchedEffect(showSettings) { if (showSettings) { withFrameNanos { }; withFrameNanos { }; runCatching { buttonFocus.getValue(Popup.AUDIO).requestFocus() } } }
    LaunchedEffect(showStrip) { if (showStrip) { withFrameNanos { }; withFrameNanos { }; runCatching { guideFocus.requestFocus() } } }
    BackHandler(enabled = popup != null) { popup = null }
    fun open(p: Popup) { lastPopup = p; popup = p }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), .18f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, .75f to Color(0xD9000000), 1f to Color(0xF2000000))))
        Text(entry?.title.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 28.dp))
        Text(clockTime(now), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 26.dp))

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(84.dp, 60.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    ChannelLogo(ch, logoSource, Modifier.fillMaxSize().padding(6.dp), fontSize = 18.sp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(cur?.title ?: "No guide information", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0A1753),
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF8DB0FF)).padding(horizontal = 6.dp, vertical = 1.dp))
                        if (recordingId != null) Text("● REC", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFD64545)).padding(horizontal = 6.dp, vertical = 1.dp))
                        if (cur != null) Text("${(cur.stop - now + 59_999) / 60_000} min left", fontSize = 13.sp, color = LocalTheme.current.colors.muted)
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val number = (ch.customNumber ?: ch.number)?.let { "$it  " }.orEmpty()
                        Text(number + ch.displayName(strip), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                        badges(formats).forEach { b ->
                            Text(b, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                                modifier = Modifier.border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    cur?.desc?.let {
                        Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFFDDE3F0), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(.75f))
                    }
                    next?.let {
                        Text("Next: ${clockTime(it.start)} ${it.title}", fontSize = 13.sp, color = LocalTheme.current.colors.muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            // The programme's progress: a rounded blue bar with its start and end times either side.
            if (cur != null && progress != null) Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clockTime(cur.start), fontSize = 12.sp, color = Color.White)
                Box(Modifier.padding(horizontal = 12.dp).weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8DB0FF)))
                }
                Text(clockTime(cur.stop), fontSize = 12.sp, color = Color(0xCCEEF3FF))
            }
            // First glance: play/pause and record. Down shows the stream's settings; Down again, the TV guide and recent channels.
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 14.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && !showSettings) { showSettings = true; true } else false
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(Modifier.focusRequester(pauseFocus), player::togglePause) { c -> PlayPauseIcon(paused, c) }
                // Record this channel now (asks where to save the first time), or stop the recording.
                RoundControl(Modifier.focusRequester(buttonFocus.getValue(Popup.RECORD_WHERE)), onClick = {
                    when {
                        recordingId != null -> vm.stopRecording(recordingId)
                        recordingOn -> vm.recordNow(ch)
                        else -> open(Popup.RECORD_WHERE)
                    }
                }) { c -> RecordIcon(recording = recordingId != null, c) }
            }
            AnimatedVisibility(!showSettings, enter = fadeIn(), exit = fadeOut()) {
                MoreHint("Stream settings")
            }
            AnimatedVisibility(showSettings, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(start = 48.dp, end = 48.dp, top = 14.dp)
                        // Down from the settings opens the TV guide and recent channels underneath, then moves into them.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && !showStrip) { showStrip = true; true } else false
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ControlButton("Audio", tracks.audio.firstOrNull { it.selected }?.label ?: "Default", { open(Popup.AUDIO) },
                        Modifier.focusRequester(buttonFocus.getValue(Popup.AUDIO)))
                    ControlButton("Subtitles", if (tracks.textOff) "Off" else tracks.text.firstOrNull { it.selected }?.label ?: "On", { open(Popup.SUBTITLES) },
                        Modifier.focusRequester(buttonFocus.getValue(Popup.SUBTITLES)))
                    ControlButton("Quality", if (tracks.autoQuality) "Auto" else tracks.video.firstOrNull { it.selected }?.label ?: "Auto", { open(Popup.QUALITY) },
                        Modifier.focusRequester(buttonFocus.getValue(Popup.QUALITY)))
                    ControlButton("Picture", pictureSize.label, { open(Popup.PICTURE) }, Modifier.focusRequester(buttonFocus.getValue(Popup.PICTURE)))
                    ControlButton("Audio delay", delayText(delayMs), { open(Popup.DELAY) }, Modifier.focusRequester(buttonFocus.getValue(Popup.DELAY)))
                    ControlButton("Versions", if (versions.isEmpty()) "None found" else "${versions.size} found", { open(Popup.VERSIONS) },
                        Modifier.focusRequester(buttonFocus.getValue(Popup.VERSIONS)))
                }
            }
            AnimatedVisibility(showSettings && !showStrip, enter = fadeIn(), exit = fadeOut()) {
                MoreHint("TV guide and recent channels")
            }
            AnimatedVisibility(showStrip, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LazyRow(
                    Modifier.padding(top = 14.dp).height(78.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                ) {
                    item(key = "guide") {
                        OverlayCard(onClick = onGuide, modifier = Modifier.focusRequester(guideFocus)) { f ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("TV guide", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (f) LocalTheme.current.colors.onFocus else Color.White)
                            }
                        }
                    }
                    items(recents, key = { it.id }) { r ->
                        val rp = recentProgs[r.id].orEmpty().firstOrNull { it.start <= now && now < it.stop }
                        OverlayCard(onClick = { onPick(r) }) { f -> RecentCard(r, r.displayName(strip), rp, f, logoSource) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // The popups sit above the controls, at the right.
        val popupModifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 190.dp)
        when (popup) {
            Popup.AUDIO -> ChoicePopup(
                "Audio",
                tracks.audio.map { PopupRow(it.label, it.detail, it.selected) }.ifEmpty { listOf(PopupRow("Default", "", true)) },
                if (tracks.audio.size <= 1) "This channel has one audio track." else null,
                { i -> tracks.audio.getOrNull(i)?.let(player::selectAudio); popup = null }, popupModifier)
            Popup.SUBTITLES -> ChoicePopup(
                "Subtitles",
                listOf(PopupRow("Off", "", tracks.textOff)) + tracks.text.map { PopupRow(it.label, it.detail, it.selected) },
                if (tracks.text.isEmpty()) "This channel has no subtitles." else null,
                { i -> player.selectText(if (i == 0) null else tracks.text.getOrNull(i - 1)); popup = null }, popupModifier)
            Popup.QUALITY -> ChoicePopup(
                "Quality",
                listOf(PopupRow("Automatic", "The best your connection can play", tracks.autoQuality)) +
                    tracks.video.map { PopupRow(it.label, it.detail, it.selected && !tracks.autoQuality) },
                if (tracks.video.size <= 1) "This channel offers one quality." else null,
                { i -> player.selectVideo(if (i == 0) null else tracks.video.getOrNull(i - 1)); popup = null }, popupModifier)
            Popup.PICTURE -> ChoicePopup(
                "Picture size",
                PictureSize.entries.map { PopupRow(it.label, it.detail, it == pictureSize) }, null,
                { i -> player.setPictureSize(PictureSize.entries[i]); popup = null }, popupModifier)
            Popup.VERSIONS -> ChoicePopup(
                "Other versions of this channel",
                versions.map { v ->
                    PopupRow(v.displayName(strip), tv.luna.player.data.catalogue.ChannelNames.tags(v.name).text, v.id == ch.id)
                },
                if (versions.isEmpty()) "No other versions of this channel were found in your playlist." else null,
                { i -> versions.getOrNull(i)?.takeIf { it.id != ch.id }?.let(onPick); popup = null }, popupModifier)
            Popup.DELAY -> DelayPopup(delayMs, player::setAudioDelayMs, { popup = null }, popupModifier)
            Popup.RECORD_WHERE -> {
                val drives = remember { vm.usbDrives() }
                val places = buildList<Pair<String, (() -> Any)?>> {
                    add("This box" to { scope.launch { vm.setRecordPlace(tv.luna.player.recording.RecStorage.INTERNAL); vm.recordNow(ch) } })
                    drives.forEach { (name, path) -> add("USB drive: $name" to { scope.launch { vm.setRecordPlace(tv.luna.player.recording.RecStorage.USB, path); vm.recordNow(ch) } }) }
                    if (drives.isEmpty()) add("USB drive (plug one in first)" to null)
                }
                ChoicePopup("Where should recordings go?", places.map { PopupRow(it.first, "", false) }, "Network shares are set up in Settings › Playback › Recording.",
                    { i -> places.getOrNull(i)?.second?.let { it(); popup = null } }, popupModifier)
            }
            null -> Unit
        }
    }
}

private fun delayText(ms: Int) = if (ms == 0) "0 ms" else "${if (ms > 0) "+" else ""}$ms ms"

/** One of the controls under the progress bar: its name, and what it is set to. */
@Composable
private fun ControlButton(
    label: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    FocusBox(onClick = onClick, modifier = modifier.width(100.dp).height(52.dp)) { f ->
        val fg = if (f) LocalTheme.current.colors.onFocus else Color.White
        Row(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(10.dp)).background(if (f) LocalTheme.current.colors.focus else Color(0x33FFFFFF))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { icon(fg); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                if (value != null) Text(value, fontSize = 11.sp, color = fg.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "⌄ Stream settings": a small arrow and what Down will show. */
@Composable
private fun MoreHint(label: String) {
    Row(Modifier.padding(start = 48.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp, 8.dp)) {
            val sw = 2.dp.toPx()
            drawLine(Color(0xFF8DB0FF), Offset(sw, sw), Offset(size.width / 2, size.height - sw), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFF8DB0FF), Offset(size.width - sw, sw), Offset(size.width / 2, size.height - sw), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Text("  $label", fontSize = 12.sp, color = LocalTheme.current.colors.muted)
    }
}

/** A round button in the controls row (play/pause, record): white with dark ink when highlighted. */
@Composable
private fun RoundControl(modifier: Modifier = Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (f) Color(0xFFEEF3FF) else Color(0x33FFFFFF))
            .border(1.dp, if (f) Color.Transparent else Color(0x40FFFFFF), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) { icon(if (f) Color(0xFF0A1753) else Color.White) }
    }
}

/** A red dot (record), or a red square in a ring while recording (stop). */
@Composable
private fun RecordIcon(recording: Boolean, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val red = Color(0xFFE05555)
        if (recording) {
            drawCircle(red, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawRect(red, Offset(size.width * .32f, size.height * .32f), androidx.compose.ui.geometry.Size(size.width * .36f, size.height * .36f))
        } else drawCircle(red, radius = size.minDimension * .42f)
    }
}

/** Two bars while playing (the button pauses), a triangle while paused (the button plays). */
@Composable
private fun PlayPauseIcon(paused: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        if (paused) {
            val tri = Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }
            drawPath(tri, color)
        } else {
            drawRect(color, Offset(size.width * .1f, 0f), androidx.compose.ui.geometry.Size(size.width * .3f, size.height))
            drawRect(color, Offset(size.width * .6f, 0f), androidx.compose.ui.geometry.Size(size.width * .3f, size.height))
        }
    }
}

private class PopupRow(val label: String, val detail: String, val selected: Boolean)

/** A list to pick from. Opens on the one in use; Left and Right do nothing, so they can't wander off to the controls. */
@Composable
private fun ChoicePopup(title: String, rows: List<PopupRow>, note: String?, onPick: (Int) -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { first.requestFocus() } }
    val start = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier.width(380.dp).smokedGlass(RoundedCornerShape(14.dp)).padding(14.dp)
            .onPreviewKeyEvent { e -> e.key == Key.DirectionLeft || e.key == Key.DirectionRight },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        if (note != null) Text(note, fontSize = 12.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
        LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(rows) { i, r ->
                FocusBox(onClick = { onPick(i) }, modifier = if (i == start) Modifier.focusRequester(first) else Modifier) { f ->
                    val fg = if (f) LocalTheme.current.colors.onFocus else Color.White
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (f) LocalTheme.current.colors.focus else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.detail.isNotEmpty()) Text(r.detail, fontSize = 11.sp, color = fg.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (r.selected) Text("●", fontSize = 12.sp, color = if (f) fg else LocalTheme.current.colors.accent)
                    }
                }
            }
        }
    }
}

/** Audio delay: Left and Right change it in 50 ms steps, OK puts it back to 0. Plus plays the sound later. */
@Composable
private fun DelayPopup(delayMs: Int, onChange: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { focus.requestFocus() } }
    FocusBox(onClick = { onChange(0) }, modifier = modifier.width(380.dp).focusRequester(focus)
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.DirectionUp || e.key == Key.DirectionDown
            when (e.key) {
                Key.DirectionLeft -> { onChange(delayMs - STEP); true }
                Key.DirectionRight -> { onChange(delayMs + STEP); true }
                Key.DirectionUp, Key.DirectionDown -> true
                else -> false
            }
        }) { _ ->
        Column(Modifier.fillMaxWidth().smokedGlass(RoundedCornerShape(14.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Audio delay", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(delayText(delayMs), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(vertical = 10.dp))
            Text("◀ ▶  change by 50 ms      OK  back to 0      Back  close", fontSize = 11.sp, color = LocalTheme.current.colors.muted, textAlign = TextAlign.Center)
            Text("Plus plays the sound later, minus earlier.", fontSize = 11.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val STEP = 50

@Composable
private fun OverlayCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable (Boolean) -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.width(190.dp).fillMaxHeight()) { f ->
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(if (f) LocalTheme.current.colors.focus else Color(0x33FFFFFF))) { content(f) }
    }
}

@Composable
private fun RecentCard(ch: ChannelEntity, name: String, cur: GuideProgramme?, focused: Boolean, logoSource: List<LogoKind>) {
    val fg = if (focused) LocalTheme.current.colors.onFocus else Color.White
    Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp, 34.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            ChannelLogo(ch, logoSource, Modifier.fillMaxSize().padding(3.dp), fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(((ch.customNumber ?: ch.number)?.let { "$it  " }.orEmpty()) + name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cur?.title ?: "No guide information", fontSize = 11.sp, color = fg.copy(alpha = .75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
