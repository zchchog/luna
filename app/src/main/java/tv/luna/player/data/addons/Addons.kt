package tv.luna.player.data.addons

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.luna.player.data.security.Secrets
import java.net.URLEncoder

/*
 * On Demand: films and series from add-ons that speak the open Stremio add-on protocol (plain JSON over HTTP):
 *   <base>/manifest.json                    what the add-on offers
 *   <base>/catalog/<type>/<id>.json         a row of titles
 *   <base>/meta/<type>/<id>.json            one title, with its episodes
 *   <base>/stream/<type>/<videoId>.json     links to play
 * Only links with a plain http(s) URL are used (debrid services give those); torrents are skipped.
 */

data class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class Catalog(val type: String, val id: String, val name: String, val needsInput: Boolean, val canSearch: Boolean)

data class Manifest(
    val id: String, val name: String, val description: String?, val logo: String?,
    val types: List<String>, val idPrefixes: List<String>?, val resources: List<Resource>, val catalogs: List<Catalog>,
) {
    /** Whether this add-on answers [resource] for [type] and an id like [id] (null: any id). */
    fun serves(resource: String, type: String, id: String? = null): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = r.idPrefixes ?: idPrefixes
        return id == null || prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }
    /** What it does, for Settings: "Films and series", "Sources to play", or both. */
    val does: String get() = listOfNotNull(
        "Films and series".takeIf { catalogs.isNotEmpty() || resources.any { it.name == "meta" } },
        "Sources to play".takeIf { resources.any { it.name == "stream" } },
    ).joinToString(" · ").ifEmpty { description ?: "Add-on" }
    /** What it is to a viewer: the provider's Metadata link (films and series) or Host link (sources). */
    val role: String get() = when {
        catalogs.isNotEmpty() || resources.any { it.name == "meta" } -> "Metadata link"
        resources.any { it.name == "stream" } -> "Host link"
        else -> "Link"
    }
}

data class MetaPreview(
    val id: String, val type: String, val name: String, val poster: String?, val background: String?,
    val description: String?, val releaseInfo: String?, val rating: String?,
)

data class Video(
    val id: String, val title: String, val season: Int?, val episode: Int?, val thumbnail: String?, val overview: String?, val released: String?,
)

data class Meta(
    val id: String, val type: String, val name: String, val poster: String?, val background: String?, val logo: String?,
    val description: String?, val releaseInfo: String?, val runtime: String?, val rating: String?,
    val genres: List<String>, val cast: List<String>, val imdbId: String?, val videos: List<Video>,
) {
    fun preview() = MetaPreview(id, type, name, poster, background, description, releaseInfo, rating)
    val seasons: List<Int> get() = videos.mapNotNull { it.season }.distinct().sorted().let { s -> if (s.size > 1) s.filter { it > 0 } else s } // specials last
}

data class Stream(
    val url: String, val name: String, val title: String, val headers: Map<String, String>, val addon: String,
) {
    /** 4K / 1080p / 720p / SD, read from the add-on's text. */
    val quality: String get() = qualityOf("$name $title")
}

fun qualityOf(text: String): String {
    val t = text.lowercase()
    return when {
        "2160" in t || "4k" in t || "uhd" in t -> "4K"
        "1080" in t -> "1080p"
        "720" in t -> "720p"
        "480" in t || "576" in t || " sd" in t -> "SD"
        else -> "?"
    }
}

/** Where a title was left: one per film, one per series (its last episode). */
@Serializable
data class Progress(
    val metaId: String, val type: String, val videoId: String, val name: String, val subtitle: String? = null,
    val poster: String? = null, val background: String? = null, val positionMs: Long, val durationMs: Long, val at: Long,
    val imdbId: String? = null,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** What is playing now, so its place can be saved when the player closes. */
data class NowPlaying(val progress: Progress, @Volatile var next: Progress? = null, @Volatile var watched: Boolean = false)

/** A title saved to My list. */
@Serializable
data class MyListItem(
    val id: String, val type: String, val name: String, val poster: String? = null, val background: String? = null,
    val description: String? = null, val releaseInfo: String? = null, val rating: String? = null,
) {
    fun preview() = MetaPreview(id, type, name, poster, background, description, releaseInfo, rating)
}
fun MetaPreview.saved() = MyListItem(id, type, name, poster, background, description, releaseInfo, rating)

@Serializable
private data class Saved(val url: String, val manifest: String)

@Serializable
data class SavedCollection(val name: String, val raw: String)

class Installed(val url: String, val manifest: Manifest) {
    val base: String get() = url.removeSuffix("/manifest.json")
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** Accepts a pasted link in any common shape and returns its https manifest address. */
fun manifestUrl(input: String): String? {
    var u = input.trim().trim('"', '\'')
    if (u.isEmpty()) return null
    if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
    u = u.substringBefore('#').trimEnd('/')
    if (u.endsWith("/configure")) u = u.removeSuffix("/configure")
    if (!u.endsWith("/manifest.json")) u = "$u/manifest.json"
    return u
}

class AddonRepository(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("addons") }
    private val addonsKey = stringPreferencesKey("addons")
    private val rawManifests: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val progressKey = stringPreferencesKey("progress")
    private val collectionsKey = stringPreferencesKey("collections")
    private val myListKey = stringPreferencesKey("my_list")
    private val qualityKey = stringPreferencesKey("preferred_quality")

    /** My list: newest first. */
    val myList = store.data.map { p -> p[myListKey]?.let { runCatching { json.decodeFromString<List<MyListItem>>(it) }.getOrNull() } ?: emptyList() }

    suspend fun toggleMyList(m: MetaPreview) {
        store.edit { prefs ->
            val list = prefs[myListKey]?.let { runCatching { json.decodeFromString<List<MyListItem>>(it) }.getOrNull() } ?: emptyList()
            val next = if (list.any { it.id == m.id }) list.filter { it.id != m.id } else listOf(m.saved()) + list
            prefs[myListKey] = json.encodeToString(next.take(200))
        }
    }

    /** The source quality picked last (4K, 1080p…), tried first next time. */
    val preferredQuality = store.data.map { it[qualityKey] }
    suspend fun setPreferredQuality(q: String) { if (q != "?") store.edit { it[qualityKey] = q } }

    private val builtinKey = stringPreferencesKey("kollection_raw")
    private val builtinOffKey = androidx.datastore.preferences.core.booleanPreferencesKey("kollection_off")

    /** Collections added by hand. */
    private val _collections = MutableStateFlow<List<Pair<SavedCollection, List<CRow>>>>(emptyList())
    val userCollections: StateFlow<List<Pair<SavedCollection, List<CRow>>>> = _collections

    /** The Kollection by Kaoxt (kollection.tv): the built-in home layout, read from its public link and kept for offline starts. */
    private var builtinRows: List<CRow> = emptyList()
    private val _kollectionOn = MutableStateFlow(true)
    val kollectionOn: StateFlow<Boolean> = _kollectionOn

    /** What the home shows: hand-added collections, else The Kollection (when on). */
    private val _shown = MutableStateFlow<List<Pair<SavedCollection, List<CRow>>>>(emptyList())
    val collections: StateFlow<List<Pair<SavedCollection, List<CRow>>>> = _shown
    private fun publish() {
        _shown.value = _collections.value.ifEmpty {
            if (_kollectionOn.value && builtinRows.isNotEmpty()) listOf(SavedCollection(KOLLECTION_NAME, "") to builtinRows) else emptyList()
        }
    }

    suspend fun setKollection(on: Boolean) {
        _kollectionOn.value = on
        store.edit { it[builtinOffKey] = !on }
        publish()
        if (on && builtinRows.isEmpty()) refreshKollection()
    }

    private suspend fun refreshKollection() {
        runCatching {
            val raw = getText(KOLLECTION_URL)
            val rows = parseCollection(raw)
            builtinRows = rows
            store.edit { it[builtinKey] = raw }
            publish()
        }.onFailure { Log.w(TAG, "The Kollection didn't load: ${it.message}") }
    }

    private val _installed = MutableStateFlow<List<Installed>?>(null)
    /** null until loaded from disk. */
    val installed: StateFlow<List<Installed>?> = _installed

    val progress = store.data.map { p ->
        p[progressKey]?.let { runCatching { json.decodeFromString<List<Progress>>(it) }.getOrNull() } ?: emptyList()
    }

    @Volatile var nowPlaying: NowPlaying? = null

    private val cache = object : LinkedHashMap<String, JsonObject>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > 120
    }

    init {
        scope.launch {
            val saved = store.data.first()[addonsKey]?.let { raw ->
                runCatching { json.decodeFromString<List<Saved>>(Secrets.decrypt(raw) ?: "[]") }.getOrNull()
            } ?: emptyList()
            val prefs = store.data.first()
            _collections.value = prefs[collectionsKey]?.let { raw ->
                runCatching { json.decodeFromString<List<SavedCollection>>(raw) }.getOrNull()
            }.orEmpty().mapNotNull { c -> runCatching { c to parseCollection(c.raw) }.getOrNull() }
            _kollectionOn.value = prefs[builtinOffKey] != true
            builtinRows = prefs[builtinKey]?.let { runCatching { parseCollection(it) }.getOrNull() }.orEmpty()
            publish()
            if (_kollectionOn.value) launch { refreshKollection() }
            _installed.value = saved.mapNotNull { s -> runCatching { Installed(s.url, parseManifest(json.parseToJsonElement(s.manifest).jsonObject)) }.getOrNull() }
            refreshManifests()
        }
    }

    private suspend fun persist(list: List<Installed>, raw: Map<String, String>) {
        _installed.value = list
        val saved = list.map { Saved(it.url, raw[it.url] ?: "{}") }
        store.edit { it[addonsKey] = Secrets.encrypt(json.encodeToString(saved)) ?: "" }
    }


    /** Add-ons can change what they offer (e.g. after reconfiguring AIOMetadata): re-read them on each start. */
    private suspend fun refreshManifests() {
        val list = _installed.value ?: return
        val fresh = list.map { a ->
            runCatching { val raw = getText(a.url); rawManifests[a.url] = raw; Installed(a.url, parseManifest(json.parseToJsonElement(raw).jsonObject)) }
                .getOrElse { a }
        }
        if (rawManifests.isNotEmpty()) persist(fresh, rawManifests + list.filter { it.url !in rawManifests }.associate { it.url to "{}" })
    }

    /** Adds the add-on at [input]; returns its name. Throws with a message a person can read. */
    suspend fun add(input: String): String {
        val url = manifestUrl(input) ?: throw IllegalArgumentException("That doesn't look like a link")
        val raw = try { getText(url) } catch (e: Exception) { throw IllegalStateException("Couldn't reach that link (${e.message ?: "no answer"})") }
        val manifest = try { parseManifest(json.parseToJsonElement(raw).jsonObject) } catch (e: Exception) {
            throw IllegalStateException("That link didn't work. Check it was copied in full")
        }
        val list = (_installed.value ?: emptyList()).filter { it.url != url } + Installed(url, manifest)
        rawManifests[url] = raw
        persist(list, rawManifests)
        return manifest.role
    }

    /** Adds a collection from a link or pasted file contents; returns a short description. */
    suspend fun addCollection(input: String): String {
        val text = input.trim()
        val raw = if (text.startsWith("[") || text.startsWith("{")) text else {
            val url = rawLink(if (text.startsWith("http")) text else "https://$text")
            try { getText(url) } catch (e: Exception) { throw IllegalStateException("Couldn't reach that link (${e.message ?: "no answer"})") }
        }
        val rows = try { parseCollection(raw) } catch (e: Exception) { throw IllegalStateException("That isn't a collection file") }
        val name = rows.take(3).joinToString(", ") { it.title } + if (rows.size > 3) "…" else ""
        val next = _collections.value + (SavedCollection(name, raw) to rows)
        _collections.value = next
        publish()
        store.edit { it[collectionsKey] = json.encodeToString(next.map { p -> p.first }) }
        return "a collection with ${rows.size} rows"
    }

    suspend fun removeCollection(index: Int) {
        val next = _collections.value.filterIndexed { i, _ -> i != index }
        _collections.value = next
        publish()
        store.edit { it[collectionsKey] = json.encodeToString(next.map { p -> p.first }) }
    }

    /** Both links in (films and series, and sources): On Demand is unlocked. */
    fun unlocked(): Boolean = (_installed.value ?: emptyList()).let { l ->
        l.any { it.manifest.catalogs.isNotEmpty() } && l.any { a -> a.manifest.resources.any { it.name == "stream" } }
    }

    suspend fun remove(url: String) {
        val list = (_installed.value ?: return).filter { it.url != url }
        rawManifests.remove(url)
        persist(list, rawManifests)
    }

    // ---- Network ----

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.string() ?: throw IllegalStateException("empty answer")
        }
    }

    private suspend fun getJson(url: String, cached: Boolean = true): JsonObject {
        if (cached) synchronized(cache) { cache[url] }?.let { return it }
        val o = json.parseToJsonElement(getText(url)).jsonObject
        if (cached) synchronized(cache) { cache[url] = o }
        return o
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The home rows: every catalog that needs no typed input, in add-on order. */
    fun homeCatalogs(): List<Pair<Installed, Catalog>> =
        (_installed.value ?: emptyList()).flatMap { a -> a.manifest.catalogs.filter { !it.needsInput && it.type in setOf("movie", "series") }.map { a to it } }.take(40)

    suspend fun catalog(addon: Installed, c: Catalog, skip: Int = 0, genre: String? = null): List<MetaPreview> {
        val parts = listOfNotNull(genre?.let { "genre=${enc(it)}" }, if (skip > 0) "skip=$skip" else null)
        val extra = if (parts.isEmpty()) "" else "/" + parts.joinToString("&")
        val o = getJson("${addon.base}/catalog/${c.type}/${enc(c.id)}$extra.json")
        return (o["metas"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parsePreview) }
    }

    /** Titles matching [query] from every add-on catalog that can search. */
    suspend fun search(query: String): List<Pair<String, List<MetaPreview>>> = coroutineScope {
        (_installed.value ?: emptyList()).flatMap { a -> a.manifest.catalogs.filter { it.canSearch && it.type in setOf("movie", "series") }.map { a to it } }
            .map { (a, c) ->
                async {
                    runCatching {
                        val o = getJson("${a.base}/catalog/${c.type}/${enc(c.id)}/search=${enc(query)}.json")
                        val label = if (c.type == "movie") "Films" else "Series"
                        label to (o["metas"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parsePreview) }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
            .groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.flatten().distinctBy { it.id } }
    }

    /** Full details from the first add-on that has them. */
    suspend fun meta(type: String, id: String): Meta? {
        for (a in _installed.value ?: emptyList()) {
            if (!a.manifest.serves("meta", type, id)) continue
            val m = runCatching { getJson("${a.base}/meta/$type/${enc(id)}.json")["meta"] as? JsonObject }.getOrNull() ?: continue
            return parseMeta(m, type)
        }
        return null
    }

    /** Links to play [videoId] from every add-on that has some, in add-on order (each add-on sorts its own). */
    suspend fun streams(type: String, videoId: String, imdbId: String?): List<Stream> = coroutineScope {
        (_installed.value ?: emptyList()).filter { it.manifest.resources.any { r -> r.name == "stream" } }.map { a ->
            async {
                // An add-on that only knows IMDb ids gets the IMDb form of a TMDB-style id.
                val id = when {
                    a.manifest.serves("stream", type, videoId) -> videoId
                    imdbId != null && a.manifest.serves("stream", type, imdbId) -> imdbId + (if (type == "movie") "" else videoId.split(':').let { p -> if (p.size >= 3) ":" + p.takeLast(2).joinToString(":") else "" })
                    else -> return@async emptyList()
                }
                runCatching {
                    val o = getJson("${a.base}/stream/$type/${enc(id)}.json", cached = false)
                    (o["streams"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { s -> parseStream(s, a.manifest.name) } }
                }.onFailure { Log.w(TAG, "streams from ${a.manifest.name} failed: ${it.message}") }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    // ---- Watching ----

    suspend fun saveProgress(p: Progress) {
        store.edit { prefs ->
            val list = prefs[progressKey]?.let { runCatching { json.decodeFromString<List<Progress>>(it) }.getOrNull() } ?: emptyList()
            // Finished (95%+) leaves Continue watching; a series keeps one entry, its latest episode.
            val rest = list.filter { it.metaId != p.metaId }
            val next = if (p.durationMs > 0 && p.positionMs > p.durationMs * 0.95) rest else listOf(p) + rest
            prefs[progressKey] = json.encodeToString(next.take(30))
        }
    }

    suspend fun forget(metaId: String) {
        store.edit { prefs ->
            val list = prefs[progressKey]?.let { runCatching { json.decodeFromString<List<Progress>>(it) }.getOrNull() } ?: return@edit
            prefs[progressKey] = json.encodeToString(list.filter { it.metaId != metaId })
        }
    }

    /** Called as the player closes: saves where [nowPlaying] got to. */
    fun leftPlayer(positionMs: Long, durationMs: Long) {
        val now = nowPlaying ?: return
        nowPlaying = null
        if (positionMs < 30_000 && !now.watched) return // a few seconds in isn't worth remembering
        val finished = now.watched || (durationMs > 0 && positionMs > durationMs * 0.95)
        val next = now.next
        scope.launch {
            // An episode watched to the end: Continue watching moves on to the next one ("Up next").
            if (finished && next != null) saveProgress(next.copy(at = System.currentTimeMillis()))
            else saveProgress(now.progress.copy(positionMs = positionMs, durationMs = durationMs, at = System.currentTimeMillis()))
        }
    }

    companion object {
        private const val TAG = "LunaAddons"
        const val KOLLECTION_NAME = "The Kollection"
        /** Kaoxt's public collection file (github.com/Kaoxt/The-Kollection). */
        const val KOLLECTION_URL = "https://raw.githubusercontent.com/Kaoxt/The-Kollection/main/nuvio-collections-profile-1-2026-06-20%20(1).json"
    }
}

// ---- Parsing (lenient: add-ons differ in which fields they send and how) ----

private fun JsonElement?.str(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    else -> null
}
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
private fun JsonElement?.strings(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.str() ?: (it as? JsonObject)?.get("name").str() }
    is JsonPrimitive -> str()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    else -> emptyList()
}

fun parseManifest(o: JsonObject): Manifest {
    val types = o["types"].strings()
    val prefixes = (o["idPrefixes"] as? JsonArray)?.mapNotNull { it.str() }
    val resources = (o["resources"] as? JsonArray).orEmpty().mapNotNull { r ->
        when (r) {
            is JsonPrimitive -> r.str()?.let { Resource(it, null, null) }
            is JsonObject -> r["name"].str()?.let { Resource(it, (r["types"] as? JsonArray)?.mapNotNull { t -> t.str() }, (r["idPrefixes"] as? JsonArray)?.mapNotNull { t -> t.str() }) }
            else -> null
        }
    }
    val catalogs = (o["catalogs"] as? JsonArray).orEmpty().mapNotNull { c ->
        val co = c as? JsonObject ?: return@mapNotNull null
        val extras = (co["extra"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val required = extras.filter { (it["isRequired"] as? JsonPrimitive)?.contentOrNull == "true" }.mapNotNull { it["name"].str() } +
            co["extraRequired"].strings()
        val supported = extras.mapNotNull { it["name"].str() } + co["extraSupported"].strings()
        Catalog(
            type = co["type"].str() ?: return@mapNotNull null, id = co["id"].str() ?: return@mapNotNull null,
            name = unbrand(co["name"].str() ?: co["id"].str()!!).ifEmpty { "Featured" }, needsInput = required.isNotEmpty(), canSearch = "search" in supported,
        )
    }
    return Manifest(
        id = o["id"].str() ?: "addon", name = o["name"].str() ?: "Add-on", description = o["description"].str(), logo = o["logo"].str(),
        types = types, idPrefixes = prefixes, resources = resources, catalogs = catalogs,
    )
}

/** Viewers see the provider's links as Luna's own: the names of the services behind them are taken out of any text shown. */
fun unbrand(s: String): String = s.replace(Regex("(?i)\\b(aio\\s*streams|aio\\s*metadata|aiostreams|aiometadata|stremio|nuvio)\\b"), "")
    .replace(Regex("[ \\t]{2,}"), " ").lines().map { it.trim().trim('|', '·', '-').trim() }.filter { it.isNotEmpty() }.joinToString("\n")

private fun parsePreview(o: JsonObject): MetaPreview? {
    val id = o["id"].str() ?: return null
    return MetaPreview(
        id = id, type = o["type"].str() ?: "movie", name = o["name"].str() ?: return null,
        poster = o["poster"].str(), background = o["background"].str(), description = o["description"].str(),
        releaseInfo = o["releaseInfo"].str() ?: o["year"].str(), rating = o["imdbRating"].str(),
    )
}

private fun parseMeta(o: JsonObject, type: String): Meta {
    val videos = (o["videos"] as? JsonArray).orEmpty().mapNotNull { v ->
        val vo = v as? JsonObject ?: return@mapNotNull null
        Video(
            id = vo["id"].str() ?: return@mapNotNull null, title = vo["title"].str() ?: vo["name"].str() ?: "",
            season = vo["season"].int(), episode = vo["episode"].int() ?: vo["number"].int(),
            thumbnail = vo["thumbnail"].str(), overview = vo["overview"].str() ?: vo["description"].str(), released = vo["released"].str(),
        )
    }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    val cast = o["cast"].strings().ifEmpty {
        (o["links"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it["category"].str() == "Cast" }.mapNotNull { it["name"].str() }
    }
    val genres = o["genres"].strings().ifEmpty { o["genre"].strings() }
    return Meta(
        id = o["id"].str() ?: "", type = o["type"].str() ?: type, name = o["name"].str() ?: "",
        poster = o["poster"].str(), background = o["background"].str(), logo = o["logo"].str(),
        description = o["description"].str(), releaseInfo = o["releaseInfo"].str() ?: o["year"].str(), runtime = o["runtime"].str(),
        rating = o["imdbRating"].str(), genres = genres, cast = cast.take(6),
        imdbId = (o["imdb_id"].str() ?: o["imdbId"].str() ?: o["id"].str())?.takeIf { it.startsWith("tt") }, videos = videos,
    )
}

private fun parseStream(o: JsonObject, addon: String): Stream? {
    val url = o["url"].str()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val hints = o["behaviorHints"] as? JsonObject
    val headers = ((hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject)
        ?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap() ?: emptyMap()
    val name = unbrand(o["name"].str() ?: "").ifEmpty { "Source" }
    val title = unbrand(o["description"].str() ?: o["title"].str() ?: "")
    return Stream(url, name, title, headers, addon)
}
