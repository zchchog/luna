package tv.luna.player.data.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNamesTest {
    @Test
    fun nameKeyIgnoresRegionAndQualityTags() {
        val plain = ChannelNames.key("BBC One")
        assertEquals(plain, ChannelNames.key("UK: BBC One HD"))
        assertEquals(plain, ChannelNames.key("|UK| BBC ONE 4K 50FPS"))
        assertEquals(plain, ChannelNames.key("[UK] BBC One FHD HEVC"))
        assertEquals(plain, ChannelNames.key("BBC One ᴴᴰ ᴿᴬᵂ"))
        assertEquals(ChannelNames.key("Channel 4"), ChannelNames.key("Channel 4 HD"))
    }

    @Test
    fun differentChannelsStayDifferent() {
        assertTrue(ChannelNames.key("Sky Sports 1") != ChannelNames.key("Sky Sports 10"))
        assertTrue(ChannelNames.key("BBC One") != ChannelNames.key("BBC Two"))
        assertTrue(ChannelNames.key("Channel 4") != ChannelNames.key("Channel 4 +1"))
    }

    @Test
    fun readsQualityFromTheName() {
        assertEquals(40, ChannelNames.tags("UK: BBC One 4K").rank)
        assertEquals("4K · 50fps · HEVC", ChannelNames.tags("BBC One 4K 50FPS HEVC").text)
        assertEquals("1080p", ChannelNames.tags("BBC One FHD").text)
        assertEquals("HD", ChannelNames.tags("BBC One ᴴᴰ").text)
        assertEquals("", ChannelNames.tags("BBC One").text)
        // Better quality ranks higher, and 50fps beats 25fps at the same size.
        assertTrue(ChannelNames.tags("X 4K").rank > ChannelNames.tags("X FHD").rank)
        assertTrue(ChannelNames.tags("X FHD").rank > ChannelNames.tags("X HD").rank)
        assertTrue(ChannelNames.tags("X HD").rank > ChannelNames.tags("X SD").rank)
        assertTrue(ChannelNames.tags("X HD 50fps").rank > ChannelNames.tags("X HD").rank)
    }
}
