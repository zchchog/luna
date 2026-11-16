package tv.luna.player.recording

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.login
import tv.luna.player.data.source.streamRequest
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * One recording from start to its final row. [otherStreams]: another stream of this app was open at the start (a
 * refusal then means the provider allows no second one). Stopping = cancelling the caller; what was saved is kept.
 */
suspend fun runRecording(id: Long, db: AppDatabase, store: RecordingStore, capture: StreamCapture, otherStreams: () -> Boolean,
                         now: () -> Long = System::currentTimeMillis): RecStatus {
    val dao = db.recordingDao()
    val row = dao.get(id) ?: return RecStatus.FAILED
    suspend fun finish(status: RecStatus, reason: String?, location: String?, bytes: Long): RecStatus {
        // The row may have been deleted meanwhile; then there's nothing to write.
        dao.get(id)?.let { dao.update(it.copy(status = status, statusReason = reason, actualEnd = now(), location = location, bytes = bytes)) }
        return status
    }
    val ch = row.channelId?.let { db.channelDao().get(it) } ?: return finish(RecStatus.FAILED, "Channel no longer in the playlist", null, 0)
    val p = db.providerDao().get(ch.providerId) ?: return finish(RecStatus.FAILED, "Channel no longer in the playlist", null, 0)
    val others = otherStreams()
    val (location, raw) = try {
        store.create(recordingFileName(row.channelName, row.title, now()))
    } catch (e: Exception) {
        Log.w(TAG, "can't create the recording file: $e")
        return finish(RecStatus.FAILED, "Failed — couldn't save to ${store.kind.label()}: ${e.message}", null, 0)
    }
    val out = Counting(raw)
    dao.update(row.copy(status = RecStatus.RECORDING, actualStart = now(), storage = store.kind, location = location))
    suspend fun done(status: RecStatus, reason: String?): RecStatus {
        runCatching { out.close() }
        if (out.count == 0L) { runCatching { store.delete(location) }; return finish(status, reason, null, 0) }
        return finish(status, reason, location, out.count)
    }
    return try {
        val end = capture.capture(streamRequest(ch.streamUrl, p.userAgent, p.login()), out, row.scheduledEnd, store::freeBytes) { b ->
            runBlocking { dao.setBytes(id, b) }
        }
        when (end) {
            CaptureEnd.Finished -> done(RecStatus.DONE, null)
            CaptureEnd.StreamLost -> done(RecStatus.STOPPED, "Stopped — stream lost")
            is CaptureEnd.Refused -> done(RecStatus.FAILED, if (others) "Failed — provider refused a second stream"
                else "Failed — the channel didn't start" + (end.code?.let { " (HTTP $it)" } ?: ""))
            CaptureEnd.StorageFull -> done(RecStatus.STOPPED, "Stopped — storage full")
            is CaptureEnd.WriteError -> done(RecStatus.STOPPED, "Stopped — ${end.message}")
            is CaptureEnd.Unsupported -> done(RecStatus.FAILED, "This channel can't be recorded (${end.why})")
        }
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            // Stopped at (or after) its end, e.g. handed over to the next programme on the channel: that's a finished one.
            val endedNormally = (dao.get(id)?.scheduledEnd ?: Long.MAX_VALUE) <= now() + 1_000
            done(if (endedNormally) RecStatus.DONE else RecStatus.STOPPED, null)
        }
        throw e
    }
}

fun RecStorage.label() = when (this) { RecStorage.INTERNAL -> "internal storage"; RecStorage.USB -> "the USB drive"; RecStorage.SMB -> "the network share" }

private class Counting(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}

private const val TAG = "IptvRecord"
