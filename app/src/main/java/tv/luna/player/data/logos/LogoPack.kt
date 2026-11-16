package tv.luna.player.data.logos

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

/**
 * Channel id → pack file name (spec §4.2): a file named after the channel's tvgId (case-insensitive), else a file
 * whose logo key equals the channel's, preferring the channel's quality hint, then no quality, then the first by name.
 */
fun matchPack(files: List<String>, channels: List<ChannelEntity>): Map<Long, String> {
    val byBase = files.groupBy { it.substringBeforeLast('.').lowercase() }
    val byKey = files.mapNotNull { f -> logoKey(f.substringBeforeLast('.'))?.let { it to f } }.groupBy({ it.first.key }, { it })
    return channels.mapNotNull { ch ->
        val byTvg = ch.tvgId?.lowercase()?.let { byBase[it]?.minOrNull() }
        val file = byTvg ?: logoKey(ch.name)?.let { k ->
            val hits = byKey[k.key].orEmpty()
            (hits.filter { it.first.quality == k.quality }.ifEmpty { hits.filter { it.first.quality == null } }.ifEmpty { hits })
                .minByOrNull { it.second }?.second
        }
        file?.let { ch.id to it }
    }.toMap()
}

data class PackResult(val images: Int, val matched: Int)

class PackException(message: String) : Exception(message)

/** The user's logo pack per playlist, unpacked into `dir/<providerId>/<stamp>/` (a new stamp per import, so Coil never shows an old image). */
class LogoPackRepository(private val db: AppDatabase, private val dir: File, private val now: () -> Long = System::currentTimeMillis) {

    /** One pack change at a time: a second import (double OK) would delete the first one's folder under it. */
    private val lock = Mutex()

    private fun providerDir(providerId: Long) = File(dir, "$providerId")

    /** The current pack folder: the newest stamp. */
    private fun current(providerId: Long): File? =
        providerDir(providerId).listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name.toLongOrNull() ?: -1 }

    suspend fun import(providerId: Long, open: () -> InputStream): PackResult = lock.withLock {
        val target = withContext(Dispatchers.IO) { unpack(providerId, open) }
        val files = target.list().orEmpty().toList()
        val matches = try {
            val channels = db.channelDao().byProvider(providerId)
            val matches = withContext(Dispatchers.Default) { matchPack(files, channels) }  // ~46k channels: keep it off the main thread
            db.withTransaction {
                db.channelDao().clearPackLogos(providerId)
                matches.forEach { (id, f) -> db.channelDao().setPackLogo(id, File(target, f).absolutePath) }
            }
            matches
        } catch (e: Throwable) {
            // Not applied: the folder must go, or the next sync's rematch would take it as the current pack.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { target.deleteRecursively() }
            throw e
        }
        withContext(Dispatchers.IO) {
            providerDir(providerId).listFiles()?.filter { it != target }?.forEach { it.deleteRecursively() }
        }
        PackResult(files.size, matches.values.toSet().size)
    }

    private fun unpack(providerId: Long, open: () -> InputStream): File {
        var stamp = now()
        while (File(providerDir(providerId), "$stamp").exists()) stamp++
        val target = File(providerDir(providerId), "$stamp")
        if (!target.mkdirs()) throw PackException("Not enough space to import this pack")
        try {
            var images = 0
            open().use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val e = readStep { zip.nextEntry } ?: break
                        val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                        if (e.isDirectory || e.name.contains("__MACOSX/") || base.startsWith("._") || base.isEmpty()) continue
                        if (base.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) continue
                        val out = try { File(target, base).outputStream() } catch (x: IOException) { throw noSpace() }
                        out.use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = readStep { zip.read(buf) }
                                if (n < 0) break
                                try { o.write(buf, 0, n) } catch (x: IOException) { throw noSpace() }
                            }
                        }
                        images++
                    }
                }
            }
            if (images == 0) throw PackException("No images in this zip")
            return target
        } catch (e: Throwable) {
            target.deleteRecursively()
            throw e
        }
    }

    private fun noSpace() = PackException("Not enough space to import this pack")

    private inline fun <T> readStep(read: () -> T): T = try { read() } catch (e: ZipException) {
        throw PackException("This zip could not be read")
    } catch (e: EOFException) {
        throw PackException("This zip could not be read")
    } catch (e: IllegalArgumentException) { // bad entry name encoding
        throw PackException("This zip could not be read")
    }

    /** After a sync: new channels get their pack logo without a re-import. No pack → nothing. */
    suspend fun rematch(providerId: Long): Unit = lock.withLock {
        val folder = withContext(Dispatchers.IO) { current(providerId) } ?: return@withLock
        val files = withContext(Dispatchers.IO) { folder.list().orEmpty().toList() }
        val channels = db.channelDao().byProvider(providerId)
        val matches = withContext(Dispatchers.Default) { matchPack(files, channels) }
        db.withTransaction {
            channels.forEach { ch ->
                val path = matches[ch.id]?.let { File(folder, it).absolutePath }
                if (path != ch.packLogo) db.channelDao().setPackLogo(ch.id, path)
            }
        }
    }

    suspend fun remove(providerId: Long): Unit = lock.withLock {
        db.channelDao().clearPackLogos(providerId)
        withContext(Dispatchers.IO) { providerDir(providerId).deleteRecursively() }
    }

    fun observeCount(providerId: Long): Flow<Int> = db.channelDao().observePackCount(providerId)
}
