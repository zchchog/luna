package tv.luna.player.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The app's colours (spec §6A.2). bg = background gradient stops; vod* = Movies/Series screens. */
data class ThemeColors(
    val bg: List<Color>, val panel: Color, val cell: Color, val cellDim: Color, val focus: Color, val onFocus: Color,
    val accent: Color, val text: Color, val muted: Color, val vodBg: Color, val vodCard: Color,
)

/** The app's one look: the deep sky-blue gradient (navy at the top left, lighter blue at the bottom right). */
val AppColors = ThemeColors(
    bg = listOf(Color(0xFF0A1753), Color(0xFF1A39A6), Color(0xFF3459CA)),
    panel = Color(0xF0071040), cell = Color(0x8C3F63CF), cellDim = Color(0x59365ABD),
    focus = Color(0xFFEEF3FF), onFocus = Color(0xFF0A1753), accent = Color(0xFF8DB0FF),
    text = Color.White, muted = Color(0x9EFFFFFF), vodBg = Color(0xFF0A1440), vodCard = Color(0xFF16245E),
)

data class Theme(val colors: ThemeColors)

val LocalTheme = staticCompositionLocalOf { Theme(AppColors) }

/** Inter, one variable font file at the weights the app uses. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = androidx.compose.ui.text.font.FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    androidx.compose.ui.text.font.Font(tv.luna.player.R.font.inter, androidx.compose.ui.text.font.FontWeight(w),
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
})
