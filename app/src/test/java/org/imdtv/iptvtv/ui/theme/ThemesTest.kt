package org.imdtv.iptvtv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.imdtv.iptvtv.data.settings.ThemeId
import org.imdtv.iptvtv.data.settings.ThemeColour
import org.imdtv.iptvtv.data.settings.themeIdOf

class ThemesTest {
    /** Celestial Glass is the only theme: any saved choice, old or removed (Plus, Retro, Liquid Glass, Xcube, Gamestation), reads as it. */
    @Test fun everySavedChoiceReadsAsCelestialGlass() {
        listOf("SKY_GLASS", "MIDNIGHT", "CUSTOM", "nonsense", "CELESTIAL_PLUS", "CELESTIAL_RETRO", "LIQUID_GLASS", "XCUBE_180", "GAMESTATION", null)
            .forEach { assertEquals(it, ThemeId.CELESTIAL_GLASS, themeIdOf(it)) }
    }

    @Test fun onlyCelestialGlassRemains() = assertEquals(listOf("Celestial Glass"), ThemeId.entries.map { it.label })

    /** The default colour keeps Celestial Glass exactly as it was signed off. */
    @Test fun defaultColourKeepsGlass() = assertEquals(colorsOf(ThemeId.CELESTIAL_GLASS), colorsOf(ThemeId.CELESTIAL_GLASS, ThemeColour.DEFAULT))

    @Test fun colourChangesTheBackground() {
        ThemeColour.entries.drop(1).forEach { c ->
            assertTrue(c.name, colorsOf(ThemeId.CELESTIAL_GLASS, c).bg != colorsOf(ThemeId.CELESTIAL_GLASS).bg)
        }
    }

    @Test fun themeHasColours() = assertTrue(colorsOf(ThemeId.CELESTIAL_GLASS).bg.size >= 2)
}
