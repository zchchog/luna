package tv.luna.player.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    @Test fun foldMatchesSmallLetters() {
        assertTrue(matchesAll(foldForSearch("UK: SKY ᴴᴰ ³⁸⁴⁰ᴾ"), queryWords("hd 3840p")))
        assertFalse(matchesAll(foldForSearch("Télé"), queryWords("tele")))
    }

    @Test fun wordsAnyOrder() {
        assertTrue(matchesAll(foldForSearch("4K-TOP - A Minecraft Movie (2025)"), queryWords("minecraft 4k")))
        assertFalse(matchesAll(foldForSearch("4K-TOP - A Minecraft Movie (2025)"), queryWords("minecraft 8k")))
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(queryWords("   ").isEmpty())
        assertTrue(queryWords("").isEmpty())
        assertEquals(listOf("b"), queryWords(" B "))
    }

    @Test fun rankStartsWithFirst() {
        val hits = listOf("Sky News", "BBC Sky", "Sky Atlantic").map { Hit(it, foldForSearch(it)) }
        assertEquals(listOf("Sky Atlantic", "Sky News", "BBC Sky"), rank(hits, queryWords("sky")))
    }

    @Test fun rankByNumberOnTie() {
        val hits = listOf(Hit("two", "bbc one", 2), Hit("none", "bbc one", null), Hit("one", "bbc one", 1))
        assertEquals(listOf("one", "two", "none"), rank(hits, queryWords("bbc")))
    }
}
