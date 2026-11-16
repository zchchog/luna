package tv.luna.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.luna.player.player.StreamFormats

class HeroBadgesTest {
    @Test fun badgesOnlyForPreviewingChannel() {
        val f = StreamFormats(height = 1080, frameRate = 25f, audioChannels = 2)
        assertEquals(listOf("FULL HD", "25 FPS"), heroBadges(5, 5, f))
        assertEquals(emptyList<String>(), heroBadges(5, 6, f))
        assertEquals(emptyList<String>(), heroBadges(5, null, f))
        assertEquals(emptyList<String>(), heroBadges(5, 5, StreamFormats.UNKNOWN))
    }
}
