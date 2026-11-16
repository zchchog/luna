package tv.luna.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgesTest {
    @Test fun resolution() = mapOf(576 to "SD", 719 to "SD", 720 to "HD", 1080 to "FULL HD", 1088 to "FULL HD", 2160 to "4K")
        .forEach { (h, b) -> assertEquals(b, badges(StreamFormats(h, null, null)).single()) }

    @Test fun frameRateRounded() {
        assertEquals(listOf("FULL HD", "25 FPS"), badges(StreamFormats(1080, 25f, null)))
        assertEquals(listOf("30 FPS"), badges(StreamFormats(null, 29.97f, null)))
    }

    @Test fun audio() = mapOf(1 to "MONO", 2 to "STEREO", 6 to "5.1", 8 to "7.1")
        .forEach { (n, b) -> assertEquals(b, badges(StreamFormats(null, null, n)).single()) }

    @Test fun unknownsOmitted() = assertEquals(emptyList<String>(), badges(StreamFormats(null, null, 3)))
}
