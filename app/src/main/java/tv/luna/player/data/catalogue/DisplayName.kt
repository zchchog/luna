package tv.luna.player.data.catalogue

import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity

/** Channel display name (spec §2.3): computed when shown, never stored. */

private const val SEP = " \t|:-_"

/**
 * Comma-separated user list. "X" removes X at either end; "X=Y" replaces X there with Y ("3840P=4K", "RAW=" removes).
 * Entries made only of separators would strip nothing meaningful, so they're dropped.
 */
fun parseStripList(s: String): List<String> = s.split(',').map { it.trim() }.filter { e -> e.substringBefore('=').any { it !in SEP } }

/** Small letters ("ᴿᴬᵂ", "³⁸⁴⁰ᴾ") as plain ones, one char for one char, so matches on it cut the original at the same place. */
private fun fold(s: String): String = if (s.all { it < '\u00A0' }) s else String(CharArray(s.length) { i ->
    java.text.Normalizer.normalize(s[i].toString(), java.text.Normalizer.Form.NFKC).singleOrNull() ?: s[i]
})

fun displayName(name: String, customName: String?, strip: List<String>): String {
    if (!customName.isNullOrBlank()) return customName
    val (renames, removes) = strip.partition { '=' in it }
    var s = name.trim()
    var f = fold(s)
    fun startsWithWord(e: String) = f.length > e.length && f.startsWith(e, ignoreCase = true) && boundary(e.last(), f[e.length])
    fun endsWithWord(e: String) = f.length > e.length && f.endsWith(e, ignoreCase = true) && boundary(e.first(), f[f.length - e.length - 1])
    var changed = true
    while (changed) { // repeat so "4K: … HD" loses both ends
        changed = false
        for (e in removes) {
            if (startsWithWord(e)) { s = s.substring(e.length).trimStart { it in SEP }; f = fold(s); changed = true }
            if (endsWithWord(e)) { s = s.dropLast(e.length).trimEnd { it in SEP }; f = fold(s); changed = true }
        }
    }
    for (r in renames) { // once each: "HD=HD" mustn't loop
        val find = r.substringBefore('=').trim(); val to = r.substringAfter('=').trim()
        if (find.isEmpty()) continue
        if (startsWithWord(find)) { s = to + s.substring(find.length); if (to.isEmpty()) s = s.trimStart { it in SEP }; f = fold(s) }
        if (endsWithWord(find)) { s = s.dropLast(find.length) + to; if (to.isEmpty()) s = s.trimEnd { it in SEP }; f = fold(s) }
    }
    return s.ifBlank { name }
}

/** Whole words only: "UK" mustn't eat the start of "UKTV". */
private fun boundary(entryEdge: Char, neighbour: Char) = !entryEdge.isLetterOrDigit() || !neighbour.isLetterOrDigit()

fun ChannelEntity.displayName(strip: List<String>) = displayName(name, customName, strip)

/** Provider divider rows such as "#### SKY CINEMA HD/4K ####" (two to five hashes in practice): any name starting with #. */
fun isHeaderChannel(name: String): Boolean = name.trimStart().startsWith('#')

/** Category name clean-up: the channel clean-up rules on a category name; never empty. Country detection and search use the raw name. */
fun categoryName(c: CategoryEntity, strip: List<String>): String = displayName(c.name, null, strip).ifEmpty { "Uncategorised" }

/** A display copy with the cleaned name (never written back: category writes re-read their rows). */
fun CategoryEntity.cleaned(strip: List<String>): CategoryEntity = if (strip.isEmpty()) this else copy(name = categoryName(this, strip))
