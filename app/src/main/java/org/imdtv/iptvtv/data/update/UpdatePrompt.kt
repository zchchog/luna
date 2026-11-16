package org.imdtv.iptvtv.data.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.ui.MenuFrame
import org.imdtv.iptvtv.ui.MenuItem

/** The update prompt, over every screen. The download runs in [background], so closing the prompt doesn't stop it. */
@Composable
fun UpdatePrompt(updater: Updater, background: CoroutineScope) {
    val open by updater.prompt.collectAsState()
    val update by updater.available.collectAsState()
    val progress by updater.progress.collectAsState()
    val error by updater.error.collectAsState()
    val scope = rememberCoroutineScope()
    val u = update ?: return
    if (!open) return
    MenuFrame("Luna ${u.version} is available", onDismiss = { scope.launch { updater.later() } }) { first ->
        val body = error ?: progress ?: u.notes.ifBlank { "A new version of Luna is ready to install." }
        Text(body, fontSize = 15.sp, color = Color.White, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        if (progress != null) MenuItem("Hide", Modifier.focusRequester(first)) { updater.prompt.value = false }
        else {
            MenuItem(if (error != null) "Try again" else "Update now", Modifier.focusRequester(first)) { background.launch { updater.install(u) } }
            MenuItem("Later", Modifier) { scope.launch { updater.later() } }
        }
    }
}
