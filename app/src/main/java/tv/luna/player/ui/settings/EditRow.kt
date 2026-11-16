package tv.luna.player.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.luna.player.ui.Field
import tv.luna.player.ui.FocusBox

/**
 * A setting edited as text: a plain row until OK, then a text box with Save and Cancel under it.
 * Only Save applies; Cancel or Back drops the draft. No box is on screen while scrolling, so the
 * keyboard only opens when asked for. [onDraft] sees each keystroke (null when editing ends), for live previews.
 */
@Composable
fun EditRow(
    title: String, explanation: String, value: String?, initial: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, onDraft: ((String?) -> Unit)? = null, onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(initial) }
    val rowFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    // Focus goes back to the row before the box and buttons leave, or it would jump out of the page.
    fun close() { runCatching { rowFocus.requestFocus() }; editing = false; onDraft?.invoke(null) }
    BackHandler(enabled = editing) { close() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingRow(title, explanation, value, modifier.focusRequester(rowFocus)) { text = initial; editing = true }
        if (editing) {
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { fieldFocus.requestFocus() } }
            // Down from the box goes to Save (the nearest-below search picked Cancel).
            Field("", text, keyboard, secret, modifier = Modifier.focusRequester(fieldFocus).focusProperties { down = saveFocus }) { text = it; onDraft?.invoke(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                PillButton("Save", Modifier.focusRequester(saveFocus)) { onSave(text.trim()); close() }
                PillButton("Cancel") { close() }
            }
        }
    }
}

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Text(label, fontSize = 15.sp, color = if (f) Color.Black else Color.White,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (f) Color.White else Color(0x14FFFFFF))
                .padding(horizontal = 20.dp, vertical = 8.dp))
    }
}
