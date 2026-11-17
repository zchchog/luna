package tv.luna.player.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp

/** Settings › Channels & categories: the channels hidden from the guide, each shown again with OK. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HiddenChannelsRows() {
    val c = (LocalContext.current.applicationContext as IptvApp).container
    val hidden by remember {
        c.providerRepository.activeProviderId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.db.channelDao().hiddenChannels(id) }
    }.collectAsState(emptyList())
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingRow("Hidden channels", "Channels you hid from the guide. Open the list and press OK on one to show it again",
        if (hidden.isEmpty()) "None" else if (open) "Close" else "${hidden.size} ›") { if (hidden.isNotEmpty()) open = !open }
    if (open) hidden.forEach { ch ->
        SettingRow(ch.customName ?: ch.name, "Hidden", "Show again") { scope.launch { c.db.channelDao().setHidden(ch.id, false) } }
    }
}
