package tv.luna.player.recording

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.luna.player.data.source.StreamRequest
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** How a capture ended. Stopping is coroutine cancellation (the file is flushed first). */
sealed interface CaptureEnd {
    /** The deadline was reached. */
    data object Finished : CaptureEnd
    /** No data for the retry window after data had flowed, or the HLS stream ended. */
    data object StreamLost : CaptureEnd
    /** The first request failed or closed before any data. */
    data class Refused(val code: Int?) : CaptureEnd
    data object StorageFull : CaptureEnd
    data class WriteError(val message: String) : CaptureEnd
    data class Unsupported(val why: String) : CaptureEnd
}

/**
 * Saves a live stream as received: MPEG-TS bodies are copied, HLS media playlists are polled and their segments appended
 * in order. No remux. Never logs the URL or headers (they can hold the login).
 */
class StreamCapture(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retryForMs: Long = 60_000,
) {
    private class WriteFailed(val end: CaptureEnd) : Exception()
    private class Ended(val end: CaptureEnd) : Exception()

    suspend fun capture(req: StreamRequest, out: OutputStream, deadline: Long, freeBytes: () -> Long?, onBytes: (Long) -> Unit): CaptureEnd =
        withContext(Dispatchers.IO) {
            if (req.url.substringBefore('?').endsWith(".mpd", ignoreCase = true)) return@withContext CaptureEnd.Unsupported("DASH")
            val w = Writer(out, freeBytes, onBytes)
            var hls: HttpUrl? = null // the media playlist, once known
            val seq = Seq() // outlives a failed poll, so segments already written are not appended again
            var failingSince: Long? = null
            var backoff = 1_000L
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (clock() >= deadline) return@withContext CaptureEnd.Finished
                    try {
                        val before = w.total
                        val playlist = hls
                        if (playlist == null) {
                            val parsed = fetch(req, req.url.toHttpUrl()) { r ->
                                if (!r.isSuccessful) throw if (w.total == 0L) Ended(CaptureEnd.Refused(r.code)) else IOException("HTTP ${r.code}")
                                val body = r.body!!.source()
                                body.request(16)
                                if (body.buffer.snapshot().utf8().removePrefix("\uFEFF").startsWith("#EXTM3U")) {
                                    parseHls(body.readUtf8(), r.request.url)?.let { it to r.request.url }
                                } else {
                                    copy(body.inputStream(), w, deadline)
                                    if (clock() < deadline) {
                                        if (w.total == 0L) throw Ended(CaptureEnd.Refused(null))
                                        throw IOException("stream closed")
                                    }
                                    null
                                }
                            }
                            when (val p = parsed?.first) {
                                is Hls.Master -> hls = p.variants.maxBy { it.bandwidth }.url
                                is Hls.Media -> { hls = parsed.second; appendMedia(p, req, w, seq, deadline) }
                                null -> Unit
                            }
                        } else {
                            val media = fetch(req, playlist) { r ->
                                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                                parseHls(r.body!!.string(), r.request.url) as? Hls.Media ?: throw IOException("not a media playlist")
                            }
                            appendMedia(media, req, w, seq, deadline)
                            if (clock() < deadline) sleep((media.targetSec * 500L).coerceIn(1_000, 6_000))
                        }
                        if (w.total > before) { failingSince = null; backoff = 1_000 }
                    } catch (e: IOException) {
                        if (w.total == 0L) return@withContext CaptureEnd.Refused(null)
                        hls = null // the media/token URL may have expired: retry from the original URL
                        val since = failingSince ?: clock().also { failingSince = it }
                        if (clock() - since >= retryForMs) return@withContext CaptureEnd.StreamLost
                        Log.i(TAG, "stream dropped, retrying: ${e.message}")
                        sleep(backoff); backoff = (backoff * 2).coerceAtMost(10_000)
                    }
                }
                @Suppress("UNREACHABLE_CODE") CaptureEnd.Finished
            } catch (e: Ended) {
                e.end
            } catch (e: WriteFailed) {
                e.end
            } finally {
                runCatching { out.flush() }
            }
        }

    private class Seq(var last: Long = -1L)

    /** Appends the segments after [seq]; updates it after each segment written. */
    private suspend fun appendMedia(m: Hls.Media, req: StreamRequest, w: Writer, seq: Seq, deadline: Long) {
        m.unsupported?.let { throw Ended(CaptureEnd.Unsupported(it)) }
        val newest = m.firstSeq + m.segments.size - 1
        // The encoder restarted (sequence went backwards): take the new numbering from its start.
        if (seq.last > newest) seq.last = m.firstSeq - 1
        m.segments.forEachIndexed { i, url ->
            val n = m.firstSeq + i
            if (n <= seq.last) return@forEachIndexed
            if (clock() >= deadline) return
            fetch(req, url) { r ->
                if (r.isSuccessful) copy(r.body!!.byteStream(), w, Long.MAX_VALUE)
                else Log.i(TAG, "segment skipped: HTTP ${r.code}") // gone already: skip it rather than stall
            }
            seq.last = n
        }
        if (m.ended) throw Ended(if (w.total == 0L) CaptureEnd.Refused(null) else CaptureEnd.StreamLost)
    }

    private fun copy(input: InputStream, w: Writer, deadline: Long) {
        val buf = ByteArray(64 * 1024)
        while (clock() < deadline) {
            val n = input.read(buf)
            if (n < 0) return
            w.write(buf, n)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    /** Runs [use] on the response; a stop (cancellation) cancels the call so a blocked read returns at once. */
    private suspend fun <T> fetch(req: StreamRequest, url: HttpUrl, use: (Response) -> T): T {
        val call: Call = http.newCall(Request.Builder().url(url).apply { req.headers.forEach { (k, v) -> header(k, v) } }.build())
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            return call.execute().use(use)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // cancelled: surface as cancellation, not a stream drop
            throw e
        } finally {
            handle?.dispose()
        }
    }

    private class Writer(private val out: OutputStream, private val freeBytes: () -> Long?, private val onBytes: (Long) -> Unit) {
        var total = 0L
            private set
        private var checkedBlock = -1L

        fun write(buf: ByteArray, n: Int) {
            val block = total / SPACE_CHECK
            if (block != checkedBlock) {
                checkedBlock = block
                freeBytes()?.let { if (it < MIN_FREE) throw WriteFailed(CaptureEnd.StorageFull) }
            }
            try {
                out.write(buf, 0, n)
            } catch (e: IOException) {
                val m = e.message.orEmpty()
                throw WriteFailed(if ("ENOSPC" in m || "No space" in m) CaptureEnd.StorageFull else CaptureEnd.WriteError(m.ifEmpty { "write error" }))
            }
            val before = total
            total += n
            if (total / MIB != before / MIB) {
                try { out.flush() } catch (e: IOException) { throw WriteFailed(CaptureEnd.WriteError(e.message ?: "write error")) }
                onBytes(total)
            }
        }
    }

    private companion object {
        const val TAG = "IptvRecord"
        const val MIB = 1L shl 20
        const val SPACE_CHECK = 32 * MIB
        const val MIN_FREE = 64 * MIB
    }
}
