package tv.luna.player.data.logos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.luna.player.data.catalogue.countryLabel

/** `countries/…` .png blob paths from a GitHub git-trees response (a truncated tree is still used). */
fun parseTree(json: String): List<String> =
    Json.parseToJsonElement(json).jsonObject["tree"]?.jsonArray.orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
        path.takeIf {
            o["type"]?.jsonPrimitive?.content == "blob" && it.startsWith("countries/") && it.endsWith(".png", ignoreCase = true)
        }
    }

/** tv-logos files by country folder (`countries/<folder>/…`) and logo key. */
class TvLogoIndex(paths: List<String>) {
    private class Entry(val path: String, val key: String, val quality: String?, val words: Set<String>)

    private val byFolder: Map<String, List<Entry>>
    /** folder → compact key → entries: one map lookup per channel instead of a scan of the folder (46k channels). */
    private val byFolderKey: Map<String, Map<String, List<Entry>>>
    val folders: Set<String>
    val size = paths.size

    init {
        val valid = paths.map { it.split('/') }.filter { it.size >= 3 && it[0] == "countries" }
        fun base(s: List<String>) = s.last().substringBeforeLast('.')
        fun mostCommonSuffix(group: List<List<String>>) =
            group.groupingBy { base(it).substringAfterLast('-', "") }.eachCount().maxByOrNull { it.value }
        val folderSuffix = valid.groupBy { it[1] }.mapValues { mostCommonSuffix(it.value)?.key }
        // nordic/denmark/…-dk.png, nordic/norway/…-no.png: a sub-folder with its own code wins over the folder's.
        val dirSuffix = valid.groupBy { it.dropLast(1).joinToString("/") }
            .mapValues { (_, g) -> mostCommonSuffix(g)?.takeIf { it.value >= 2 }?.key }
        byFolder = valid.map { s ->
            val folder = s[1]
            var name = base(s).lowercase()
            val cc = dirSuffix[s.dropLast(1).joinToString("/")] ?: folderSuffix[folder]
            if (cc != null && cc.isNotEmpty() && name.endsWith("-$cc")) name = name.removeSuffix("-$cc")
            val quality = when {
                name.endsWith("-uhd") || name.endsWith("-4k") -> "uhd"
                name.endsWith("-hd") || name.endsWith("-fhd") -> "hd"
                else -> null
            }
            if (quality != null) name = name.substringBeforeLast('-')
            // Compared without dashes and with digits: "bbc-two" = "bbc-2", "itv-1" = "itv1".
            val words = digitsKey(name).split('-')
            folder to Entry(s.joinToString("/"), words.joinToString(""), quality, words.toSet())
        }.groupBy({ it.first }, { it.second })
        folders = byFolder.keys
        byFolderKey = byFolder.mapValues { (_, es) -> es.groupBy { it.key } }
    }

    /** First folder with the key; within it the hinted quality, else the plain file, else the first by path. */
    fun match(key: LogoKey, folders: List<String>): String? {
        val compact = key.compact()
        for (f in folders) {
            val hits = byFolderKey[f]?.get(compact).orEmpty()
            if (hits.isEmpty()) continue
            return (hits.firstOrNull { it.quality == key.quality } ?: hits.firstOrNull { it.quality == null }
                ?: hits.minBy { it.path }).path
        }
        return null
    }

    /** For "Change logo…": the match, the key in [folders], the key anywhere, then files anywhere with every word of the key. */
    fun candidates(key: LogoKey, folders: List<String>, limit: Int = 8): List<String> {
        val words = digitsKey(key.key).split('-').toSet()
        val compact = key.compact()
        val inFolders = folders.flatMap { byFolder[it].orEmpty() }
        val all = byFolder.values.flatten()
        return (listOfNotNull(match(key, folders)) +
            inFolders.filter { it.key == compact }.sortedBy { it.path }.map { it.path } +
            all.filter { it.key == compact }.sortedBy { it.path }.map { it.path } +
            all.filter { it.key != compact && it.words.containsAll(words) }.sortedBy { it.path }.map { it.path })
            .distinct().take(limit)
    }
}

private fun LogoKey.compact() = digitsKey(key).replace("-", "")

private val FALLBACK_FOLDERS = listOf("united-kingdom", "united-states", "international")

/** Countries whose tv-logos folder isn't the slug of our label (checked against the real tree, 2026-11-11). */
private val FOLDER_OVERRIDES: Map<String, String> = buildMap {
    put("CZ", "czech-republic")
    listOf("SE", "NO", "DK", "FI").forEach { put(it, "nordic") }
    listOf("SA", "EG", "QA", "KW", "MA", "DZ", "TN", "JO", "IQ", "SY", "OM", "BH", "YE", "LY", "SD").forEach { put(it, "world-middle-east") }
}

private val NON_SLUG = Regex("""[^a-z0-9]+""")

/** The category's tv-logos folder, or UK → US → international when it has no country or no folder. */
fun countryFolders(country: String?, index: TvLogoIndex): List<String> {
    val folder = when {
        country == null || country.startsWith("script:") || country.startsWith("lang:") -> null
        else -> FOLDER_OVERRIDES[country] ?: countryLabel(country).lowercase().replace(NON_SLUG, "-").trim('-')
    }
    return if (folder != null && folder in index.folders) listOf(folder) else FALLBACK_FOLDERS.filter { it in index.folders }
}
