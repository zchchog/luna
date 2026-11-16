package tv.luna.player.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.luna.player.data.catalogue.cleaned
import tv.luna.player.data.catalogue.parseStripList
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.CustomGroupEntity
import tv.luna.player.data.db.GroupPlacement
import tv.luna.player.data.model.Kind

/** One entry of the live guide's category rail (spec §4.3). */
sealed interface RailEntry {
    val key: String
    val title: String

    data object Favourites : RailEntry { override val key = "fav"; override val title = "★ Favourites" }
    data object Recent : RailEntry { override val key = "recent"; override val title = "Recently watched" }
    data class Custom(val group: CustomGroupEntity) : RailEntry {
        override val key get() = "g${group.id}"
        override val title get() = group.name
    }
    data class Provider(val category: CategoryEntity) : RailEntry {
        override val key get() = "c${category.id}"
        override val title get() = category.name.ifEmpty { "Uncategorised" }
    }
}

data class RailItem(val entry: RailEntry, val count: Int)

class RailRepository(private val db: AppDatabase) {
    /**
     * Top user categories, Favourites, Recently watched, normal user categories, visible provider categories, bottom
     * user categories (each set in sortOrder, then name); empty entries are left out.
     */
    fun rail(providerId: Long): Flow<List<RailItem>> = combine(
        db.providerDao().observe(providerId).map { parseStripList(it?.categoryStrip.orEmpty()) }.distinctUntilChanged(),
        railRaw(providerId),
    ) { strip, items -> if (strip.isEmpty()) items else items.map { i -> (i.entry as? RailEntry.Provider)?.let { RailItem(RailEntry.Provider(it.category.cleaned(strip)), i.count) } ?: i } }

    private fun railRaw(providerId: Long): Flow<List<RailItem>> = combine(
        combine(db.channelDao().observeFavourites(providerId), db.recentDao().observe(providerId), ::Pair),
        db.customGroupDao().observe(providerId),
        db.customGroupDao().observeCounts(providerId),
        db.categoryDao().observeVisible(providerId, Kind.LIVE),
        db.channelDao().observeCounts(providerId),
    ) { (favs, recents), groups, groupCounts, cats, catCounts ->
        val gn = groupCounts.associate { it.groupId to it.n }
        val cn = catCounts.associate { it.categoryId to it.n }
        buildList {
            fun groupsAt(p: GroupPlacement) = groups.filter { it.placement == p }.forEach { add(RailItem(RailEntry.Custom(it), gn[it.id] ?: 0)) }
            groupsAt(GroupPlacement.TOP)
            add(RailItem(RailEntry.Favourites, favs.size))
            add(RailItem(RailEntry.Recent, recents.size))
            groupsAt(GroupPlacement.NORMAL)
            cats.forEach { add(RailItem(RailEntry.Provider(it), cn[it.id] ?: 0)) }
            groupsAt(GroupPlacement.BOTTOM)
        }.filter { it.count > 0 }
    }

    fun channels(providerId: Long, entry: RailEntry): Flow<List<ChannelEntity>> = when (entry) {
        RailEntry.Favourites -> db.channelDao().observeFavourites(providerId)
        RailEntry.Recent -> db.recentDao().observe(providerId)
        is RailEntry.Custom -> db.customGroupDao().observeChannels(entry.group.id)
        is RailEntry.Provider -> db.channelDao().observeByCategory(entry.category.id)
    }
}
