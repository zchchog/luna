package tv.luna.player.data.catalogue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderChannelTest {
    @Test fun anyNameStartingWithHash() {
        listOf("#### SKY CINEMA HD/4K ####", "## NEWS ##", "### UK GENERAL HEVC/4K ###", "  # Sports", "#").forEach { assertTrue(it, isHeaderChannel(it)) }
        listOf("UK: BBC 1 HD", "Channel #1", "", "UK: #Hashtag TV").forEach { assertFalse(it, isHeaderChannel(it)) }
    }
}
