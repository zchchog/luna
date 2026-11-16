package tv.luna.player.ui.live

/** Offset from the window start and visible length, both in whole minutes. */
data class Span(val offsetMin: Int, val lengthMin: Int)

object Timeline {
    /** Row to focus when the guide (re)appears: the channel last focused, if it's in this list. */
    fun rowFor(channelIds: List<Long>, focusedId: Long?): Int = channelIds.indexOf(focusedId).coerceAtLeast(0)

    private const val MIN = 60_000L
    private const val HALF_HOUR = 30 * MIN

    fun windowStart(now: Long) = now - now % HALF_HOUR

    fun span(start: Long, stop: Long, from: Long, to: Long): Span? {
        val s = maxOf(start, from)
        val e = minOf(stop, to)
        if (e <= s) return null
        return Span(((s - from) / MIN).toInt(), ((e - s) / MIN).toInt())
    }
}

enum class OkAction { PREVIEW, FULL_SCREEN }

/** Spec §4.1: without autoplay, OK previews a channel and a second OK on the previewing one goes full screen. */
fun okAction(autoplayOnScroll: Boolean, previewingId: Long?, channelId: Long): OkAction =
    if (autoplayOnScroll || previewingId == channelId) OkAction.FULL_SCREEN else OkAction.PREVIEW

/** Channel [delta] places from [currentId] in [ids], wrapping at the ends; a channel not in the list zaps to the first. */
fun zap(ids: List<Long>, currentId: Long, delta: Int): Long? {
    if (ids.isEmpty()) return null
    val i = ids.indexOf(currentId)
    if (i < 0) return ids.first()
    return ids[Math.floorMod(i + delta, ids.size)]
}

/** Zapping through Recently watched must not record recents: that would reorder the list being zapped and Down would bounce. */
fun zapRecordsRecent(entry: tv.luna.player.data.repo.RailEntry?): Boolean = entry != tv.luna.player.data.repo.RailEntry.Recent
