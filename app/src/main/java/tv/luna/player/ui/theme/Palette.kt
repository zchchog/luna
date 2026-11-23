package tv.luna.player.ui.theme

import androidx.compose.ui.graphics.Color

/** The brand colours used across the app: the one sky-blue look. Plain getters, so drawing code can read them anywhere. */
object Palette {
    /** Bright highlight: progress bars, LIVE badge, small labels. */
    val accent = Color(0xFF5CB8FF)
    /** Dark text on a white (focused) control. */
    val ink = Color(0xFF06143D)
    val inkMid = Color(0xFF0F3A9E)
    /** The strong brand colour (switches turned on, cover fallbacks). */
    val bright = Color(0xFF1F7BFF)
    /** Soft glow around focused cells. */
    val glow = Color(0xFFBFE0FF)
    /** The moon on the background. */
    val moon = Color(0xFF8CC8FF)
    /** The top menu's glass and its edge. */
    val navGlass = Color(0xA6061A4D)
    val navBorder = Color(0x525CB8FF)
    val selected = Color(0x385CB8FF)
}
