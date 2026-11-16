package tv.luna.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import tv.luna.player.data.backup.Backup
import tv.luna.player.data.settings.themeIdOf
import tv.luna.player.ui.theme.LocalTheme

/**
 * Back up now; Restore → backups found → summary → a login per Xtream playlist (Skip leaves it out) → progress → result.
 * "Back up now" and "Restore" stay put while the list/form below them changes, so focus always has a row to return to.
 */
@Composable
fun BackupRows(vm: SettingsViewModel, first: Modifier) {
    val status by vm.backupStatus.collectAsState()
    val files by vm.backupFiles.collectAsState()
    val pending by vm.pendingRestore.collectAsState()
    val running by vm.restoreRunning.collectAsState()
    var listing by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf<BrowseMode?>(null) }
    val settings by vm.settings.collectAsState()
    val folderRow = remember { FocusRequester() }
    // Rows below come and go; focus parks on "Backup folder" (always there) first, or it falls to the group list.
    fun park() = runCatching { folderRow.requestFocus() }
    val restoreRow = remember { FocusRequester() }
    fun backToRestore() = runCatching { restoreRow.requestFocus() }
    var refocusRestore by remember { mutableStateOf(false) }
    LaunchedEffect(refocusRestore) { if (refocusRestore) { withFrameNanos { }; backToRestore(); refocusRestore = false } }

    status?.let { Text(it, fontSize = 15.sp, color = Color.White, modifier = Modifier.padding(bottom = 8.dp)) }
    SettingRow("Back up now", "Save playlists, categories, favourites and settings to a file — logins are never saved", null, first) { vm.backUpNow() }
    SettingRow("Backup folder", "Where backups are saved; OK to choose", settings.backupFolder?.substringAfterLast('/') ?: "Downloads",
        Modifier.focusRequester(folderRow)) { listing = false; browsing = BrowseMode.SAVE }
    browsing?.let { mode ->
        FolderBrowser(mode, park = ::park, onClose = { park(); browsing = null; if (mode == BrowseMode.RESTORE) refocusRestore = true },
            onFolder = { vm.setBackupFolder(it) }, onFile = { vm.openBackup(it); listing = false })
        return
    }
    SettingRow("Restore", "Choose a backup from Downloads or a USB stick", if (listing || pending != null) "▾" else "›",
        Modifier.focusRequester(restoreRow), busy = running) {
        if (running) return@SettingRow
        vm.cancelRestore(); listing = !listing; if (listing) vm.refreshBackups()
    }
    val b = pending
    when {
        running -> Unit
        b != null -> RestoreForm(b, vm, park = { backToRestore() }) { backToRestore(); vm.cancelRestore() }
        listing -> {
            files.forEach { f -> SettingRow(f.name, f.volume, null) { backToRestore(); listing = false; vm.openBackup(f) } }
            if (files.isEmpty()) Text("No backups in Downloads, your backup folder or on USB sticks", fontSize = 14.sp, color = LocalTheme.current.colors.muted)
            SettingRow("Browse for a backup…", "Find a backup file in any folder", "›") { park(); browsing = BrowseMode.RESTORE }
        }
    }
}

@Composable
private fun RestoreForm(b: Backup, vm: SettingsViewModel, park: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val asks = remember(b) { b.playlists.indices.filter { needsLogin(b.playlists[it]) && !vm.hasSavedLogin(b.playlists[it]) } }
    var step by remember(b) { mutableStateOf(0) }
    val logins = remember(b) { mutableStateMapOf<Int, Pair<String, String>>() }
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(step) { withFrameNanos { }; runCatching { firstRow.requestFocus() } }

    val names = b.playlists.joinToString { it.name }.ifEmpty { "none" }
    Text("This backup: ${b.playlists.size} playlist(s) — $names · settings (theme ${themeIdOf(b.settings.theme).label})",
        fontSize = 14.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(top = 8.dp))
    if (step < asks.size) {
        val i = asks[step]
        val p = b.playlists[i]
        var user by remember(step) { mutableStateOf("") }
        var pass by remember(step) { mutableStateOf("") }
        Text("Login for ${p.name}", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
        // The password keyboard has the number row many providers' logins need (as the playlist form).
        EditRow("Username", "As given by your provider", user.ifEmpty { "Not set" }, user, Modifier.focusRequester(firstRow),
            keyboard = androidx.compose.ui.text.input.KeyboardType.Password) { user = it }
        EditRow("Password", "As given by your provider", if (pass.isEmpty()) "Not set" else "••••••", pass,
            keyboard = androidx.compose.ui.text.input.KeyboardType.Password, secret = true) { pass = it }
        SettingRow(if (step == asks.size - 1) "Next: review" else "Next playlist",
            if (user.isBlank() || pass.isBlank()) "Enter the username and password first" else "Use this login for ${p.name}", null) {
            // The last step swaps these rows out: park focus first, or it falls to the group list (and switches group).
            if (user.isNotBlank() && pass.isNotBlank()) { logins[i] = user to pass; if (step == asks.size - 1) park(); step++ }
        }
        SettingRow("Skip", "Leave ${p.name} out of the restore", null) { logins.remove(i); if (step == asks.size - 1) park(); step++ }
    } else {
        val left = asks.filter { it !in logins }.map { b.playlists[it].name }
        SettingRow("Restore now", if (left.isEmpty()) "Playlists are updated from the provider, then your choices applied"
            else "Leaving out: ${left.joinToString()}", null, Modifier.focusRequester(firstRow)) { park(); vm.restore(b, logins.toMap()) }
        SettingRow("Cancel", "Change nothing", null) { onCancel() }
    }
}

enum class BrowseMode { SAVE, RESTORE }

/**
 * In-app folder browser (Google TV has no system folder picker): drives first, then folders. SAVE picks a folder for
 * backups; RESTORE picks a .json file. Needs all files access; without it, says so and offers the grant screen.
 */
@Composable
private fun FolderBrowser(mode: BrowseMode, park: () -> Unit, onClose: () -> Unit, onFolder: (String?) -> Unit, onFile: (tv.luna.player.data.files.SharedFile) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var access by remember { mutableStateOf(tv.luna.player.data.files.hasAllFilesAccess()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { access = tv.luna.player.data.files.hasAllFilesAccess(); onPauseOrDispose { } }
    var dir by remember { mutableStateOf<java.io.File?>(null) } // null = the list of drives
    val roots = remember { tv.luna.player.data.files.storageRoots(context) }
    val top = remember { FocusRequester() }
    LaunchedEffect(dir, access) { withFrameNanos { }; runCatching { top.requestFocus() } }
    // Every row is replaced on a folder change: park focus first (see BackupRows), then the new top row takes it.
    fun go(to: java.io.File?) { park(); dir = to }
    fun up() { val d = dir ?: return onClose(); go(if (roots.any { it.first == d }) null else d.parentFile) }
    BackHandler { up() }

    Text(if (mode == BrowseMode.SAVE) "Choose the backup folder" else "Choose a backup file", fontSize = 15.sp, color = Color.White,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    if (!access) {
        val grant = remember { tv.luna.player.data.files.allFilesAccessIntent(context) }
        if (grant != null) SettingRow("Allow access to all files", "Needed to browse folders; returns here afterwards", "›",
            Modifier.focusRequester(top)) { context.startActivity(grant) }
        else Text("This box can't give apps access to all files: backups are saved to Downloads.", fontSize = 14.sp,
            color = LocalTheme.current.colors.muted)
        SettingRow("Cancel", "", null, if (grant == null) Modifier.focusRequester(top) else Modifier) { onClose() }
        return
    }
    val d = dir
    if (d == null) {
        roots.forEachIndexed { i, (root, label) ->
            SettingRow(label, root.path, "›", if (i == 0) Modifier.focusRequester(top) else Modifier) { go(root) }
        }
        if (mode == BrowseMode.SAVE) SettingRow("Use Downloads", "The default: Downloads, or a USB stick when one is plugged in", null) { onFolder(null); onClose() }
        SettingRow("Cancel", "", null) { onClose() }
        return
    }
    val folders = remember(d) { d.listFiles().orEmpty().filter { it.isDirectory && !it.isHidden }.sortedBy { it.name.lowercase() } }
    val files = remember(d, mode) { if (mode == BrowseMode.RESTORE) tv.luna.player.data.backup.backupFilesIn(d).sortedByDescending { it.modified } else emptyList() }
    SettingRow("↑ Up", d.path, null, Modifier.focusRequester(top)) { up() }
    if (mode == BrowseMode.SAVE) SettingRow("Use this folder", d.path, null) { onFolder(d.path); onClose() }
    files.forEach { f -> SettingRow(f.name, "Restore from this file", null) { onFile(f); onClose() } }
    folders.forEach { f -> SettingRow("📁 " + f.name, "Open folder", "›") { go(f) } }
    if (folders.isEmpty() && files.isEmpty()) Text(if (mode == BrowseMode.SAVE) "No folders here" else "No folders or backup files here",
        fontSize = 14.sp, color = LocalTheme.current.colors.muted)
}
