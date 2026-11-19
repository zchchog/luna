package tv.luna.player.ui.live

import tv.luna.player.ui.theme.fadeListEdges
import tv.luna.player.ui.theme.railBackground
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import tv.luna.player.ui.theme.LocalTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.ui.AppLogo
import tv.luna.player.ui.onLongOk
import tv.luna.player.data.repo.RailEntry
import tv.luna.player.data.repo.RailItem
import tv.luna.player.ui.FocusBox

/** How far the guide moves right while the categories are open. */
val RAIL_WIDTH = 300.dp // wide enough to read most category names in full

/** Rail move mode (spec §4.3): Up/Down moves, OK drops, Back puts it back. */
internal class RailMove(val cat: CategoryEntity, val from: Int)

@Composable
internal fun Rail(
    items: List<RailItem>, selectedKey: String?, focus: FocusRequester, move: RailMove?,
    onPick: (RailEntry) -> Unit, onLongPress: (RailEntry) -> Unit, onMove: (Int) -> Unit, onMoveEnd: (cancel: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val start = items.indexOfFirst { it.entry.key == selectedKey }.coerceAtLeast(0)
    val state = rememberLazyListState(start)
    val movingKey = move?.let { "c${it.cat.id}" }
    val moveFocus = remember { FocusRequester() }
    val movingIndex = items.indexOfFirst { it.entry.key == movingKey }
    LaunchedEffect(movingKey) { if (movingKey != null) { withFrameNanos { }; runCatching { moveFocus.requestFocus() } } }
    LaunchedEffect(movingIndex) {
        if (movingIndex >= 0 && movingIndex !in state.layoutInfo.visibleItemsInfo.map { it.index }.drop(1).dropLast(1)) {
            state.scrollToItem((movingIndex - 3).coerceAtLeast(0))
        }
    }
    Column(
        // Part of the screen, not a panel over it: the guide moves right to make room, and the background shows through.
        Modifier.fillMaxHeight().width(RAIL_WIDTH)
            // A faint line down its right side, fading out at both ends, so it reads as its own panel.
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, .15f to Color(0x33EFFBFC), .8f to Color(0x33EFFBFC), 1f to Color.Transparent),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
            }
            .padding(top = 4.dp) // no bottom padding: the list runs to the screen's bottom edge
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                if (move != null) {
                    // An ancestor sees keys first: handle the whole move here so the row's click and the close never fire.
                    when (e.key) {
                        Key.DirectionUp -> if (down) onMove(-1)
                        Key.DirectionDown -> if (down) onMove(1)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!down) onMoveEnd(false)
                        Key.Back -> if (!down) onMoveEnd(true)
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (down && (e.key == Key.Back || e.key == Key.DirectionLeft)) { onClose(); true } else false
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CATEGORIES", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tv.luna.player.ui.theme.Palette.accent, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f))
        }
        if (move != null) Text("Up/Down move · OK drop · Back cancel", fontSize = 11.sp, color = LocalTheme.current.colors.muted,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        LazyColumn(
            state = state, verticalArrangement = Arrangement.spacedBy(5.dp),
            // Room under the last category, so it can be scrolled up out of the fade at the bottom.
            // Sideways room too: the highlighted row grows and glows, and the list's fade would otherwise cut it flat.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 14.dp, top = 10.dp, bottom = 90.dp),
            modifier = Modifier.weight(1f).fadeListEdges(state),
        ) {
            itemsIndexed(items, key = { _, c -> c.entry.key }) { i, rc ->
                val isMoving = rc.entry.key == movingKey
                FocusBox(
                    onClick = { onPick(rc.entry) },
                    modifier = (if (i == start) Modifier.focusRequester(focus) else Modifier)
                        .then(if (isMoving) Modifier.focusRequester(moveFocus) else Modifier)
                        // Only provider categories have a menu; Favourites and user categories are managed in Settings.
                        .then(if (rc.entry is RailEntry.Provider) Modifier.onLongOk { onLongPress(rc.entry) } else Modifier)
                        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onPick(rc.entry); true } else false },
                ) { f ->
                    val colors = LocalTheme.current.colors
                    // The same pills as the guide's channel names: glass cells, a glowing light one where the remote is.
                    Row(
                        Modifier.fillMaxWidth().height(32.dp)
                            .graphicsLayer { val sc = if (f) 1.025f else 1f; scaleX = sc; scaleY = sc }
                            .then(if (f) Modifier.shadow(14.dp, RoundedCornerShape(5.dp), ambientColor = tv.luna.player.ui.theme.Palette.glow, spotColor = tv.luna.player.ui.theme.Palette.glow) else Modifier)
                            .background(
                                if (isMoving) Color(0xFFE5A00D) else if (f) colors.focus else colors.cell,
                                RoundedCornerShape(5.dp),
                            )
                            // The category being shown has a light edge, so it can be found again.
                            .then(if (!f && !isMoving && rc.entry.key == selectedKey) Modifier.border(1.5.dp, Color(0xCCFFFFFF), RoundedCornerShape(5.dp)) else Modifier)
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (f || isMoving) colors.onFocus else Color.White
                        Text((if (isMoving) "↕ " else "") + rc.entry.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("  " + rc.count, fontSize = 12.sp, color = fg.copy(alpha = .85f))
                    }
                }
            }
        }
    }
}

/** Ends move mode; Back (cancel) puts the category back where it started. */
internal fun endRailMove(vm: LiveViewModel, state: GuideState, providerKeys: List<String>, cancel: Boolean) {
    state.railMove?.let { m -> if (cancel) vm.moveCategory(m.cat, m.from - providerKeys.indexOf("c${m.cat.id}")) }
    state.railMove = null
}

/** The category rail wired to the guide: pick, long-press Hide/Move, move mode, close (spec §4.3). */
@Composable
fun CategoryRail(vm: LiveViewModel, state: GuideState, modifier: Modifier = Modifier) {
    val rail by vm.rail.collectAsState()
    val entry by vm.entry.collectAsState()
    val providerKeys = rail.orEmpty().filter { it.entry is RailEntry.Provider }.map { it.entry.key }
    Box(modifier) {
        Rail(rail.orEmpty(), entry?.key, state.railFocus, state.railMove,
            onPick = { vm.select(it); state.railOpen = false },
            onLongPress = { e -> if (e is RailEntry.Provider) state.menu = GuideMenu.Category(e.category) },
            onMove = { d -> state.railMove?.let { vm.moveCategory(it.cat, d) } },
            onMoveEnd = { cancel -> endRailMove(vm, state, providerKeys, cancel) },
            onClose = { state.railOpen = false; state.refocus() })
    }
}
