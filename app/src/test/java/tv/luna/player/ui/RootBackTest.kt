package tv.luna.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RootBackTest {
    @Test
    fun backGoesToTheMenuThenPromptsThenExits() {
        assertEquals(RootBack.FOCUS_MENU, rootBack(Screen.Live, menuFocused = false, hintShowing = false, confirmExit = true))
        assertEquals(RootBack.FOCUS_MENU, rootBack(Screen.Vod, menuFocused = false, hintShowing = false, confirmExit = true))
        assertEquals(RootBack.SHOW_HINT, rootBack(Screen.Live, menuFocused = true, hintShowing = false, confirmExit = true))
        assertEquals(RootBack.EXIT, rootBack(Screen.Live, menuFocused = true, hintShowing = true, confirmExit = true))
        assertEquals(RootBack.EXIT, rootBack(Screen.Setup(), menuFocused = false, hintShowing = false, confirmExit = true)) // first-run setup has no menu
    }

    @Test fun searchIsARoot() {
        assertEquals(RootBack.FOCUS_MENU, rootBack(Screen.Search, menuFocused = false, hintShowing = false, confirmExit = true))
        assertEquals(RootBack.SHOW_HINT, rootBack(Screen.Search, menuFocused = true, hintShowing = false, confirmExit = true))
    }

    @Test fun confirmExitOffExitsAtOnce() {
        assertEquals(RootBack.EXIT, rootBack(Screen.Live, menuFocused = false, hintShowing = false, confirmExit = false))
        assertEquals(RootBack.EXIT, rootBack(Screen.Vod, menuFocused = true, hintShowing = false, confirmExit = false))
    }

    @Test fun recordingsIsARoot() =
        assertEquals(RootBack.FOCUS_MENU, rootBack(Screen.Recordings, menuFocused = false, hintShowing = false, confirmExit = true))
}
