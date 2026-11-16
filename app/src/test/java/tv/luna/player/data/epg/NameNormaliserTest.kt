package tv.luna.player.data.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.luna.player.data.db.NameRuleEntity
import tv.luna.player.data.db.NameRuleType

class NameNormaliserTest {
    private fun builtins(disabled: Set<BuiltinRule> = emptySet(), firstOrder: Int = 0) =
        BuiltinRule.entries.mapIndexed { i, r ->
            NameRuleEntity(providerId = 1, order = firstOrder + i, enabled = r !in disabled, type = NameRuleType.BUILTIN, pattern = r.name)
        }

    private val all = NameNormaliser(builtins())

    @Test
    fun builtInRules() {
        assertEquals("bbc one", all.normalise("UK: BBC One FHD"))
        assertEquals("itv 1", all.normalise("|UK| ITV 1 HD"))
        assertEquals("channel 4", all.normalise("[UK] Channel 4 +1"))
        assertEquals("sky sports", all.normalise("Sky Sports (Backup) 4K"))
        assertEquals("dave", all.normalise("[VIP] Dave"))
        assertEquals("film4", all.normalise("UK - Film4"))
    }

    @Test
    fun disabledRuleNotApplied() {
        assertEquals("bbc one hd", NameNormaliser(builtins(disabled = setOf(BuiltinRule.QUALITY_SUFFIX))).normalise("BBC One HD"))
    }

    @Test
    fun customRegexRuleRunsInOrder() {
        val rules = builtins(firstOrder = 1) +
            NameRuleEntity(providerId = 1, order = 0, enabled = true, type = NameRuleType.REGEX, pattern = "^SKY ", replacement = "")
        assertEquals("atlantic", NameNormaliser(rules).normalise("SKY Atlantic"))
    }

    @Test
    fun findReplaceRule() {
        val rules = listOf(NameRuleEntity(providerId = 1, order = 0, enabled = true, type = NameRuleType.FIND_REPLACE, pattern = "&", replacement = "and"))
        assertEquals("tom and jerry", NameNormaliser(rules).normalise("Tom & Jerry"))
    }

    @Test
    fun invalidRegexIgnored() {
        val rules = builtins() + NameRuleEntity(providerId = 1, order = 9, enabled = true, type = NameRuleType.REGEX, pattern = "([", replacement = "")
        assertEquals("bbc one", NameNormaliser(rules).normalise("BBC One HD"))
    }

    @Test
    fun suffixRuleOnlyStripsTrailingTokens() {
        assertEquals("hdtv channel", all.normalise("HDTV Channel"))
        assertEquals("shd news", all.normalise("SHD News"))
    }

    @Test
    fun qualityTagBeforeOtherNoise() {
        assertEquals("bbc one", all.normalise("BBC One HD (Backup)"))
        assertEquals("sky sports f1", all.normalise("Sky Sports F1 HD [Multi-Audio]"))
        assertEquals("itv1", all.normalise("ITV1 HD +1"))
        assertEquals("channel 4", all.normalise("Channel 4 HD+1"))
    }

    @Test
    fun nonBreakingSpaces() {
        assertEquals("bbc one", all.normalise("BBC\u00A0One\u00A0HD"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("bbc one", NameNormaliser(emptyList()).normalise("  BBC   One "))
    }
}
