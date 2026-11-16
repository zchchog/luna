package tv.luna.player.ui.settings

import tv.luna.player.ui.theme.LocalTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.luna.player.data.model.Kind
import tv.luna.player.data.settings.LogoKind
import tv.luna.player.data.catalogue.parseStripList
import tv.luna.player.ui.FocusBox
import tv.luna.player.ui.onLongOk
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import tv.luna.player.data.db.GroupPlacement
import tv.luna.player.data.db.CustomGroupEntity
import tv.luna.player.ui.TextPrompt
import tv.luna.player.ui.ContextMenu

/** Pages of Channels & categories; a page with a parent opens from (and Back returns to) that page. */
enum class ChannelPage(val title: String, val explanation: String, val parent: ChannelPage? = null) {
    CHANNELS("Channels", "Logos, name clean-up and header channels"),
    CHANNEL_LOGOS("Channel logos", "Where logos come from, in order; your logo pack", parent = CHANNELS),
    CATEGORIES("Categories", "Show, hide and reorder categories; languages; your own categories"),
    LANGUAGES("Languages & countries", "Show or hide every category of a language or country", parent = CATEGORIES),
    YOUR_CATEGORIES("Your categories", "Create, rename and delete your own categories", parent = CATEGORIES),
    CATEGORY_NAME_CLEANUP("Category name clean-up", "Remove or rename prefixes and suffixes in category names", parent = CATEGORIES),
    PROVIDER_CATEGORIES("Manage provider categories", "Show, hide and reorder the playlist's categories", parent = CATEGORIES),
    LIVE_CATEGORIES("Live", "Live TV categories", parent = PROVIDER_CATEGORIES),
    MOVIE_CATEGORIES("Movies", "Movie categories", parent = PROVIDER_CATEGORIES),
    SERIES_CATEGORIES("Series", "Series categories", parent = PROVIDER_CATEGORIES),
    CHANNEL_NAME_CLEANUP("Channel name clean-up", "Remove or rename prefixes and suffixes such as “UK|”", parent = CHANNELS),
}

private fun LogoKind.short() = when (this) { LogoKind.PLAYLIST -> "Playlist"; LogoKind.OPEN_SOURCE -> "Open-source"; LogoKind.PACK -> "My pack" }

/** The Channels & categories group: Channels › and Categories ›. */
@Composable
fun ChannelGroupRows(vm: SettingsViewModel, first: Modifier, openers: Map<ChannelPage, FocusRequester>, onOpen: (ChannelPage) -> Unit) {
    SettingRow(ChannelPage.CHANNELS.title, ChannelPage.CHANNELS.explanation, "›", first.focusRequester(openers.getValue(ChannelPage.CHANNELS))) { onOpen(ChannelPage.CHANNELS) }
    SettingRow(ChannelPage.CATEGORIES.title, ChannelPage.CATEGORIES.explanation, "›", Modifier.focusRequester(openers.getValue(ChannelPage.CATEGORIES))) { onOpen(ChannelPage.CATEGORIES) }
}

/** Channels: Channel logos ›, Channel name clean-up ›, Hide header channels. */
@Composable
private fun ChannelsPageRows(vm: SettingsViewModel, first: FocusRequester, openers: Map<ChannelPage, FocusRequester>, onOpen: (ChannelPage) -> Unit) {
    val strip by vm.nameStrip.collectAsState()
    val s by vm.settings.collectAsState()
    val matching by vm.logoMatching.collectAsState()
    val logos = ChannelPage.CHANNEL_LOGOS
    SettingRow(logos.title, logos.explanation, s.logoOrder.joinToString(" → ") { it.short() } + "  ›",
        Modifier.focusRequester(first).focusRequester(openers.getValue(logos)), matching) { onOpen(logos) }
    val names = ChannelPage.CHANNEL_NAME_CLEANUP
    SettingRow(names.title, names.explanation, strip.ifBlank { "None" }, Modifier.focusRequester(openers.getValue(names))) { onOpen(names) }
    SwitchRow("Hide header channels", "Hide divider rows such as “#### SKY CINEMA ####”", s.hideHeaderChannels) { on ->
        vm.update { it.copy(hideHeaderChannels = on) }
    }
}

/** A sub-page in the right pane. Back closes it (the categories page first cancels a move). */
@Composable
fun ChannelPageView(
    page: ChannelPage, vm: SettingsViewModel, modifier: Modifier, first: FocusRequester, openers: Map<ChannelPage, FocusRequester>,
    restoring: Boolean, onOpen: (ChannelPage) -> Unit, onClose: () -> Unit,
) {
    // Coming back from a sub-page, the screen puts focus on the row that opened it instead.
    LaunchedEffect(page) { if (!restoring) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(page.title, fontSize = 20.sp, fontFamily = tv.luna.player.ui.Montserrat, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        when (page) {
            ChannelPage.CHANNEL_LOGOS -> { BackHandler(onBack = onClose); ChannelLogosPage(vm, first) }
            ChannelPage.CATEGORIES -> { BackHandler(onBack = onClose); PageRows(listOf(ChannelPage.LANGUAGES, ChannelPage.YOUR_CATEGORIES, ChannelPage.PROVIDER_CATEGORIES, ChannelPage.CATEGORY_NAME_CLEANUP), vm, first, openers, onOpen) }
            ChannelPage.PROVIDER_CATEGORIES -> { BackHandler(onBack = onClose); PageRows(listOf(ChannelPage.LIVE_CATEGORIES, ChannelPage.MOVIE_CATEGORIES, ChannelPage.SERIES_CATEGORIES), vm, first, openers, onOpen) }
            ChannelPage.LIVE_CATEGORIES -> CategoryListPage(vm, Kind.LIVE, first, onClose)
            ChannelPage.MOVIE_CATEGORIES -> CategoryListPage(vm, Kind.MOVIE, first, onClose)
            ChannelPage.SERIES_CATEGORIES -> CategoryListPage(vm, Kind.SERIES, first, onClose)
            ChannelPage.LANGUAGES -> { BackHandler(onBack = onClose); LanguagesPage(vm, first) }
            ChannelPage.CHANNEL_NAME_CLEANUP -> { BackHandler(onBack = onClose); NameCleanupPage(vm, first, category = false) }
            ChannelPage.CATEGORY_NAME_CLEANUP -> { BackHandler(onBack = onClose); NameCleanupPage(vm, first, category = true) }
            ChannelPage.CHANNELS -> { BackHandler(onBack = onClose); ChannelsPageRows(vm, first, openers, onOpen) }
            ChannelPage.YOUR_CATEGORIES -> { BackHandler(onBack = onClose); YourCategoriesPage(vm, first) }
        }
    }
}

/** Logo priorities; the logo pack section shows only when a priority is "My logo pack" (user, 2026-11-12). */
@Composable
private fun ChannelLogosPage(vm: SettingsViewModel, first: FocusRequester) {
    val s by vm.settings.collectAsState()
    val matching by vm.logoMatching.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PriorityRows("logos", s.logoOrder, LogoKind.entries, 3, { it.label }, Modifier.focusRequester(first),
            busy = { it == LogoKind.OPEN_SOURCE && matching }) { vm.setLogoOrder(it) }
        if (matching) Text("Matching open-source logos to your channels…", fontSize = 13.sp, color = LocalTheme.current.colors.muted)
        if (LogoKind.PACK in s.logoOrder) {
            Text("My logo pack", fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            LogoPackSection(vm)
        }
    }
}

private val KIND_TABS = listOf(Kind.LIVE to "Live", Kind.MOVIE to "Movies", Kind.SERIES to "Series")

@Composable
private fun PageRows(pages: List<ChannelPage>, vm: SettingsViewModel, first: FocusRequester, openers: Map<ChannelPage, FocusRequester>, onOpen: (ChannelPage) -> Unit) {
    val groups by vm.groups.collectAsState()
    pages.forEachIndexed { i, p ->
        val m = Modifier.focusRequester(openers.getValue(p))
        SettingRow(p.title, p.explanation, if (p == ChannelPage.YOUR_CATEGORIES) "${groups.size} ›" else "›",
            if (i == 0) m.focusRequester(first) else m) { onOpen(p) }
    }
}

/** One kind's provider categories: Show all / Hide all, then each category with move (OK) and Shown/Hidden. */
@Composable
private fun CategoryListPage(vm: SettingsViewModel, kind: Kind, first: FocusRequester, onClose: () -> Unit) {
    LaunchedEffect(kind) { vm.categoryKind.value = kind }
    val rows = vm.categoryRows.collectAsState().value.filter { it.category.kind == kind } // not the previous kind's list
    /** Toggles pressed but not yet written back by the database: category id → hidden. */
    val pending = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    var moving by remember { mutableStateOf<Long?>(null) }
    var movedFrom by remember { mutableStateOf(0) }
    BackHandler(enabled = moving == null, onBack = onClose)
    val list = rememberLazyListState()
    val movingIndex = rows.indexOfFirst { it.category.id == moving }
    // Keep the row being moved on screen (+2 for the Show all / Hide all rows above it).
    LaunchedEffect(movingIndex) {
        if (movingIndex < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
        if (movingIndex + 2 !in visible.drop(1).dropLast(1)) list.scrollToItem((movingIndex + 2 - 3).coerceAtLeast(0))
    }
    val noun = when (kind) { Kind.LIVE -> "channels"; Kind.MOVIE -> "movies"; Kind.SERIES -> "series" }
    val scope = rememberCoroutineScope()
    // Leaving the list (to the tabs or the group list) resets it, so coming back starts at the top.
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.onFocusChanged { if (!it.hasFocus) scope.launch { list.scrollToItem(0) } }) {
        item { SettingRow("Show all", "Show every ${KIND_TABS.first { it.first == kind }.second} category", null, Modifier.focusRequester(first)) { pending.clear(); vm.setAllHidden(false) } }
        item { SettingRow("Hide all", "Hide every ${KIND_TABS.first { it.first == kind }.second} category", null) { pending.clear(); vm.setAllHidden(true) } }
        items(rows, key = { it.category.id }) { r ->
            val cat = r.category
            val isMoving = moving == cat.id
            // Shown at once: the write can queue behind a playlist sync or guide import for a while (user bug, 2026-11-12).
            val hidden = pending[cat.id] ?: cat.hidden
            LaunchedEffect(cat.hidden) { if (pending[cat.id] == cat.hidden) pending.remove(cat.id) }
            // OK toggles shown/hidden, a long OK picks the category up to move (user, 2026-11-13).
            // canFocus reads `moving` itself: the captured isMoving is stale when the lambda re-runs, which dropped focus.
            FocusBox(
                onClick = { if (isMoving) moving = null else { pending[cat.id] = !hidden; vm.setHidden(cat, !hidden) } },
                modifier = Modifier.focusProperties { canFocus = moving == null || moving == cat.id }
                    .onLongOk { if (moving == null) { moving = cat.id; movedFrom = rows.indexOf(r) } }
                    .onPreviewKeyEvent { e ->
                        if (!isMoving) return@onPreviewKeyEvent false
                        val down = e.type == KeyEventType.KeyDown
                        when (e.key) {
                            Key.DirectionUp -> { if (down) vm.move(cat, -1); true }
                            Key.DirectionDown -> { if (down) vm.move(cat, 1); true }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            Key.Back -> { // cancel: put it back where it started
                                if (!down) { vm.move(cat, movedFrom - rows.indexOfFirst { it.category.id == cat.id }); moving = null }
                                true
                            }
                            else -> false
                        }
                    },
            ) { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isMoving) Color(0xFFE5A00D) else if (f) Color.White else Color(0x14FFFFFF))
                    .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val fg = if (f || isMoving) Color.Black else Color.White
                        Text((if (isMoving) "↕ " else "") + cat.name.ifEmpty { "Uncategorised" }, fontSize = 16.sp, color = fg, maxLines = 1)
                        Text(if (isMoving) "Up/Down to move · OK to drop · Back to cancel" else "${r.count} $noun · OK to show/hide · hold OK to move",
                            fontSize = 12.sp, color = if (f || isMoving) Color(0xFF555555) else LocalTheme.current.colors.muted)
                    }
                    if (!isMoving) Toggle(!hidden, f) // hidden while moving: the gold track would vanish into the gold row
                }
            }
        }
    }
}

@Composable
private fun LanguagesPage(vm: SettingsViewModel, first: FocusRequester) {
    val langs by vm.languages.collectAsState()
    val s by vm.settings.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow("Match TV language", "Show only ${vm.tvLanguage} categories and ones with no language; hide the rest",
            s.matchTvLanguage, Modifier.focusRequester(first).padding(bottom = 12.dp)) { vm.setMatchTvLanguage(it) }
        if (langs.isEmpty()) Text("No categories yet", fontSize = 15.sp, color = LocalTheme.current.colors.muted)
        langs.forEach { l ->
            val shown = l.countries.count { it.shown }
            val value = when (shown) { l.countries.size -> "Shown"; 0 -> "Hidden"; else -> "Partly" }
            val explanation = if (l.countries.size == 1) l.countries[0].label else "${l.countries.size} countries"
            SettingRow(l.language, explanation, value) {
                vm.setCountriesShown(l.countries.map { it.country }, shown != l.countries.size)
            }
            if (l.countries.size > 1) l.countries.forEach { c ->
                SwitchRow(c.label, "Every category detected as ${c.label}", c.shown, Modifier.padding(start = 32.dp)) {
                    vm.setCountriesShown(listOf(c.country), it)
                }
            }
        }
    }
}

@Composable
private fun NameCleanupPage(vm: SettingsViewModel, first: FocusRequester, category: Boolean) {
    val strip by (if (category) vm.categoryStrip else vm.nameStrip).collectAsState()
    val preview by (if (category) vm.categoryStripPreview else vm.stripPreview).collectAsState()
    val draft = if (category) vm.categoryStripDraft else vm.stripDraft
    val save: (String) -> Unit = if (category) vm::setCategoryStrip else { s -> vm.setNameStrip(s) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // One saved list: plain entries remove, "find=replace" entries rename (parseStripList).
        val (renames, removes) = parseStripList(strip).partition { '=' in it }
        fun join(a: List<String>, b: String?) = (a + parseStripList(b.orEmpty())).joinToString(", ")
        val removeText = removes.joinToString(", "); val renameText = renames.joinToString(", ")
        EditRow("Prefixes or suffixes to remove", "Comma-separated, e.g. UK|, US |, 4K:", removeText.ifBlank { "None" }, removeText,
            Modifier.focusRequester(first), onDraft = { d -> draft.value = d?.let { join(renames, it) } }) { save(join(renames, it)) }
        EditRow("Prefixes or suffixes to rename", "Comma-separated find=replace, e.g. 3840P=4K, RAW=", renameText.ifBlank { "None" }, renameText,
            onDraft = { d -> draft.value = d?.let { join(removes, it) } }) { save(join(removes, it)) }
        Text("Preview", fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(top = 12.dp))
        if (preview.isEmpty()) Text(if (category) "No category names change" else "No channel names change", fontSize = 14.sp, color = LocalTheme.current.colors.muted)
        preview.forEach { (before, after) -> Text("$before  →  $after", fontSize = 14.sp, color = Color.White, maxLines = 1) }
    }
}

/** What the Options button of one of your categories has open. */
private sealed interface GroupMenu {
    val g: CustomGroupEntity
    data class Options(override val g: CustomGroupEntity) : GroupMenu
    data class Rename(override val g: CustomGroupEntity) : GroupMenu
    data class Delete(override val g: CustomGroupEntity) : GroupMenu
    data class Priority(override val g: CustomGroupEntity) : GroupMenu
}

private fun GroupPlacement.label() = when (this) {
    GroupPlacement.TOP -> "Top of the list"; GroupPlacement.NORMAL -> "Above provider categories"; GroupPlacement.BOTTOM -> "Below provider categories"
}

/** [order] with the item at [i] moved [d] steps, staying among items with the same placement; null if it can't. */
internal fun <T> moveWithin(order: List<T>, i: Int, d: Int, placement: (T) -> GroupPlacement): List<T>? {
    val j = i + d
    if (i !in order.indices || j !in order.indices || placement(order[j]) != placement(order[i])) return null
    return order.toMutableList().apply { add(j, removeAt(i)) }
}

/** Add, then each of your categories on one line: its name (OK to move, like provider categories) and an Options button (Rename, Delete, Priority). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun YourCategoriesPage(vm: SettingsViewModel, first: FocusRequester) {
    val saved by vm.groups.collectAsState()
    var menu by remember { mutableStateOf<GroupMenu?>(null) }
    var moving by remember { mutableStateOf<Long?>(null) }
    // While moving, this page's order leads (fast presses mustn't wait for the database); grouped by side of the list.
    var order by remember { mutableStateOf<List<tv.luna.player.data.db.CustomGroupEntity>?>(null) }
    val groups = order ?: saved.sortedBy { it.placement.ordinal }
    fun drop() { moving = null; order?.let(vm::saveGroupOrder) }
    BackHandler(enabled = moving != null) { drop() }
    LaunchedEffect(saved) { if (moving == null) order = null }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EditRow("Add category", "Create a category of your own", null, "", Modifier.focusRequester(first)) {
            if (it.isNotBlank()) vm.createGroup(it)
        }
        Text("Add channels with a long press of OK in the guide.", fontSize = 12.sp, color = LocalTheme.current.colors.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        groups.forEach { g ->
            key(g.id) {
                val isMoving = moving == g.id
                val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                // Keep the row being moved on screen, as the provider category list does.
                if (isMoving) LaunchedEffect(groups.indexOf(g)) { bring.bringIntoView() }
                // OK opens Options (rename, delete, priority), a long OK picks it up to move (user, 2026-11-13).
                SettingRow((if (isMoving) "↕ " else "") + g.name,
                    if (isMoving) "Up/Down to move · OK to drop" else "OK for options · hold OK to move · ${g.placement.label()}", null,
                    // canFocus reads `moving` itself: the captured isMoving is stale when the lambda re-runs, which dropped focus.
                    Modifier.bringIntoViewRequester(bring).focusProperties { canFocus = moving == null || moving == g.id }
                        .onLongOk { if (moving == null) { order = groups; moving = g.id } }
                        .onPreviewKeyEvent { e ->
                            if (!isMoving) return@onPreviewKeyEvent false
                            val d = when (e.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; Key.DirectionLeft, Key.DirectionRight -> 0; else -> return@onPreviewKeyEvent false }
                            if (d != 0 && e.type == KeyEventType.KeyDown) moveWithin(groups, groups.indexOf(g), d) { it.placement }?.let { order = it }
                            true
                        }) { if (isMoving) drop() else menu = GroupMenu.Options(g) }
            }
        }
    }
    when (val m = menu) {
        null -> Unit
        is GroupMenu.Options -> ContextMenu("“${m.g.name}”", listOf(
            "Rename…" to { menu = GroupMenu.Rename(m.g) },
            "Delete" to { menu = GroupMenu.Delete(m.g) },
            "Priority ›" to { menu = GroupMenu.Priority(m.g) },
        )) { menu = null }
        is GroupMenu.Rename -> TextPrompt("Rename “${m.g.name}”", m.g.name, "Name", onSave = { if (it.isNotBlank()) vm.renameGroup(m.g, it); menu = null }) { menu = null }
        is GroupMenu.Delete -> ContextMenu("Delete “${m.g.name}”? Its channels stay in their own categories", listOf(
            // The row is about to vanish: focus Add category first, or focus falls to the group list.
            "Delete" to { menu = null; runCatching { first.requestFocus() }; vm.deleteGroup(m.g) },
            "Cancel" to { menu = null },
        )) { menu = null }
        is GroupMenu.Priority -> ContextMenu("Priority", GroupPlacement.entries.map { p ->
            ((if (p == m.g.placement) "✓ " else "    ") + p.label()) to { vm.setGroupPlacement(m.g, p); menu = null }
        }) { menu = null }
    }
}
