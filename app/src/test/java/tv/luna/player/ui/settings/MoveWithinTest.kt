package tv.luna.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.luna.player.data.db.GroupPlacement.BOTTOM
import tv.luna.player.data.db.GroupPlacement.NORMAL

class MoveWithinTest {
    private val list = listOf("a" to NORMAL, "b" to NORMAL, "c" to BOTTOM, "d" to BOTTOM)

    @Test fun movesWithinItsSide() = assertEquals(listOf("b", "a", "c", "d"), moveWithin(list, 0, 1) { it.second }!!.map { it.first })

    @Test fun stopsAtTheOtherSide() = assertNull(moveWithin(list, 1, 1) { it.second })

    @Test fun stopsAtTheEnds() {
        assertNull(moveWithin(list, 0, -1) { it.second })
        assertNull(moveWithin(list, 3, 1) { it.second })
    }
}
