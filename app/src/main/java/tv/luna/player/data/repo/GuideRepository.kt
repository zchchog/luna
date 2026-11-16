package tv.luna.player.data.repo

import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.SQL_CHUNK
import tv.luna.player.data.epg.effectiveOffsetMinutes

/** A programme on a channel's own clock (EPG offset already applied). */
data class GuideProgramme(val title: String, val desc: String?, val episodeNum: String?, val start: Long, val stop: Long)

class GuideRepository(private val db: AppDatabase) {
    /** Programmes overlapping [from, to) per channel id, in start order. Unlinked channels are absent. */
    suspend fun programmes(providerId: Long, channels: List<ChannelEntity>, from: Long, to: Long): Map<Long, List<GuideProgramme>> {
        val links = channels.map { it.id }.chunked(SQL_CHUNK).flatMap { db.epgDao().linksFor(it) }.associateBy { it.channelId }
        val sources = db.epgDao().sources(providerId).associateBy { it.id }
        val offsets = channels.mapNotNull { c ->
            val l = links[c.id] ?: return@mapNotNull null
            val s = sources[l.sourceId] ?: return@mapNotNull null
            c.id to (ProgrammeEntity.key(l.sourceId, l.xmltvId) to effectiveOffsetMinutes(c, s) * MIN)
        }.toMap()
        if (offsets.isEmpty()) return emptyMap()
        val slack = offsets.values.maxOf { kotlin.math.abs(it.second) }
        val byKey = offsets.values.map { it.first }.distinct().chunked(SQL_CHUNK)
            .flatMap { db.programmeDao().window(it, from - slack, to + slack) }
            .groupBy { it.epgChannelKey }
        return offsets.mapValues { (_, ko) ->
            val (key, off) = ko
            byKey[key].orEmpty().map { GuideProgramme(it.title, it.desc, it.episodeNum, it.start + off, it.stop + off) }
                .filter { it.stop > from && it.start < to }
                .sortedBy { it.start }
        }
    }

    private companion object { const val MIN = 60_000L }
}
