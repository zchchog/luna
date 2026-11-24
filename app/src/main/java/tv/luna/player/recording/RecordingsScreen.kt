package tv.luna.player.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.ui.ContextMenu
import tv.luna.player.ui.FocusBox
import tv.luna.player.ui.clockTime
import tv.luna.player.ui.dayDate
import tv.luna.player.ui.duration
import tv.luna.player.ui.onLongOk
import tv.luna.player.ui.theme.LocalTheme
import tv.luna.player.ui.theme.guideBackground

/** "Sat 15/11 6.00pm · 1h 05m" (length once known). */
fun recordingWhen(r: RecordingEntity): String {
    val start = r.actualStart ?: r.scheduledStart
    val length = r.actualEnd?.let { it - start } ?: (r.scheduledEnd - start).takeIf { r.status == RecStatus.SCHEDULED }
    return "${dayDate(start)} ${clockTime(start)}" + (length?.let { " · ${duration(it)}" } ?: "")
}

fun recordingStatus(r: RecordingEntity): String = when (r.status) {
    RecStatus.SCHEDULED -> "Scheduled"
    RecStatus.RECORDING -> "Recording · ends ${clockTime(r.scheduledEnd)}"
    RecStatus.DONE -> ""
    RecStatus.STOPPED -> r.statusReason ?: "Stopped"
    RecStatus.FAILED -> r.statusReason ?: "Failed"
    RecStatus.SKIPPED -> r.statusReason ?: "Skipped — you were watching"
    RecStatus.MISSED -> r.statusReason ?: "Missed — the box was off"
}

/** The row last focused, kept across the screen being rebuilt (full-screen playback and back). */
private var lastRow: Long? = null

private fun RecordingEntity.playable() = location != null && (status == RecStatus.DONE || status == RecStatus.STOPPED)

/** What a recording's menu offers: [all] = every action (Gamestation's OK), else the hold-OK menu. */
@Composable
fun RecordingMenu(r: RecordingEntity, all: Boolean, onPlay: (RecordingEntity) -> Unit, onDismiss: () -> Unit) {
    val c = (LocalContext.current.applicationContext as IptvApp).container
    val scope = rememberCoroutineScope()
    // "one": deleting this recording; "series": what to do with the whole series.
    var confirm by remember(r.id) { mutableStateOf<String?>(null) }
    val seriesId = r.seriesId
    fun stopSeries(deleteRecorded: Boolean) {
        scope.launch { if (seriesId != null) c.recordings.stopSeries(seriesId, deleteRecorded, c::deleteRecordingFile) }
        onDismiss()
    }
    when (confirm) {
        "one" -> { ContextMenu("Delete ${r.title}?", listOf(
            "Delete" to { scope.launch { c.recordings.delete(r.id, c::deleteRecordingFile) }; onDismiss() },
            "Cancel" to onDismiss,
        ), onDismiss); return }
        "series" -> { ContextMenu("${r.title}\nThe whole series", listOf(
            "Stop the series, keep what's recorded" to { stopSeries(false) },
            "Stop the series and delete all its recordings" to { stopSeries(true) },
            "Cancel" to onDismiss,
        ), onDismiss); return }
    }
    val items = buildList {
        if (all && r.playable()) add("Play" to { onDismiss(); onPlay(r) })
        when (r.status) {
            RecStatus.SCHEDULED -> add((if (seriesId != null) "Cancel this episode" else "Cancel recording") to { scope.launch { c.recordings.cancel(r.id) }; onDismiss() })
            RecStatus.RECORDING -> add("Stop recording" to { c.recordings.stop(r.id); onDismiss() })
            else -> add((if (seriesId != null) "Delete this episode" else "Delete") to { confirm = "one" })
        }
        if (seriesId != null) add((if (r.status == RecStatus.SCHEDULED || r.status == RecStatus.RECORDING) "Stop recording the series" else "Delete all episodes in this series") to { confirm = "series" })
    }
    ContextMenu(r.title, items, onDismiss)
}

/**
 * The Recordings tab: scheduled first, then finished newest first. OK plays (or offers Cancel / Stop), hold OK → Delete.
 * [nav] (Luna, Plus, Liquid Glass) draws the theme's background and menu above the list; without it the list
 * sits inside the theme's own frame (Retro, Xcube). [firstModifier] goes on the first row (Xcube's blade card).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RecordingsScreen(nav: (@Composable () -> Unit)?, onPlay: (RecordingEntity) -> Unit, firstModifier: Modifier = Modifier, textColor: Color = Color.White) {
    val c = (LocalContext.current.applicationContext as IptvApp).container
    val rows by remember { c.recordings.observe() }.collectAsState(null)
    val colors = LocalTheme.current.colors
    var menu by remember { mutableStateOf<Pair<RecordingEntity, Boolean>?>(null) }
    // Back from playing a recording lands on its row again (the screen is rebuilt meanwhile).
    val rowFocus = remember { mutableMapOf<Long, androidx.compose.ui.focus.FocusRequester>() }
    androidx.compose.runtime.LaunchedEffect(rows != null) {
        val id = lastRow ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        rowFocus[id]?.let { runCatching { it.requestFocus() } }
    }
    Box(if (nav != null) Modifier.fillMaxSize().guideBackground() else Modifier.fillMaxSize()) {
        Column(Modifier.padding(if (nav != null) androidx.compose.foundation.layout.PaddingValues(start = 50.dp, end = 50.dp, top = 22.dp) else androidx.compose.foundation.layout.PaddingValues(0.dp))) {
            nav?.invoke()
            val list = rows?.filter { !(it.status == RecStatus.SKIPPED && it.statusReason == CANCELLED) }
            if (list != null && list.isEmpty()) {
                // Plain text: nothing to select here.
                Column(Modifier.padding(start = 8.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nothing recorded yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                    Text("Hold OK on a programme in the TV guide and choose Record. It will show up here.", fontSize = 15.sp, color = textColor.copy(alpha = .8f))
                }
            }
            val all = list.orEmpty()
            val sections = listOf(
                "Recording now" to all.filter { it.status == RecStatus.RECORDING },
                "Scheduled" to all.filter { it.status == RecStatus.SCHEDULED },
                "Recorded" to all.filter { it.playable() },
                "Didn't record" to all.filter { it.status == RecStatus.FAILED || it.status == RecStatus.SKIPPED || it.status == RecStatus.MISSED },
            ).filter { it.second.isNotEmpty() }
            val firstId = sections.firstOrNull()?.second?.firstOrNull()?.id
            // Entering the list (Down from the menu) lands on a card: the last one used, else the first.
            val enterRow = (lastRow?.takeIf { id -> list.orEmpty().any { it.id == id } } ?: firstId)?.let { rowFocus[it] }
            LazyColumn(Modifier.padding(top = if (nav != null) 8.dp else 8.dp)
                .focusProperties { enter = { enterRow ?: androidx.compose.ui.focus.FocusRequester.Default } }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp)) {
                item(key = "header") { RecordingsHeader(c, all.count { it.playable() }, all.count { it.status == RecStatus.SCHEDULED }) }
                sections.forEach { (heading, items) ->
                    item(key = "h-$heading") {
                        Text(heading.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = Color(0xB3FFFFFF),
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    // Cards three across, so a long list stays a calm grid instead of a wall of rows.
                    items(items.chunked(3), key = { row -> "r-" + row.first().id }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { r ->
                                FocusBox(
                                    onClick = { if (r.playable()) onPlay(r) else menu = r to false },
                                    modifier = Modifier.weight(1f)
                                        .then(if (r.id == firstId) firstModifier else Modifier)
                                        .focusRequester(rowFocus.getOrPut(r.id) { androidx.compose.ui.focus.FocusRequester() })
                                        .onLongOk { menu = r to false },
                                    onFocused = { lastRow = r.id },
                                ) { f -> RecordingCard(r, f, textColor) }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
    menu?.let { (r, all) -> RecordingMenu(r, all, onPlay) { menu = null } }
}

/** The page's title, and one quiet line: where recordings go, what is free, and how many there are. */
@Composable
private fun RecordingsHeader(c: tv.luna.player.AppContainer, recorded: Int, scheduled: Int) {
    val settings by c.settings.settings.collectAsState(null)
    val place = when (settings?.recordTo) {
        RecStorage.USB -> "USB drive"; RecStorage.SMB -> "Network share"; else -> "This box"
    }
    val free by androidx.compose.runtime.produceState<Long?>(null, settings?.recordTo, settings?.recordUsbPath) {
        val st = settings ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { c.recordingStore(st)?.freeBytes() }.getOrNull() }
    }
    Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
        Text("Recordings", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        val line = buildList {
            add("Saving to $place")
            free?.let { add(freeSpaceText(it)) }
            if (recorded + scheduled > 0) add("$recorded recorded, $scheduled scheduled")
        }.joinToString("  ·  ")
        Text(line, fontSize = 14.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp))
    }
}

/** One recording as a card: channel logo, a status pill, the title, when it was on, and how far you have watched. */
@Composable
private fun RecordingCard(r: RecordingEntity, f: Boolean, textColor: Color) {
    val colors = LocalTheme.current.colors
    val fg = if (f) colors.onFocus else textColor
    val shape = RoundedCornerShape(16.dp)
    val recording = r.status == RecStatus.RECORDING
    val failed = r.status == RecStatus.FAILED || r.status == RecStatus.SKIPPED || r.status == RecStatus.MISSED
    val length = r.actualEnd?.let { e -> e - (r.actualStart ?: r.scheduledStart) }
    val watched = if (length != null && length > 0 && r.positionMs > 0) (r.positionMs.toFloat() / length).coerceIn(0f, 1f) else null
    Column(
        Modifier.fillMaxWidth().height(142.dp)
            .graphicsLayer { val sc = if (f) 1.03f else 1f; scaleX = sc; scaleY = sc }
            .then(if (f) Modifier.shadow(18.dp, shape, ambientColor = tv.luna.player.ui.theme.Palette.glow, spotColor = tv.luna.player.ui.theme.Palette.glow) else Modifier)
            .clip(shape)
            .background(if (f) SolidColor(colors.focus) else Brush.linearGradient(listOf(Color(0x24FFFFFF), Color(0x14FFFFFF))))
            .border(1.dp, if (f) Color.Transparent else Color(0x2EFFFFFF), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp, 34.dp).clip(RoundedCornerShape(7.dp)).background(if (r.channelLogo != null) Color(0x8C050F33) else Color.Transparent).padding(4.dp), contentAlignment = Alignment.Center) {
                if (r.channelLogo != null) coil.compose.AsyncImage(r.channelLogo, null, Modifier.fillMaxSize())
                else Text(r.channelName.take(3), fontSize = 13.sp, color = fg, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            val pill = when {
                recording -> "● REC"
                r.status == RecStatus.SCHEDULED -> if (r.seriesId != null) "SERIES" else "SCHEDULED"
                failed -> "DIDN'T RECORD"
                r.seriesId != null -> "SERIES"
                else -> null
            }
            if (pill != null) Text(pill, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp,
                color = if (recording || failed) Color.White else if (f) colors.onFocus else Color.White,
                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                    .background(when { recording -> Color(0xFFE53935); failed -> Color(0xCC9E3A3A); f -> Color(0x1F000000); else -> Color(0x33FFFFFF) })
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Column {
            Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (failed) recordingStatus(r) else if (recording) recordingStatus(r) else if (r.channelLogo != null) recordingWhen(r) else "${r.channelName} · ${recordingWhen(r)}"
            Text(detail, fontSize = 13.sp, color = fg.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (watched != null) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = .22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(watched).background(if (f) colors.onFocus else tv.luna.player.ui.theme.Palette.accent))
            }
        }
    }
}

/** Solid on the left, then fading to nothing towards the right edge. */
private fun fadeRight(c: Color) = Brush.horizontalGradient(0f to c, .45f to c.copy(alpha = c.alpha * .7f), 1f to c.copy(alpha = 0f))
