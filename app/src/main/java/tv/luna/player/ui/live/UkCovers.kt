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

/** Well-known UK series and shows whose covers fill the poster wall (found by name, once, on TVmaze). */
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

private const val MAX_AGE_MS = 14L * 24 * 3_600_000

/**
 * Cover pictures for the UK shows above, from TVmaze's free public search (no account or key; only the show names are sent).
 * Remembered in a file for two weeks, so the search runs rarely. [onMore] gets the covers found so far, as they arrive.
 */
suspend fun ukCovers(context: Context, http: OkHttpClient, onMore: (List<String>) -> Unit): List<String> = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, "uk-covers.txt")
    if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
        val saved = file.readLines().filter { it.startsWith("http") }
        if (saved.size >= 12) return@withContext saved.shuffled()
    }
    val found = ArrayList<String>()
    for (title in UK_SHOWS) {
        val url = runCatching { coverFor(http, title) }.getOrNull()
        if (url != null && url !in found) {
            found += url
            if (found.size == 6 || found.size % 6 == 0) onMore(found.shuffled())
        }
        delay(550) // the service allows about 20 searches every 10 seconds
    }
    if (found.size >= 12) file.writeText(found.joinToString("\n"))
    found.shuffled()
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
