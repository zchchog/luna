package tv.luna.player.data.epg

import tv.luna.player.data.security.withLogin
import tv.luna.player.data.db.login

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.EpgChannelEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ProgrammeEntity
import okio.buffer
import okio.sink
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Downloads one XMLTV source and replaces its channels and programmes in a single transaction,
 * so a failed or truncated download leaves the previous guide intact. Streams end to end.
 */
class EpgImporter(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val parser: XmltvParser,
    private val now: () -> Long = System::currentTimeMillis,
    /** Test seam: receives each programme batch. Defaults to inserting it. */
    programmeSink: ((List<ProgrammeEntity>) -> Unit)? = null,
) {
    private val sink: (List<ProgrammeEntity>) -> Unit = programmeSink ?: { db.programmeDao().insertAllBlocking(it) }

    /** Returns the number of programmes stored. */
    suspend fun import(source: EpgSourceEntity, daysToKeep: Int, pastDays: Int, batchSize: Int = 2000): Int =
        withContext(Dispatchers.IO) {
            val t = now()
            val from = t - pastDays * DAY
            val to = t + daysToKeep * DAY
            // Spool to a temp file first so the DB write lock is never held during network I/O.
            val file = File.createTempFile("epg-${source.id}-", ".xml")
            try {
                val url = withLogin(source.url, db.providerDao().get(source.providerId)?.login())
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("EPG request failed: HTTP ${resp.code} ${resp.message}")
                    file.sink().buffer().use { it.writeAll(resp.body!!.source()) }
                }
                // The generation from the database: a caller's copy of the source may be older than the last import.
                val shown = db.epgDao().source(source.id)?.gen ?: return@withContext 0
                importFile(source, shown, file, from, to, t, batchSize)
            } finally {
                file.delete()
            }
        }

    /**
     * Writes the next programme generation in [batchSize] batches, each its own short transaction, then switches the
     * source to it in one tiny transaction and drops the old one in small deletes: user writes (favourites, hides…)
     * never wait behind a whole import, and the guide never shows a half-imported source.
     */
    private fun importFile(source: EpgSourceEntity, shown: Int, file: File, from: Long, to: Long, t: Long, batchSize: Int): Int =
        maybeGunzip(file.inputStream()).use { body ->
            val gen = shown + 1
            dropOtherGens(source.id, shown) // leftovers of an import that failed half-way
            var stored = 0
            val batch = ArrayList<ProgrammeEntity>(batchSize)
            val channels = ArrayList<EpgChannelEntity>() // a few thousand at most: written with the switch
            val skipped = parser.parse(
                body,
                onChannel = { c ->
                    // A channel with no display-name is still matchable by id.
                    c.displayNames.distinct().ifEmpty { listOf(c.id) }.forEach { n ->
                        channels += EpgChannelEntity(source.id, c.id, n, simpleNormalise(n), c.icon)
                    }
                },
                onProgramme = { p ->
                    if (p.stop >= from && p.start <= to) {
                        batch += ProgrammeEntity(
                            sourceId = source.id, epgChannelKey = ProgrammeEntity.key(source.id, p.channelId),
                            start = p.start, stop = p.stop, title = p.title, desc = p.desc,
                            episodeNum = p.episodeNum, icon = p.icon, gen = gen,
                        )
                        if (batch.size >= batchSize) { sink(ArrayList(batch)); stored += batch.size; batch.clear() }
                    }
                },
            )
            if (batch.isNotEmpty()) { sink(ArrayList(batch)); stored += batch.size }
            db.runInTransaction {
                db.epgDao().deleteChannelsBlocking(source.id)
                channels.chunked(batchSize).forEach { db.epgDao().insertChannelsBlocking(it) }
                db.epgDao().setGenBlocking(source.id, gen, t)
            }
            dropOtherGens(source.id, gen)
            Log.i(TAG, "Imported ${logUrl(source.url)}: $stored programmes, $skipped skipped")
            stored
        }

    private fun dropOtherGens(sourceId: Long, keep: Int) {
        while (db.programmeDao().deleteOtherGensBlocking(sourceId, keep, 5_000) > 0) Unit
    }

    companion object {
        const val TAG = "IptvEpg"
        /** Xtream EPG URLs carry the username and password in the query. */
        fun logUrl(url: String) = url.substringBefore('?')
        private const val DAY = 86_400_000L

        /** Gzip is detected by magic bytes, not by URL or headers. */
        internal fun maybeGunzip(input: InputStream): InputStream {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val b1 = buffered.read()
            val b2 = buffered.read()
            buffered.reset()
            return if (b1 == 0x1F && b2 == 0x8B) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
        }

        /** Rule-free normalisation stored on `epg_channel`; EpgMatcher applies the provider's rules itself. */
        internal fun simpleNormalise(name: String) = name.lowercase().replace(WHITESPACE, " ").trim()
        private val WHITESPACE = Regex("""[\s\p{Z}]+""")
    }
}
