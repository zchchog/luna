package tv.luna.player.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The app's colours (spec §6A.2). bg = background gradient stops; vod* = Movies/Series screens. */
data class ThemeColors(
    val bg: List<Color>, val panel: Color, val cell: Color, val cellDim: Color, val focus: Color, val onFocus: Color,
    val accent: Color, val text: Color, val muted: Color, val vodBg: Color, val vodCard: Color,
)

/** The app's one look: the sky-blue gradient (deep navy at the top left, bright blue at the bottom right). */
val AppColors = ThemeColors(
    bg = listOf(Color(0xFF030B2E), Color(0xFF0A2C86), Color(0xFF1F63E0)),
    panel = Color(0xF0020822), cell = Color(0x8C3170E2), cellDim = Color(0x591F63E0),
    focus = Color(0xFFEEF3FF), onFocus = Color(0xFF030B2E), accent = Color(0xFF5CB8FF),
    text = Color.White, muted = Color(0x9EFFFFFF), vodBg = Color(0xFF030A29), vodCard = Color(0xFF05174D),
)

data class Theme(val colors: ThemeColors)

val LocalTheme = staticCompositionLocalOf { Theme(AppColors) }

/** Inter, one variable font file at the weights the app uses. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = androidx.compose.ui.text.font.FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    androidx.compose.ui.text.font.Font(tv.luna.player.R.font.inter, androidx.compose.ui.text.font.FontWeight(w),
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
})
