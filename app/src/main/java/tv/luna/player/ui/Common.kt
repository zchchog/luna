package tv.luna.player.ui

import tv.luna.player.ui.theme.snowCap
import androidx.compose.ui.draw.drawBehind
import tv.luna.player.ui.theme.LocalTheme

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import kotlinx.coroutines.delay
import tv.luna.player.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun keepOrFirst(current: tv.luna.player.data.db.CategoryEntity?, list: List<tv.luna.player.data.db.CategoryEntity>, preferId: Long? = null) =
    list.firstOrNull { it.id == current?.id } ?: list.firstOrNull { it.id == preferId } ?: list.firstOrNull()

enum class Tab(val label: String, @androidx.annotation.DrawableRes val icon: Int) {
    SEARCH("Search", R.drawable.ic_tab_search), LIVE("TV guide", R.drawable.ic_tab_live), MOVIES("Movies", R.drawable.ic_tab_movies),
    SERIES("Series", R.drawable.ic_tab_series), RECORDINGS("Recordings", R.drawable.ic_tab_recordings), SETTINGS("Settings", R.drawable.ic_tab_settings),
}

/** The main menu's entries: Recordings only while recording is switched on. */
fun visibleTabs(recordingOn: Boolean): List<Tab> = Tab.entries.filter { it != Tab.RECORDINGS || recordingOn }

/** A D-pad focusable, OK-clickable box that tells its content whether it has focus. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (focused) onFocused() } }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) { content(focused) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, selectedFocus: FocusRequester? = null, showClock: Boolean = true,
           showDate: Boolean = false, pingMs: Int? = null, tabs: List<Tab> = visibleTabs(false)) {
    val focusFor = { t: Tab -> if (t == selected && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier }
    // Search and Settings are round buttons after the sections.
    val sections = tabs.filter { it != Tab.SEARCH && it != Tab.SETTINGS }
    val sectionRow = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            sections.forEach { t ->
                FocusBox(onClick = { onSelect(t) }, modifier = focusFor(t)) { f ->
                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = when { f -> tv.luna.player.ui.theme.Palette.ink; t == selected -> Color.White; else -> Color(0xB3EFFBFC) },
                        modifier = Modifier.clip(RoundedCornerShape(13.dp))
                            .background(when { f -> Color(0xFFEFFBFC); t == selected -> tv.luna.player.ui.theme.Palette.selected; else -> Color.Transparent })
                            .padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
    }
    // Entering the menu from below lands on the current tab, not the nearest one.
    Row(
        modifier.focusProperties { enter = { selectedFocus ?: FocusRequester.Default } }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The sections sit together in one glass panel.
        Box(Modifier.clip(RoundedCornerShape(17.dp)).background(tv.luna.player.ui.theme.Palette.navGlass).drawBehind { if (tv.luna.player.ui.theme.Palette.christmas) snowCap() }.border(1.dp, tv.luna.player.ui.theme.Palette.navBorder, RoundedCornerShape(17.dp)).padding(3.dp)) {
            sectionRow()
        }
        listOf(Tab.SEARCH, Tab.SETTINGS).filter { it in tabs }.forEach { t ->
            FocusBox(onClick = { onSelect(t) }, modifier = focusFor(t)) { f ->
                Box(
                    Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(when { f -> Color(0xFFEFFBFC); t == selected -> tv.luna.player.ui.theme.Palette.selected; else -> tv.luna.player.ui.theme.Palette.navGlass })
                        .border(1.dp, if (f) Color.Transparent else tv.luna.player.ui.theme.Palette.navBorder, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(t.icon), contentDescription = t.label, tint = if (f) tv.luna.player.ui.theme.Palette.ink else Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (showClock) NavClock(showDate)
        pingMs?.let { Text(pingLabel(it), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LocalTheme.current.colors.muted, modifier = Modifier.padding(start = 6.dp)) }
    }
}

/** Its own composable, so the minute tick recomposes only this Text, not the nav. */
@Composable
private fun NavClock(showDate: Boolean) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value = System.currentTimeMillis() }
    }
    Text(if (showDate) "${clockTime(now)} ${dayDate(now)}" else clockTime(now), fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = Color.White, modifier = Modifier.padding(start = 14.dp))
}

private val clock = DateTimeFormatter.ofPattern("h.mma", Locale.UK)
fun clockTime(t: Long): String = clock.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())).lowercase()

private val day = DateTimeFormatter.ofPattern("EEE d/M", Locale.UK)
/** "Fri 13/11". */
fun dayDate(t: Long): String = day.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

/** "12 ms", or "offline" when the last check failed (-1). */
fun pingLabel(ms: Int): String = if (ms < 0) "offline" else "$ms ms"

fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private val OK_KEYS = setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)

/** OK held: the first auto-repeat (or a long-press flagged event) of an OK key going down. */
fun isLongOk(e: android.view.KeyEvent): Boolean =
    e.keyCode in OK_KEYS && e.action == android.view.KeyEvent.ACTION_DOWN && (e.repeatCount == 1 || e.isLongPress)

/** Long OK runs [action] and swallows the rest of that press (repeats and key-up), so the normal click doesn't fire too. */
fun Modifier.onLongOk(action: () -> Unit): Modifier = composed {
    var swallowing by remember { mutableStateOf(false) }
    onPreviewKeyEvent { k ->
        val e = k.nativeKeyEvent
        when {
            isLongOk(e) -> { swallowing = true; action(); true }
            swallowing && e.keyCode in OK_KEYS -> { if (e.action == android.view.KeyEvent.ACTION_UP) swallowing = false; true }
            else -> false
        }
    }
}

/** The app's logo (res/drawable-xhdpi/logo.png, rendered from art/mark.svg). */
@Composable
fun AppLogo(modifier: Modifier = Modifier) =
    Box(modifier, contentAlignment = Alignment.Center) {
        // The name as text (never next to the icon): the glossy Montserrat "LUNA", as on the TV banner (ui/Brand.kt).
        LunaWordmark(17.sp, 2.sp)
    }
