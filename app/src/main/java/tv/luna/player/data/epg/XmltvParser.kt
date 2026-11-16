package tv.luna.player.data.epg

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

data class XChannel(val id: String, val displayNames: List<String>, val icon: String?)

data class XProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
    val episodeNum: String?,
    val icon: String?,
)

object XmltvTime {
    private val FORMAT = Regex("""^\s*(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*(?:([+-])(\d{2}):?(\d{2}))?""")

    /** `yyyyMMddHHmm[ss][ ±HHMM]` → epoch millis; a missing offset means UTC. */
    fun parse(s: String): Long? {
        val m = FORMAT.find(s) ?: return null
        val g = m.groupValues
        return try {
            val local = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            val offset = if (g[7].isEmpty()) ZoneOffset.UTC else {
                val sign = if (g[7] == "-") -1 else 1
                ZoneOffset.ofHoursMinutes(sign * g[8].toInt(), sign * g[9].toInt())
            }
            local.toInstant(offset).toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}

/** Streaming XMLTV reader: memory use is independent of document size. */
class XmltvParser {

    /** Returns the number of programmes skipped as malformed. */
    fun parse(input: InputStream, onChannel: (XChannel) -> Unit, onProgramme: (XProgramme) -> Unit): Int {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Real feeds contain bare '&' and HTML entities; relaxed mode keeps them as text instead of failing.
        p.setFeature(RELAXED, true)
        p.setInput(input, null)
        var skipped = 0
        var sawRootEnd = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && p.depth == 1) sawRootEnd = true
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p)?.let(onChannel)
                    "programme" -> readProgramme(p)?.let(onProgramme) ?: skipped++
                }
            }
            event = p.next()
        }
        // Relaxed mode tolerates EOF anywhere; a missing root end tag means a truncated download.
        if (!sawRootEnd) throw org.xmlpull.v1.XmlPullParserException("Truncated XMLTV document")
        return skipped
    }

    private companion object {
        const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    }

    private fun readChannel(p: XmlPullParser): XChannel? {
        val id = p.getAttributeValue(null, "id")?.trim()
        val names = ArrayList<String>(2)
        var icon: String? = null
        children(p) { name ->
            when (name) {
                "display-name" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
                "icon" -> { icon = icon ?: p.getAttributeValue(null, "src"); skip(p) }
                else -> skip(p)
            }
        }
        return id?.takeIf { it.isNotEmpty() }?.let { XChannel(it, names, icon) }
    }

    private fun readProgramme(p: XmlPullParser): XProgramme? {
        val start = p.getAttributeValue(null, "start")?.let(XmltvTime::parse)
        val stop = p.getAttributeValue(null, "stop")?.let(XmltvTime::parse)
        val channel = p.getAttributeValue(null, "channel")?.trim()
        var title: String? = null
        var desc: String? = null
        var episode: String? = null
        var onscreen: String? = null
        var icon: String? = null
        children(p) { name ->
            when (name) {
                "title" -> p.nextText().trim().let { if (title == null) title = it }
                "desc" -> p.nextText().trim().let { if (desc == null && it.isNotEmpty()) desc = it }
                "episode-num" -> {
                    val system = p.getAttributeValue(null, "system")
                    val text = p.nextText().trim().takeIf { it.isNotEmpty() }
                    if (system == "onscreen") onscreen = onscreen ?: text else episode = episode ?: text
                }
                "icon" -> { icon = icon ?: p.getAttributeValue(null, "src"); skip(p) }
                else -> skip(p)
            }
        }
        if (start == null || stop == null || channel.isNullOrEmpty()) return null
        return XProgramme(channel, start, stop, title.orEmpty(), desc, onscreen ?: episode, icon)
    }

    /** Calls [onChild] for each direct child start tag; [onChild] must consume the child up to its end tag. */
    private inline fun children(p: XmlPullParser, onChild: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) throw org.xmlpull.v1.XmlPullParserException("Unexpected end of document")
            if (e == XmlPullParser.END_TAG && p.depth == depth) return
            if (e == XmlPullParser.START_TAG) onChild(p.name)
        }
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> throw org.xmlpull.v1.XmlPullParserException("Unexpected end of document")
            }
        }
    }
}
