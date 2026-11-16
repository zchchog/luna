package tv.luna.player.data.repo

import androidx.room.withTransaction
import tv.luna.player.data.catalogue.matchesLanguage
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.model.Kind

class CategoryRepository(private val db: AppDatabase) {
    /** "Match TV language": shows that language's categories (and ones with none detected), hides the rest; null shows all. */
    suspend fun matchLanguage(providerId: Long, language: String?) = db.withTransaction {
        val changed = db.categoryDao().allKinds(providerId).mapNotNull { c ->
            val hide = language != null && !matchesLanguage(c.country, language)
            if (c.hidden != hide) c.copy(hidden = hide) else null
        }
        db.categoryDao().update(changed)
    }

    /** Renumbers the kind's categories 0..n−1 in their current order, then moves one [delta] places, clamped at the ends. */
    suspend fun move(providerId: Long, kind: Kind, categoryId: Long, delta: Int) = db.withTransaction {
        val list = db.categoryDao().all(providerId, kind).toMutableList()
        val from = list.indexOfFirst { it.id == categoryId }
        if (from < 0) return@withTransaction
        val cat = list.removeAt(from)
        list.add((from + delta).coerceIn(0, list.size), cat)
        db.categoryDao().update(list.mapIndexedNotNull { i, c -> if (c.sortOrder != i) c.copy(sortOrder = i) else null })
    }
}
