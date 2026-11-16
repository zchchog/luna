package tv.luna.player.data.epg

/**
 * Built-in name clean-up rules (spec §4.4). Stored in `NameRuleEntity.pattern` by name.
 * Declaration order is the default rule order: QUALITY_SUFFIX only strips trailing tokens,
 * so it runs after the rules that remove trailing noise ("HD (Backup)", "HD +1").
 */
enum class BuiltinRule { COUNTRY_PREFIX, BRACKETS, VIP, PLUS_ONE, QUALITY_SUFFIX }
