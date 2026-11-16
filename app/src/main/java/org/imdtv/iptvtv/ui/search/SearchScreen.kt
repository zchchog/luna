package org.imdtv.iptvtv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.ChannelEntity
import org.imdtv.iptvtv.data.search.Section
import org.imdtv.iptvtv.ui.ChannelLogo
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.theme.LocalTheme
import org.imdtv.iptvtv.ui.theme.guideBackground
import org.imdtv.iptvtv.ui.vod.Poster
import org.imdtv.iptvtv.ui.vod.VodItem

/**
 * Universal search (search spec): field at the top with the system keyboard open on arrival, then three separate
 * sections — Channels, Movies, Series — each a row. Coming back from full screen or detail refocuses that result.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel, nav: @Composable () -> Unit, onChannel: (ChannelEntity) -> Unit, onOpen: (VodItem) -> Unit,
    /** Retro and Xcube: three side-by-side lists of bars in the theme's colours, no theme background (the frame draws it). */
    lists: Boolean = false,
    /** Xcube: the field is the blade card (Left/Right switch blade). */
    /** Given "is the field being typed in". */
    fieldModifier: (typing: () -> Boolean) -> Modifier = { Modifier },
    /** Headings and empty-state text in lists mode (Retro's pale sky needs its navy). */
    textColor: Color = Color.White,
    /** False where the field is reached by moving onto it (Xcube's blade card): the keyboard waits for OK there. */
    openKeyboard: Boolean = true,
) {
    val colors = LocalTheme.current.colors
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val logos by vm.logoSource.collectAsState()
    val strip by vm.strip.collectAsState()
    val field = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() } // Down from the field: the first tile, not the one under the field's middle
    val returnTo = remember { vm.returnTo.also { vm.returnTo = null } }
    // The keyboard opens on arrival and on OK only: read-only otherwise, so passing the field with the D-pad doesn't pop it up.
    var editing by remember { mutableStateOf(returnTo == null && openKeyboard) }
    val hadFocus = remember { mutableStateOf(false) } // onFocusChanged also reports "not focused" once on first layout
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { vm.warm() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (returnTo != null && runCatching { back.requestFocus() }.isSuccess) return@LaunchedEffect
        if (!openKeyboard) return@LaunchedEffect
        runCatching { field.requestFocus() }
        keyboard?.show()
    }

    val page = if (lists) Modifier.fillMaxSize() else Modifier.fillMaxSize().guideBackground().padding(start = 24.dp, end = 24.dp, top = 22.dp)
    val shape = RoundedCornerShape(if (lists) 4.dp else 24.dp)
    Column(page) {
        nav()
        Row(
            fieldModifier { editing }.padding(top = if (lists) 0.dp else 18.dp).fillMaxWidth().clip(shape)
                .background(if (lists) colors.cell else Color(0x1FFFFFFF))
                .border(2.dp, if (hadFocus.value) Color.White else Color.Transparent, shape).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_tab_search), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Box(Modifier.padding(start = 12.dp).weight(1f)) {
                if (query.isEmpty()) Text("Search channels, films and series", fontSize = 18.sp, color = LocalTheme.current.colors.muted)
                BasicTextField(
                    query, { vm.query.value = it }, singleLine = true, readOnly = !editing,
                    textStyle = androidx.tv.material3.LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 18.sp)), cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Done/Search on the keyboard drops focus to the results.
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); runCatching { firstResult.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { if (hadFocus.value && !it.isFocused) editing = false; hadFocus.value = it.isFocused }.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { if (runCatching { firstResult.requestFocus() }.isFailure) focus.moveFocus(FocusDirection.Down); true }
                            Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                            Key.DirectionCenter, Key.Enter -> if (editing) false else { editing = true; keyboard?.show(); true }
                            else -> false
                        }
                    },
                )
            }
        }
        val r = results
        if (r == null) {
            Text("Type or say part of a name", fontSize = 15.sp, color = if (lists) textColor else colors.muted, modifier = Modifier.padding(top = 14.dp))
            return@Column
        }
        // Up out of the top results goes to the field: the wide field's far-off centre made focus search pick the nav tab.
        val resultsGroup = Modifier.focusProperties { exit = { if (it == FocusDirection.Up) field else FocusRequester.Default } }.focusGroup()
        val firstSection = listOf(r.channels.items, r.movies.items, r.series.items).indexOfFirst { it.isNotEmpty() }
        if (lists) Row(resultsGroup.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val strip2 = strip
            listOf(Triple("Channels", r.channels, 0), Triple("Movies", r.movies, 1), Triple("Series", r.series, 2)).forEach { (title, s, n) ->
                Column(Modifier.weight(1f)) {
                    @Suppress("UNCHECKED_CAST")
                    ResultList(title, s as Section<Any>, n, returnTo, back, firstResult.takeIf { firstSection == n }, textColor) { i, item, m ->
                        val name = if (item is ChannelEntity) item.displayName(strip2) else (item as VodItem).name
                        ListBar(name, m) { vm.returnTo = n to i; if (item is ChannelEntity) onChannel(item) else onOpen(item as VodItem) }
                    }
                }
            }
        } else Column(resultsGroup.verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ResultRow("Channels", r.channels, 0, returnTo, back, firstResult.takeIf { firstSection == 0 }) { i, ch, m ->
                ChannelTile(ch, ch.displayName(strip), logos, m) { vm.returnTo = 0 to i; onChannel(ch) }
            }
            ResultRow("Movies", r.movies, 1, returnTo, back, firstResult.takeIf { firstSection == 1 }) { i, item, m -> Poster(item, m, caption = true, onFocused = {}) { vm.returnTo = 1 to i; onOpen(item) } }
            ResultRow("Series", r.series, 2, returnTo, back, firstResult.takeIf { firstSection == 2 }) { i, item, m -> Poster(item, m, caption = true, onFocused = {}) { vm.returnTo = 2 to i; onOpen(item) } }
        }
    }
}

/** "Channels  12" or "Channels  50 of 312" over a row of tiles; [returnTo] in this section gets [back]. */
@Composable
private fun <T> ResultRow(
    title: String, s: Section<T>, section: Int, returnTo: Pair<Int, Int>?, back: FocusRequester, first: FocusRequester?,
    tile: @Composable (Int, T, Modifier) -> Unit,
) {
    val count = if (s.total > s.items.size) "${s.items.size} of ${s.total}" else "${s.total}"
    Text("$title  $count", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    if (s.items.isEmpty()) { Text("No ${title.lowercase()} found", fontSize = 13.sp, color = LocalTheme.current.colors.muted); return }
    val start = returnTo?.takeIf { it.first == section }?.second?.coerceIn(0, s.items.size - 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start ?: 0)
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(s.items) { i, item ->
            val m = if (i == start) Modifier.focusRequester(back) else Modifier
            tile(i, item, if (i == 0 && first != null) m.focusRequester(first) else m)
        }
    }
}

@Composable
private fun ChannelTile(ch: ChannelEntity, name: String, logos: List<org.imdtv.iptvtv.data.settings.LogoKind>, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Column(
            Modifier.width(168.dp).clip(RoundedCornerShape(10.dp)).background(if (f) Color.White else Color(0x1FFFFFFF))
                .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp)).padding(8.dp),
        ) {
            Box(Modifier.fillMaxWidth().size(width = 152.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)).background(LocalTheme.current.colors.vodCard).padding(6.dp)) {
                ChannelLogo(ch, logos, Modifier.fillMaxSize())
            }
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (f) Color.Black else Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text((ch.customNumber ?: ch.number)?.toString() ?: " ", fontSize = 11.sp, color = if (f) Color(0xFF555555) else LocalTheme.current.colors.muted)
        }
    }
}

/** Retro/Xcube: a section as a vertical list of bars under its count. */
@Composable
private fun <T> ResultList(
    title: String, s: Section<T>, section: Int, returnTo: Pair<Int, Int>?, back: FocusRequester, first: FocusRequester?,
    textColor: Color, bar: @Composable (Int, T, Modifier) -> Unit,
) {
    val count = if (s.total > s.items.size) "${s.items.size} of ${s.total}" else "${s.total}"
    Text("${title.uppercase()}  $count", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor, modifier = Modifier.padding(bottom = 6.dp))
    if (s.items.isEmpty()) { Text("None found", fontSize = 13.sp, color = textColor); return }
    val start = returnTo?.takeIf { it.first == section }?.second?.coerceIn(0, s.items.size - 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start ?: 0)
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        itemsIndexed(s.items) { i, item ->
            val m = if (i == start) Modifier.focusRequester(back) else Modifier
            bar(i, item, if (i == 0 && first != null) m.focusRequester(first) else m)
        }
    }
}

@Composable
private fun ListBar(title: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalTheme.current.colors
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (f) colors.onFocus else Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(if (f) colors.focus else colors.cell, RoundedCornerShape(3.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
