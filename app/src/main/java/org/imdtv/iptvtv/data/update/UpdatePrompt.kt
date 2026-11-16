package org.imdtv.iptvtv.data.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.imdtv.iptvtv.ui.FocusBox
import org.imdtv.iptvtv.ui.theme.LocalTheme

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The update pop-up, over whatever screen is showing, in Luna's teal. "Not now" closes it until the app is next opened
 * (with "Check for updates when Luna opens" on, it comes back then). The download runs in [background], so closing the
 * pop-up doesn't stop it.
 */
@Composable
fun UpdatePrompt(updater: Updater, background: CoroutineScope) {
    val open by updater.prompt.collectAsState()
    val update by updater.available.collectAsState()
    val progress by updater.progress.collectAsState()
    val error by updater.error.collectAsState()
    val scope = rememberCoroutineScope()
    val u = update ?: return
    if (!open) return
    val colors = LocalTheme.current.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    // The pop-up can appear while the remote's OK is held: only a fresh press after it opens counts.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { scope.launch { updater.later() } }) {
        Column(
            Modifier.onPreviewKeyEvent { e ->
                if (armed || e.key !in OK_KEYS) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { armed = true; false } else true
            }.width(560.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(colors.bg))
                .background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF))))
                .padding(horizontal = 36.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("UPDATE AVAILABLE", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.muted)
            Text("Luna ${u.version}", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            val body = error ?: progress ?: u.notes.ifBlank { "A new version of Luna is ready to install." }
            Text(body, fontSize = 16.sp, color = Color.White, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (progress != null) {
                    PromptButton("Hide", Modifier.focusRequester(first)) { updater.prompt.value = false }
                } else {
                    PromptButton(if (error != null) "Try again" else "Update now", Modifier.focusRequester(first)) {
                        background.launch { updater.install(u) }
                    }
                    PromptButton("Not now", Modifier) { scope.launch { updater.later() } }
                }
            }
        }
    }
}

/** A pop-up button: white when highlighted, frosted otherwise. */
@Composable
private fun PromptButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalTheme.current.colors
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (f) colors.onFocus else Color.White,
            modifier = Modifier.width(200.dp).clip(RoundedCornerShape(12.dp))
                .background(if (f) colors.focus else Color(0x26FFFFFF)).padding(vertical = 14.dp))
    }
}
