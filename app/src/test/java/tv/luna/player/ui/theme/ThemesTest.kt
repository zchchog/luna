package tv.luna.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.luna.player.data.settings.ThemeId
import tv.luna.player.data.settings.ThemeColour
import tv.luna.player.data.settings.themeIdOf

class ThemesTest {
    /** Luna is the only theme: any saved choice, old or removed (Plus, Retro, Liquid Glass, Xcube, Gamestation), reads as it. */
    @Test fun everySavedChoiceReadsAsLuna() {
        listOf("SKY_GLASS", "MIDNIGHT", "CUSTOM", "nonsense", "CELESTIAL_PLUS", "CELESTIAL_RETRO", "LIQUID_GLASS", "XCUBE_180", "GAMESTATION", null)
            .forEach { assertEquals(it, ThemeId.LUNA, themeIdOf(it)) }
    }

    @Test fun onlyLunaRemains() = assertEquals(listOf("Luna"), ThemeId.entries.map { it.label })

    /** The default colour keeps Luna exactly as it was signed off. */
    @Test fun defaultColourKeepsGlass() = assertEquals(colorsOf(ThemeId.LUNA), colorsOf(ThemeId.LUNA, ThemeColour.DEFAULT))

    @Test fun colourChangesTheBackground() {
        ThemeColour.entries.drop(1).forEach { c ->
            assertTrue(c.name, colorsOf(ThemeId.LUNA, c).bg != colorsOf(ThemeId.LUNA).bg)
        }
    }

    @Test fun themeHasColours() = assertTrue(colorsOf(ThemeId.LUNA).bg.size >= 2)
}
