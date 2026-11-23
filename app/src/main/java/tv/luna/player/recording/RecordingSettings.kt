package tv.luna.player.recording

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
import tv.luna.player.data.settings.END_LATE
import tv.luna.player.data.settings.START_EARLY
import tv.luna.player.ui.MenuFrame
import tv.luna.player.ui.MenuItem
import tv.luna.player.ui.settings.ChoiceRow
import tv.luna.player.ui.settings.EditRow
import tv.luna.player.ui.settings.SettingRow
import tv.luna.player.ui.settings.SettingsViewModel
import tv.luna.player.ui.settings.SwitchRow

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
    var picking by remember { mutableStateOf(false) }
    var smbSetup by remember { mutableStateOf(false) }
    var askDefault by remember { mutableStateOf<Pending?>(null) }
    val drives = remember(s.recordTo) { usbDirs(context) }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    LaunchedEffect(s.recordingOn, s.recordTo, s.recordUsbPath, s.smbServer, s.smbShare, s.smbFolder, s.smbUser, s.smbPassword) {
        free = null
        if (s.recordingOn && (s.recordTo != RecStorage.SMB || s.smbServer.isNotBlank() && s.smbShare.isNotBlank())) free = vm.recordingFreeBytes(s)
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recording", fontSize = 20.sp, fontFamily = tv.luna.player.ui.Montserrat, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        SwitchRow("Recording", "Record live channels from the guide (hold OK on a channel)", s.recordingOn, Modifier.focusRequester(first)) { on ->
            if (on) info = true else confirmOff = true
        }
        if (!s.recordingOn) return@Column
        // The box running the app is the everyday place. External storage (a USB drive or a network share) is set up once,
        // and the app then asks whether it should become the default.
        val usbOk = !s.recordUsbPath.isNullOrBlank() && drives.any { it.path == s.recordUsbPath }
        val shareOk = s.smbServer.isNotBlank() && s.smbShare.isNotBlank()
        val extKind = when { s.recordTo != RecStorage.INTERNAL -> s.recordTo; usbOk -> RecStorage.USB; shareOk -> RecStorage.SMB; else -> null }
        val extName = when (extKind) {
            RecStorage.USB -> "USB drive: " + (drives.firstOrNull { it.path == s.recordUsbPath }?.let { driveName(context, it) } ?: "not plugged in")
            RecStorage.SMB -> "Network share: ${s.smbServer}/${s.smbShare}"
            else -> null
        }
        SettingRow("This box", "Saves on the box the app is running on", if (s.recordTo == RecStorage.INTERNAL) "In use" else "") {
            vm.update { it.copy(recordTo = RecStorage.INTERNAL) }
        }
        if (extKind != null && extName != null) SettingRow(extName, "Your external storage", if (s.recordTo != RecStorage.INTERNAL) "In use" else "") {
            if (extKind == RecStorage.USB && !usbOk) vm.notice("Plug the USB drive back in first") else vm.update { it.copy(recordTo = extKind) }
        }
        SettingRow(if (extKind == null) "Set up external storage" else "Change external storage", "A USB drive or a network share (NAS)", "") { picking = true }
        if (smbSetup || s.recordTo == RecStorage.SMB) {
            EditRow("Server", "Name or address, e.g. nas or 192.168.1.20", s.smbServer.ifEmpty { "Not set" }, s.smbServer, keyboard = KeyboardType.Uri) { v -> vm.update { it.copy(smbServer = v) } }
            EditRow("Share", "The shared folder's name on the server", s.smbShare.ifEmpty { "Not set" }, s.smbShare) { v -> vm.update { it.copy(smbShare = v) } }
            EditRow("Folder", "Inside the share; empty = the top", s.smbFolder.ifEmpty { "Top of the share" }, s.smbFolder) { v -> vm.update { it.copy(smbFolder = v) } }
            EditRow("Username", "Empty = guest", s.smbUser.ifEmpty { "Guest" }, s.smbUser, keyboard = KeyboardType.Password) { v -> vm.update { it.copy(smbUser = v) } }
            EditRow("Password", "Stored encrypted on this box; not in backups", if (s.smbPassword.isEmpty()) "Not set" else "••••••", s.smbPassword,
                keyboard = KeyboardType.Password, secret = true) { v -> vm.update { it.copy(smbPassword = v) } }
            SettingRow("Test", "Writes and removes a small file", test) {
                test = "Testing…"
                scope.launch {
                    val err = vm.testShare(s)
                    test = err?.let { "Failed: $it" } ?: "OK"
                    if (err == null && s.recordTo != RecStorage.SMB) askDefault = Pending(RecStorage.SMB, "the network share")
                }
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

    if (picking) MenuFrame("Set up external storage", onDismiss = { picking = false }) { f ->
        drives.forEachIndexed { i, dir ->
            val name = driveName(context, dir)
            MenuItem("USB drive: $name", if (i == 0) Modifier.focusRequester(f) else Modifier) {
                picking = false
                scope.launch {
                    val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { testFolder(dir) }
                    if (err != null) vm.notice("Couldn't save to that drive: $err")
                    else { vm.update { it.copy(recordUsbPath = dir.path) }; askDefault = Pending(RecStorage.USB, name) }
                }
            }
        }
        if (drives.isEmpty()) MenuItem("USB drive: plug one in first", Modifier.focusRequester(f)) { }
        MenuItem("Network share (NAS)…", Modifier) { picking = false; smbSetup = true }
        MenuItem("Cancel", Modifier) { picking = false }
    }
    askDefault?.let { d ->
        MenuFrame("Record to ${d.name} by default?", onDismiss = { askDefault = null }) { f ->
            Text("It's ready to use. You can switch back to this box at any time here.", fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(bottom = 10.dp))
            MenuItem("Yes, use it by default", Modifier.focusRequester(f)) { askDefault = null; vm.update { it.copy(recordTo = d.kind) } }
            MenuItem("No, keep using this box", Modifier) { askDefault = null; vm.update { it.copy(recordTo = RecStorage.INTERNAL) } }
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

private class Pending(val kind: RecStorage, val name: String)
