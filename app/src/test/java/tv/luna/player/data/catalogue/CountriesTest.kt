package tv.luna.player.data.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountriesTest {
    @Test fun prefixes() = mapOf(
        "UK| ENTERTAINMENT" to "GB", "UK | SPORTS" to "GB", "GB: News" to "GB", "US | NEWS" to "US", "USA - Movies" to "US",
        "DE: Sport" to "DE", "4K-AR Movies" to "SA", "AR| MBC" to "SA", "ARAB | Kids" to "SA", "FR| Cinéma" to "FR",
        "|IN| Hindi" to "IN", "[PL] Filmy" to "PL",
    ).forEach { (name, code) -> assertEquals(name, code, detectCountry(name)) }

    @Test fun scripts() {
        assertEquals("script:Arab", detectCountry("قنوات رياضية"))
        assertEquals("script:Cyrl", detectCountry("Кино"))
        assertEquals("script:Grek", detectCountry("Ταινίες"))
    }

    @Test fun noCountry() = listOf("Sports", "Movies 2024", "Kids", "UKTV Play", "USA Network").forEach { assertNull(it, detectCountry(it)) }

    @Test fun languages() {
        assertEquals("English", languageOf("GB")); assertEquals("English", languageOf("US"))
        assertEquals("Arabic", languageOf("SA")); assertEquals("Arabic", languageOf("script:Arab")); assertEquals("Other", languageOf(null))
    }

    @Test fun labels() {
        assertEquals("United Kingdom", countryLabel("GB"))
        assertEquals("Arabic script", countryLabel("script:Arab"))
        assertEquals("Other", countryLabel(null))
    }

    @Test fun languageWords() {
        assertEquals("lang:English", detectCountry("ENGLISH SERIES ⁴ᴷ"))
        assertEquals("lang:English", detectCountry("English Kids"))
        assertEquals("lang:Turkish", detectCountry("TURKISH ATV SERIES"))
        assertEquals("GB", detectCountry("UK| ENGLISH MOVIES")) // a country prefix wins
        assertNull(detectCountry("NETFLIX SERIES"))
        assertEquals("English", languageOf("lang:English"))
        assertEquals("Any country", countryLabel("lang:English"))
    }

    @Test fun matchesTvLanguage() {
        assertTrue(matchesLanguage("GB", "English"))
        assertTrue(matchesLanguage("lang:English", "English"))
        assertTrue(matchesLanguage(null, "English")) // no language detected: keep it
        assertFalse(matchesLanguage("SA", "English"))
        assertFalse(matchesLanguage("lang:Turkish", "English"))
    }

    @Test fun englishCountries() {
        assertEquals(setOf("GB", "US", "CA", "AU", "IE", "NZ"), countriesOfLanguage("English"))
        assertEquals(emptySet<String>(), countriesOfLanguage("Klingon"))
    }
}
