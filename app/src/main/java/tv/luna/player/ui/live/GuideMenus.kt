package tv.luna.player.ui.live

import kotlinx.coroutines.launch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.repo.RailEntry
import tv.luna.player.ui.ContextMenu
import tv.luna.player.ui.TextPrompt

internal sealed interface GuideMenu {
    data class Channel(val ch: ChannelEntity) : GuideMenu
    data class AddTo(val ch: ChannelEntity) : GuideMenu
    data class NewGroup(val ch: ChannelEntity) : GuideMenu
    data class Rename(val ch: ChannelEntity) : GuideMenu
    data class ChangeLogo(val ch: ChannelEntity) : GuideMenu
    data class UseGuideFrom(val ch: ChannelEntity) : GuideMenu
    data class RecordLater(val ch: ChannelEntity) : GuideMenu
    data class CancelRecording(val ch: ChannelEntity, val p: tv.luna.player.data.repo.GuideProgramme) : GuideMenu
    data class Category(val cat: CategoryEntity) : GuideMenu
    /** Where recordings go: asked the first time, or to change it. [then]: what to do after (null = just set it). */
    data class StopSeries(val rule: tv.luna.player.recording.SeriesRuleEntity) : GuideMenu
    data class RecordWhere(val ch: ChannelEntity, val then: RecordNext?, val p: tv.luna.player.data.repo.GuideProgramme? = null) : GuideMenu
    data class RemindLater(val ch: ChannelEntity) : GuideMenu
    /** Hold OK on a programme: Play, Record, Remind. */
    data class Programme(val ch: ChannelEntity, val p: tv.luna.player.data.repo.GuideProgramme) : GuideMenu
    /** The channel's less-used options, one step down. */
    data class ChannelMore(val ch: ChannelEntity) : GuideMenu
}

internal enum class RecordNext { NOW, LATER, PROGRAMME }

/** The long-press menus of a channel row and a rail category, shared by every theme. */
@Composable
fun GuideMenus(vm: LiveViewModel, state: GuideState, onFullScreen: (ChannelEntity) -> Unit = {}) {
    val m = state.menu ?: return
    val strip by vm.strip.collectAsState()
    val entry by vm.entry.collectAsState()
    val favourites by vm.favouriteIds.collectAsState()
    val groups by vm.groups.collectAsState()
    val rail by vm.rail.collectAsState()
    val recordingOn by vm.recordingOn.collectAsState()
    val recordingOnChannel by vm.recordingOnChannel.collectAsState()
    val marks by vm.marks.collectAsState()
    val reminderMarks by vm.reminderMarks.collectAsState()
    val recordPlace by vm.recordPlace.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // The first Record press just works: it turns recording on and saves to this box. Another place (USB drive, network share) is
    // chosen later in Settings › Playback › Recording, or from a channel's More… menu.
    fun recordHere(ch: tv.luna.player.data.db.ChannelEntity, then: RecordNext, p: tv.luna.player.data.repo.GuideProgramme?) {
        scope.launch {
            vm.setRecordPlace(tv.luna.player.recording.RecStorage.INTERNAL)
            when (then) {
                RecordNext.NOW -> vm.recordNow(ch)
                RecordNext.LATER -> { state.menu = GuideMenu.RecordLater(ch); return@launch }
                RecordNext.PROGRAMME -> p?.let { vm.schedule(ch, it) }
            }
            state.menu = null
        }
    }
    when (m) {
        // Hold OK on a channel's name: just the everyday three, the rest one step down.
        is GuideMenu.Channel -> ContextMenu(m.ch.displayName(strip), buildList {
            val fav = m.ch.id in favourites
            add((if (fav) "★  Remove from Favourites" else "★  Add to Favourites") to {
                if (fav && entry == RailEntry.Favourites) state.refocusOnChange = true
                vm.toggleFavourite(m.ch); state.menu = null
            })
            add("Rename" to { state.menu = GuideMenu.Rename(m.ch) })
            add("Hide" to { state.refocusOnChange = true; vm.hide(m.ch); state.menu = null })
            add("More…" to { state.menu = GuideMenu.ChannelMore(m.ch) })
        }) { state.menu = null }
        // Hold OK on a programme: Play, Record, Remind.
        is GuideMenu.Programme -> {
            val now = System.currentTimeMillis()
            val live = m.p.start <= now && now < m.p.stop
            val recorded = (m.ch.id to m.p.start) in marks || (live && m.ch.id in recordingOnChannel)
            val reminded = (m.ch.id to m.p.start) in reminderMarks
            val rules by vm.seriesRules.collectAsState()
            val rule = rules.firstOrNull { it.channelId == m.ch.id && it.title.equals(m.p.title, ignoreCase = true) }
            val future = m.p.start > now
            val watch = (if (live) "Play" else "Watch this channel") to { state.menu = null; onFullScreen(m.ch) }
            val recordItem = (if (recorded) (if (live) "Stop recording this episode" else "Cancel this episode") else "Record episode") to {
                when {
                    recorded && live -> { recordingOnChannel[m.ch.id]?.let(vm::stopRecording); state.menu = null }
                    recorded -> { vm.cancelRecording(m.ch, m.p); state.menu = null }
                    !recordingOn -> recordHere(m.ch, if (live) RecordNext.NOW else RecordNext.PROGRAMME, m.p)
                    live -> { vm.recordNow(m.ch); state.menu = null }
                    else -> { vm.schedule(m.ch, m.p); state.menu = null }
                }
            }
            // A series rule keeps scheduling every new showing of this title on this channel until it is stopped.
            val seriesItem = if (rule != null) "Stop recording the series" to { state.menu = GuideMenu.StopSeries(rule) }
            else "Record series" to {
                if (recordingOn) vm.recordSeries(m.ch, m.p)
                else scope.launch { vm.setRecordPlace(tv.luna.player.recording.RecStorage.INTERNAL); vm.recordSeries(m.ch, m.p) }
                state.menu = null
            }
            val remindItem = (if (reminded) "Cancel reminder" else "Remind me") to {
                if (reminded) vm.cancelReminder(m.ch, m.p) else {
                    val activity = context as? android.app.Activity
                    if (android.os.Build.VERSION.SDK_INT >= 33 && activity != null &&
                        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                    }
                    vm.remind(m.ch, m.p)
                }
                state.menu = null
            }
            // A programme still to come: what you want is Record or Remind, so they come first and the highlight starts on Record.
            val items = when {
                m.p.stop <= now -> listOf(watch)
                future -> listOf(recordItem, remindItem, seriesItem, watch)
                else -> listOf(watch, recordItem, seriesItem)
            }
            ContextMenu(progTitle(m.ch.displayName(strip), m.p), items) { state.menu = null }
        }
        is GuideMenu.StopSeries -> ContextMenu("Stop recording ${m.rule.title}?\n${m.rule.channelName}", listOf(
            "Stop the series, keep what's recorded" to { vm.stopSeries(m.rule.id, false); state.menu = null },
            "Stop the series and delete its recordings" to { vm.stopSeries(m.rule.id, true); state.menu = null },
            "Keep recording the series" to { state.menu = null },
        )) { state.menu = null }
        is GuideMenu.ChannelMore -> ContextMenu(m.ch.displayName(strip), buildList {
            add("Record a later programme…" to { if (recordingOn) state.menu = GuideMenu.RecordLater(m.ch) else recordHere(m.ch, RecordNext.LATER, null) })
            add("Remind me about a programme…" to { state.menu = GuideMenu.RemindLater(m.ch) })
            if (recordingOn) add("Recordings save to $recordPlace (change)…" to { state.menu = GuideMenu.RecordWhere(m.ch, null) })
            add("Add to category…" to { state.menu = GuideMenu.AddTo(m.ch) })
            (entry as? RailEntry.Custom)?.let { e ->
                add("Remove from ${e.group.name}" to { state.refocusOnChange = true; vm.removeFromGroup(m.ch, e.group.id); state.menu = null })
            }
            add("Change logo…" to { state.menu = GuideMenu.ChangeLogo(m.ch) })
            add("Use guide from…" to { state.menu = GuideMenu.UseGuideFrom(m.ch) })
        }) { state.menu = null }
        is GuideMenu.AddTo -> ContextMenu("Add ${m.ch.displayName(strip)} to",
            groups.map { g -> g.name to { vm.addToGroup(m.ch, g.id); state.menu = null } } +
                ("New category…" to { state.menu = GuideMenu.NewGroup(m.ch) })) { state.menu = null }
        is GuideMenu.NewGroup -> TextPrompt("New category", "", "Category name",
            onSave = { if (it.isNotEmpty()) vm.addToNewGroup(m.ch, it); state.menu = null }) { state.menu = null }
        is GuideMenu.Rename -> TextPrompt("Rename channel", m.ch.customName ?: m.ch.displayName(strip), "Name (leave empty for the provider's name)",
            onSave = { vm.rename(m.ch, it); state.menu = null }) { state.menu = null }
        is GuideMenu.ChangeLogo -> LogoPicker(vm, m.ch, m.ch.displayName(strip)) { state.menu = null }
        is GuideMenu.UseGuideFrom -> GuidePicker(vm, m.ch, strip) { state.menu = null }
        is GuideMenu.RecordLater -> {
            val list by androidx.compose.runtime.produceState<List<tv.luna.player.data.repo.GuideProgramme>?>(null, m.ch) { value = vm.upcoming(m.ch) }
            val progs = list ?: return
            val today = tv.luna.player.ui.dayDate(System.currentTimeMillis())
            ContextMenu("Record on ${m.ch.displayName(strip)}", if (progs.isEmpty()) listOf("No guide for this channel" to { state.menu = null })
                else progs.map { p ->
                    val marked = (m.ch.id to p.start) in marks
                    val day = tv.luna.player.ui.dayDate(p.start).takeIf { it != today }?.let { "$it " }.orEmpty()
                    "${if (marked) "● " else ""}$day${tv.luna.player.ui.clockTime(p.start)}  ${p.title}" to {
                        if (marked) state.menu = GuideMenu.CancelRecording(m.ch, p) else { vm.schedule(m.ch, p); state.menu = null }
                    }
                }) { state.menu = null }
        }
        is GuideMenu.RecordWhere -> {
            val drives = androidx.compose.runtime.remember { vm.usbDrives() }
            val share by androidx.compose.runtime.produceState(false) { value = vm.shareSetUp() }
            fun pick(to: tv.luna.player.recording.RecStorage, path: String? = null) {
                scope.launch {
                    vm.setRecordPlace(to, path)
                    when (m.then) {
                        RecordNext.NOW -> { vm.recordNow(m.ch); state.menu = null }
                        RecordNext.LATER -> state.menu = GuideMenu.RecordLater(m.ch)
                        RecordNext.PROGRAMME -> { m.p?.let { vm.schedule(m.ch, it) }; state.menu = null }
                        null -> state.menu = null
                    }
                }
            }
            ContextMenu(m.p?.let { "Record ${it.title}\nWhere should it be saved?" } ?: "Where should recordings go?", buildList {
                add("This box (about 2 hours of HD)" to { pick(tv.luna.player.recording.RecStorage.INTERNAL) })
                if (drives.isEmpty()) add("USB drive: plug one in first" to { })
                drives.forEach { (name, path) -> add("USB drive: $name" to { pick(tv.luna.player.recording.RecStorage.USB, path) }) }
                if (share) add("Network share" to { pick(tv.luna.player.recording.RecStorage.SMB) })
                else add("Network share: set it up in Settings › Playback › Recording" to { })
            }) { state.menu = null }
        }
        is GuideMenu.RemindLater -> {
            val list by androidx.compose.runtime.produceState<List<tv.luna.player.data.repo.GuideProgramme>?>(null, m.ch) { value = vm.upcoming(m.ch) }
            val progs = list ?: return
            val today = tv.luna.player.ui.dayDate(System.currentTimeMillis())
            val now = System.currentTimeMillis()
            val later = progs.filter { it.start > now }
            ContextMenu("Remind me: ${m.ch.displayName(strip)}", if (later.isEmpty()) listOf("No upcoming programmes in the guide" to { state.menu = null })
                else later.map { p ->
                    val set = (m.ch.id to p.start) in reminderMarks
                    val day = tv.luna.player.ui.dayDate(p.start).takeIf { it != today }?.let { "$it " }.orEmpty()
                    "${if (set) "● " else ""}$day${tv.luna.player.ui.clockTime(p.start)}  ${p.title}" to {
                        if (set) vm.cancelReminder(m.ch, p) else vm.remind(m.ch, p)
                        state.menu = null
                    }
                }) { state.menu = null }
        }
        is GuideMenu.CancelRecording -> ContextMenu(progTitle(m.ch.displayName(strip), m.p), listOf(
            (if (m.p.start <= System.currentTimeMillis()) "Stop recording" else "Cancel recording") to { vm.cancelRecording(m.ch, m.p); state.menu = null },
            "Keep it" to { state.menu = null },
        )) { state.menu = null }
        is GuideMenu.Category -> ContextMenu(m.cat.name, listOf(
            "Hide" to { vm.hideCategory(m.cat); state.menu = null; state.railOpen = false; state.refocus() },
            "Move" to {
                state.railMove = RailMove(m.cat, rail.orEmpty().filter { it.entry is RailEntry.Provider }.indexOfFirst { it.entry.key == "c${m.cat.id}" })
                state.menu = null
            },
        )) { state.menu = null }
    }
}

/** The heading of a programme's menu: its title, then the channel and start time, so it is clear what is being recorded or reminded. */
private fun progTitle(channel: String, p: tv.luna.player.data.repo.GuideProgramme) =
    "${p.title}\n$channel · ${tv.luna.player.ui.clockTime(p.start)}"
