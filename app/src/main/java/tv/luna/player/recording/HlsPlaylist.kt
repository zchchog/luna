package tv.luna.player.recording

import okhttp3.HttpUrl

data class Variant(val url: HttpUrl, val bandwidth: Long)

sealed interface Hls {
    data class Master(val variants: List<Variant>) : Hls
    /** [unsupported]: "encrypted" or "fMP4" — this app saves plain MPEG-TS segments only. */
    data class Media(val firstSeq: Long, val targetSec: Int, val segments: List<HttpUrl>, val ended: Boolean, val unsupported: String?) : Hls
}

/** An HLS playlist, or null when [text] isn't one. URIs resolve against [base] (the final URL after redirects). */
fun parseHls(text: String, base: HttpUrl): Hls? {
    val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.firstOrNull() != "#EXTM3U") return null
    val variants = mutableListOf<Variant>()
    val segments = mutableListOf<HttpUrl>()
    var seq = 0L; var target = 6; var ended = false; var unsupported: String? = null
    var pendingBandwidth: Long? = null
    for (l in lines.drop(1)) when {
        l.startsWith("#EXT-X-STREAM-INF:") -> pendingBandwidth = Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0
        l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0
        l.startsWith("#EXT-X-TARGETDURATION:") -> target = l.substringAfter(':').toDoubleOrNull()?.toInt() ?: 6
        l == "#EXT-X-ENDLIST" -> ended = true
        l.startsWith("#EXT-X-KEY:") -> if (!l.contains("METHOD=NONE")) unsupported = "encrypted"
        l.startsWith("#EXT-X-MAP:") -> unsupported = unsupported ?: "fMP4"
        l.startsWith("#") -> Unit
        else -> base.resolve(l)?.let { u -> pendingBandwidth?.let { variants += Variant(u, it); pendingBandwidth = null } ?: segments.add(u) }
    }
    return if (variants.isNotEmpty()) Hls.Master(variants) else Hls.Media(seq, target, segments, ended, unsupported)
}
