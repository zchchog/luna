package org.imdtv.iptvtv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
 * This is Lunaro's own version of the free, open source "IMD TV" player by majorleeevil; the GPL-3.0 licence requires keeping
 * that credit and the licence, so the last line says so.
 */
@Composable
fun AboutRows(first: Modifier) {
    var result by remember { mutableStateOf<String?>(null) }
    Text("Created by Lunaro Developers", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Text("Based off modified version of IMD TV (GPL-3.0)", fontSize = 16.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 18.dp))
    // Not connected to anywhere to download updates from yet: it says so rather than pretending.
    SettingRow("Check for updates", result ?: "Look for a newer version of Lunaro", null, first) {
        result = "Updates aren't set up yet"
    }
}
