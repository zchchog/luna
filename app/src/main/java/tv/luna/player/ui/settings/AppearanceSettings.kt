package tv.luna.player.ui.settings

import tv.luna.player.data.settings.ThemeColour
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.ThemeId

@Composable
fun AppearanceRows(s: AppSettings, first: Modifier, onTheme: (ThemeId) -> Unit, onColour: (ThemeColour) -> Unit) {
    ChoiceRow("Theme colour", "Background colour of the app", ThemeColour.entries, s.themeColour, { it.label }, first, onPick = onColour)
}
