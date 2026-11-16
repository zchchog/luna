package tv.luna.player.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.luna.player.data.files.SharedFile
import tv.luna.player.data.files.hasAllFilesAccess
import tv.luna.player.data.files.listSharedFiles
import java.io.File
import java.time.LocalDate

fun backupFileName(date: LocalDate) = "iptv-backup-$date.json"

/** Saves to Download/ on a USB volume if one is mounted, else internal Downloads. Returns where, for the message. */
/** Saves to [folder] when one is set and readable with all files access; otherwise to Downloads / a USB stick. */
suspend fun writeBackup(context: Context, json: String, folder: String? = null): String = withContext(Dispatchers.IO) {
    val name = backupFileName(LocalDate.now())
    val dir = folder?.let(::File)?.takeIf { hasAllFilesAccess() && (it.isDirectory || it.mkdirs()) }
    if (dir != null) {
        // Same day twice: "name (1).json", like MediaStore does.
        val file = generateSequence(0) { it + 1 }.map { n -> File(dir, if (n == 0) name else name.replace(".json", " ($n).json")) }
            .first { !it.exists() }
        file.writeText(json)
        return@withContext "Saved ${file.name} to ${dir.path}"
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val usb = MediaStore.getExternalVolumeNames(context).firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        val volume = usb ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(volume), values) ?: error("Couldn't create $name")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames a clash to "name (1).json"; report the name it really used.
        val saved = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        "Saved $saved to ${if (usb != null) "the USB stick" else "Downloads"}"
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        File(dir, name).writeText(json)
        "Saved $name to Downloads"
    }
}

/** Backups in Downloads / on USB, newest first (files this app wrote are visible without storage permission). */
suspend fun listBackups(context: Context, folder: String? = null): List<SharedFile> = withContext(Dispatchers.IO) {
    val inFolder = folder?.let(::File)?.takeIf { hasAllFilesAccess() }?.let { backupFilesIn(it, folder) }.orEmpty()
    (inFolder + listSharedFiles(context, setOf("json")).filter { it.name.startsWith("iptv-backup-") })
        .distinctBy { it.uri }.sortedByDescending { it.modified }
}

/** Backup files (*.json) directly in [dir]. */
fun backupFilesIn(dir: File, label: String = dir.path): List<SharedFile> =
    dir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("json", true) }
        .map { SharedFile(it.name, android.net.Uri.fromFile(it), label, it.length(), it.lastModified()) }

suspend fun readBackup(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw BackupException("Not a readable backup file")
}
