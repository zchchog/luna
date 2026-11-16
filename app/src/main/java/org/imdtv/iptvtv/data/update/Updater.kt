package org.imdtv.iptvtv.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.imdtv.iptvtv.BuildConfig
import org.imdtv.iptvtv.IptvApp
import java.io.File
import java.io.IOException

private const val LATEST = "https://api.github.com/repos/zchchog/lunaro-tv/releases/latest"
private const val APK_NAME = "lunaro-tv.apk"
private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

@Serializable private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String)
@Serializable private data class GhRelease(@SerialName("tag_name") val tag: String, val body: String? = null, val assets: List<GhAsset> = emptyList())

data class Update(val version: String, val notes: String, val apkUrl: String)

/** True when release tag [tag] ("v0.2.0") is a newer version than [current] ("0.1.0"). */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String) = v.trim().trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * One-click updates from GitHub Releases: the latest release's lunaro-tv.apk goes to Android's installer, which asks
 * the user to confirm (and, the first time, to allow installs from Luna). Releases must keep the same signing key.
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val json: Json, private val prefs: DataStore<Preferences>) {
    val available = MutableStateFlow<Update?>(null)
    /** The update prompt is open. Opens by itself once per version; About › Version opens it any time. */
    val prompt = MutableStateFlow(false)
    /** "Downloading… 40%", "Installing…", or null when idle. */
    val progress = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private val later = stringPreferencesKey("update_later")
    @Volatile private var lastCheck = 0L

    /** Asks GitHub for the latest release; [manual] ignores the 6-hour spacing and "Later". Returns false on failure. */
    suspend fun check(manual: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!manual && System.currentTimeMillis() - lastCheck < CHECK_EVERY_MS) return@withContext true
        val release = try {
            http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                json.decodeFromString<GhRelease>(r.body!!.string())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.i("Updater", "Update check failed: $e") // offline or GitHub down: try again next time
            return@withContext false
        }
        lastCheck = System.currentTimeMillis()
        val apk = release.assets.find { it.name == APK_NAME }
        val u = if (apk != null && isNewer(release.tag, BuildConfig.VERSION_NAME))
            Update(release.tag.trimStart('v', 'V'), plainNotes(release.body.orEmpty()), apk.url) else null
        available.value = u
        if (u != null && (manual || prefs.data.first()[later] != u.version)) prompt.value = true
        true
    }

    /** "Later": the prompt stays closed until a newer version than this one comes out. */
    suspend fun later() {
        prompt.value = false
        available.value?.let { u -> prefs.edit { it[later] = u.version } }
    }

    suspend fun install(u: Update) = withContext(Dispatchers.IO) {
        if (progress.value != null) return@withContext
        error.value = null
        val file = File(context.cacheDir, APK_NAME)
        try {
            progress.value = "Downloading…"
            http.newCall(Request.Builder().url(u.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("download failed (HTTP ${r.code})")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input -> file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress.value = "Downloading… ${done * 100 / total}%"
                    }
                } }
            }
            progress.value = "Installing…"
            val installer = context.packageManager.packageInstaller
            val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            installer.openSession(id).use { s ->
                s.openWrite(APK_NAME, 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                // Mutable: the installer adds the status extras UpdateReceiver reads.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                reopenMarker(context).createNewFile()
                s.commit(PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags).intentSender)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("Updater", "Update failed", e)
            failed("Update failed: ${e.message ?: e.javaClass.simpleName}. Check your connection and try again.")
        } finally {
            file.delete()
        }
    }

    internal fun failed(message: String) { progress.value = null; error.value = message; prompt.value = true }

    /** The installer is up (or was cancelled). The prompt stays open behind it: Android TV closes the installer when the
     *  user goes to Settings to allow installs, and they come back to Update now. */
    internal fun waitingForUser() { progress.value = null }
}

/** Exists while a one-click update is installing: BootReceiver reopens the app once it's replaced. */
fun reopenMarker(context: Context) = File(context.noBackupFilesDir, "reopen-after-update")

/** Release notes as plain text: no Markdown headings, bold or links' brackets, at most ~600 characters. */
internal fun plainNotes(md: String): String = md.lines()
    .map { it.trimStart('#', ' ').replace("**", "").replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1") }
    .joinToString("\n").trim().let { if (it.length > 600) it.take(600).substringBeforeLast('\n') + "\n…" else it }

/** Android's installer reports here: show its confirm screen, or say why the install didn't happen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as IptvApp).container.updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                updater.waitingForUser()
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> updater.waitingForUser() // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.waitingForUser() // the user pressed Cancel
            else -> updater.failed("The update didn't install (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"}).")
        }
    }
}
