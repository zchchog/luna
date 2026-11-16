package tv.luna.player.data.epg

import tv.luna.player.data.db.NameRuleEntity
import tv.luna.player.data.db.NameRuleType

/**
 * Applies a provider's enabled name rules in `order`, then lower-cases, collapses whitespace and trims
 * (spec §4.4). Used on both channel names and XMLTV display names so they compare equal.
 */
class NameNormaliser(rules: List<NameRuleEntity>) {

    private val steps: List<(String) -> String> = rules.filter { it.enabled }.sortedBy { it.order }.mapNotNull(::step)

    fun normalise(name: String): String =
        steps.fold(name) { acc, f -> f(acc) }.lowercase().replace(WHITESPACE, " ").trim()

    private fun step(rule: NameRuleEntity): ((String) -> String)? = when (rule.type) {
        NameRuleType.BUILTIN -> BuiltinRule.entries.firstOrNull { it.name == rule.pattern }?.let(::builtin)
        NameRuleType.FIND_REPLACE -> rule.pattern.takeIf { it.isNotEmpty() }?.let { find ->
            { s: String -> s.replace(find, rule.replacement.orEmpty(), ignoreCase = true) }
        }
        NameRuleType.REGEX -> runCatching { Regex(rule.pattern) }.getOrNull()?.let { re ->
            { s: String -> runCatching { re.replace(s, rule.replacement.orEmpty()) }.getOrDefault(s) }
        }
    }

    private fun builtin(rule: BuiltinRule): (String) -> String = { s -> applyBuiltin(rule, s) }

    companion object {
        fun applyBuiltin(rule: BuiltinRule, s: String): String = when (rule) {
            BuiltinRule.COUNTRY_PREFIX -> s.replace(COUNTRY_PREFIX, "")
            BuiltinRule.QUALITY_SUFFIX -> s.replace(QUALITY_SUFFIX, "")
            BuiltinRule.PLUS_ONE -> s.replace(PLUS_ONE, " ")
            BuiltinRule.BRACKETS -> s.replace(BRACKETS, " ")
            BuiltinRule.VIP -> s.replace(VIP, " ")
        }

        private val WHITESPACE = Regex("""[\s\p{Z}]+""")
        /** `UK:`, `|UK|`, `[UK]`, `UK -` — an upper-case 2–3 letter code at the start. */
        private val COUNTRY_PREFIX = Regex("""^[\s\p{Z}]*(?:\|[A-Z]{2,3}\||\[[A-Z]{2,3}\]|[A-Z]{2,3}[\s\p{Z}]*[:|]|[A-Z]{2,3}[\s\p{Z}]+-)[\s\p{Z}]*""")
        /** One or more trailing quality tokens. */
        private val QUALITY_SUFFIX = Regex("""(?i)(?:[\s\p{Z}\-|]+(?:FHD|UHD|HD|SD|4K))+[\s\p{Z}]*$""")
        private val PLUS_ONE = Regex("""[\s\p{Z}]*\+[\s\p{Z}]*1(?!\d)""")
        private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
        private val VIP = Regex("""(?i)\[VIP\]|\bVIP\b""")
    }
}
