package tv.luna.player.ui.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.luna.player.data.addons.CFolder
import tv.luna.player.data.addons.CRow
import tv.luna.player.data.addons.Meta
import tv.luna.player.data.addons.MetaPreview
import tv.luna.player.data.addons.Progress
import tv.luna.player.data.addons.Stream
import tv.luna.player.ui.FocusBox
import tv.luna.player.ui.Montserrat
import tv.luna.player.ui.theme.LocalTheme
import tv.luna.player.ui.theme.guideBackground
import tv.luna.player.ui.vod.Backdrop

private val Accent = Color(0xFF5FD6DC)
private val Glow = Color(0xFFEFFBFC)
private val Card = Color(0x8C06323A)
private val Line = Color(0x335FD6DC)

private fun typeLabel(t: String) = if (t == "movie") "Films" else if (t == "series") "Series" else t.replaceFirstChar { it.uppercase() }

private fun minutes(ms: Long) = (ms / 60_000).coerceAtLeast(1)

// ---------------------------------------------------------------- Live TV / On Demand switch

/**
 * The top-left panel of the main screens: the sliding Live TV / On Demand switch (OK flips it) and, joined on in the same
 * glass panel, [extra]: the Live TV sections. On Demand passes none, so the panel folds down to just the switch.
 */
@Composable
fun ModeSwitch(onDemand: Boolean, modifier: Modifier = Modifier, switchModifier: Modifier = Modifier,
               extra: (@Composable () -> Unit)? = null, onSwitch: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(17.dp)).background(Color(0xA606323A)).border(1.dp, Line, RoundedCornerShape(17.dp))
            .padding(3.dp).animateContentSize(androidx.compose.animation.core.tween(260)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusBox(onClick = onSwitch, modifier = switchModifier) { f ->
            val x by androidx.compose.animation.core.animateDpAsState(if (onDemand) 74.dp else 0.dp, label = "knobX")
            val w by androidx.compose.animation.core.animateDpAsState(if (onDemand) 96.dp else 74.dp, label = "knobW")
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (f) Color(0x33EFFBFC) else Color.Transparent).size(170.dp, 26.dp)) {
                Box(Modifier.offset(x = x).size(w, 26.dp)
                    .then(if (f) Modifier.shadow(10.dp, RoundedCornerShape(13.dp), ambientColor = Glow, spotColor = Glow) else Modifier)
                    .clip(RoundedCornerShape(13.dp)).background(if (f) Glow else Color(0xE6EFFBFC)))
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live TV", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (!onDemand) Color(0xFF06323A) else Color(0xCCEFFBFC),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(74.dp))
                    Text("On Demand", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (onDemand) Color(0xFF06323A) else Color(0xCCEFFBFC),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(96.dp))
                }
            }
        }
        if (extra != null) {
            // A soft teal divider, then the sections, all in the one panel.
            Box(Modifier.padding(horizontal = 7.dp).size(1.dp, 16.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x995FD6DC), Color.Transparent))))
            extra()
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
fun OnDemandHome(
    vm: OnDemandViewModel, headerModifier: Modifier, switchFocus: FocusRequester,
    onSwitch: () -> Unit, onSettings: () -> Unit,
    onOpen: (MetaPreview) -> Unit, onFolder: (CFolder) -> Unit, onResume: (Progress) -> Unit, onAddons: () -> Unit,
) {
    val installed by vm.installed.collectAsState()
    val collections by vm.collections.collectAsState()
    val progress by vm.progress.collectAsState()
    val focused by vm.focused.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val myList by vm.myList.collectAsState()
    val shownRows = remember(collections, installed) {
        collections.flatMap { it.second }.map { r -> r.copy(folders = r.folders.filter { f -> vm.folderRows(f).isNotEmpty() }) }.filter { it.folders.isNotEmpty() }
    }
    val first = remember { FocusRequester() }
    var claimed by remember { mutableStateOf(false) }
    val claim: @Composable () -> Unit = { ClaimFirst(vm, "home", first, { claimed }) { claimed = true } }

    Box(Modifier.fillMaxSize().guideBackground()) {
        Backdrop(focused?.let { it.background ?: it.poster })
        // Top bar: the switch, search and Settings; the Luna wordmark on the right.
        Row(headerModifier.padding(start = 50.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeSwitch(true, switchModifier = Modifier.focusRequester(switchFocus), onSwitch = onSwitch)
            tv.luna.player.ui.search.SearchBar(query, { vm.search(it) }, "Search films and series…", Modifier.width(280.dp))
            FocusBox(onClick = onSettings) { f ->
                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (f) Glow else Card)
                    .border(1.dp, if (f) Color.Transparent else Line, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Icon(androidx.compose.ui.res.painterResource(tv.luna.player.R.drawable.ic_tab_settings), "Settings",
                        tint = if (f) Color(0xFF06323A) else Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        val list = installed
        if (list != null && (list.none { it.manifest.catalogs.isNotEmpty() } || list.none { it.manifest.resources.any { r -> r.name == "stream" } })) {
            Locked(collections.flatMap { it.second }, onAddons)
            return@Box
        }
        focused?.let { Hero(it, Modifier.padding(start = 50.dp, top = 74.dp).width(480.dp)) }

        Box(Modifier.padding(start = 50.dp, top = 228.dp).fillMaxSize()) {
            val found = results
            LazyColumn(Modifier.fillMaxSize(), state = vm.listState(if (found != null) "search" else "home"), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (found != null) {
                    if (found.isEmpty()) item { Text("No matches", fontSize = 15.sp, color = LocalTheme.current.colors.muted) }
                    itemsIndexed(found, key = { _, r -> "s" + r.first }) { i, (label, items) ->
                        PosterRow("home", label, items, if (i == 0) first else null, claim, vm, onOpen)
                    }
                } else {
                    if (progress.isNotEmpty()) item(key = "continue") {
                        ContinueRow("home", progress, first, claim, vm, onResume)
                    }
                    if (myList.isNotEmpty()) item(key = "mylist") {
                        PosterRow("home", "My list", myList.map { it.preview() }, if (progress.isEmpty()) first else null, claim, vm, onOpen)
                    }
                    val topTaken = progress.isNotEmpty() || myList.isNotEmpty()
                    // Only folders the Metadata link can fill: one it has no catalogs for would open empty.
                    val rows = shownRows
                    if (rows.isNotEmpty()) {
                        // A collection is the home layout: its rows of folders replace the add-ons' own rows.
                        itemsIndexed(rows, key = { i, r -> "c$i" + r.title }) { i, r ->
                            FolderRow("home", r, if (i == 0 && !topTaken) first else null, claim, vm, onFolder, branded = i == 0)
                        }
                    } else {
                        val catalogs = vm.homeRows()
                        itemsIndexed(catalogs, key = { _, r -> r.first.url + r.second.type + r.second.id }) { i, (addon, cat) ->
                            val items by produceState<List<MetaPreview>?>(null, addon.url, cat.id, cat.type) { value = vm.row(addon, cat) }
                            if (items?.isEmpty() != true) PosterRow("home", "${cat.name} · ${typeLabel(cat.type)}", items,
                                if (i == 0 && !topTaken) first else null, claim, vm, onOpen)
                        }
                    }
                }
            }
        }
    }
}

/** Before the two links are added: the home is shown blurred behind a lock. */
@Composable
private fun Locked(rows: List<CRow>, onAddons: () -> Unit) {
    val go = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { go.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        // A preview of what's waiting (The Kollection's folders), blurred. Blur needs Android 12; older boxes get the dim only.
        // Bigger than the screen on every side and blurred without a clip, so no edge of it is ever on screen.
        Column(Modifier.align(Alignment.Center).requiredSize(1160.dp, 760.dp).blur(22.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            val source = rows.ifEmpty { listOf(CRow("", emptyList())) }
            val preview = List(6) { source[it % source.size] }
            preview.forEach { r ->
                Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val tiles = r.folders.take(6)
                    if (tiles.isEmpty()) repeat(6) { Box(Modifier.size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(LocalTheme.current.colors.vodCard)) }
                    tiles.forEach { f ->
                        Box(Modifier.size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(LocalTheme.current.colors.vodCard)) {
                            f.cover?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xB306323A), Color(0x6606323A)))))
        Column(
            Modifier.align(Alignment.Center).padding(top = 30.dp).width(410.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xE606323A))
                .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x265FD6DC)), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(19.dp)) { // padlock
                    val w = size.width; val h = size.height
                    drawArc(Accent, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(w * .22f, h * .05f),
                        size = androidx.compose.ui.geometry.Size(w * .56f, h * .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * .11f))
                    drawLine(Accent, androidx.compose.ui.geometry.Offset(w * .22f, h * .35f), androidx.compose.ui.geometry.Offset(w * .22f, h * .45f), strokeWidth = w * .11f)
                    drawLine(Accent, androidx.compose.ui.geometry.Offset(w * .78f, h * .35f), androidx.compose.ui.geometry.Offset(w * .78f, h * .45f), strokeWidth = w * .11f)
                    drawRoundRect(Accent, topLeft = androidx.compose.ui.geometry.Offset(w * .1f, h * .45f), size = androidx.compose.ui.geometry.Size(w * .8f, h * .52f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .1f))
                }
            }
            Text("On Demand is locked", fontSize = 18.sp, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Add your Metadata link and Host link from your provider to unlock films and series.",
                fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xCCEFFBFC), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Pill("Add my links", primary = true, modifier = Modifier.focusRequester(go), onClick = onAddons)
            Text("No links yet? Message @novadrop:matrix.org on the Element X app.", fontSize = 10.sp, color = LocalTheme.current.colors.muted, maxLines = 1, softWrap = false,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Hero(p: MetaPreview, modifier: Modifier) {
    Column(modifier) {
        if (p.type == "folder") Text("COLLECTION", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, color = Accent)
        Text(p.name, fontSize = 30.sp, fontFamily = Montserrat, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp))
        val line = if (p.type == "folder") p.releaseInfo.orEmpty()
            else listOfNotNull(p.releaseInfo, if (p.type == "movie") "Film" else "Series", p.rating?.let { "★ $it" }).joinToString("  ·  ")
        if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = Color(0xCCEFFBFC), modifier = Modifier.padding(top = 6.dp))
        p.description?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xBFEFFBFC), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun FolderRow(page: String, row: CRow, focus: FocusRequester?, claim: @Composable () -> Unit, vm: OnDemandViewModel, onFolder: (CFolder) -> Unit,
                      /** The top row (Discover): Luna's own cards instead of the collection's artwork. */ branded: Boolean = false) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(state = vm.listState(page + "|" + row.title), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 8.dp, end = 50.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.offset(x = (-8).dp)) {
            itemsIndexed(row.folders, key = { i, f -> i.toString() + f.id }) { i, f ->
                if (i == 0 && focus != null) claim()
                FolderTile(f, (if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier).restoreFocus(vm, page, "folder:" + f.id), branded = branded, onFocused = {
                    vm.focused.value = MetaPreview("folder:" + f.id, "folder", f.title, f.cover, f.backdrop ?: f.cover, null,
                        "${f.sources.size} catalog" + if (f.sources.size == 1) "" else "s", null)
                }) { vm.restore[page] = "folder:" + f.id; onFolder(f) }
            }
        }
    }
}

@Composable
private fun FolderTile(f: CFolder, modifier: Modifier, branded: Boolean = false, onFocused: () -> Unit, onClick: () -> Unit) {
    FocusBox(onClick = onClick, onFocused = onFocused, modifier = modifier) { focused ->
        val (w, h) = if (f.landscape) 200.dp to 112.dp else 110.dp to 165.dp
        Box(
            Modifier.graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }
                .border(2.dp, if (focused) Glow else Color.Transparent, RoundedCornerShape(10.dp)).padding(3.dp)
                .size(w, h).clip(RoundedCornerShape(8.dp)).background(LocalTheme.current.colors.vodCard),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (branded) { BrandedTile(f.title, w, h); return@Box }
            CardFallback(if (f.cover == null || !f.hideTitle) null else f.title) // the title underneath shows only if the cover fails
            if (f.cover != null) AsyncImage(f.cover, f.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (f.cover == null || !f.hideTitle) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, 1f to Color(0xCC06323A))))
                Text(f.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Collection folder

@Composable
fun OnDemandFolder(vm: OnDemandViewModel, folder: CFolder, onOpen: (MetaPreview) -> Unit) {
    val installed by vm.installed.collectAsState()
    val sources = remember(folder, installed) { vm.folderRows(folder) }
    val first = remember { FocusRequester() }
    var claimed by remember { mutableStateOf(false) }
    val page = "f:" + folder.id
    val claim: @Composable () -> Unit = { ClaimFirst(vm, page, first, { claimed }) { claimed = true } }
    LaunchedEffect(folder.id) { if (vm.restore[page] == null) vm.focused.value = null }
    val focused by vm.focused.collectAsState()

    Box(Modifier.fillMaxSize().guideBackground()) {
        Backdrop(focused?.let { it.background ?: it.poster } ?: folder.backdrop ?: folder.cover)
        Column(Modifier.padding(start = 50.dp, top = 34.dp).width(480.dp)) {
            if (folder.logo != null) AsyncImage(folder.logo, folder.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.height(64.dp).width(320.dp))
            else Text(folder.title, fontSize = 30.sp, fontFamily = Montserrat, fontWeight = FontWeight.Bold, color = Color.White)
            focused?.let { p ->
                Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                p.description?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xBFEFFBFC), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        Box(Modifier.padding(start = 50.dp, top = 200.dp).fillMaxSize()) {
            if (sources.isEmpty()) Text("Nothing to show here yet. Your Metadata link may not include this folder.",
                fontSize = 14.sp, lineHeight = 21.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.width(560.dp))
            else LazyColumn(Modifier.fillMaxSize(), state = vm.listState(page), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(sources, key = { i, s -> i.toString() + s.first.second.id }) { i, (src, genre) ->
                    val (addon, cat) = src
                    val items by produceState<List<MetaPreview>?>(null, addon.url, cat.id, genre) { value = vm.row(addon, cat, genre) }
                    if (items?.isEmpty() != true) PosterRow(page, cat.name + (genre?.let { " · $it" } ?: ""), items, if (i == 0) first else null, claim, vm, onOpen)
                }
            }
        }
    }
}

private const val PLACEHOLDERS = 6

/** The card behind every picture: Luna teal with its two faint moons and, if given, the title. Shown while loading and
 *  whenever a picture is missing or fails. */
@Composable
private fun CardFallback(title: String?) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C9AA0), Color(0xFF0D6F7A), Color(0xFF06323A)))),
        contentAlignment = Alignment.BottomStart) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension
            drawCircle(Color(0x14EFFBFC), radius = r * .75f, center = androidx.compose.ui.geometry.Offset(size.width * .85f, size.height * .1f))
            drawCircle(Color(0x10EFFBFC), radius = r * .45f, center = androidx.compose.ui.geometry.Offset(size.width * .7f, size.height * .28f))
        }
        if (title != null) Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
    }
}

/** Back on a page: the item that was opened takes focus again (its row and the page kept their scroll in the view model). */
@Composable
private fun Modifier.restoreFocus(vm: OnDemandViewModel, page: String, key: String): Modifier {
    if (vm.restore[page] != key) return this
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { req.requestFocus() }; vm.restore.remove(page) }
    return this.focusRequester(req)
}

/** First visit: focus the page's first item. Coming back: leave it to [restoreFocus], unless that item has gone. */
@Composable
private fun ClaimFirst(vm: OnDemandViewModel, page: String, first: FocusRequester, claimed: () -> Boolean, onClaimed: () -> Unit) {
    LaunchedEffect(Unit) {
        if (claimed()) return@LaunchedEffect
        if (vm.restore[page] != null) { delay(900); if (vm.restore[page] == null) { onClaimed(); return@LaunchedEffect }; vm.restore.remove(page) }
        withFrameNanos { }; runCatching { first.requestFocus(); onClaimed() }
    }
}

@Composable
private fun PosterRow(page: String, title: String, items: List<MetaPreview>?, focus: FocusRequester?, claim: @Composable () -> Unit, vm: OnDemandViewModel, onOpen: (MetaPreview) -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(state = vm.listState(page + "|" + title), horizontalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(start = 8.dp, end = 50.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.offset(x = (-8).dp)) {
            itemsIndexed(items ?: List(PLACEHOLDERS) { null }, key = { i, _ -> i }) { i, item ->
                if (i == 0 && focus != null) claim()
                OdPoster(item, Modifier.then(if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .then(if (item != null) Modifier.restoreFocus(vm, page, title + "|" + item.id) else Modifier),
                    onFocused = { item?.let { vm.focused.value = it } }, onClick = { item?.let { vm.restore[page] = title + "|" + it.id; onOpen(it) } })
            }
        }
    }
}

@Composable
fun OdPoster(item: MetaPreview?, modifier: Modifier = Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    FocusBox(onClick = onClick, onFocused = onFocused, modifier = modifier) { f ->
        if (f && item != null) LaunchedEffect(item.id) { onFocused() }
        Box(
            Modifier.graphicsLayer { val s = if (f) 1.06f else 1f; scaleX = s; scaleY = s }
                .border(2.dp, if (f) Glow else Color.Transparent, RoundedCornerShape(9.dp)).padding(3.dp)
                .size(110.dp, 165.dp).clip(RoundedCornerShape(7.dp)).background(LocalTheme.current.colors.vodCard),
            contentAlignment = Alignment.BottomStart,
        ) {
            // Under the picture: a Luna card with the title, so a missing or slow poster still looks finished.
            CardFallback(item?.name)
            if (item?.poster != null) AsyncImage(item.poster, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ContinueRow(page: String, items: List<Progress>, focus: FocusRequester, claim: @Composable () -> Unit, vm: OnDemandViewModel, onResume: (Progress) -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text("Continue watching", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 8.dp, end = 50.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.offset(x = (-8).dp)) {
            itemsIndexed(items, key = { _, p -> p.metaId }) { i, p ->
                if (i == 0) claim()
                FocusBox(onClick = { vm.restore[page] = "cw:" + p.metaId; onResume(p) }, onFocused = {
                    vm.focused.value = MetaPreview(p.metaId, p.type, p.name, p.poster, p.background, p.subtitle, null, null)
                }, modifier = (if (i == 0) Modifier.focusRequester(focus) else Modifier).restoreFocus(vm, page, "cw:" + p.metaId)) { f ->
                    Column(Modifier.width(206.dp)) {
                        Box(
                            Modifier.graphicsLayer { val s = if (f) 1.05f else 1f; scaleX = s; scaleY = s }
                                .border(2.dp, if (f) Glow else Color.Transparent, RoundedCornerShape(10.dp)).padding(3.dp)
                                .size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(LocalTheme.current.colors.vodCard),
                        ) {
                            CardFallback(null)
                            (p.background ?: p.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000)))
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction).height(4.dp).background(Accent))
                        }
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp, top = 6.dp))
                        val left = if (p.durationMs > 0) "${minutes(p.durationMs - p.positionMs)} min left" else null
                        Text(listOfNotNull(p.subtitle, left).joinToString(" · "), fontSize = 11.sp, color = LocalTheme.current.colors.muted, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Details

@Composable
fun OnDemandDetail(vm: OnDemandViewModel, preview: MetaPreview, onPlay: (SourceRequest) -> Unit) {
    LaunchedEffect(preview.id) { vm.loadMeta(preview) }
    val metas by vm.metas.collectAsState()
    val progress by vm.progress.collectAsState()
    val loaded = preview.id in metas
    val meta: Meta? = metas[preview.id]
    val saved = progress.firstOrNull { it.metaId == preview.id }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(loaded) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().guideBackground()) {
        Backdrop(meta?.background ?: preview.background ?: preview.poster)
        Row(Modifier.padding(start = 50.dp, top = 40.dp, end = 50.dp)) {
            Box(Modifier.size(150.dp, 225.dp).clip(RoundedCornerShape(10.dp)).background(LocalTheme.current.colors.vodCard)) {
                (meta?.poster ?: preview.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.padding(start = 28.dp).width(640.dp)) {
                Text(if (preview.type == "series") "SERIES" else "FILM", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, color = Accent)
                Text(meta?.name ?: preview.name, fontSize = 30.sp, fontFamily = Montserrat, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                val bits = listOfNotNull(meta?.releaseInfo ?: preview.releaseInfo, meta?.runtime,
                    meta?.seasons?.size?.takeIf { it > 0 && preview.type == "series" }?.let { "$it season" + if (it > 1) "s" else "" },
                    meta?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotEmpty() }, (meta?.rating ?: preview.rating)?.let { "★ $it" })
                Text(bits.joinToString("  ·  "), fontSize = 12.sp, color = Color(0xCCEFFBFC), modifier = Modifier.padding(top = 6.dp))
                (meta?.description ?: preview.description)?.let {
                    Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xC7EFFBFC), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                }
                meta?.cast?.takeIf { it.isNotEmpty() }?.let {
                    Text("Starring " + it.take(4).joinToString(", "), fontSize = 11.sp, color = LocalTheme.current.colors.muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val m = meta
                    fun request(videoId: String, subtitle: String?, resume: Long) = SourceRequest(
                        preview.type, preview.id, videoId, m?.imdbId, m?.name ?: preview.name, subtitle,
                        m?.poster ?: preview.poster, m?.background ?: preview.background, resume)
                    if (preview.type == "series") {
                        val eps = m?.videos.orEmpty()
                        val resumeEp = saved?.let { s -> eps.firstOrNull { it.id == s.videoId } }
                        val firstEp = eps.firstOrNull { (it.season ?: 0) > 0 } ?: eps.firstOrNull()
                        when {
                            resumeEp != null -> Pill("▶  Resume ${epLabel(resumeEp.season, resumeEp.episode)}", true, Modifier.focusRequester(playFocus)) {
                                onPlay(request(resumeEp.id, epLabel(resumeEp.season, resumeEp.episode), saved?.positionMs ?: 0))
                            }
                            firstEp != null -> Pill("▶  Play ${epLabel(firstEp.season, firstEp.episode)}", true, Modifier.focusRequester(playFocus)) {
                                onPlay(request(firstEp.id, epLabel(firstEp.season, firstEp.episode), 0))
                            }
                            !loaded -> Pill("Loading…", true, Modifier.focusRequester(playFocus)) {}
                            else -> Pill("No episodes found", true, Modifier.focusRequester(playFocus)) {}
                        }
                    } else {
                        if (saved != null) {
                            Pill("▶  Resume · ${minutes(saved.positionMs)} min", true, Modifier.focusRequester(playFocus)) { onPlay(request(preview.id, null, saved.positionMs)) }
                            Pill("From start") { onPlay(request(preview.id, null, 0)) }
                        } else Pill("▶  Play", true, Modifier.focusRequester(playFocus)) { onPlay(request(preview.id, null, 0)) }
                    }
                    val myList by vm.myList.collectAsState()
                    val inList = myList.any { it.id == preview.id }
                    Pill(if (inList) "✓  In My list" else "+  My list") { vm.toggleMyList(m?.preview() ?: preview) }
                    if (saved != null) Pill("✕  Remove") { vm.forget(preview.id) }
                }
            }
        }
        if (preview.type == "series" && meta != null && meta.videos.isNotEmpty()) {
            Episodes(meta, saved, Modifier.align(Alignment.BottomStart).padding(start = 50.dp, bottom = 26.dp)) { v ->
                onPlay(SourceRequest(preview.type, preview.id, v.id, meta.imdbId, meta.name, epLabel(v.season, v.episode),
                    meta.poster ?: preview.poster, meta.background ?: preview.background,
                    saved?.takeIf { it.videoId == v.id }?.positionMs ?: 0))
            }
        }
        if (loaded && meta == null && preview.type == "series") {
            Text("Couldn't load the episodes. Check your Metadata link in Settings › On Demand.", fontSize = 13.sp,
                color = LocalTheme.current.colors.muted, modifier = Modifier.align(Alignment.BottomStart).padding(start = 50.dp, bottom = 40.dp))
        }
    }
}

private fun epLabel(season: Int?, episode: Int?) = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" · ").ifEmpty { "episode" }

@Composable
private fun Episodes(meta: Meta, saved: Progress?, modifier: Modifier, onPick: (tv.luna.player.data.addons.Video) -> Unit) {
    val seasons = meta.seasons
    val startSeason = saved?.let { s -> meta.videos.firstOrNull { it.id == s.videoId }?.season } ?: seasons.firstOrNull()
    var season by remember(meta.id) { mutableStateOf(startSeason) }
    Column(modifier) {
        if (seasons.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 4.dp, end = 50.dp, top = 4.dp, bottom = 4.dp)) {
            items(seasons) { s ->
                FocusBox(onClick = { season = s }, onFocused = { season = s }) { f ->
                    val on = s == season
                    Text(if (s == 0) "Specials" else "Season $s", fontSize = 13.sp, fontWeight = if (on || f) FontWeight.SemiBold else FontWeight.Medium,
                        color = when { f -> Color.Black; on -> Color(0xFF06323A); else -> Color.White },
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(when { f -> Glow; on -> Accent; else -> Card })
                            .border(1.dp, if (on || f) Color.Transparent else Line, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 7.dp))
                }
            }
        }
        val eps = meta.videos.filter { seasons.isEmpty() || it.season == season }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 4.dp, end = 50.dp, top = 10.dp, bottom = 4.dp)) {
            items(eps, key = { it.id }) { v ->
                FocusBox(onClick = { onPick(v) }) { f ->
                    Column(Modifier.width(196.dp)) {
                        Box(
                            Modifier.graphicsLayer { val s = if (f) 1.05f else 1f; scaleX = s; scaleY = s }
                                .border(2.dp, if (f) Glow else Color.Transparent, RoundedCornerShape(10.dp)).padding(3.dp)
                                .size(190.dp, 106.dp).clip(RoundedCornerShape(8.dp)).background(LocalTheme.current.colors.vodCard),
                        ) {
                            CardFallback(null)
                            (v.thumbnail ?: meta.background)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Text("E${v.episode ?: "?"}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xB306323A)).padding(horizontal = 6.dp, vertical = 2.dp))
                            saved?.takeIf { it.videoId == v.id }?.let { sv -> Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(sv.fraction).height(4.dp).background(Accent)) }
                        }
                        Text(v.title.ifEmpty { "Episode ${v.episode ?: ""}" }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp, top = 6.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Sources

@Composable
fun OnDemandSources(vm: OnDemandViewModel, req: SourceRequest, autoPlay: Boolean, maxQuality: String = "Best", onPlay: (Stream) -> Unit) {
    LaunchedEffect(req) { vm.loadSources(req) }
    val state by vm.sources.collectAsState()
    val key = req.type + "|" + req.videoId
    val s = state?.takeIf { it.key == key }
    val loading = s == null || s.loading
    // Highest quality first (within a quality, the add-on's own order); anything above the chosen limit goes last.
    val streams = remember(s?.streams, maxQuality) {
        val rank = mapOf("4K" to 0, "1080p" to 1, "720p" to 2, "SD" to 3)
        val cap = rank[maxQuality] ?: 0
        s?.streams.orEmpty().sortedWith(compareBy({ (rank[it.quality] ?: 4) < cap }, { rank[it.quality] ?: 4 }))
    }
    val qualities = remember(streams) { listOf("4K", "1080p", "720p", "SD").filter { q -> streams.any { it.quality == q } } }
    var filter by remember(key) { mutableStateOf<String?>(null) }
    val shown = streams.filter { filter == null || it.quality == filter }
    val firstRow = remember { FocusRequester() }
    var autoDone by remember(key) { mutableStateOf(false) }
    LaunchedEffect(loading, streams.size) {
        if (!loading && streams.isNotEmpty()) {
            if ((autoPlay || req.autoPlay) && !autoDone) { autoDone = true; onPlay(streams.first()); return@LaunchedEffect }
            withFrameNanos { }; runCatching { firstRow.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().guideBackground()) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x9906323A), 1f to Color(0xE606323A))))
        Column(Modifier.padding(start = 50.dp, top = 40.dp).width(250.dp)) {
            Box(Modifier.size(250.dp, 140.dp).clip(RoundedCornerShape(12.dp)).background(LocalTheme.current.colors.vodCard)) {
                (req.background ?: req.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            req.subtitle?.let { Text(it.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, color = Accent, modifier = Modifier.padding(top = 14.dp)) }
            Text(req.name, fontSize = 22.sp, fontFamily = Montserrat, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (req.resumeMs > 0) Text("Starts ${minutes(req.resumeMs)} min in", fontSize = 12.sp, color = Color(0xCCEFFBFC), modifier = Modifier.padding(top = 6.dp))
            Text("Tip: turn on Auto-play best source in Settings › On Demand to skip this list.", fontSize = 11.sp, lineHeight = 17.sp,
                color = LocalTheme.current.colors.muted, modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp))
        }
        Column(Modifier.padding(start = 330.dp, top = 40.dp, end = 50.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Pick a source", fontSize = 20.sp, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (loading) "Looking…" else "${streams.size} found", fontSize = 12.sp, color = LocalTheme.current.colors.muted)
            }
            if (qualities.size > 1) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<String?>(null) + qualities).forEach { q ->
                    FocusBox(onClick = { filter = q }) { f ->
                        val on = q == filter
                        Text(q ?: "All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = when { f -> Color.Black; on -> Color(0xFF06323A); else -> Color.White },
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(when { f -> Glow; on -> Accent; else -> Card })
                                .padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
            when {
                loading -> Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    tv.luna.player.ui.Spinner(size = 22.dp, color = Color.White)
                    Text("Finding sources…", fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(start = 12.dp))
                }
                streams.isEmpty() -> Text("No sources found for this one. Check your Host link is added in Settings › On Demand.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(top = 30.dp).width(520.dp))
                else -> LazyColumn(Modifier.padding(top = 14.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 30.dp)) {
                    itemsIndexed(shown, key = { i, st -> i.toString() + st.url.hashCode() }) { i, st ->
                        SourceRow(st, if (i == 0) Modifier.focusRequester(firstRow) else Modifier) { onPlay(st) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(s: Stream, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (f) Glow else Card)
                .border(1.dp, if (f) Color.Transparent else Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.quality, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (f) Accent else Accent,
                modifier = Modifier.width(58.dp).clip(RoundedCornerShape(8.dp)).background(if (f) Color(0xFF06323A) else Color(0x265FD6DC)).padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                val head = s.name.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ·  ")
                val detail = s.title.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ·  ")
                Text(head, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (f) Color(0xFF06323A) else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, fontSize = 11.sp, lineHeight = 15.sp, color = if (f) Color(0xFF2A5A62) else Color(0xB3EFFBFC), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Shared

@Composable
fun Pill(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        // One line always: a crowded row must never squash a button into a column of letters.
        Text(label, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = if (f) Color(0xFF06323A) else Color.White, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (f) Glow else if (primary) Color(0x40EFFBFC) else Card)
                .border(1.dp, if (f) Color.Transparent else Color(0x66EFFBFC), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 9.dp))
    }
}
