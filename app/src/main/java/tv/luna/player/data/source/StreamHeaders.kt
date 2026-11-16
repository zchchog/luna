package tv.luna.player.data.source

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Per-stream HTTP headers carried on the link, Kodi style: `url|User-Agent=…&Referer=…` (values percent-encoded).
 * M3U `#EXTVLCOPT:http-user-agent=` / `http-referrer=` lines are stored this way too, so no extra column is needed.
 */
fun splitStreamHeaders(url: String): Pair<String, Map<String, String>> {
    val bar = url.indexOf('|')
    if (bar < 0) return url to emptyMap()
    val headers = url.substring(bar + 1).split('&').mapNotNull { kv ->
        val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val name = when (k.trim().lowercase()) { "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; else -> k.trim() }
        name to runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v)
    }.toMap()
    return url.substring(0, bar) to headers
}

fun withStreamHeaders(url: String, headers: Map<String, String>): String =
    if (headers.isEmpty()) url
    else url + "|" + headers.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

/** What to request for a saved link: the login filled in, link headers (Kodi/EXTVLCOPT) over the playlist's user agent. */
data class StreamRequest(val url: String, val headers: Map<String, String>)

fun streamRequest(savedUrl: String, userAgent: String?, login: tv.luna.player.data.security.Login?): StreamRequest {
    val (address, headers) = splitStreamHeaders(savedUrl)
    val agent = headers["User-Agent"] ?: userAgent
    return StreamRequest(tv.luna.player.data.security.withLogin(address, login),
        if (agent == null) headers else headers + ("User-Agent" to agent))
}
