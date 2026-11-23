package tv.luna.player.ui.live

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

/** Well-known UK series, used for the poster wall only when the popularity lookup fails (found by name on TVmaze). */
private val UK_SHOWS = listOf(
    "I'm a Celebrity...Get Me Out of Here!", "MobLand", "Line of Duty", "Peaky Blinders", "Happy Valley", "Doctor Who", "Sherlock",
    "Downton Abbey", "The Crown", "Broadchurch", "Luther", "Slow Horses", "Blue Lights", "Adolescence", "Bodyguard", "Fleabag",
    "The Office", "Only Fools and Horses", "Gavin & Stacey", "Derry Girls", "Peep Show", "Taskmaster", "Strictly Come Dancing",
    "Love Island", "The Traitors", "Gladiators", "Britain's Got Talent", "The Great British Bake Off", "MasterChef", "Top Gear",
    "The Grand Tour", "Mrs Brown's Boys", "EastEnders", "Coronation Street", "Emmerdale", "Casualty", "Call the Midwife", "Vera",
    "Shetland", "Midsomer Murders", "Death in Paradise", "Killing Eve", "Black Mirror", "Skins", "Misfits", "The Inbetweeners",
    "Gogglebox", "The Responder", "Baby Reindeer", "Mr Bates vs The Post Office", "Father Ted", "Blackadder", "Fawlty Towers",
    "Ghosts", "Inside No. 9", "Bake Off: The Professionals", "Would I Lie to You?", "Dragons' Den", "The Apprentice",
)

private const val MAX_AGE_MS = 28L * 24 * 3_600_000

/**
 * Cover pictures for the poster wall, from TVmaze's free public service (no account or key; no personal data is sent).
 * Every 28 days it looks at what is on UK TV and the streaming services over the coming two weeks, ranks the series by
 * TVmaze's popularity score and keeps the top ones; if that fails, a fixed list of well-known UK shows is searched instead.
 * The result is remembered in a file. While it is out of date, the old covers are shown until the new ones arrive.
 */
suspend fun ukCovers(context: Context, http: OkHttpClient, onMore: (List<String>) -> Unit): List<String> = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, "uk-covers.txt")
    val saved = if (file.exists()) file.readLines().filter { it.startsWith("http") } else emptyList()
    val fresh = saved.size >= 12 && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
    if (fresh) return@withContext saved.shuffled()
    if (saved.size >= 12) onMore(saved.shuffled()) // out of date: show the old ones while the new ones are fetched
    val popular = runCatching { popularCovers(http) }.getOrDefault(emptyList())
    val found = if (popular.size >= 20) popular else seededCovers(http, onMore.takeIf { saved.size < 12 })
    if (found.size >= 12) file.writeText(found.joinToString("\n"))
    if (found.size >= 12) found.shuffled() else saved.shuffled()
}

/** The most popular series on UK TV and on streaming over the next two weeks, as cover links. */
private suspend fun popularCovers(http: OkHttpClient): List<String> {
    val shows = LinkedHashMap<Long, Pair<Int, String>>() // show id -> (popularity, cover)
    val day = java.util.Calendar.getInstance()
    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    repeat(7) {
        val date = format.format(day.time)
        for (path in listOf("schedule?country=GB&date=$date", "schedule/web?date=$date")) {
            runCatching { collectShows(http, "https://api.tvmaze.com/$path", shows) }
            delay(550) // the service allows about 20 requests every 10 seconds
        }
        day.add(java.util.Calendar.DAY_OF_MONTH, 2)
    }
    return shows.values.sortedByDescending { it.first }.take(54).map { it.second }
}

private fun collectShows(http: OkHttpClient, url: String, into: MutableMap<Long, Pair<Int, String>>) {
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return
        val episodes = JSONArray(r.body?.string().orEmpty())
        for (i in 0 until episodes.length()) {
            val e = episodes.getJSONObject(i)
            val show = e.optJSONObject("show") ?: e.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
            val image = show.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("http") } ?: continue
            // Series only, in English: no news, sport, chat shows or game shows.
            if (show.optString("language") != "English" || show.optString("type") in SKIP_TYPES) continue
            val genres = show.optJSONArray("genres")
            if (genres != null && (0 until genres.length()).any { genres.optString(it) == "Children" }) continue
            into.putIfAbsent(show.optLong("id"), show.optInt("weight") to image.replace("http://", "https://"))
        }
    }
}

private val SKIP_TYPES = setOf("News", "Sports", "Talk Show", "Game Show", "Variety", "Panel Show", "Award Show")

/** The fixed list of well-known UK shows above, searched by name: the fallback when the popularity lookup fails. */
private suspend fun seededCovers(http: OkHttpClient, onMore: ((List<String>) -> Unit)?): List<String> {
    val found = ArrayList<String>()
    for (title in UK_SHOWS) {
        val url = runCatching { coverFor(http, title) }.getOrNull()
        if (url != null && url !in found) {
            found += url
            if (onMore != null && (found.size == 6 || found.size % 6 == 0)) onMore(found.shuffled())
        }
        delay(550)
    }
    return found
}

private fun coverFor(http: OkHttpClient, title: String): String? {
    val url = "https://api.tvmaze.com/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", title).build()
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return null
        val results = JSONArray(r.body?.string().orEmpty())
        var fallback: String? = null
        for (i in 0 until results.length()) {
            val show = results.getJSONObject(i).optJSONObject("show") ?: continue
            val image = show.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("http") } ?: continue
            val country = (show.optJSONObject("network") ?: show.optJSONObject("webChannel"))?.optJSONObject("country")?.optString("code")
            // The best match that comes from the UK; otherwise the first one with a picture.
            if (country == "GB") return image.replace("http://", "https://")
            if (fallback == null) fallback = image.replace("http://", "https://")
        }
        return fallback
    }
}
