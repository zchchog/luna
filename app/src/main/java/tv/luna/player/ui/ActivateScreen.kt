package tv.luna.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.luna.player.ui.theme.guideBackground

/** First ever start: the registration code. [check] says whether a code is accepted (and remembers it if so). */
@Composable
fun ActivateScreen(check: suspend (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableIntStateOf(0) }
    var waitUntil by remember { mutableStateOf(0L) }
    val field = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }

    fun submit() {
        if (busy || code.isBlank()) return
        val now = System.currentTimeMillis()
        if (now < waitUntil) { message = "Too many tries. Wait ${(waitUntil - now) / 1000 + 1} seconds."; return }
        busy = true; message = null
        scope.launch {
            val ok = check(code)
            busy = false
            if (!ok) {
                wrong++
                // A pause after a few wrong codes, so codes can't be guessed by trying lots.
                if (wrong % 5 == 0) waitUntil = System.currentTimeMillis() + 30_000
                message = "That code isn't right. Check it and try again."
                delay(300)
            }
        }
    }

    Box(Modifier.fillMaxSize().guideBackground(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LunaWordmark(40.sp, 10.sp)
            Text("Welcome to Luna", fontSize = 22.sp, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.padding(top = 8.dp))
            Text("Enter your registration code to get started.", fontSize = 14.sp, color = Color(0xCCEFFBFC), textAlign = TextAlign.Center)
            Field("Registration code", code, modifier = Modifier.fillMaxWidth().focusRequester(field)) { code = it; message = null }
            message?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFFB4A9), textAlign = TextAlign.Center) }
            FocusBox(onClick = { submit() }) { f ->
                Text(if (busy) "Checking…" else "Continue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (f) Color(0xFF06323A) else Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.width(200.dp).clip(RoundedCornerShape(20.dp)).background(if (f) Color(0xFFEFFBFC) else Color(0x33FFFFFF))
                        .padding(vertical = 10.dp))
            }
            Text("No code? Message @novadrop:matrix.org on the Element X app.", fontSize = 12.sp, color = Color(0x99EFFBFC),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
