package tv.luna.player.ui.live

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.ui.MenuFrame
import tv.luna.player.ui.MenuItem
import tv.luna.player.ui.settings.EditRow

/** Use guide from… (spec §5.4): same-name channels first, a search row, Use automatic guide when the link is manual. */
@Composable
fun GuidePicker(vm: LiveViewModel, ch: ChannelEntity, strip: List<String>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var donors by remember { mutableStateOf<List<Pair<ChannelEntity, String?>>?>(null) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(ch.id) { manual = vm.hasManualGuide(ch) }
    LaunchedEffect(ch.id, query) { donors = vm.donors(ch, query) }
    androidx.compose.runtime.DisposableEffect(ch.id) { onDispose { vm.clearDonors() } }
    MenuFrame("Guide for ${ch.displayName(strip)}", onDismiss) { first ->
        if (manual) MenuItem("Use automatic guide", Modifier.focusRequester(first)) { vm.useAutomaticGuide(ch); onDismiss() }
        // A row that opens the box on OK, so scrolling past it never opens the keyboard (user, 2026-11-10).
        EditRow("Search channels", if (query.isBlank()) "Type part of a channel name" else "“$query”", null, query,
            if (manual) Modifier else Modifier.focusRequester(first)) { query = it.trim() }
        when (val d = donors) {
            null -> Note("Looking…")
            emptyList<Pair<ChannelEntity, String?>>() -> Note(if (query.isBlank()) "No same-name channel has a guide — search for one" else "No channel with a guide matches")
            else -> d.forEach { (donor, cat) ->
                MenuItem(donor.displayName(strip) + (cat?.let { " · $it" }.orEmpty()), Modifier) { vm.useGuideFrom(ch, donor); onDismiss() }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
