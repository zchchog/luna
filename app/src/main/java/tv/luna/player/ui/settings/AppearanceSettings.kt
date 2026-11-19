package tv.luna.player.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tv.luna.player.data.settings.AppSettings
import tv.luna.player.data.settings.ThemeColour
import tv.luna.player.data.settings.ThemeId

/** The look of the app inside (blue by default), as stored; anything other than Teal or Holiday Red is Luna Blue. */
val THEMES = listOf(ThemeColour.SKY, ThemeColour.TEAL, ThemeColour.CHRISTMAS)
fun themeLabel(c: ThemeColour) = when (c) { ThemeColour.TEAL -> "Teal"; ThemeColour.CHRISTMAS -> "Holiday Red"; else -> "Luna Blue (Default)" }

/** The home-screen icon: each is an activity-alias in the manifest, and exactly one is switched on. */
enum class AppIcon(val alias: String, val label: String) {
    BLUE(".IconBlue", "Luna Blue (Default)"), TEAL(".IconTeal", "Teal"), RED(".IconRed", "Holiday Red");

    fun component(ctx: Context) = ComponentName(ctx.packageName, ctx.packageName + alias)

    companion object {
        fun current(ctx: Context): AppIcon = entries.firstOrNull { icon ->
            when (ctx.packageManager.getComponentEnabledSetting(icon.component(ctx))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == BLUE // as the manifest says
                else -> false
            }
        } ?: BLUE

        /** Switch the chosen icon on first, then the others off, so the app is never without one. */
        fun set(ctx: Context, chosen: AppIcon) {
            val pm = ctx.packageManager
            runCatching {
                pm.setComponentEnabledSetting(chosen.component(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                entries.filter { it != chosen }.forEach {
                    pm.setComponentEnabledSetting(it.component(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}

@Composable
fun AppearanceRows(s: AppSettings, first: Modifier, onTheme: (ThemeId) -> Unit, onColour: (ThemeColour) -> Unit) {
    val picked = s.themeColour.takeIf { it == ThemeColour.TEAL || it == ThemeColour.CHRISTMAS } ?: ThemeColour.SKY
    ChoiceRow("Theme", "The colour of the app (Holiday Red adds fine snow)", THEMES, picked, ::themeLabel, first, onPick = onColour)
    val ctx = LocalContext.current
    var icon by remember { mutableStateOf(AppIcon.current(ctx)) }
    ChoiceRow("App icon", "The Luna icon on your TV's home screen (it can take a moment to change there)", AppIcon.entries, icon,
        { it.label }, onPick = { AppIcon.set(ctx, it); icon = it })
}
