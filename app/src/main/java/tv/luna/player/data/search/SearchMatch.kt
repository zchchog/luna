package tv.luna.player.data.search

import java.text.Normalizer
import java.util.Locale

/** NFKC (small-letter tags ᴴᴰ ³⁸⁴⁰ᴾ become HD 3840P) then lower case. Accents stay: `tele` doesn't find `Télé`. */
fun foldForSearch(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** Folded words of the query; empty for a blank query (which searches nothing). */
fun queryWords(q: String): List<String> = foldForSearch(q).split(Regex("\\s+")).filter { it.isNotEmpty() }

/** Every word appears somewhere in [folded], in any order. */
fun matchesAll(folded: String, words: List<String>): Boolean = words.all { it in folded }

data class Hit<T>(val item: T, val folded: String, val number: Int? = null)

/** Names starting with the first word first, then by folded name, then by number (none last). */
fun <T> rank(hits: List<Hit<T>>, words: List<String>): List<T> {
    val first = words.firstOrNull().orEmpty()
    return hits.sortedWith(compareBy<Hit<T>>({ !it.folded.startsWith(first) }, { it.folded }, { it.number ?: Int.MAX_VALUE }))
        .map { it.item }
}
