package tv.luna.player.data.catalogue

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {
    @Test fun parsesCommaList() = assertEquals(listOf("UK|", "US |", "4K:"), parseStripList("UK|, US |, 4K: ,"))
    @Test fun separatorOnlyEntriesIgnored() = assertEquals(listOf("HD"), parseStripList("|, - ,HD"))
    @Test fun stripsPrefixAndSuffixCaseInsensitive() {
        val s = parseStripList("UK|, US |, 4K:, HD")
        assertEquals("BBC One", displayName("UK| BBC One", null, s))
        assertEquals("CNN", displayName("us | CNN", null, s))
        assertEquals("Sky Cinema", displayName("4K: Sky Cinema HD", null, s))
        assertEquals("ITV", displayName("UK|ITV", null, s))
    }
    @Test fun leftoverSeparatorsTrimmed() = assertEquals("BBC Two", displayName("UK - BBC Two", null, parseStripList("UK")))
    @Test fun onlyAtEdges() = assertEquals("Sky UK| News", displayName("Sky UK| News", null, parseStripList("UK|")))
    @Test fun wholeWordsOnly() {
        assertEquals("UKTV Gold", displayName("UKTV Gold", null, parseStripList("UK")))
        assertEquals("CBBC", displayName("CBBC", null, parseStripList("BBC")))
    }
    @Test fun customNameWins() = assertEquals("Mine", displayName("UK| BBC One", "Mine", parseStripList("UK|")))
    @Test fun stripNeverEmptiesName() = assertEquals("BBC", displayName("BBC", null, parseStripList("BBC")))

    /** Small-letter tags can't be typed on a TV keyboard: "RAW" must match "ᴿᴬᵂ". */
    @Test fun stripMatchesSmallLetters() =
        assertEquals("UK: SKY ONE", displayName("UK: SKY ONE ᴿᴬᵂ", null, parseStripList("RAW")))

    @Test fun renameReplacesPrefixOrSuffix() {
        val rules = parseStripList("3840P=4K, UK:=GB:")
        assertEquals("GB: SKY CINEMA 4K", displayName("UK: SKY CINEMA ³⁸⁴⁰ᴾ", null, rules))
        assertEquals("ITV ᴴᴰ", displayName("ITV ᴴᴰ", null, rules))          // untouched names keep their small letters
        assertEquals("SKY 3840PLUS", displayName("SKY 3840PLUS", null, rules)) // whole words only
    }

    @Test fun renameToNothingStrips() = assertEquals("SKY", displayName("SKY ᴿᴬᵂ", null, parseStripList("RAW=")))
}
