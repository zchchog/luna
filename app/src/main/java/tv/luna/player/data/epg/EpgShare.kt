package tv.luna.player.data.epg

import androidx.room.withTransaction
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ORIGIN_EPGSHARE
import tv.luna.player.data.db.ORIGIN_PLAYLIST
import tv.luna.player.data.settings.GuideKind

const val EPGSHARE_BASE = "https://epgshare01.online/epgshare01/"

private val RIPPER = Regex("""^epg_ripper_([A-Z]{2})(\d*)\.xml\.gz$""")
private val HREF = Regex("""href="([^"]+)"""")

/** Country code → its `epg_ripper_<CC><n>.xml.gz` files, by number. Never the all-countries or regional files. */
fun parseEpgShareIndex(html: String): Map<String, List<String>> =
    HREF.findAll(html).mapNotNull { RIPPER.matchEntire(it.groupValues[1]) }.distinctBy { it.value }
        .groupBy({ it.groupValues[1] }, { it })
        .mapValues { (_, ms) -> ms.sortedBy { it.groupValues[2].toIntOrNull() ?: 0 }.map { it.value } }

/** Our category country → epgshare01's code. SA (our Arabic tag) is left out at the user's request. */
fun epgShareCode(country: String?): String? = when {
    country == null || country.startsWith("script:") || country.startsWith("lang:") || country == "SA" -> null
    country == "GB" -> "UK"
    else -> country
}

/** Sources used per Guide source order (spec §5.1): all of the first kind by priority, then the next kind. */
fun selectSources(sources: List<EpgSourceEntity>, order: List<GuideKind>): List<EpgSourceEntity> = order.flatMap { k ->
    val origin = if (k == GuideKind.PLAYLIST) ORIGIN_PLAYLIST else ORIGIN_EPGSHARE
    sources.filter { it.origin == origin }.sortedBy { it.priority }
}

/** Keeps one EPGSHARE source per epgshare01 file for the countries of the provider's shown categories (spec §5.2). */
class EpgShareSources(
    private val db: AppDatabase,
    /** When set, these countries (ISO codes) decide which files are kept instead of the shown categories. */
    private val countries: (suspend () -> Set<String>)? = null,
    private val fetch: suspend (String) -> String,
) {

    /** Returns the number of epgshare01 sources kept. A failed fetch throws and changes nothing. */
    suspend fun sync(providerId: Long): Int {
        val index = parseEpgShareIndex(fetch(EPGSHARE_BASE))
        val wantedCountries = countries?.invoke() ?: db.categoryDao().allKinds(providerId).filter { !it.hidden }.map { it.country }
        val codes = wantedCountries.mapNotNull { epgShareCode(it) }.toSortedSet()
        val wanted = codes.flatMap { index[it].orEmpty() }.map { EPGSHARE_BASE + it }
        db.withTransaction {
            val existing = db.epgDao().sources(providerId).filter { it.origin == ORIGIN_EPGSHARE }
            existing.filter { it.url !in wanted }.forEach { s ->
                db.programmeDao().deleteForSource(s.id)
                db.epgDao().deleteLinksForSource(s.id)
                db.epgDao().deleteSource(s.id)
            }
            val have = existing.map { it.url }.toSet()
            wanted.forEachIndexed { i, url ->
                if (url !in have) db.epgDao().insertSource(EpgSourceEntity(providerId = providerId, url = url, priority = i,
                    name = "Open-source " + url.substringAfterLast("epg_ripper_").substringBefore('.'), origin = ORIGIN_EPGSHARE))
            }
        }
        return wanted.size
    }
}
