package tv.luna.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    /** The app has one look: the original deep blue gradient. */
    @Test fun theLookIsTheDeepBlueGradient() {
        assertEquals(3, AppColors.bg.size)
        assertTrue(AppColors.bg.first() != AppColors.bg.last())
    }
}
