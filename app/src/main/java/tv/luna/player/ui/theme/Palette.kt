package tv.luna.player.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The brand colours used across the app, switched by the seasonal theme. Plain getters (not @Composable), so they
 * can be read anywhere, drawing code included; reading [christmas] redraws whatever used it when the theme changes.
 */
object Palette {
    var christmas by mutableStateOf(false)

    /** Bright highlight: progress bars, LIVE badge, small labels. Teal, or gold for Christmas. */
    val accent: Color get() = if (christmas) Color(0xFFF2C46D) else Color(0xFF5FD6DC)
    /** Dark text on a white (focused) control. */
    val ink: Color get() = if (christmas) Color(0xFF4A0D16) else Color(0xFF06323A)
    val inkMid: Color get() = if (christmas) Color(0xFF8C1A2A) else Color(0xFF0D6F7A)
    /** The strong brand colour (switches turned on, cover fallbacks). */
    val bright: Color get() = if (christmas) Color(0xFFC8384A) else Color(0xFF1C9AA0)
    /** Soft glow around focused cells. */
    val glow: Color get() = if (christmas) Color(0xFFFFD9D4) else Color(0xFFB5F1F4)
    /** The moon on the background. */
    val moon: Color get() = if (christmas) Color(0xFFFFC9C2) else Color(0xFF8FE3E6)
    /** The top menu's glass and its edge. */
    val navGlass: Color get() = if (christmas) Color(0xA64A0D16) else Color(0xA606323A)
    val navBorder: Color get() = if (christmas) Color(0x52F2C46D) else Color(0x525FD6DC)
    val selected: Color get() = if (christmas) Color(0x38F2C46D) else Color(0x385FD6DC)
}
