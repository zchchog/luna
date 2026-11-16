package tv.luna.player.data.epg

import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.logos.logoKey
import tv.luna.player.data.catalogue.detectCountry

/**
 * Spec §5.3: each channel without a link takes the link of a same-name channel (logo key: no country prefix,
 * quality or encoding tags) in the same category country. Donors are only channels already linked in [links],
 * so borrowed links never donate. Donor: a manual link first, else the lowest channel id. Returns new automatic links.
 */
fun borrowSiblings(channels: List<ChannelEntity>, countryOf: (ChannelEntity) -> String?, links: List<EpgLinkEntity>): List<EpgLinkEntity> {
    val byChannel = links.associateBy { it.channelId }
    val keyOf = channels.associate { it.id to logoKey(it.name)?.key }
    // A mixed category ("SPORTS") has no country: fall back to the channel's own prefix ("DE: Sport").
    val country = channels.associate { it.id to (countryOf(it) ?: detectCountry(it.name)) }
    val donors = channels.filter { it.id in byChannel && keyOf[it.id] != null }
        .groupBy { keyOf[it.id] to country[it.id] }
        .mapValues { (_, cs) -> byChannel.getValue((cs.firstOrNull { byChannel.getValue(it.id).manual } ?: cs.minBy { it.id }).id) }
    return channels.mapNotNull { c ->
        if (c.id in byChannel) return@mapNotNull null
        val key = keyOf[c.id] ?: return@mapNotNull null
        donors[key to country[c.id]]?.let { EpgLinkEntity(c.id, it.sourceId, it.xmltvId, manual = false) }
    }
}

/** Same-name (logo key) channels with a guide, by id: the slow part of [guideDonors] (a key per channel), worked out once per picker. */
fun guideSiblings(target: ChannelEntity, withGuide: List<ChannelEntity>): List<ChannelEntity> {
    val key = logoKey(target.name)?.key ?: return emptyList()
    return withGuide.filter { it.id != target.id && logoKey(it.name)?.key == key }.sortedBy { it.id }
}

/** "Use guide from…" (spec §5.4): same-name channels first (by id), then channels whose name has every word of [query]. */
fun guideDonors(target: ChannelEntity, withGuide: List<ChannelEntity>, query: String, limit: Int = 50,
                siblings: List<ChannelEntity> = guideSiblings(target, withGuide)): List<ChannelEntity> {
    val others = withGuide.filter { it.id != target.id }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val found = if (words.isEmpty()) emptyList() else others.filter { c -> words.all { c.name.lowercase().contains(it) } }.sortedBy { it.name }
    return (siblings + found).distinctBy { it.id }.take(limit)
}
