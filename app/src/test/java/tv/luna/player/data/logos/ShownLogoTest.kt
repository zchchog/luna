package tv.luna.player.data.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.settings.LogoKind

class ShownLogoTest {
    private fun ch(logo: String?, builtin: String?, pack: String?, custom: String? = null) = ChannelEntity(
        providerId = 1, categoryId = null, remoteId = "1", name = "x", logo = logo, customLogo = custom,
        number = 1, tvgId = null, streamUrl = "u", packLogo = pack, builtinLogo = builtin,
    )

    private val P = LogoKind.PLAYLIST; private val O = LogoKind.OPEN_SOURCE; private val K = LogoKind.PACK

    @Test fun firstAvailableInOrder() {
        val all = ch(logo = "l", builtin = "b.png", pack = "/p.png")
        val url = TV_LOGOS_RAW + "b.png"
        assertEquals("l", shownLogo(all, listOf(P, O))); assertEquals(url, shownLogo(all, listOf(O, P)))
        assertEquals("/p.png", shownLogo(all, listOf(K, O, P)))
        val noPack = ch(logo = "l", builtin = "b.png", pack = null)
        assertEquals(url, shownLogo(noPack, listOf(K, O, P))); assertEquals("l", shownLogo(noPack, listOf(K, P, O)))
        val noLogo = ch(logo = null, builtin = "b.png", pack = null)
        assertNull(shownLogo(noLogo, listOf(P))); assertEquals(url, shownLogo(noLogo, listOf(P, O)))
        // Open-source only: no playlist fallback (user, 2026-11-11).
        assertNull(shownLogo(ch(logo = "l", builtin = null, pack = null), listOf(O)))
    }

    @Test fun customAlwaysWins() = listOf(listOf(P), listOf(O, P), listOf(K, O, P)).forEach {
        assertEquals("c", shownLogo(ch(logo = "l", builtin = "b", pack = "p", custom = "c"), it))
    }

    @Test fun nothingAtAll() = assertNull(shownLogo(ch(null, null, null), listOf(K, O, P)))

    @Test fun fallbacksInOrderForFailedLoads() = assertEquals(
        listOf("c", TV_LOGOS_RAW + "b.png", "l"), shownLogos(ch(logo = "l", builtin = "b.png", pack = null, custom = "c"), listOf(K, O, P)))
}
