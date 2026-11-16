package org.imdtv.iptvtv.player

import java.util.Locale

/** Words for what a stream offers: "English", "Dolby Digital Plus 5.1", "1080p · 50 fps · 6.5 Mbps". */
object TrackLabels {

    /** "English" from "en" or "eng"; null when the language isn't known. */
    fun language(tag: String?): String? {
        if (tag.isNullOrBlank() || tag == "und" || tag == "mul") return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { tag }
    }

    fun resolution(width: Int, height: Int): String? {
        val byWidth = when {
            width >= 7000 -> "8K"
            width >= 3600 -> "4K"
            width >= 2400 -> "1440p"
            width >= 1800 -> "1080p"
            width >= 1200 -> "720p"
            else -> null
        }
        return byWidth ?: if (height > 0) "${height}p" else null
    }

    fun frameRate(fps: Float): String? = if (fps > 0f) "${Math.round(fps)} fps" else null

    fun audioCodec(mime: String?): String? = when {
        mime == null -> null
        mime == "audio/ac3" -> "Dolby Digital"
        mime == "audio/eac3" -> "Dolby Digital Plus"
        mime == "audio/eac3-joc" -> "Dolby Atmos"
        mime == "audio/true-hd" -> "Dolby TrueHD"
        mime == "audio/mp4a-latm" -> "AAC"
        mime == "audio/mpeg" -> "MP3"
        mime == "audio/mpeg-L2" -> "MP2"
        mime == "audio/opus" -> "Opus"
        mime == "audio/flac" -> "FLAC"
        mime.startsWith("audio/vnd.dts") -> "DTS"
        else -> null
    }

    fun layout(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count channels"
    }

    /** An audio track: the name, and its format underneath. */
    fun audio(language: String?, label: String?, mime: String?, channels: Int, number: Int): Pair<String, String> {
        val name = language(language) ?: label?.takeIf { it.isNotBlank() } ?: "Track $number"
        return name to listOfNotNull(audioCodec(mime), layout(channels)).joinToString(" ")
    }

    /** A video quality: the size, and the rate and bitrate underneath. */
    fun video(width: Int, height: Int, fps: Float, bitrate: Int): Pair<String, String> {
        val name = resolution(width, height) ?: "Quality"
        val mbps = if (bitrate > 0) String.format(Locale.ENGLISH, "%.1f Mbps", bitrate / 1_000_000f) else null
        return name to listOfNotNull(frameRate(fps), mbps).joinToString(" · ")
    }

    /** A subtitle track. */
    fun text(language: String?, label: String?, number: Int): Pair<String, String> {
        val name = language(language) ?: label?.takeIf { it.isNotBlank() } ?: "Subtitles $number"
        return name to (label?.takeIf { it.isNotBlank() && it != name }.orEmpty())
    }
}
