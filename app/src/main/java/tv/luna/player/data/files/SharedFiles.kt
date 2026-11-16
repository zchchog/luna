package tv.luna.player.data.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file in Downloads or on a removable volume (logo packs, logo images; backups in Stage 6). */
data class SharedFile(val name: String, val uri: Uri, val volume: String, val size: Long, val modified: Long = 0)

private val WALK_DIRS = listOf("", "Download", "Downloads", "logos")

fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun canWalk(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
    else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

/**
 * Files with one of [extensions] (lower case, no dot), newest first. With all files access (or the read
 * permission on Android 10 and older) every volume's top level and Download/, Downloads/, logos/ are listed;
 * otherwise only what MediaStore shows this app.
 */
suspend fun listSharedFiles(context: Context, extensions: Set<String>): List<SharedFile> = withContext(Dispatchers.IO) {
    val files = if (canWalk(context)) walk(context, extensions) else mediaStore(context, extensions)
    files.distinctBy { it.uri }.sortedByDescending { it.modified }
}

/** Each mounted volume's top folder with its name ("Internal shared storage", a USB stick's label). */
fun storageRoots(context: Context): List<Pair<File, String>> =
    context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { v ->
        val dir = when {
            Build.VERSION.SDK_INT >= 30 -> v.directory
            v.isPrimary -> Environment.getExternalStorageDirectory()
            else -> v.uuid?.let { File("/storage/$it") }
        }
        dir?.let { it to v.getDescription(context) }
    }

private fun walk(context: Context, extensions: Set<String>): List<SharedFile> {
    val roots = storageRoots(context)
    return roots.flatMap { (root, label) ->
        WALK_DIRS.flatMap { sub ->
            (if (sub.isEmpty()) root else File(root, sub)).listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in extensions }
                .map { SharedFile(it.name, Uri.fromFile(it), label, it.length(), it.lastModified()) }
        }
    }
}

private fun mediaStore(context: Context, extensions: Set<String>): List<SharedFile> {
    if (Build.VERSION.SDK_INT < 29) return emptyList()
    return MediaStore.getExternalVolumeNames(context).flatMap { v ->
        val base = MediaStore.Files.getContentUri(v)
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED)
        val label = if (v == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Internal storage" else "USB"
        runCatching {
            context.contentResolver.query(base, cols, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name.substringAfterLast('.', "").lowercase() !in extensions) continue
                        add(SharedFile(name, ContentUris.withAppendedId(base, c.getLong(0)), label, c.getLong(2), c.getLong(3) * 1000))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** The system screen that grants all files access, or null when this device has none (some Google TV builds). */
fun allFilesAccessIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < 30) return null
    return listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    ).firstOrNull { it.resolveActivity(context.packageManager) != null }
}

private fun getContent(mime: String) = Intent(Intent.ACTION_GET_CONTENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)

/**
 * Offers the built-in file manager and any file manager app (ES File Explorer, X-plore, MiXplorer). Check the picked
 * name's extension. Use a specific type ("application/zip"): on Google TV the any-type request goes to the photo picker, which loops.
 */
fun pickFileIntent(mime: String): Intent = Intent.createChooser(getContent(mime), "Choose a file manager")
    // The photo picker hands off to DocumentsUI, which Google TV lacks, and the two relaunch each other for ever.
    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, PHOTO_PICKER.map { android.content.ComponentName(it, "com.android.providers.media.photopicker.PhotoPickerGetContentActivity") }.toTypedArray())

private val PHOTO_PICKER = listOf("com.google.android.providers.media.module", "com.android.providers.media.module")

fun canPickFile(context: Context, mime: String): Boolean = getContent(mime).resolveActivity(context.packageManager) != null

/** The picked file's name (content URIs ask the provider; file URIs use the path). */
fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
