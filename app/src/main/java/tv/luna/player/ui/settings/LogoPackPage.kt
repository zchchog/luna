package tv.luna.player.ui.settings

import tv.luna.player.ui.theme.LocalTheme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import tv.luna.player.data.files.allFilesAccessIntent
import tv.luna.player.data.files.canPickFile
import tv.luna.player.data.files.displayName
import tv.luna.player.data.files.hasAllFilesAccess
import tv.luna.player.data.files.pickFileIntent

private const val ZIP = "application/zip"

/** Import logo pack (spec §4.2), inside the Channel logos page: Browse files…, all files access, the .zip files found, Remove. */
@Composable
fun LogoPackSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val status by vm.packStatus.collectAsState()
    val files by vm.zipFiles.collectAsState()
    val count by vm.packCount.collectAsState()
    var access by remember { mutableStateOf(canRead(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Back from the system access screen or a file manager: look again.
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { access = canRead(context); vm.refreshZips() } }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { vm.importPack(it, displayName(context, it)) }
    }
    val askRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it; vm.refreshZips() }
    var confirmRemove by remember { mutableStateOf(false) }
    fun firstOr(): Modifier = Modifier

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        status?.let { Text(it, fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(bottom = 8.dp)) }
        if (remember { canPickFile(context, ZIP) }) {
            SettingRow("Browse files…", "Needs a file manager app such as X-plore or MiXplorer", null, firstOr()) { pick.launch(pickFileIntent(ZIP)) }
        }
        if (!access) {
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = remember { allFilesAccessIntent(context) }
                SettingRow("Allow access to all files",
                    if (intent != null) "So the app can find .zip files in Downloads and on USB sticks by itself"
                    else "Not available on this device — use Browse files…", null, firstOr()) {
                    intent?.let { runCatching { context.startActivity(it) } }
                }
            } else {
                SettingRow("Allow access to files", "So the app can find .zip files in Downloads and on USB sticks", null, firstOr()) {
                    askRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
        files.forEach { f ->
            SettingRow(f.name, "${f.volume} · ${"%.1f".format(f.size / 1_048_576.0)} MB", null, firstOr()) { vm.importPack(f.uri, f.name) }
        }
        if (files.isEmpty() && access) Text("No .zip files in Downloads or on USB sticks", fontSize = 14.sp, color = LocalTheme.current.colors.muted)
        if (count > 0) {
            SettingRow("Remove logo pack",
                if (confirmRemove) "Remove your logo pack? Channels go back to their other logos. OK to confirm." else "$count logos in use",
                null, firstOr()) {
                // This row disappears: move focus up first, or it falls to the group list and switches group.
                if (confirmRemove) { focus.moveFocus(FocusDirection.Up); vm.removePack(); confirmRemove = false } else confirmRemove = true
            }
        }
    }
}

private fun canRead(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) hasAllFilesAccess()
    else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
