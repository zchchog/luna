package tv.luna.player.data.epg

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import tv.luna.player.data.logos.compactKey
import kotlinx.coroutines.withContext
import tv.luna.player.data.settings.GuideKind
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.db.EpgSourceEntity

/** [matched] includes [borrowed]. */
data class MatchResult(val matched: Int, val unmatched: Int, val borrowed: Int = 0)

/** Channel override if set, otherwise its EPG source's offset (spec §4.4). */
fun effectiveOffsetMinutes(channel: ChannelEntity, source: EpgSourceEntity): Int =
    channel.epgOffsetMinutes ?: source.offsetMinutes

/**
 * Resolves each channel's EPG link (spec §4.4), first hit wins: manual link (never touched) →
 * exact tvg-id across sources by priority → normalised name equality across sources by priority.
 * Auto links are rewritten on every run.
 */
class EpgMatcher(
    private val db: AppDatabase,
    /** Guide source and borrowing settings; read on each rematch so every caller follows them. */
    private val settings: suspend () -> AppSettings = { AppSettings() },
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun rematch(providerId: Long, mode: List<GuideKind>? = null, borrow: Boolean? = null): MatchResult {
        val s = settings()
        return rematchWith(providerId, mode ?: s.guideOrder, borrow ?: s.borrowSiblingGuide)
    }

    /**
     * Matching (~46k channels, tens of seconds on a TV box) runs outside any transaction, so user writes (favourites,
     * rename, manual links) aren't queued behind it; only replacing the automatic links is one short transaction.
     */
    private suspend fun rematchWith(providerId: Long, mode: List<GuideKind>, borrow: Boolean): MatchResult = withContext(Dispatchers.Default) {
        val epg = db.epgDao()
        val normaliser = NameNormaliser(db.ruleDao().nameRules(providerId))
        val sources = selectSources(epg.sources(providerId), mode)

        // Per source, in priority order: xmltv ids, exact (lightly normalised) names, and rule-normalised names.
        val ids = ArrayList<Pair<Long, Set<String>>>(sources.size)
        val names = ArrayList<NameIndex>(sources.size)
        for (s in sources) {
            val rows = epg.channels(s.id)
            ids += s.id to rows.mapTo(HashSet()) { it.xmltvId }
            val index = NameIndex(s.id)
            for (r in rows) {
                index.exact.putIfAbsent(EpgImporter.simpleNormalise(r.displayName), r.xmltvId)
                index.ruled.getOrPut(normaliser.normalise(r.displayName)) { ArrayList() } +=
                    Candidate(r.xmltvId, PLUS_ONE.containsMatchIn(r.displayName))
                compactKey(r.displayName, dropCountry = false)?.let { index.compact.putIfAbsent(it, r.xmltvId) }
            }
            names += index
        }

        val manualLinks = epg.links(providerId).filter { it.manual }
        val manual = manualLinks.mapTo(HashSet()) { it.channelId }
        val channels = db.channelDao().byProvider(providerId)
        // A guide that has run out (provider feeds sometimes stop hours early) gives way to a later source that still has one.
        val live = db.programmeDao().liveKeys(now()).toHashSet()
        val links = ArrayList<EpgLinkEntity>()
        for (c in channels) {
            if (c.id in manual) continue
            // Same precedence as before (ids across sources, then names across sources); the first one still on air wins.
            val keys = NameKeys(c.name, normaliser)  // once per channel, not once per source
            val candidates = sequence {
                for (i in ids) byIdIn(c, i)?.let { yield(it) }
                for (n in names) byName(c, keys, n)?.let { yield(it) }
            }
            var first: EpgLinkEntity? = null
            for (l in candidates) {
                if (first == null) first = l
                if (ProgrammeEntity.key(l.sourceId, l.xmltvId) in live) { first = l; break }
            }
            first?.let(links::add)
        }
        val borrowed = if (!borrow) emptyList() else {
            val country = db.categoryDao().allKinds(providerId).associate { it.id to it.country }
            borrowSiblings(channels, { ch -> ch.categoryId?.let(country::get) }, links + manualLinks)
        }
        links += borrowed
        db.withTransaction {
            // A manual link made while matching ran wins over the automatic one computed for that channel.
            val manualNow = epg.links(providerId).filter { it.manual }.mapTo(HashSet()) { it.channelId }
            epg.deleteAutoLinks(providerId)
            links.filter { it.channelId !in manualNow }.chunked(500).forEach { epg.upsertLinks(it) }
        }
        val matched = links.size + manual.size
        MatchResult(matched = matched, unmatched = channels.size - matched, borrowed = borrowed.size)
    }

    private class Candidate(val xmltvId: String, val plusOne: Boolean)
    private class NameIndex(val sourceId: Long) {
        val exact = HashMap<String, String>()
        val ruled = HashMap<String, MutableList<Candidate>>()
        /** Last resort: number words as digits, tags and spacing ignored (open-source files name channels differently). */
        val compact = HashMap<String, String>()
    }

    /**
     * Per source by priority: an exact name match first, then rule-normalised equality. Rules strip "+1",
     * so "Channel 4" and "Channel 4 +1" share a key; prefer the candidate whose +1-ness matches the channel,
     * falling back to the other (a +1 channel with no +1 guide uses the main guide).
     */
    private fun byIdIn(c: ChannelEntity, ids: Pair<Long, Set<String>>): EpgLinkEntity? =
        c.tvgId?.takeIf { it.isNotBlank() && it in ids.second }?.let { EpgLinkEntity(c.id, ids.first, it, manual = false) }

    private class NameKeys(name: String, normaliser: NameNormaliser) {
        val exact = EpgImporter.simpleNormalise(name)
        val ruled = normaliser.normalise(name).takeIf { it.isNotEmpty() }
        val plusOne = PLUS_ONE.containsMatchIn(name)
        val compact = compactKey(name)
    }

    private fun byName(c: ChannelEntity, k: NameKeys, n: NameIndex): EpgLinkEntity? {
        val ruled = k.ruled ?: return null
        val id = n.exact[k.exact]
            ?: n.ruled[ruled]?.let { cands -> (cands.firstOrNull { it.plusOne == k.plusOne } ?: cands.first()).xmltvId }
            ?: k.compact?.let { n.compact[it] }
        return id?.let { EpgLinkEntity(c.id, n.sourceId, it, manual = false) }
    }

    private companion object {
        val PLUS_ONE = Regex("""\+\s*1(?!\d)""")
    }
}
