package tv.luna.player.ui.settings

import tv.luna.player.data.settings.ThemeColour
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.ThemeId

@Composable
fun AppearanceRows(s: AppSettings, first: Modifier, onTheme: (ThemeId) -> Unit, onColour: (ThemeColour) -> Unit) {
    // Luna's own teal, or the seasonal Christmas look (red with clear white touches and fine snow).
    val picked = if (s.themeColour == ThemeColour.CHRISTMAS) ThemeColour.CHRISTMAS else ThemeColour.TEAL
    ChoiceRow("Theme", "Luna's teal, or a festive red with snow", listOf(ThemeColour.TEAL, ThemeColour.CHRISTMAS), picked,
        { if (it == ThemeColour.CHRISTMAS) "Christmas" else "Luna" }, first, onPick = onColour)
}
