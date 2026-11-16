package tv.luna.player.data.logos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvLogosTest {
    private val fixture = javaClass.getResource("/tvlogos/tree.json")!!.readText()
    private val index = TvLogoIndex(parseTree(fixture))

    @Test fun parsesOnlyCountryPngBlobs() = assertEquals(10, parseTree(fixture).size)

    @Test fun prefersQualityThenPlain() {
        val uk = listOf("united-kingdom")
        assertEquals("countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png", index.match(LogoKey("sky-sports-main-event", "uhd"), uk))
        assertEquals("countries/united-kingdom/sky-sports-main-event-uk.png", index.match(LogoKey("sky-sports-main-event", null), uk))
        assertEquals("countries/united-kingdom/bbc-one-uk.png", index.match(LogoKey("bbc-one", "uhd"), uk))   // no uhd → plain
        assertEquals("countries/united-kingdom/sky-cinema-hd-uk.png", index.match(LogoKey("sky-cinema", null), uk)) // no plain → any variant
    }

    @Test fun fileWithoutCountrySuffixKeepsItsName() =
        assertEquals("countries/united-kingdom/sky-news.png", index.match(LogoKey("sky-news", null), listOf("united-kingdom")))

    @Test fun onlyTheCountryFolder() = assertNull(index.match(LogoKey("cnn", null), countryFolders("GB", index)))

    @Test fun countryFolders() {
        assertEquals(listOf("united-kingdom"), countryFolders("GB", index)); assertEquals(listOf("germany"), countryFolders("DE", index))
        val fallback = listOf("united-kingdom", "united-states", "international")
        assertEquals(fallback, countryFolders(null, index)); assertEquals(fallback, countryFolders("script:Arab", index)); assertEquals(fallback, countryFolders("ZZ", index))
    }

    /** Real tree: nordic/<country>/ sub-folders, each with its own country suffix (-dk, -se, …). */
    @Test fun subFolderSuffixesAreDropped() {
        val nordic = TvLogoIndex(listOf(
            "countries/nordic/denmark/tv-2-dk.png", "countries/nordic/denmark/dr1-dk.png",
            "countries/nordic/norway/nrk1-no.png", "countries/nordic/norway/nrk2-no.png", "countries/nordic/norway/tv-2-no.png",
        ))
        assertEquals("countries/nordic/denmark/dr1-dk.png", nordic.match(LogoKey("dr1", null), listOf("nordic")))
        assertEquals(listOf("nordic"), countryFolders("DK", nordic))
    }

    @Test fun candidatesKeyFirstThenAllWords() {
        assertEquals(
            listOf("countries/united-kingdom/sky-sports-main-event-uk.png", "countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png"),
            index.candidates(LogoKey("sky-sports-main-event", null), listOf("united-kingdom")),
        )
        assertEquals(setOf("countries/united-kingdom/sky-sports-main-event-uk.png", "countries/united-kingdom/hd/sky-sports-main-event-uhd-uk.png",
            "countries/united-kingdom/sky-cinema-hd-uk.png", "countries/united-kingdom/sky-news.png"),
            index.candidates(LogoKey("sky", null), listOf("germany")).toSet())
        // Exact key outside the channel's country comes before word matches.
        assertEquals("countries/united-states/cnn-us.png", index.candidates(LogoKey("cnn", null), listOf("united-kingdom")).firstOrNull())
    }

    /** User 2026-11-12: "BBC 2 HD" and "ITV +1" channels found no open-source logo. */
    @Test fun numberWordsSpacingAndPlusOne() {
        val uk = TvLogoIndex(listOf("bbc-two-hd-uk.png", "bbc-two-uk.png", "itv-1-plus-uk.png", "itv-1-uk.png", "e4-uk.png")
            .map { "countries/united-kingdom/$it" })
        val f = listOf("united-kingdom")
        assertEquals("countries/united-kingdom/bbc-two-hd-uk.png", uk.match(logoKey("UK: BBC 2 HD")!!, f))
        assertEquals("countries/united-kingdom/itv-1-plus-uk.png", uk.match(logoKey("UK: ITV1 +1")!!, f))
        assertEquals("countries/united-kingdom/itv-1-uk.png", uk.match(logoKey("UK: ITV1 HD")!!, f))
        assertEquals("countries/united-kingdom/e4-uk.png", uk.match(logoKey("UK: E 4")!!, f))
    }
}
