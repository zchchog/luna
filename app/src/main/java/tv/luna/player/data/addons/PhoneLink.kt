package tv.luna.player.data.addons

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Add from your phone": while the page is open, a tiny web page on the TV (home network only) where a phone can
 * paste an add-on link. The address carries a random code, so only someone who scanned the QR code can use it.
 */
class PhoneLink(private val onLink: suspend (String) -> String, private val onCollection: suspend (String) -> String) {
    private val code = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var server: ServerSocket? = null

    /** The address to show (null when the TV has no home-network address). */
    var address: String? = null
        private set

    /** Runs until cancelled; [onAdded] gets each add-on's name (or an error message). */
    suspend fun run(onReady: (String?) -> Unit, onAdded: (String) -> Unit) = coroutineScope {
        val ip = localIp()
        if (ip == null) { onReady(null); return@coroutineScope }
        val s = withContext(Dispatchers.IO) { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) } }
        server = s
        address = "http://$ip:${s.localPort}/$code"
        onReady(address)
        try {
            while (isActive) {
                val client = withContext(Dispatchers.IO) { runCatching { s.accept() }.getOrNull() } ?: break
                launch(Dispatchers.IO) { runCatching { handle(client, onAdded) }.onFailure { Log.w("LunaPhoneLink", "request failed", it) } }
            }
        } finally { stop() }
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private suspend fun handle(socket: Socket, onAdded: (String) -> Unit) { socket.use { sock ->
        sock.soTimeout = 10_000
        val input = sock.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, 8_000_000) ?: 0
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val body = if (method == "POST" && length > 0) ByteArray(length).also { buf ->
            var read = 0
            while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
        }.toString(Charsets.UTF_8) else ""
        val page = when {
            path.substringBefore('?') != "/$code" -> return respond(sock, 404, "<p>Not found</p>")
            method == "POST" -> {
                val fields = body.split('&').map { it.split('=', limit = 2) }.associate { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") }
                val link = fields["link"].orEmpty().trim()
                val collection = fields["collection"].orEmpty().trim()
                val message = try {
                    if (collection.isNotEmpty()) "Added <b>${esc(onCollection(collection))}</b> to Luna."
                    else "Added <b>${esc(onLink(link))}</b> to Luna."
                } catch (e: Exception) { esc(e.message ?: "Couldn't add that") }
                onAdded(message.replace(Regex("<[^>]+>"), ""))
                form(message)
            }
            else -> form(null)
        }
        respond(sock, 200, page)
    } }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(c.toChar())
        }
    }

    private fun respond(sock: Socket, status: Int, html: String) {
        val bytes = html.toByteArray()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun form(message: String?) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Luna · Add your links</title><style>
body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#06323A;color:#EFFBFC;padding:28px}
h1{font-size:28px;letter-spacing:5px;margin:0 0 6px}p{color:#bfe9ec;line-height:1.5}
textarea{width:100%;box-sizing:border-box;height:120px;border-radius:12px;border:1px solid #5FD6DC;background:#0b4650;color:#EFFBFC;font-size:15px;padding:12px}
button{margin-top:14px;width:100%;padding:15px;border:0;border-radius:999px;background:#EFFBFC;color:#06323A;font-size:17px;font-weight:600}
.msg{margin-top:18px;padding:14px;border-radius:12px;background:#0D6F7A}</style></head><body>
<h1>LUNA</h1><p>Paste the links from your provider. They go straight to your TV.</p>
<h2 style="font-size:18px">Metadata link</h2><form method="post"><textarea name="link" placeholder="https://…" required></textarea><button type="submit">Add Metadata link</button></form>
<h2 style="margin-top:30px;font-size:18px">Host link</h2><form method="post"><textarea name="link" placeholder="https://…" required></textarea><button type="submit">Add Host link</button></form>
<h2 style="margin-top:34px;font-size:18px">Collection</h2><p>Optional: a different home layout. Paste its link or the whole file.</p>
<form method="post"><textarea name="collection" placeholder="https://… .json  or  [ { … } ]" required></textarea><button type="submit">Add collection</button></form>
${message?.let { "<div class=\"msg\">$it</div>" } ?: ""}</body></html>"""

    companion object {
        /** The TV's home-network IPv4 address (Wi-Fi or Ethernet). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
