package tv.luna.player.recording

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/** Where recordings are written. Locations are file paths or smb:// addresses (see SmbStore). */
interface RecordingStore {
    val kind: RecStorage
    /** A new file named [fileName] (or "… (2).ts" if taken): its location and an open stream. */
    fun create(fileName: String): Pair<String, OutputStream>
    fun delete(location: String)
    fun freeBytes(): Long?
}

/** Internal storage or a USB drive: a folder this app owns, so no storage permission is needed. */
class FileStore(override val kind: RecStorage, private val dir: File) : RecordingStore {
    override fun create(fileName: String): Pair<String, OutputStream> {
        dir.mkdirs()
        val f = File(dir, uniqueName(fileName) { File(dir, it).exists() })
        return f.path to FileOutputStream(f)
    }
    override fun delete(location: String) { File(location).delete() }
    override fun freeBytes(): Long? = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace?.takeIf { it > 0 }
}

fun internalDir(context: Context) = File(context.filesDir, "recordings")

/** Each mounted removable drive's folder for this app (Android/data/<app>/files/Recordings on the drive). */
fun usbDirs(context: Context): List<File> = context.getExternalFilesDirs(null).drop(1).filterNotNull()
    .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.map { File(it, "Recordings") }

private const val BYTES_PER_HD_HOUR = 2_000_000_000.0

/** "About 3.8 GB free ≈ 1 h 54 min HD" (about 2 GB per hour of HD). */
fun freeSpaceText(bytes: Long?): String {
    if (bytes == null) return "Free space unknown"
    val minutes = (bytes / BYTES_PER_HD_HOUR * 60).toLong()
    val time = when { minutes < 60 -> "$minutes min"; minutes % 60 == 0L -> "${minutes / 60} h"; else -> "${minutes / 60} h ${minutes % 60} min" }
    return "About ${String.format(Locale.UK, "%.1f", bytes / 1e9)} GB free ≈ $time HD"
}

/** A recording's file (path or smb://), as opposed to a provider stream the player is showing. */
fun isRecordingLocation(url: String) = url.startsWith("/") || url.startsWith("smb://")

/** Writes and removes a small file in [dir]; null when that works, otherwise the reason it didn't. */
fun testFolder(dir: File): String? = runCatching {
    dir.mkdirs()
    val f = File(dir, ".test")
    f.writeText("ok"); check(f.delete()) { "couldn't remove the test file" }
}.exceptionOrNull()?.let { it.message ?: "not writable" }
