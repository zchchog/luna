package tv.luna.player.data.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoKeyTest {
    @Test fun spec() = assertEquals(LogoKey("sky-sports-main-event", "uhd"), logoKey("UK: Sky Sports Main Event ᵁᴴᴰ"))

    @Test fun encodingVariantsShareAKey() {
        assertEquals(LogoKey("sport", null), logoKey("Sport hevc")); assertEquals(logoKey("Sport hevc"), logoKey("Sport RAW"))
        assertEquals(LogoKey("sky-cinema", "uhd"), logoKey("Sky Cinema 4K HEVC 60fps"))
    }

    @Test fun qualityHints() {
        assertEquals(LogoKey("bbc-1", "hd"), logoKey("|UK| BBC One FHD")); assertEquals(LogoKey("bbc-1", "hd"), logoKey("BBC One HD"))
        assertEquals(LogoKey("bbc-1", null), logoKey("BBC One SD"))
    }

    @Test fun accentsBracketsVip() = assertEquals("canal-cinema", logoKey("FR: Canal+ Cinéma (Backup) [VIP]")?.key)

    /** tv-logos names +1 channels "-plus" (channel-4-plus-uk.png); still distinct from the main channel. */
    @Test fun plusOneStaysDistinct() {
        assertEquals("channel-4-plus", logoKey("Channel 4 +1")?.key)
        assertEquals("itv-1-plus", logoKey("UK: ITV 1 +1 HD")?.key)
    }

    /** tv-logos spells numbers ("bbc-two-hd-uk.png"); providers mostly use digits ("BBC 2 HD"). */
    @Test fun numberWordsAsDigits() {
        assertEquals(LogoKey("bbc-2", "hd"), logoKey("UK: BBC 2 HD")); assertEquals(LogoKey("bbc-2", "hd"), logoKey("BBC Two HD"))
    }

    @Test fun hdInsideAWordIsKept() = assertEquals("hdtv-plus", logoKey("HDTV Plus")?.key)

    @Test fun nothingLeft() { assertNull(logoKey("HD")); assertNull(logoKey("  ")); assertNull(logoKey("UK: ")) }

    @Test fun trailingSymbolDoesNotKeepTags() = assertEquals(LogoKey("itv-1", "hd"), logoKey("UK: ITV 1 HD ◉"))

    /** Small-letter tags from providers: "³⁸⁴⁰ᴾ", "ᴿᴬᵂ", "⁶⁰ᶠᵖˢ", and plain resolutions. */
    @Test fun resolutionAndSmallLetterTags() {
        assertEquals(LogoKey("sky-cinema-action", "uhd"), logoKey("UK: SKY CINEMA ACTION ³⁸⁴⁰ᴾ"))
        assertEquals(LogoKey("sky-cinema-action", "uhd"), logoKey("UK: SKY CINEMA ACTION 2160p"))
        assertEquals(LogoKey("bbc-news", "hd"), logoKey("UK: BBC NEWS 1080P ᴿᴬᵂ"))
        assertEquals(LogoKey("prime-baywatch", null), logoKey("PRIME: BAYWATCH ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }

    @Test fun countrySuffixIsDropped() {
        assertEquals(compactKey("BBC News HD"), compactKey("BBC News UK HD"))
        assertEquals("dunya-news", logoKey("Dunya News UK")?.key)
        assertEquals("hd", logoKey("BBC News UK HD")?.quality)
        assertEquals("5usa", logoKey("5USA")?.key) // a country inside a word stays
        assertEquals("uk", logoKey("UK")?.key) // nothing else left: keep it
    }
}
