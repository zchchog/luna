package tv.luna.player.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import tv.luna.player.data.settings.ThemeId
import tv.luna.player.data.settings.ThemeColour
import androidx.compose.ui.graphics.lerp

/** One theme's colours (spec §6A.2). bg = background gradient stops; vod* = Movies/Series screens. */
data class ThemeColors(
    val bg: List<Color>, val panel: Color, val cell: Color, val cellDim: Color, val focus: Color, val onFocus: Color,
    val accent: Color, val text: Color, val muted: Color, val vodBg: Color, val vodCard: Color,
)

fun colorsOf(id: ThemeId, colour: ThemeColour = ThemeColour.DEFAULT): ThemeColors {
    val base = baseColors(id)
    if (colour == ThemeColour.DEFAULT) return base
    return when (id) {
        // Luna: cells, panel and VOD colours follow the chosen gradient the way the default blue's do.
        ThemeId.LUNA -> {
            val (d, m, l) = colour.glass.map { Color(it) }
            base.copy(bg = listOf(d, m, l), cell = lerp(l, Color.White, .08f).copy(alpha = .55f), cellDim = l.copy(alpha = .35f),
                panel = lerp(d, Color.Black, .25f).copy(alpha = .94f), onFocus = d, vodBg = lerp(d, Color.Black, .1f), vodCard = lerp(d, m, .35f),
                accent = if (colour == ThemeColour.CHRISTMAS) Color(0xFFF2C46D) else base.accent)
        }
        else -> base
    }
}

private fun baseColors(id: ThemeId): ThemeColors = when (id) {
    // Luna: teal everywhere (no blue left over from the old look).
    ThemeId.LUNA -> ThemeColors(
        bg = listOf(Color(0xFF06323A), Color(0xFF0D6F7A), Color(0xFF1C9AA0)),
        panel = Color(0xF0052228), cell = Color(0x8C2BA2A8), cellDim = Color(0x591C9AA0),
        focus = Color(0xFFEFFBFC), onFocus = Color(0xFF06323A), accent = Color(0xFF5FD6DC),
        text = Color.White, muted = Color(0x9EFFFFFF), vodBg = Color(0xFF052A31), vodCard = Color(0xFF0A4650),
    )
}

data class Theme(val id: ThemeId, val colors: ThemeColors)

fun themeOf(id: ThemeId, colour: ThemeColour = ThemeColour.DEFAULT) = Theme(id, colorsOf(id, colour))

val LocalTheme = staticCompositionLocalOf { themeOf(ThemeId.LUNA) }

/** Luna and Liquid Glass use Inter (the website's font); the other themes keep the TV's default font. */
fun usesInter(id: ThemeId) = true

/** Inter, one variable font file at the weights the app uses. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = androidx.compose.ui.text.font.FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
    androidx.compose.ui.text.font.Font(tv.luna.player.R.font.inter, androidx.compose.ui.text.font.FontWeight(w),
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)))
})
