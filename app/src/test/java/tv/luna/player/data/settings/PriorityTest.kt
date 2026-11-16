package tv.luna.player.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PriorityTest {
    private val P = LogoKind.PLAYLIST; private val O = LogoKind.OPEN_SOURCE; private val K = LogoKind.PACK
    private val all = LogoKind.entries

    @Test fun choicesExcludeHigherLevels() {
        assertEquals(listOf(P, O, K), priorityChoices(listOf(P, O), 0, all))          // first: everything, no None
        assertEquals(listOf(null, O, K), priorityChoices(listOf(P, O), 1, all))      // second: None + not the first
        assertEquals(listOf(null, K), priorityChoices(listOf(P, O), 2, all))
    }

    @Test fun pickingReorders() {
        assertEquals(listOf(O, P), withPriority(listOf(P, O), 0, O))        // the reverse: picked moves up, the rest keep their order
        assertEquals(listOf(P, K), withPriority(listOf(P, O), 1, K))
        assertEquals(listOf(P), withPriority(listOf(P, O, K), 1, null))     // None drops this level and everything below
        assertEquals(listOf(P, O, K), withPriority(listOf(P, O), 2, K))
    }
}
