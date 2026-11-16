package tv.luna.player.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import tv.luna.player.R

/**
 * In-category search bar (Movies/Series): a focusable line that opens the system keyboard only on OK, so D-pad
 * moves past it freely. Up/Down leave it; the keyboard's Search key drops focus to the results below.
 */
@Composable
fun SearchBar(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
              color: Color = Color.White, background: Color = Color(0x26FFFFFF),
              /** Left (-1) / Right (1) while not typing, instead of moving focus (Xcube: switch blade). */
              onSide: ((Int) -> Unit)? = null) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Row(
        modifier.background(background, RoundedCornerShape(14.dp))
            .border(2.dp, if (focused) color else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_tab_search), null, tint = color, modifier = Modifier.size(14.dp))
        Box(Modifier.padding(start = 8.dp)) {
            if (query.isEmpty()) Text(placeholder, fontSize = 13.sp, color = color.copy(alpha = .7f), maxLines = 1)
            BasicTextField(
                query, onQuery, singleLine = true, readOnly = !editing,
                textStyle = androidx.tv.material3.LocalTextStyle.current.merge(TextStyle(color = color, fontSize = 13.sp)), cursorBrush = SolidColor(color),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.onFocusChanged {
                    // The first report is "not focused" (on first layout); only a real loss ends typing.
                    if (focused && !it.isFocused) editing = false
                    focused = it.isFocused
                }.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                        Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                        // Not typing: the read-only field would swallow Left/Right as cursor moves.
                        Key.DirectionLeft, Key.DirectionRight -> if (editing) false else {
                            val left = e.key == Key.DirectionLeft
                            // With onSide (Xcube) always switch: Right's 2D search would otherwise land on a poster below.
                            if (onSide != null) { if (e.nativeKeyEvent.repeatCount == 0) onSide(if (left) -1 else 1) }
                            else focus.moveFocus(if (left) FocusDirection.Left else FocusDirection.Right)
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> if (editing) false else { editing = true; keyboard?.show(); true }
                        else -> false
                    }
                },
            )
        }
    }
}
