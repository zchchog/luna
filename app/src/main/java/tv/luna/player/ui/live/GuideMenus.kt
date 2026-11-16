package tv.luna.player.ui.live

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
}

/** The long-press menus of a channel row and a rail category, shared by every theme. */
@Composable
fun GuideMenus(vm: LiveViewModel, state: GuideState) {
    val m = state.menu ?: return
    val strip by vm.strip.collectAsState()
    val entry by vm.entry.collectAsState()
    val favourites by vm.favouriteIds.collectAsState()
    val groups by vm.groups.collectAsState()
    val rail by vm.rail.collectAsState()
    val recordingOn by vm.recordingOn.collectAsState()
    val recordingOnChannel by vm.recordingOnChannel.collectAsState()
    val marks by vm.marks.collectAsState()
    when (m) {
        is GuideMenu.Channel -> ContextMenu(m.ch.displayName(strip), buildList {
            val fav = m.ch.id in favourites
            add((if (fav) "Remove from Favourites" else "Add to Favourites") to {
                if (fav && entry == RailEntry.Favourites) state.refocusOnChange = true
                vm.toggleFavourite(m.ch); state.menu = null
            })
            if (recordingOn) {
                val rec = recordingOnChannel[m.ch.id]
                if (rec != null) add("Stop recording" to { vm.stopRecording(rec); state.menu = null })
                else add("Record now" to { vm.recordNow(m.ch); state.menu = null })
                add("Record a later programme…" to { state.menu = GuideMenu.RecordLater(m.ch) })
            }
            add("Add to category…" to { state.menu = GuideMenu.AddTo(m.ch) })
            (entry as? RailEntry.Custom)?.let { e ->
                add("Remove from ${e.group.name}" to { state.refocusOnChange = true; vm.removeFromGroup(m.ch, e.group.id); state.menu = null })
            }
            add("Rename" to { state.menu = GuideMenu.Rename(m.ch) })
            add("Change logo…" to { state.menu = GuideMenu.ChangeLogo(m.ch) })
            add("Use guide from…" to { state.menu = GuideMenu.UseGuideFrom(m.ch) })
            add("Hide" to { state.refocusOnChange = true; vm.hide(m.ch); state.menu = null })
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
        is GuideMenu.CancelRecording -> ContextMenu(m.p.title, listOf(
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
