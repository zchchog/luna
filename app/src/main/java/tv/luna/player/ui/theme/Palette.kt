package tv.luna.player.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The brand colours used across the app, switched by the chosen theme (Luna teal, Christmas red, or Sky blue).
 * Plain getters (not @Composable), so they can be read anywhere, drawing code included; reading [christmas] or [sky]
 * redraws whatever used them when the theme changes.
 */
object Palette {
    var christmas by mutableStateOf(false)
    var sky by mutableStateOf(false)

    private fun pick(luna: Long, xmas: Long, blue: Long) = Color(if (christmas) xmas else if (sky) blue else luna)

    /** Bright highlight: progress bars, LIVE badge, small labels. */
    val accent: Color get() = pick(0xFF5FD6DC, 0xFFFFFFFF, 0xFF5CB8FF)
    /** Dark text on a white (focused) control. */
    val ink: Color get() = pick(0xFF06323A, 0xFF4A0D16, 0xFF06143D)
    val inkMid: Color get() = pick(0xFF0D6F7A, 0xFF8C1A2A, 0xFF0F3A9E)
    /** The strong brand colour (switches turned on, cover fallbacks). */
    val bright: Color get() = pick(0xFF1C9AA0, 0xFFC8384A, 0xFF1F7BFF)
    /** Soft glow around focused cells. */
    val glow: Color get() = pick(0xFFB5F1F4, 0xFFFFD9D4, 0xFFBFE0FF)
    /** The moon on the background. */
    val moon: Color get() = pick(0xFF8FE3E6, 0xFFFFC9C2, 0xFF8CC8FF)
    /** The top menu's glass and its edge. */
    val navGlass: Color get() = pick(0xA606323A, 0xA64A0D16, 0xA6061A4D)
    val navBorder: Color get() = pick(0x525FD6DC, 0x40FFFFFF, 0x525CB8FF)
    val selected: Color get() = pick(0x385FD6DC, 0x2EFFFFFF, 0x385CB8FF)
}
