package tv.luna.player.data.addons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Collections: a home-screen layout in the format Nuvio exports (e.g. The Kollection). Rows of folders; each folder has
 * artwork and a list of add-on catalogs to show inside it. A collection holds no films itself: the catalogs come from
 * the add-ons (usually AIOMetadata), so the matching AIOMetadata setup must be added too.
 */

data class CSource(val type: String, val catalogId: String, val addonId: String?, val genre: String?)

data class CFolder(
    val id: String, val title: String, val cover: String?, val logo: String?, val backdrop: String?,
    val hideTitle: Boolean, val landscape: Boolean, val sources: List<CSource>,
)

data class CRow(val title: String, val folders: List<CFolder>)

private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

/** Reads a collection file; throws if it isn't one. */
fun parseCollection(raw: String): List<CRow> {
    val root = Json { isLenient = true }.parseToJsonElement(raw.trim())
    // A list of rows, or an object holding one (exports differ).
    val rows: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root.values.filterIsInstance<JsonArray>().firstOrNull { a -> a.any { (it as? JsonObject)?.containsKey("folders") == true } }
            ?: throw IllegalArgumentException("not a collection")
        else -> throw IllegalArgumentException("not a collection")
    }
    val parsed = rows.mapNotNull { r ->
        val ro = r as? JsonObject ?: return@mapNotNull null
        val folders = (ro["folders"] as? JsonArray ?: return@mapNotNull null).mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            val src = (fo["sources"] as? JsonArray ?: fo["catalogSources"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { x ->
                val xo = x as? JsonObject ?: return@mapNotNull null
                CSource(xo["type"].s() ?: "", xo["catalogId"].s() ?: return@mapNotNull null, xo["addonId"].s(),
                    xo["genre"].s()?.takeIf { !it.equals("None", true) })
            }
            CFolder(
                id = fo["id"].s() ?: (fo["title"].s() ?: "folder"), title = fo["title"].s() ?: "",
                cover = fo["coverImageUrl"].s(), logo = fo["titleLogoUrl"].s(), backdrop = fo["heroBackdropUrl"].s(),
                hideTitle = fo["hideTitle"].s() == "true", landscape = fo["tileShape"].s()?.uppercase() != "POSTER", sources = src,
            )
        }.filter { it.sources.isNotEmpty() }
        CRow(ro["title"].s() ?: "", folders).takeIf { folders.isNotEmpty() }
    }
    if (parsed.isEmpty()) throw IllegalArgumentException("not a collection")
    return parsed
}

/** A GitHub page link becomes its raw file link. */
fun rawLink(url: String): String {
    val m = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/(.+)$""").find(url.trim()) ?: return url.trim()
    return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
}

/** Matches a collection source to an installed add-on's catalog: by catalog id, preferring the same kind (film/series). */
fun resolveSource(installed: List<Installed>, s: CSource): Pair<Installed, Catalog>? {
    val want = s.type.lowercase().let { t -> when { t.startsWith("movie") -> "movie"; t == "series" || t == "tv" || t == "shows" -> "series"; else -> t } }
    val candidates = installed.sortedByDescending { it.manifest.id == s.addonId }.flatMap { a -> a.manifest.catalogs.filter { it.id == s.catalogId }.map { a to it } }
    return candidates.firstOrNull { it.second.type.equals(want, true) } ?: candidates.firstOrNull()
}
