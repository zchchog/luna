package tv.luna.player.ui.ondemand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.ui.settings.EditRow
import tv.luna.player.ui.settings.SettingRow
import tv.luna.player.ui.settings.SwitchRow
import tv.luna.player.ui.theme.LocalTheme

/** Settings › Add-ons: add (from a phone or by typing), remove, and the auto-play switch. */
@Composable
fun AddonRows(first: Modifier, s: tv.luna.player.data.settings.AppSettings, update: ((tv.luna.player.data.settings.AppSettings) -> tv.luna.player.data.settings.AppSettings) -> Unit) {
    val repo = (LocalContext.current.applicationContext as IptvApp).container.addons
    val installed by repo.installed.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    val list = installed ?: emptyList()
    val hasMeta = list.any { it.manifest.role == "Metadata link" }
    val hasHost = list.any { it.manifest.role == "Host link" }
    Text("On Demand needs two links from your provider: a Metadata link and a Host link. No links yet? Message @novadrop:matrix.org on the Element X app.",
        fontSize = 12.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
    val add = { link: String ->
        if (link.isNotBlank()) {
            busy = true; status = "Adding…"
            scope.launch {
                status = try { "Added your ${repo.add(link)}" } catch (e: Exception) { e.message ?: "Couldn't add that link" }
                busy = false
            }
        }
    }
    EditRow("Metadata link", "Films, series and search", if (hasMeta) "Added ✓" else "Not added", "", first) { add(it) }
    EditRow("Host link", "Sources to play", if (hasHost) "Added ✓" else "Not added", "") { add(it) }
    status?.let { Text(it, fontSize = 13.sp, color = Color(0xFF5FD6DC), modifier = Modifier.padding(vertical = 4.dp)) }
    list.forEach { a ->
        val confirming = removing == a.url
        SettingRow(a.manifest.role, a.manifest.does, if (confirming) "Press OK again to remove" else "Remove") {
            if (confirming) { removing = null; scope.launch { repo.remove(a.url); status = "Removed your ${a.manifest.role}" } } else removing = a.url
        }
    }
    val collections by repo.userCollections.collectAsState()
    val kollection by repo.kollectionOn.collectAsState()
    Text("Collections", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
    Text("The On Demand home is laid out by The Kollection (by Kaoxt). It works with the Metadata link from your provider. You can add a different collection instead.",
        fontSize = 12.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
    SwitchRow("The Kollection", "Built-in home layout by Kaoxt (kollection.tv). Off: rows straight from your Metadata link", kollection) { on ->
        scope.launch { repo.setKollection(on) }
    }
    EditRow("Type a collection link", "Use a different collection: a link to its file (.json)", null, "") { link ->
        if (link.isBlank()) return@EditRow
        status = "Adding…"
        scope.launch { status = try { "Added ${repo.addCollection(link)}" } catch (e: Exception) { e.message ?: "Couldn't add that collection" } }
    }
    collections.forEachIndexed { i, (saved, rows) ->
        val id = "collection:$i"
        val confirming = removing == id
        SettingRow("Collection: ${saved.name}", "${rows.size} rows · ${rows.sumOf { it.folders.size }} folders", if (confirming) "Press OK again to remove" else "Remove") {
            if (confirming) { removing = null; scope.launch { repo.removeCollection(i); status = "Removed the collection" } } else removing = id
        }
    }
    tv.luna.player.ui.settings.ChoiceRow("Picture quality", "Highest available plays first; lower it if films buffer", listOf("Best", "1080p", "720p"), s.maxQuality,
        { when (it) { "Best" -> "Highest available"; "1080p" -> "Up to 1080p (Full HD)"; else -> "Up to 720p" } }) { q -> update { it.copy(maxQuality = q) } }
    SwitchRow("Auto-play best source", "Skip the source list and start the top result", s.autoPlayBestSource) { on -> update { it.copy(autoPlayBestSource = on) } }
    tv.luna.player.ui.settings.ChoiceRow("Next episode button", "When it shows up before an episode ends", listOf(0, 20, 45, 60, 120), s.nextEpisodeSeconds,
        { when (it) { 0 -> "Never"; 60 -> "Last minute"; 120 -> "Last 2 minutes"; else -> "Last $it seconds" } }) { v -> update { it.copy(nextEpisodeSeconds = v) } }
    SwitchRow("Play the next episode automatically", "When an episode finishes, the next one starts", s.autoNextEpisode) { on -> update { it.copy(autoNextEpisode = on) } }
}
