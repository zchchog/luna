package tv.luna.player.data.catalogue

/** Country / language detection from provider category names (spec §3). */

private class Country(val label: String, val language: String)

private val COUNTRIES: Map<String, Country> = buildMap {
    fun add(language: String, vararg pairs: Pair<String, String>) = pairs.forEach { (code, label) -> put(code, Country(label, language)) }
    add("English", "GB" to "United Kingdom", "US" to "United States", "CA" to "Canada", "AU" to "Australia", "IE" to "Ireland", "NZ" to "New Zealand")
    add("Arabic", "SA" to "Arabic", "AE" to "United Arab Emirates", "EG" to "Egypt", "QA" to "Qatar", "KW" to "Kuwait", "MA" to "Morocco",
        "DZ" to "Algeria", "TN" to "Tunisia", "LB" to "Lebanon", "JO" to "Jordan", "IQ" to "Iraq", "SY" to "Syria", "OM" to "Oman",
        "BH" to "Bahrain", "YE" to "Yemen", "LY" to "Libya", "SD" to "Sudan")
    add("French", "FR" to "France", "BE" to "Belgium")
    add("German", "DE" to "Germany", "AT" to "Austria", "CH" to "Switzerland")
    add("Spanish", "ES" to "Spain", "MX" to "Mexico")
    add("Portuguese", "PT" to "Portugal", "BR" to "Brazil")
    add("Italian", "IT" to "Italy")
    add("Dutch", "NL" to "Netherlands")
    add("Polish", "PL" to "Poland")
    add("Turkish", "TR" to "Turkey")
    add("Hindi", "IN" to "India")
    add("Urdu", "PK" to "Pakistan")
    add("Bengali", "BD" to "Bangladesh")
    add("Persian", "IR" to "Iran")
    add("Russian", "RU" to "Russia")
    add("Ukrainian", "UA" to "Ukraine")
    add("Greek", "GR" to "Greece")
    add("Swedish", "SE" to "Sweden")
    add("Norwegian", "NO" to "Norway")
    add("Danish", "DK" to "Denmark")
    add("Finnish", "FI" to "Finland")
    add("Romanian", "RO" to "Romania")
    add("Albanian", "AL" to "Albania")
    add("Serbian", "RS" to "Serbia")
    add("Croatian", "HR" to "Croatia")
    add("Bulgarian", "BG" to "Bulgaria")
    add("Hungarian", "HU" to "Hungary")
    add("Czech", "CZ" to "Czechia")
    add("Hebrew", "IL" to "Israel")
    add("Chinese", "CN" to "China")
    add("Japanese", "JP" to "Japan")
    add("Korean", "KR" to "South Korea")
    add("Thai", "TH" to "Thailand")
}

private val ALIASES = mapOf("UK" to "GB", "USA" to "US", "AR" to "SA", "ARAB" to "SA", "KSA" to "SA", "UAE" to "AE", "EN" to "GB")

/** ISO 15924 tag → (script label, language). */
private val SCRIPTS: Map<Character.UnicodeScript, Triple<String, String, String>> = mapOf(
    Character.UnicodeScript.ARABIC to Triple("Arab", "Arabic", "Arabic"),
    Character.UnicodeScript.CYRILLIC to Triple("Cyrl", "Cyrillic", "Russian"),
    Character.UnicodeScript.GREEK to Triple("Grek", "Greek", "Greek"),
    Character.UnicodeScript.HEBREW to Triple("Hebr", "Hebrew", "Hebrew"),
    Character.UnicodeScript.HAN to Triple("Hani", "Chinese", "Chinese"),
    Character.UnicodeScript.HIRAGANA to Triple("Jpan", "Japanese", "Japanese"),
    Character.UnicodeScript.KATAKANA to Triple("Jpan", "Japanese", "Japanese"),
    Character.UnicodeScript.HANGUL to Triple("Hang", "Korean", "Korean"),
    Character.UnicodeScript.THAI to Triple("Thai", "Thai", "Thai"),
    Character.UnicodeScript.DEVANAGARI to Triple("Deva", "Devanagari", "Hindi"),
    Character.UnicodeScript.BENGALI to Triple("Beng", "Bengali", "Bengali"),
    Character.UnicodeScript.TAMIL to Triple("Taml", "Tamil", "Tamil"),
    Character.UnicodeScript.GURMUKHI to Triple("Guru", "Gurmukhi", "Punjabi"),
    Character.UnicodeScript.ARMENIAN to Triple("Armn", "Armenian", "Armenian"),
    Character.UnicodeScript.GEORGIAN to Triple("Geor", "Georgian", "Georgian"),
)
private val SCRIPT_BY_TAG = SCRIPTS.values.associateBy { it.first }

private val NOISE = Regex("^(?:4K|UHD|FHD|HD)\\s*[-|:]\\s*")
/** Optional opener, an upper-case 2–4 letter code not followed by another letter, then the next non-space char. */
private val PREFIX = Regex("^([\\[(|]?)\\s*([A-Z]{2,4})(?![A-Za-z])\\s*(\\S?)")
private const val SEPARATORS = "|:-])"

/** "ENGLISH SERIES", "TURKISH ATV SERIES": a language named in the category, with no country. */
private val LANGUAGE_WORD = Regex(
    "\\b(ENGLISH|ARABIC|TURKISH|FRENCH|GERMAN|SPANISH|ITALIAN|PORTUGUESE|DUTCH|POLISH|HINDI|URDU|PERSIAN|RUSSIAN|GREEK|ALBANIAN|KURDISH|CHINESE|JAPANESE|KOREAN)\\b",
    RegexOption.IGNORE_CASE,
)

fun detectCountry(categoryName: String): String? {
    var s = categoryName.trim()
    val noise = NOISE.find(s)
    if (noise != null) s = s.substring(noise.range.last + 1)
    PREFIX.find(s)?.let { m ->
        val (opener, code, next) = m.destructured
        val country = ALIASES[code] ?: code.takeIf { it in COUNTRIES }
        // A bare "CODE " only counts after noise like "4K-", so "USA Network" stays null.
        if (country != null && (opener.isNotEmpty() || (next.isNotEmpty() && next[0] in SEPARATORS) || noise != null)) return country
    }
    LANGUAGE_WORD.find(categoryName)?.let { return "lang:" + it.value.lowercase().replaceFirstChar(Char::uppercase) }
    return dominantScript(categoryName)
}

private fun dominantScript(s: String): String? {
    var letters = 0
    val counts = HashMap<String, Int>()
    s.codePoints().forEach { cp ->
        if (!Character.isLetter(cp)) return@forEach
        letters++
        SCRIPTS[Character.UnicodeScript.of(cp)]?.let { counts.merge(it.first, 1, Int::plus) }
    }
    val best = counts.maxByOrNull { it.value } ?: return null
    return if (best.value * 2 >= letters) "script:${best.key}" else null
}

fun languageOf(country: String?): String = when {
    country == null -> "Other"
    country.startsWith("script:") -> SCRIPT_BY_TAG[country.removePrefix("script:")]?.third ?: "Other"
    country.startsWith("lang:") -> country.removePrefix("lang:")
    else -> COUNTRIES[country]?.language ?: "Other"
}

/** ISO codes of the countries whose language is [language] ("English" → GB, US, CA, AU, IE, NZ). */
fun countriesOfLanguage(language: String): Set<String> = COUNTRIES.filterValues { it.language == language }.keys

fun countryLabel(country: String?): String = when {
    country == null -> "Other"
    country.startsWith("script:") -> SCRIPT_BY_TAG[country.removePrefix("script:")]?.let { "${it.second} script" } ?: country
    country.startsWith("lang:") -> "Any country"
    else -> COUNTRIES[country]?.label ?: country
}

/** The TV's language as an English name ("English", "Arabic"), the form [languageOf] returns. */
fun tvLanguage(): String = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)

/** Kept by "Match TV language": that language, or no language detected at all (generic names like "NETFLIX SERIES"). */
fun matchesLanguage(country: String?, language: String): Boolean = languageOf(country).let { it == language || it == "Other" }
