package tv.luna.player.data.logos

import tv.luna.player.data.epg.BuiltinRule
import tv.luna.player.data.epg.NameNormaliser
import java.text.Normalizer

/** [key] is the name without country prefix, quality/encoding tags and brackets; [quality] is "uhd", "hd" or null. */
data class LogoKey(val key: String, val quality: String?)

/** Trailing run of whole-word quality and encoding tokens. */
private val TAIL = Regex("""(?i)(?:(?:^|[\s\p{Z}\-|]+)(?:UHD|4K|FHD|HD|SD|HEVC|H\.?265|H\.?264|RAW|\d{2,3}[\s\p{Z}]?FPS|\d{3,4}[PI]))+[\s\p{Z}\-|]*$""")
/** "Dunya News UK", "BBC News UK HD": a trailing country word, as guides and tv-logos name the channel without it.
 *  shortcut: English-speaking countries only; extend the list if other countries' playlists name channels this way. */
private val COUNTRY_TAIL = Regex("""(?i)[\s\p{Z}\-|]+(?:UK|GB|US|USA|IE|CA|AU|NZ)[\s\p{Z}\-|]*$""")
private val UHD_LINES = setOf("2160P", "3840P", "4320P")
private val HD_LINES = setOf("720P", "1080P", "1080I")
private val TOKEN_SPLIT = Regex("""[\s\p{Z}\-|]+""")
private val MARKS = Regex("""[\p{M}]+""")
/** "◉", "★" and the like: as spaces, so a trailing one doesn't hide the HD/4K tag before it. */
private val SYMBOLS = Regex("""[\p{So}]+""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * The logo key of spec §4.2 (`"UK: Sky Sports Main Event ᵁᴴᴰ"` → `sky-sports-main-event`, uhd);
 * also the sibling key used to borrow guides. Null when nothing is left.
 */
private val NUMBER_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    .withIndex().associate { (i, w) -> w to "${i + 1}" }
/** "+1" timeshift channels: tv-logos names them "-plus" (channel-4-plus-uk.png). */
private val PLUS_ONE = Regex("""\+[\s\p{Z}]*1(?![0-9])""")

/** Number words as digits, token by token ("bbc-two" → "bbc-2"): tv-logos spells numbers, providers mostly don't. */
fun digitsKey(key: String): String = key.split('-').joinToString("-") { NUMBER_WORDS[it] ?: it }

/** The logo key without separators: "BBC Two HD", "UK: BBC 2 HEVC" → "bbc2"; "ITV1" = "ITV 1". */
fun compactKey(name: String, dropCountry: Boolean = true): String? = logoKey(name, dropCountry)?.key?.replace("-", "")?.takeIf { it.isNotEmpty() }

/** [dropCountry] false for guide-side names: "Comedy Central US" must not become a key a UK channel can match. */
fun logoKey(name: String, dropCountry: Boolean = true): LogoKey? {
    var s = Normalizer.normalize(name, Normalizer.Form.NFKC).replace(SYMBOLS, " ")
    for (r in listOf(BuiltinRule.COUNTRY_PREFIX, BuiltinRule.BRACKETS, BuiltinRule.VIP)) s = NameNormaliser.applyBuiltin(r, s)
    var quality: String? = null
    fun dropTail() = TAIL.find(s)?.let { m ->
        val tokens = m.value.split(TOKEN_SPLIT).map { it.uppercase() }
        quality = quality ?: when {
            tokens.any { it == "UHD" || it == "4K" || it in UHD_LINES } -> "uhd"
            tokens.any { it == "HD" || it == "FHD" || it in HD_LINES } -> "hd"
            else -> null
        }
        s = s.substring(0, m.range.first)
    }
    dropTail()
    if (dropCountry) COUNTRY_TAIL.find(s)?.let { s = s.substring(0, it.range.first); dropTail() } // "… UK HD" and "… HD UK"
    val key = digitsKey(Normalizer.normalize(s.replace(PLUS_ONE, " plus "), Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(NON_WORD, "-").trim('-'))
    return if (key.isEmpty()) null else LogoKey(key, quality)
}
