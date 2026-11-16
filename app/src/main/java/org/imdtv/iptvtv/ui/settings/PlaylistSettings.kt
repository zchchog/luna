package org.imdtv.iptvtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.data.db.ProviderEntity
import org.imdtv.iptvtv.data.db.ProviderType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val time = DateTimeFormatter.ofPattern("h.mma", Locale.UK)
private val dayTime = DateTimeFormatter.ofPattern("d MMM, h.mma", Locale.UK)

fun updatedLabel(lastSync: Long?): String {
    val t = Instant.ofEpochMilli(lastSync ?: return "Not updated yet").atZone(ZoneId.systemDefault())
    return "Updated " + (if (t.toLocalDate() == LocalDate.now()) time else dayTime).format(t).lowercase(Locale.UK)
}

private fun ProviderEntity.typeLabel() = if (type == ProviderType.XTREAM) "Xtream" else "M3U"

/** The Playlists group: Add playlist, then one row per playlist opening its page. */
@Composable
fun PlaylistGroupRows(vm: SettingsViewModel, first: Modifier, openers: MutableMap<Long, FocusRequester>, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val providers by vm.providers.collectAsState()
    val activeId by vm.providerId.collectAsState()
    SettingRow("Add playlist", "Add another Xtream or M3U playlist", null, first, onClick = onAdd)
    providers.forEach { p ->
        SettingRow(p.name, "${p.typeLabel()} · ${updatedLabel(p.lastSync)}${if (p.id == activeId) " · In use" else ""}", "›",
            Modifier.focusRequester(openers.getOrPut(p.id) { FocusRequester() })) { onOpen(p.id) }
    }
}

/** One playlist: edit, use, update, delete (its EPG sources are under TV guide › EPG sources). Back closes it. */
@Composable
fun PlaylistPage(
    vm: SettingsViewModel, id: Long, modifier: Modifier, first: FocusRequester,
    onEdit: (Long) -> Unit, onDelete: (Long) -> Unit, onUse: (Long) -> Unit, onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val providers by vm.providers.collectAsState()
    val p = providers.firstOrNull { it.id == id }
    LaunchedEffect(p == null) { if (p == null && providers.isNotEmpty()) onClose() } // deleted
    val update by vm.playlistUpdate.collectAsState()
    val activeId by vm.providerId.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { withFrameNanos { }; runCatching { first.requestFocus() } }
    if (p == null) return
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.name, fontSize = 20.sp, fontFamily = org.imdtv.iptvtv.ui.Montserrat, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        SettingRow("Edit", "URL, login and user agent", null, Modifier.focusRequester(first)) { onEdit(id) }
        if (activeId == id) SettingRow("In use", "This is the playlist the app is showing", null) { }
        else SettingRow("Use this playlist", "Show this playlist's channels, films and series", null) { onUse(id) }
        SettingRow("Update playlist now", update[id] ?: updatedLabel(p.lastSync), null) { vm.updatePlaylist(id) }
        SettingRow(
            "Delete playlist",
            if (confirmDelete) "Delete ${p.name}? Its channels, favourites, your categories and guide links are removed. OK to confirm."
            else "Remove this playlist from the app",
            null,
        ) { if (confirmDelete) { runCatching { first.requestFocus() }; onDelete(id) } else confirmDelete = true }

    }
}
