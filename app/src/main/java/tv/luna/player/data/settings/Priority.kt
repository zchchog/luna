package tv.luna.player.data.settings

/** What the drop-down at [level] offers: every kind not used above it; levels after the first also offer None (null). */
fun <K> priorityChoices(order: List<K>, level: Int, all: List<K>): List<K?> {
    val free = all.filter { it !in order.take(level) }
    return if (level == 0) free else listOf<K?>(null) + free
}

/**
 * The order after picking [picked] at [level]: None (null) ends the order there; a kind already lower down swaps places
 * with this level's ("Playlist → Open-source" becomes "Open-source → Playlist"); a new kind replaces this level's.
 */
fun <K> withPriority(order: List<K>, level: Int, picked: K?): List<K> {
    if (picked == null) return order.take(level)
    val out = order.toMutableList()
    val at = out.indexOf(picked)
    when {
        level >= out.size -> out += picked
        at >= 0 -> { out[at] = out[level]; out[level] = picked }
        else -> out[level] = picked
    }
    return out.distinct()
}
