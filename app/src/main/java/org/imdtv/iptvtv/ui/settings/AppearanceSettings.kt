package org.imdtv.iptvtv.ui.settings

import org.imdtv.iptvtv.data.settings.ThemeColour
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.imdtv.iptvtv.data.settings.AppSettings
import org.imdtv.iptvtv.data.settings.ThemeId

@Composable
fun AppearanceRows(s: AppSettings, first: Modifier, onTheme: (ThemeId) -> Unit, onColour: (ThemeColour) -> Unit) {
    ChoiceRow("Theme colour", "Background colour of the app", ThemeColour.entries, s.themeColour, { it.label }, first, onPick = onColour)
}
