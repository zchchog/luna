package tv.luna.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.luna.player.data.db.ProviderEntity
import tv.luna.player.data.db.ProviderType

/** Add a playlist (first run: Welcome, then the form), or edit [editId]'s URL, login and user agent. */
@Composable
fun ProviderFormScreen(vm: AppViewModel, editId: Long? = null) {
    // Wait for the playlist being edited so the fields start filled in.
    val editing by produceState<ProviderEntity?>(null, editId) { value = editId?.let { vm.provider(it) } }
    if (editId != null && editing == null) return
    key(editing?.id) { ProviderForm(vm, editing) }
}

private enum class Step { CHOOSE, FORM }

// Luna's setup colours (on top of the teal theme).
private val Ink = Color(0xFF06323A)
private val InkMid = Color(0xFF0D6F7A)
private val Soft = Color(0xD1EFFBFC)
private val Faint = Color(0x99EFFBFC)
private val Problem = Color(0xFFFFC9BF)
private val FocusFill = Brush.verticalGradient(listOf(Color.White, Color(0xFFE3F7F8)))
private val PanelShape = RoundedCornerShape(18.dp)

private fun Modifier.panel() = clip(PanelShape).background(Color(0x8C052228)).border(1.dp, Color(0x29FFFFFF), PanelShape)
private fun Modifier.glow(on: Boolean, shape: androidx.compose.ui.graphics.Shape) =
    if (on) shadow(22.dp, shape, ambientColor = BrandGlow, spotColor = BrandGlow) else this
private fun Modifier.grow(on: Boolean, by: Float = 1.05f) = graphicsLayer { val s = if (on) by else 1f; scaleX = s; scaleY = s }

private fun strokeIcon(vararg paths: String): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    paths.forEach {
        addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

private val KeyIcon = strokeIcon("M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M11 12l9-9M17 6l3 3M15 8l2 2")
private val LinkIcon = strokeIcon("M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1", "M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1")
private val InfoIcon = strokeIcon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 8h.01M11 12h1v5h1")
private val CheckIcon = strokeIcon("M5 12l5 5 9-10")
private val ChatIcon = strokeIcon("M4 5h16v11H9l-5 4z")

@Composable
private fun Icon(v: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp) =
    Image(rememberVectorPainter(v), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size))

/** Focus [r] once the screen has drawn (a request before the first frame is ignored). */
@Composable
private fun FocusOnShow(r: FocusRequester) {
    LaunchedEffect(r) { withFrameNanos { }; runCatching { r.requestFocus() } }
}

@Composable
private fun ProviderForm(vm: AppViewModel, editing: ProviderEntity?) {
    val state by vm.form.collectAsState()
    var type by rememberSaveable { mutableStateOf(editing?.type ?: ProviderType.XTREAM) }
    var url by rememberSaveable { mutableStateOf(editing?.url.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(editing?.username.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(editing?.password.orEmpty()) }
    var epg by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(editing?.userAgent.orEmpty()) }
    var step by rememberSaveable { mutableStateOf(if (editing == null) Step.CHOOSE else Step.FORM) }

    // Back on the form of a new playlist returns to the Welcome choice (not out of setup).
    BackHandler(enabled = editing == null && step == Step.FORM && !state.busy && state.result == null) {
        vm.form.value = FormState(); step = Step.CHOOSE
    }

    val atChoose = step == Step.CHOOSE && !state.busy && state.result == null
    BrandBackground(glowX = if (atChoose) .26f else .86f, glowY = 1.25f, glowR = .45f) {
        when {
            state.busy -> Connecting(state.progress)
            state.result != null -> Ready(state.result!!, editing != null) { if (editing == null) vm.done() else vm.back() }
            step == Step.CHOOSE -> Choose { t -> type = t; step = Step.FORM }
            else -> {
                val xtream = type == ProviderType.XTREAM
                FormStep(
                    stepLabel = if (editing == null) "STEP 2 OF 2" else null,
                    title = when { editing != null -> "Edit ${editing.name}"; xtream -> "Sign in to your TV service"; else -> "Add your playlist link" },
                    subtitle = if (xtream) "Type the details your provider sent you." else "Type the web address your provider sent you.",
                    sideTitle = when { state.error != null -> "Still stuck?"; xtream -> "Where do I find these?"; else -> "Tip" },
                    sideText = when {
                        state.error != null -> "Check the address starts with http:// and includes the port number (the :8080 part) if your provider gave one."
                        xtream -> "Your TV provider sends them by message or email after you subscribe. Ask them if you can't find them."
                        else -> "Typing long links with a remote is slow. You can also restore a saved setup later from Settings › Backup & restore."
                    },
                    error = state.error,
                    agent = agent, onAgent = { agent = it },
                    mainLabel = when { state.error != null -> "Try again"; editing != null -> "Save"; else -> "Connect" },
                    onMain = { vm.save(type, url, user, pass, epg, agent, editing) },
                    showBack = true,
                    onBack = { if (editing == null) { vm.form.value = FormState(); step = Step.CHOOSE } else vm.back() },
                ) { first ->
                    LunaField(if (xtream) "Server address" else "Playlist link", url, KeyboardType.Uri,
                        placeholder = if (xtream) "e.g. http://myprovider.tv:8080" else "e.g. http://myprovider.tv/get.php?…",
                        problem = state.error != null, modifier = Modifier.focusRequester(first)) { url = it }
                    if (xtream) {
                        // The password keyboard has the number row many providers' usernames need.
                        LunaField("Username", user, KeyboardType.Password, placeholder = "Your username") { user = it }
                        LunaField("Password", pass, KeyboardType.Password, secret = true, placeholder = "Your password") { pass = it }
                    } else if (editing == null) {
                        LunaField("TV guide link (optional)", epg, KeyboardType.Uri, multiLine = true,
                            placeholder = "Leave empty to use the one in the playlist") { epg = it }
                    }
                }
            }
        }
    }
}

/** Welcome: the name, a friendly line, and the two ways to sign in. */
@Composable
private fun Choose(onPick: (ProviderType) -> Unit) {
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.width(390.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LunaWordmark(72.sp, 12.sp)
                Text("Welcome! Let's get your TV set up.", fontFamily = Montserrat, fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp, lineHeight = 32.sp, color = Color.White)
                Text("Choose how your TV service gave you your details. You only need to do this once.",
                    fontSize = 15.sp, lineHeight = 22.sp, color = Soft)
                // Where to get an account, for people setting Luna up from scratch.
                Row(Modifier.padding(top = 8.dp).panel().padding(horizontal = 15.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Icon(ChatIcon, BrandGlow, 20.dp)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("No account yet? To get a subscription, message ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("@novadrop:matrix.org") }
                            append(" on the ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("Element X") }
                            append(" app.")
                        },
                        fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xE6EFFBFC),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ChoiceCard("Xtream Codes", "Server, username and password", KeyIcon, Modifier.focusRequester(first)) { onPick(ProviderType.XTREAM) }
                ChoiceCard("M3U playlist", "A web address ending in .m3u", LinkIcon) { onPick(ProviderType.M3U) }
            }
        }
        Text("Use the arrows on your remote to choose, then press OK", fontSize = 12.sp, color = Faint,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 30.dp))
    }
}

@Composable
private fun ChoiceCard(title: String, sub: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Row(
            Modifier.width(330.dp).grow(f, 1.04f).glow(f, shape).clip(shape)
                .background(if (f) FocusFill else SolidColor(Color(0x1AFFFFFF)))
                .border(1.dp, if (f) Color.Transparent else Color(0x2EFFFFFF), shape)
                .padding(horizontal = 24.dp, vertical = 21.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, if (f) InkMid else BrandGlow, 33.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (f) Ink else Color.White)
                Text(sub, fontSize = 13.sp, color = if (f) InkMid else Soft)
            }
        }
    }
}

/** The sign-in form: heading and step at the top left, LUNA at the top right, fields in a frosted panel, help beside it. */
@Composable
private fun FormStep(
    stepLabel: String?, title: String, subtitle: String, sideTitle: String, sideText: String, error: String?,
    agent: String, onAgent: (String) -> Unit, mainLabel: String, onMain: () -> Unit, showBack: Boolean, onBack: () -> Unit,
    fields: @Composable (FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    var more by rememberSaveable { mutableStateOf(agent.isNotEmpty()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (stepLabel != null) Text(stepLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = BrandGlow)
                Text(title, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, color = Color.White)
                Text(subtitle, fontSize = 14.sp, color = Soft)
            }
            LunaWordmark(30.sp, 6.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(480.dp).panel().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                fields(first)
                if (more) LunaField("User agent (optional)", agent, placeholder = "Only if your provider asks for one") { onAgent(it) }
                else FocusBox(onClick = { more = true }) { f ->
                    Text("More options (user agent) ›", fontSize = 12.sp, color = if (f) Ink else Faint,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (f) Color.White else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
                if (error != null) Text(error, fontSize = 13.sp, lineHeight = 18.sp, color = Problem)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PillButton(mainLabel, onClick = onMain)
                    if (showBack) PillButton("Back", onClick = onBack)
                }
            }
            Column(Modifier.width(270.dp).panel().padding(21.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(InfoIcon, BrandGlow, 24.dp)
                Text(sideTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(sideText, fontSize = 12.sp, lineHeight = 18.sp, color = Soft)
            }
        }
    }
}

/** A text box for the remote: highlighted with a white glow, "Press OK to type" until OK opens the keyboard. */
@Composable
private fun LunaField(
    label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, placeholder: String = "",
    multiLine: Boolean = false, problem: Boolean = false, modifier: Modifier = Modifier, onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val keyboardCtl = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(11.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xCCEFFBFC))
        // Selected text: a light teal wash, not the default dark blue.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                androidx.compose.foundation.text.selection.TextSelectionColors(handleColor = Color.White, backgroundColor = Color(0x66B5F1F4)),
        ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = !multiLine, readOnly = !editing,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.tv.material3.LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 15.sp)),
            cursorBrush = SolidColor(Color.White),
            // A text field keeps Up/Down as cursor moves, which traps a D-pad: use them to leave the field.
            modifier = modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                    Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                    Key.DirectionCenter, Key.Enter -> if (editing) false else { editing = true; keyboardCtl?.show(); true }
                    else -> false
                }
            }.onFocusChanged { if (!it.isFocused) editing = false }.fillMaxWidth()
                .glow(focused, shape).clip(shape)
                .background(if (focused) Color(0x29FFFFFF) else Color(0x12FFFFFF))
                .border(2.dp, when { focused -> Color(0xFFEFFBFC); problem -> Color(0xFFFFB4A8); else -> Color(0x38FFFFFF) }, shape)
                .padding(horizontal = 15.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Faint, maxLines = 1)
                        inner()
                    }
                    if (focused && !editing) Text("Press OK to type", fontSize = 11.sp, color = BrandGlow, modifier = Modifier.padding(start = 12.dp))
                }
            },
        )
        }
    }
}

@Composable
private fun PillButton(label: String, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 180.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    FocusBox(onClick = onClick, modifier = modifier) { f ->
        Text(label, fontSize = 14.sp, fontWeight = if (f) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (f) Ink else Color.White,
            modifier = Modifier.width(width).grow(f).glow(f, shape).clip(shape)
                .background(if (f) FocusFill else SolidColor(Color(0x1FFFFFFF))).padding(vertical = 13.dp))
    }
}

/** Loading the playlist: the name, a spinner with what's happening, and a moving bar. */
@Composable
private fun Connecting(progress: String?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
        LunaWordmark(42.sp, 9.sp)
        Text("Getting your channels ready…", fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Color.White)
        Column(Modifier.width(420.dp).panel().padding(horizontal = 24.dp, vertical = 21.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Spinner(size = 24.dp, color = Color.White)
                Text(progress ?: "Connecting to your TV service…", fontSize = 15.sp, color = Color.White, maxLines = 2)
            }
            val t by rememberInfiniteTransition(label = "bar").animateFloat(0f, 1f,
                infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "slide")
            BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x26FFFFFF))) {
                Box(Modifier.offset(x = maxWidth * (t * 1.35f - .35f)).width(maxWidth * .35f).height(6.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF8FE3E6), Color(0xFFEFFBFC))), RoundedCornerShape(3.dp)))
            }
        }
        Text("Big services can take a minute the first time", fontSize = 11.sp, color = Faint)
    }
}

/** Done: a glowing tick, the summary, and one button. */
@Composable
private fun Ready(result: String, edited: Boolean, onGo: () -> Unit) {
    val go = remember { FocusRequester() }
    FocusOnShow(go)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(63.dp).shadow(30.dp, CircleShape, ambientColor = BrandGlow, spotColor = BrandGlow).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFDDF6F8)))), contentAlignment = Alignment.Center) {
            Icon(CheckIcon, Ink, 33.dp)
        }
        Text(if (edited) "Saved" else "You're all set", fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 33.sp, color = Color.White)
        Text(result, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xD9EFFBFC), textAlign = TextAlign.Center, modifier = Modifier.width(600.dp))
        PillButton(if (edited) "Done" else "Start watching", Modifier.padding(top = 12.dp).focusRequester(go), width = 225.dp, onClick = onGo)
    }
}

@Composable
fun Field(
    label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false,
    modifier: Modifier = Modifier, openOnOk: Boolean = false, onChange: (String) -> Unit,
) {
    // openOnOk: read-only until OK, so D-pad can pass through a form without a keyboard on every field.
    var editing by remember { mutableStateOf(!openOnOk) }
    val keyboardCtl = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    Column {
        if (label.isNotEmpty()) Text(label, color = Color.LightGray)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = !label.startsWith("EPG"), readOnly = !editing,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.tv.material3.LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 20.sp)),
            cursorBrush = SolidColor(Color.White),
            // A text field keeps Up/Down as cursor moves, which traps a D-pad: use them to leave the field.
            modifier = modifier.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                    Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                    Key.DirectionCenter, Key.Enter -> if (editing) false else { editing = true; keyboardCtl?.show(); true }
                    else -> false
                }
            }.onFocusChanged { if (openOnOk && !it.isFocused) editing = false }.fillMaxWidth()
                .border(2.dp, if (focused) Color.White else Color.Gray, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
    }
}

private val LockIcon = strokeIcon("M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")

/**
 * First ever start: Luna is invite only, so it asks for a registration code before anything else. Same look as the
 * welcome screen. The box only opens the keyboard when OK is pressed on it, never by itself.
 * [check] says whether a code is accepted (and remembers it if so).
 */
@Composable
fun ActivateScreen(check: suspend (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(0) }
    var waitUntil by remember { mutableStateOf(0L) }
    val first = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    FocusOnShow(first)

    fun submit() {
        if (busy) return
        if (code.isBlank()) { message = "Type your code first."; return }
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
            }
        }
    }

    BrandBackground(glowX = .26f, glowY = 1.25f, glowR = .45f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.width(390.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LunaWordmark(72.sp, 12.sp)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x295FD6DC)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(LockIcon, BrandGlow, 14.dp)
                    Text("INVITE ONLY", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = BrandGlow)
                }
                Text("Welcome to Luna", fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, color = Color.White)
                Text("Luna is invite only. Enter the registration code you were given to get started. You only need to do this once.",
                    fontSize = 15.sp, lineHeight = 22.sp, color = Soft)
                Row(Modifier.padding(top = 8.dp).panel().padding(horizontal = 15.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Icon(ChatIcon, BrandGlow, 20.dp)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("No code yet? To get an invite, message ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("@novadrop:matrix.org") }
                            append(" on the ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append("Element X") }
                            append(" app.")
                        },
                        fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xE6EFFBFC),
                    )
                }
            }
            Column(Modifier.width(380.dp).panel().padding(horizontal = 26.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Registration code", fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White)
                LunaField("Your code", code, placeholder = "e.g. NOVA-LUNA-1234", problem = message != null && !busy,
                    modifier = Modifier.focusRequester(first)) { code = it; message = null }
                message?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFFB4A8)) }
                PillButton(if (busy) "Checking…" else "Continue", width = 328.dp) { submit() }
            }
        }
    }
}
