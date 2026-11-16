package org.imdtv.iptvtv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.IptvApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.imdtv.iptvtv.BuildConfig

/**
 * Settings > About. Plain lines, not boxes: only the update button can be pressed.
 * This is Luna's own version of the free, open source "IMD TV" player by majorleeevil; the GPL-3.0 licence requires keeping
 * that credit and the licence, so the last line says so.
 */
@Composable
fun AboutRows(first: Modifier) {
    var result by remember { mutableStateOf<String?>(null) }
    Text("Created by Luna Developers", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Text("Based off modified version of IMD TV (GPL-3.0)", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 18.dp))
    // Checks github.com/zchchog/lunaro-tv; OK opens the update prompt when a newer version is out.
    val updater = (LocalContext.current.applicationContext as IptvApp).container.updater
    val update by updater.available.collectAsState()
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val u = update
    SettingRow("Check for updates", result ?: if (u != null) "Version ${u.version} is available. Press OK to update" else "Look for a newer version of Luna",
        null, first, busy = checking) {
        if (u != null) updater.prompt.value = true
        else if (!checking) scope.launch {
            checking = true
            val ok = updater.check(manual = true)
            checking = false
            result = if (!ok) "Couldn't check for updates. Check your connection" else if (updater.available.value == null) "You have the latest version" else null
        }
    }
}
