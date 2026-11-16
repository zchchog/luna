package tv.luna.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun applicationIdIsCorrect() {
        assertEquals("tv.luna.player", BuildConfig.APPLICATION_ID)
    }
}
