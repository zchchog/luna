package tv.luna.player.recording

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import android.net.Uri

/** Settings › Recording › Network share. [folder] is inside the share ("" = its top). */
data class SmbSettings(val server: String, val share: String, val folder: String, val user: String, val password: String) {
    val complete get() = server.isNotBlank() && share.isNotBlank()
}

fun smbLocation(server: String, share: String, path: String) = "smb://$server/$share/$path"

/** (server, share, path inside the share) of an smb:// location; null if it isn't one. */
fun parseSmbLocation(location: String): Triple<String, String, String>? {
    if (!location.startsWith("smb://")) return null
    val parts = location.removePrefix("smb://").split('/', limit = 3)
    if (parts.size < 3 || parts.any { it.isEmpty() }) return null
    return Triple(parts[0], parts[1], parts[2])
}

/** An open share; closing it closes the session, connection and client. */
private class Connected(val share: DiskShare, private val closers: List<AutoCloseable>) : Closeable {
    override fun close() = closers.asReversed().forEach { runCatching { it.close() } }
}

private fun connect(s: SmbSettings, server: String = s.server, shareName: String = s.share): Connected {
    val client = SMBClient(com.hierynomus.smbj.SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build())
    try {
        val conn = client.connect(server)
        val auth = if (s.user.isBlank()) AuthenticationContext.guest() else {
            // "DOMAIN\user" logins are common on Windows shares.
            val (domain, user) = if ('\\' in s.user) s.user.substringBefore('\\') to s.user.substringAfter('\\') else "" to s.user
            AuthenticationContext(user, s.password.toCharArray(), domain)
        }
        val session = conn.authenticate(auth)
        val share = session.connectShare(shareName) as? DiskShare ?: throw IOException("$shareName is not a folder share")
        return Connected(share, listOf(client, conn, session, share))
    } catch (e: Exception) {
        client.close()
        throw e
    }
}

private fun pathIn(folder: String, name: String) = folder.trim('/', '\\').replace('/', '\\').let { if (it.isEmpty()) name else "$it\\$name" }

private fun DiskShare.makeFolders(folder: String) {
    var path = ""
    folder.trim('/', '\\').split('/', '\\').filter { it.isNotEmpty() }.forEach { part ->
        path = if (path.isEmpty()) part else "$path\\$part"
        if (!folderExists(path)) mkdir(path)
    }
}

private fun DiskShare.create(path: String): SmbFile = openFile(path, setOf(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
    SMB2CreateDisposition.FILE_CREATE, setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))

/** Recordings written straight to a network share (SMB 2/3). */
class SmbStore(private val s: SmbSettings) : RecordingStore {
    override val kind = RecStorage.SMB

    override fun create(fileName: String): Pair<String, OutputStream> {
        val c = connect(s)
        try {
            c.share.makeFolders(s.folder)
            val name = uniqueName(fileName) { c.share.fileExists(pathIn(s.folder, it)) }
            val path = pathIn(s.folder, name)
            val file = c.share.create(path)
            val out = object : FilterOutputStream(file.outputStream) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { try { super.close() } finally { runCatching { file.close() }; c.close() } }
            }
            return smbLocation(s.server, s.share, path.replace('\\', '/')) to out
        } catch (e: Exception) {
            c.close()
            throw e
        }
    }

    override fun delete(location: String) {
        val (server, share, path) = parseSmbLocation(location) ?: return
        connect(s, server, share).use { it.share.rm(path.replace('/', '\\')) }
    }

    override fun freeBytes(): Long? = runCatching { connect(s).use { it.share.shareInformation.freeSpace } }.getOrNull()
}

/** Settings › Test: writes and removes a small file. Null = it worked; else what went wrong. */
fun testShare(s: SmbSettings): String? = try {
    connect(s).use { c ->
        c.share.makeFolders(s.folder)
        val path = pathIn(s.folder, ".luna-test")
        c.share.openFile(path, setOf(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            .use { f -> f.outputStream.use { it.write("Luna".toByteArray()) } }
        c.share.rm(path)
    }
    null
} catch (e: Exception) {
    e.message ?: e::class.simpleName
}

/** Plays smb:// recordings: reads at any offset, so seeking works. */
@OptIn(UnstableApi::class)
class SmbDataSource(private val settings: () -> SmbSettings) : BaseDataSource(true) {
    private var c: Connected? = null
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val (server, share, path) = parseSmbLocation(dataSpec.uri.toString()) ?: throw IOException("Not a network share address")
        transferInitializing(dataSpec)
        val conn = connect(settings(), server, share).also { c = it }
        val f = conn.share.openFile(path.replace('/', '\\'), setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).also { file = it }
        uri = dataSpec.uri
        val size = f.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = file!!.read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }; file = null
        c?.close(); c = null
        if (uri != null) { uri = null; transferEnded() }
    }
}
