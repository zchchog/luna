package tv.luna.player.data.logos

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.luna.player.data.db.AppDatabase
import java.io.File
import java.io.InputStream
import tv.luna.player.data.db.ChannelEntity

const val TREE_URL = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
private const val WEEK_MS = 7 * 24 * 3600_000L
private const val TAG = "LogoRepository"

/** tv-logos file list (cached for a week in [dir]) and the per-channel built-in logo match. */
class LogoRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val treeUrl: String = TREE_URL,
) {
    private val cache get() = File(dir, "tvlogos-paths.txt")
    @Volatile private var memo: Pair<Long, TvLogoIndex>? = null

    /** The cached list, refetched when a week old; a failed fetch (403 rate limit, no network) keeps the stale one. */
    suspend fun index(): TvLogoIndex? = withContext(Dispatchers.IO) {
        val f = cache
        if (!f.exists() || now() - f.lastModified() >= WEEK_MS) fetch()?.let { paths ->
            dir.mkdirs()
            val tmp = File(dir, "tvlogos-paths.tmp")
            tmp.writeText(paths.joinToString("\n"))
            tmp.renameTo(f)
            f.setLastModified(now())
            memo = null
        }
        if (!f.exists()) return@withContext null
        memo?.takeIf { it.first == f.lastModified() }?.second
            ?: TvLogoIndex(f.readLines().filter { it.isNotBlank() }).also { memo = f.lastModified() to it }
    }

    private fun fetch(): List<String>? = try {
        http.newCall(Request.Builder().url(treeUrl).build()).execute().use { r ->
            if (!r.isSuccessful) { Log.w(TAG, "tv-logos list: HTTP ${r.code}, try next time"); null }
            else parseTree(r.body!!.string()).takeIf { it.isNotEmpty() }?.also { Log.i(TAG, "tv-logos list: ${it.size} files") }
        }
    } catch (e: Exception) {
        Log.w(TAG, "tv-logos list: $e, try next time"); null
    }

    private val customDir get() = File(dir, "custom")

    /** A hand-picked file: copied to `custom/<channelId>-<now>.<ext>` (a new name, so Coil never shows the old image). */
    suspend fun setCustomFromFile(channelId: Long, open: () -> InputStream, ext: String) {
        val target = withContext(Dispatchers.IO) {
            customDir.mkdirs()
            val f = File(customDir, "$channelId-${now()}.$ext")
            open().use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }
        db.channelDao().setCustomLogo(channelId, target.absolutePath)
        deleteOldCustom(channelId, keep = target)
    }

    suspend fun setCustomUrl(channelId: Long, url: String) {
        db.channelDao().setCustomLogo(channelId, url)
        deleteOldCustom(channelId, keep = null)
    }

    /** "Use automatic logo". */
    suspend fun clearCustom(channelId: Long) {
        db.channelDao().setCustomLogo(channelId, null)
        deleteOldCustom(channelId, keep = null)
    }

    /** Hand-picked logo files no channel uses any more (its channel or playlist was deleted). A minute's grace: a file being
     *  picked is written before its channel row points at it. */
    suspend fun pruneCustom() {
        val used = db.channelDao().customLogos().toHashSet()
        withContext(Dispatchers.IO) { customDir.listFiles()?.filter { it.absolutePath !in used && now() - it.lastModified() > 60_000 }?.forEach { it.delete() } }
    }

    private suspend fun deleteOldCustom(channelId: Long, keep: File?) = withContext(Dispatchers.IO) {
        customDir.listFiles()?.filter { it.name.startsWith("$channelId-") && it != keep }?.forEach { it.delete() }
    }

    /** Up to 8 tv-logos URLs for "Change logo…"; empty when the list isn't available. */
    suspend fun candidatesFor(ch: ChannelEntity): List<String> {
        val index = index() ?: return emptyList()
        val key = logoKey(ch.name) ?: return emptyList()
        val country = ch.categoryId?.let { db.categoryDao().get(it)?.country }
        return index.candidates(key, countryFolders(country, index)).map { TV_LOGOS_RAW + it }
    }

    /** Matches every channel of the provider; returns how many have a tv-logos file. No list → nothing changes. */
    suspend fun refreshBuiltin(providerId: Long): Int {
        val index = index() ?: return 0
        val country = db.categoryDao().allKinds(providerId).associate { it.id to it.country }
        val channels = db.channelDao().byProvider(providerId)
        // ~46k channels: off the main thread, or the TV reports "not responding" and closes the app.
        val matched = withContext(Dispatchers.Default) {
            val folders = HashMap<String?, List<String>>() // per country, not per channel
            channels.associate { ch ->
                ch.id to logoKey(ch.name)?.let { k -> index.match(k, folders.getOrPut(ch.categoryId?.let(country::get)) { countryFolders(ch.categoryId?.let(country::get), index) }) }
            }
        }
        db.withTransaction {
            channels.forEach { ch -> matched[ch.id].let { if (it != ch.builtinLogo) db.channelDao().setBuiltinLogo(ch.id, it) } }
        }
        return matched.values.count { it != null }.also { Log.i(TAG, "tv-logos: $it of ${channels.size} channels matched") }
    }
}
