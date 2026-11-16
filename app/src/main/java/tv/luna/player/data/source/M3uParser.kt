package tv.luna.player.data.source

import tv.luna.player.data.model.Kind
import java.io.InputStream

data class M3uEntry(val name: String, val attrs: Map<String, String>, val url: String)

data class M3uParseResult(val entries: List<M3uEntry>, val skipped: Int)

object M3uParser {
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    private val SXXEYY = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")
    private val VOD_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    /** Streams line by line; never holds more than the parsed entries in memory. */
    fun parse(input: InputStream): M3uParseResult {
        val entries = ArrayList<M3uEntry>()
        var skipped = 0
        var pending: Pair<String, Map<String, String>>? = null
        val headers = LinkedHashMap<String, String>() // #EXTVLCOPT http-user-agent / http-referrer of the pending entry
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.removePrefix("﻿").trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        if (pending != null) skipped++
                        pending = parseExtinf(line)
                        headers.clear()
                        // Some playlists (iptv-org) put the headers in the #EXTINF line itself.
                        pending!!.second.let { a ->
                            (a["http-user-agent"] ?: a["user-agent"])?.let { headers["User-Agent"] = it }
                            (a["http-referrer"] ?: a["http-referer"])?.let { headers["Referer"] = it }
                        }
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val (k, v) = line.substring(11).split('=', limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                        when (k) { "http-user-agent" -> headers["User-Agent"] = v; "http-referrer", "http-referer" -> headers["Referer"] = v }
                    }
                    line.startsWith("#") -> Unit // #EXTM3U, #EXTGRP …
                    else -> pending?.let { (name, attrs) ->
                        entries += M3uEntry(name, attrs, if ('|' in line) line else withStreamHeaders(line, headers))
                        pending = null
                        headers.clear()
                    }
                }
            }
        }
        if (pending != null) skipped++
        return M3uParseResult(entries, skipped)
    }

    /** `#EXTINF:-1 key="v, w" …,Display Name` — the name starts after the first comma outside quotes. */
    private fun parseExtinf(line: String): Pair<String, Map<String, String>> {
        var inQuote = false
        var split = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) { split = i; break }
            }
        }
        val header = if (split >= 0) line.substring(0, split) else line
        val name = if (split >= 0) line.substring(split + 1).trim() else ""
        val attrs = ATTR.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return name.ifEmpty { attrs["tvg-name"].orEmpty() } to attrs
    }

    fun classify(e: M3uEntry): Kind {
        val path = e.url.substringBefore('|').substringBefore('?').substringBefore('#')
        val lower = path.lowercase()
        return when {
            "/movie/" in lower -> Kind.MOVIE
            "/series/" in lower -> Kind.SERIES
            lower.substringAfterLast('/').substringAfterLast('.', "") in VOD_EXT -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    fun seasonEpisode(title: String): Pair<Int, Int>? =
        SXXEYY.find(title)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** Title text before the SxxEyy marker, e.g. "Harbour Lights" for "Harbour Lights S02E04". */
    fun seriesTitle(title: String): String? =
        SXXEYY.find(title)?.let { title.substring(0, it.range.first).trim().trimEnd('-', '|', ':', '.').trim() }
            ?.takeIf { it.isNotEmpty() }
}
