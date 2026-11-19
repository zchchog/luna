package tv.luna.player.ui.settings

import tv.luna.player.data.settings.ThemeColour
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.ThemeId

@Composable
fun AppearanceRows(s: AppSettings, first: Modifier, onTheme: (ThemeId) -> Unit, onColour: (ThemeColour) -> Unit) {
    // Luna's own teal, or the seasonal Christmas look (red with clear white touches and fine snow).
    val themes = listOf(ThemeColour.TEAL, ThemeColour.SKY, ThemeColour.CHRISTMAS)
    val picked = s.themeColour.takeIf { it in themes } ?: ThemeColour.TEAL
    ChoiceRow("Theme", "Luna's teal, a deep sky blue, or a festive red with snow", themes, picked,
        { when (it) { ThemeColour.CHRISTMAS -> "Christmas"; ThemeColour.SKY -> "Sky blue"; else -> "Luna" } }, first, onPick = onColour)
}
