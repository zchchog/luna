package org.imdtv.iptvtv.data.catalogue

import java.text.Normalizer
import org.imdtv.iptvtv.data.db.ChannelEntity

/** What a channel's name says about its picture and sound, e.g. "4K · 50fps · HEVC". [rank]: higher is better. */
data class NameTags(val rank: Int, val text: String)

/** Reads channel names the way providers write them: "UK: BBC One ᴴᴰ", "|UK| BBC ONE 4K 50FPS". */
object ChannelNames {

    private val region = Regex("""^\s*(\[[A-Za-z]{2,3}\]|\([A-Za-z]{2,3}\)|\|[A-Za-z]{2,3}\||[A-Za-z]{2,3}\s*[:|])\s*""")

    // Words that describe the stream and not the channel. Removed to find the plain name.
    private val describing = Regex(
        """(?<![a-z0-9])(8k|4k|uhd|fhd|hd|sd|2160p|1440p|1080p|1080i|720p|576p|480p|hevc|h\.?265|h\.?264|x265|x264|avc|raw|""" +
            """\d{2}\s?fps|fps|dolby|atmos|ac3|eac3|ddp|dts|hdr10|hdr|hlg|sdr|backup)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val fps = Regex("""(?<![a-z0-9])(\d{2})\s?fps""")

    /** Plain form for comparing: "UK: BBC One ᴴᴰ 50FPS" -> "bbcone". */
    fun key(name: String): String {
        // The compatibility form turns small raised letters and digits into plain ones.
        val plain = Normalizer.normalize(name, Normalizer.Form.NFKD)
        return plain.replace(region, "").replace(describing, " ").lowercase().filter { it.isLetterOrDigit() }
    }

    fun tags(name: String): NameTags {
        val n = Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase()
        fun has(pattern: String) = Regex("(?<![a-z0-9])($pattern)(?![a-z0-9])").containsMatchIn(n)
        var (rank, resolution) = when {
            has("8k|4320p") -> 80 to "8K"
            has("4k|uhd|2160p") -> 40 to "4K"
            has("1440p|2k") -> 30 to "1440p"
            has("fhd|1080p|1080i") -> 25 to "1080p"
            has("hd|720p") -> 20 to "HD"
            has("sd|576p|480p") -> 10 to "SD"
            else -> 15 to ""
        }
        val rate = fps.find(n)?.groupValues?.get(1)?.toIntOrNull()
        if (rate != null && rate >= 50) rank += 1
        val text = listOfNotNull(
            resolution.ifEmpty { null },
            rate?.let { "${it}fps" },
            if (has("hevc|h\\.?265|x265")) "HEVC" else null,
            if (has("dolby|atmos|ac3|eac3|ddp")) "Dolby" else null,
            if (has("hdr10|hdr|hlg")) "HDR" else null,
            if (has("raw")) "RAW" else null,
        ).joinToString(" · ")
        return NameTags(rank, text)
    }
}

/**
 * Finds the other versions of a channel in a playlist: the same channel offered again in other qualities ("BBC One HD",
 * "BBC One 4K"). Providers usually give them the same guide id or the same name apart from a quality tag; either counts.
 */
class VersionIndex(channels: List<ChannelEntity>) {
    private val byId = HashMap<String, MutableList<ChannelEntity>>()
    private val byName = HashMap<String, MutableList<ChannelEntity>>()

    private fun guideId(c: ChannelEntity) = (c.epgChannelId ?: c.tvgId).orEmpty().trim().lowercase()

    init {
        for (c in channels) {
            val id = guideId(c)
            if (id.isNotEmpty()) byId.getOrPut(id) { ArrayList(2) } += c
            val key = ChannelNames.key(c.name)
            if (key.length >= MIN_KEY) byName.getOrPut(key) { ArrayList(2) } += c
        }
    }

    /** All versions including [channel] itself, best first; empty if there is only one. */
    fun versionsOf(channel: ChannelEntity): List<ChannelEntity> {
        val found = LinkedHashMap<Long, ChannelEntity>()
        fun add(list: List<ChannelEntity>?) {
            // A big pile under one id or name is a provider's mistake, not one channel.
            if (list != null && list.size <= MAX_GROUP) for (c in list) found.putIfAbsent(c.id, c)
        }
        guideId(channel).takeIf { it.isNotEmpty() }?.let { add(byId[it]) }
        ChannelNames.key(channel.name).takeIf { it.length >= MIN_KEY }?.let { add(byName[it]) }
        found.putIfAbsent(channel.id, channel)
        if (found.size < 2) return emptyList()
        return found.values.sortedWith(compareByDescending<ChannelEntity> { ChannelNames.tags(it.name).rank }.thenBy { it.name })
    }

    private companion object {
        const val MIN_KEY = 3
        const val MAX_GROUP = 12
    }
}
