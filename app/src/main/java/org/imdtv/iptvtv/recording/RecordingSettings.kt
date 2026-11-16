package org.imdtv.iptvtv.recording

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.data.settings.END_LATE
import org.imdtv.iptvtv.data.settings.START_EARLY
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem
import org.imdtv.iptvtv.ui.settings.ChoiceRow
import org.imdtv.iptvtv.ui.settings.EditRow
import org.imdtv.iptvtv.ui.settings.SettingRow
import org.imdtv.iptvtv.ui.settings.SettingsViewModel
import org.imdtv.iptvtv.ui.settings.SwitchRow

const val RECORDING_INFO = "Recording one channel while you watch another needs two connections from your provider. Xtream " +
    "logins tell the app their limit; M3U playlists don't. Small boxes have little room: a Chromecast with Google TV has " +
    "about 4 GB free, about 2 hours of HD. Use a USB drive or a network share for more. Recordings on the box or the USB " +
    "drive are removed if you uninstall the app."

/** Settings › Playback › Recording. */
@Composable
fun RecordingPage(vm: SettingsViewModel, modifier: Modifier, first: FocusRequester, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableStateOf<Long?>(null) }
    val drives = remember(s.recordTo) { usbDirs(context) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    LaunchedEffect(s.recordingOn, s.recordTo, s.recordUsbPath, s.smbServer, s.smbShare, s.smbFolder, s.smbUser, s.smbPassword) {
        free = null
        if (s.recordingOn && (s.recordTo != RecStorage.SMB || s.smbServer.isNotBlank() && s.smbShare.isNotBlank())) free = vm.recordingFreeBytes(s)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recording", fontSize = 20.sp, fontFamily = org.imdtv.iptvtv.ui.Montserrat, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        SwitchRow("Recording", "Record live channels from the guide (hold OK on a channel)", s.recordingOn, Modifier.focusRequester(first)) { on ->
            if (on) info = true else confirmOff = true
        }
        if (!s.recordingOn) return@Column
        val usbLabel = if (drives.isEmpty()) "USB drive (none found)" else "USB drive"
        ChoiceRow("Save to", "Where new recordings go", RecStorage.entries, s.recordTo,
            { when (it) { RecStorage.INTERNAL -> "Internal storage"; RecStorage.USB -> usbLabel; RecStorage.SMB -> "Network share" } }) { to ->
            if (to == RecStorage.USB && drives.isEmpty()) vm.notice("Plug in a USB drive first") else vm.update { it.copy(recordTo = to) }
        }
        if (s.recordTo == RecStorage.USB && drives.size > 1) {
            val current = drives.firstOrNull { it.path == s.recordUsbPath } ?: drives.first()
            ChoiceRow("USB drive", "Which drive recordings go to", drives, current, { driveName(context, it) }) { d -> vm.update { it.copy(recordUsbPath = d.path) } }
        }
        if (s.recordTo == RecStorage.SMB) {
            EditRow("Server", "Name or address, e.g. nas or 192.168.1.20", s.smbServer.ifEmpty { "Not set" }, s.smbServer, keyboard = KeyboardType.Uri) { v -> vm.update { it.copy(smbServer = v) } }
            EditRow("Share", "The shared folder's name on the server", s.smbShare.ifEmpty { "Not set" }, s.smbShare) { v -> vm.update { it.copy(smbShare = v) } }
            EditRow("Folder", "Inside the share; empty = the top", s.smbFolder.ifEmpty { "Top of the share" }, s.smbFolder) { v -> vm.update { it.copy(smbFolder = v) } }
            EditRow("Username", "Empty = guest", s.smbUser.ifEmpty { "Guest" }, s.smbUser, keyboard = KeyboardType.Password) { v -> vm.update { it.copy(smbUser = v) } }
            EditRow("Password", "Stored encrypted on this box; not in backups", if (s.smbPassword.isEmpty()) "Not set" else "••••••", s.smbPassword,
                keyboard = KeyboardType.Password, secret = true) { v -> vm.update { it.copy(smbPassword = v) } }
            SettingRow("Test", "Writes and removes a small file", test) {
                test = "Testing…"
                scope.launch { test = vm.testShare(s)?.let { "Failed: $it" } ?: "OK" }
            }
        }
        SettingRow("Free space", "Where recordings are saved", free?.let(::freeSpaceText) ?: "…") {}
        ChoiceRow("Start early", "Guide times drift: scheduled recordings start this much earlier", START_EARLY, s.startEarlyMin, { "$it min" }) { m ->
            vm.update { it.copy(startEarlyMin = m) }
        }
        ChoiceRow("End late", "…and end this much later", END_LATE, s.endLateMin, { "$it min" }) { m -> vm.update { it.copy(endLateMin = m) } }
        SwitchRow("Keep the box awake while recording", "Stops the box sleeping or turning Wi-Fi off mid-recording", s.keepAwake) { on ->
            vm.update { it.copy(keepAwake = on) }
        }
    }

    if (info) MenuFrame("Before you record", onDismiss = { info = false }) { f ->
        Text(RECORDING_INFO, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
        MenuItem("OK", Modifier.focusRequester(f)) { info = false; vm.update { it.copy(recordingOn = true) } }
        MenuItem("Cancel", Modifier) { info = false }
    }
    if (confirmOff) MenuFrame("Turn recording off?", onDismiss = { confirmOff = false }) { f ->
        Text("Scheduled recordings are cancelled and any recording now stops. Your recordings are kept.", fontSize = 14.sp,
            color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
        MenuItem("Turn off", Modifier.focusRequester(f)) { confirmOff = false; vm.recordingOff() }
        MenuItem("Cancel", Modifier) { confirmOff = false }
    }
}

/** A drive's name as Android shows it ("SanDisk", "USB drive"), from the volume holding [dir]. */
private fun driveName(context: android.content.Context, dir: java.io.File): String =
    context.getSystemService(android.os.storage.StorageManager::class.java).getStorageVolume(dir)?.getDescription(context) ?: dir.path
