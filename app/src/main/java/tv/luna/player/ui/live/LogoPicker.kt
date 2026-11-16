package tv.luna.player.ui.live

import tv.luna.player.ui.theme.LocalTheme

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.files.SharedFile
import tv.luna.player.data.files.allFilesAccessIntent
import tv.luna.player.data.files.canPickFile
import tv.luna.player.data.files.displayName
import tv.luna.player.data.files.hasAllFilesAccess
import tv.luna.player.data.files.listSharedFiles
import tv.luna.player.data.files.pickFileIntent
import tv.luna.player.ui.FocusBox
import tv.luna.player.ui.MenuFrame
import tv.luna.player.ui.MenuItem

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")

/** Change logo… (spec §4.3): open-source matches, images on this device, Use automatic logo. Picking closes it. */
@Composable
fun LogoPicker(vm: LiveViewModel, ch: ChannelEntity, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<String>?>(null) }
    var files by remember { mutableStateOf<List<SharedFile>?>(null) }
    var filesAsked by remember { mutableStateOf(0) } // bumped when image access is granted: list again
    LaunchedEffect(ch.id) { candidates = runCatching { vm.logoCandidates(ch) }.getOrDefault(emptyList()) }
    LaunchedEffect(ch.id, filesAsked) { files = listSharedFiles(context, IMAGE_EXTS) }
    fun pickFile(uri: Uri, fileName: String?) {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        if (ext !in IMAGE_EXTS) { Toast.makeText(context, "That isn't a PNG, JPG or WEBP image", Toast.LENGTH_LONG).show(); return }
        vm.setCustomLogoFile(ch, uri, ext!!); onDismiss()
    }
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { pickFile(it, displayName(context, it)) }
    }
    // Android 13+: without all files access, the device's images are listed only once the app may read them.
    val photoPermission = android.Manifest.permission.READ_MEDIA_IMAGES
    var canReadPhotos by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(photoPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canReadPhotos = granted
        if (granted) filesAsked++
        // Denied for good: Android no longer shows the dialog, so the row would do nothing. Open the app's settings page.
        else if ((context as? android.app.Activity)?.shouldShowRequestPermissionRationale(photoPermission) == false) runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        }
    }
    MenuFrame("Logo for $name", onDismiss) { first ->
        var firstUsed = false
        fun firstOr(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(first) }

        Section("Open-source logos")
        when (val c = candidates) {
            null -> Note("Looking…")
            emptyList<String>() -> Note("None found")
            else -> c.forEach { url -> LogoRow(url, url.substringAfterLast('/'), firstOr()) { vm.setCustomLogoUrl(ch, url); onDismiss() } }
        }
        Section("On this device")
        if (remember { canPickFile(context, "image/*") }) {
            MenuItem("Browse files… (needs a file manager app)", firstOr()) { browse.launch(pickFileIntent("image/*")) }
        }
        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            val intent = remember { allFilesAccessIntent(context) }
            if (intent != null) MenuItem("Allow access to all files", firstOr()) { runCatching { context.startActivity(intent) } }
            if (!canReadPhotos) MenuItem("Allow access to images", firstOr()) { askPhotos.launch(photoPermission) }
        }
        when (val f = files) {
            null -> Unit
            emptyList<SharedFile>() -> Note("None found")
            else -> f.forEach { file -> LogoRow(file.uri, file.name, firstOr()) { pickFile(file.uri, file.name) } }
        }
        Spacer(Modifier.size(6.dp))
        MenuItem("Use automatic logo", firstOr()) { vm.useAutomaticLogo(ch); onDismiss() }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, fontSize = 13.sp, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

@Composable
private fun Note(text: String) = Text(text, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))

@Composable
private fun LogoRow(image: Any, label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (f) Color.White else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                AsyncImage(image, null, contentScale = ContentScale.Fit, modifier = Modifier.padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 14.sp, color = if (f) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
