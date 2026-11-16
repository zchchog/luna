package tv.luna.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TabsTest {
    @Test fun recordingsHiddenWhenOff() = assertFalse(Tab.RECORDINGS in visibleTabs(false))
    @Test fun recordingsBeforeSettingsWhenOn() =
        assertEquals(listOf(Tab.SEARCH, Tab.LIVE, Tab.MOVIES, Tab.SERIES, Tab.RECORDINGS, Tab.SETTINGS), visibleTabs(true))
}
