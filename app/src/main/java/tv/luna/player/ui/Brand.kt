package tv.luna.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.luna.player.R
import tv.luna.player.ui.theme.LocalTheme

/** Luna's brand pieces: the Montserrat name, the glossy "LUNA" wordmark, the teal moon-glow background, the opening screen. */
val Montserrat = FontFamily(Font(R.font.montserrat_semibold, FontWeight.SemiBold))

val BrandGlow = Color(0xFFB5F1F4)
private val MoonTeal = Color(0xFF8FE3E6)
private val Shine = Brush.verticalGradient(
    0f to Color.White, .46f to Color(0xFFE9FBFC), .54f to Color(0xFFA9E3E8), 1f to Color(0xFFE3F8FA),
)

/** "LUNA" in glossy white Montserrat, like the TV banner. */
@Composable
fun LunaWordmark(size: TextUnit, spacing: TextUnit, modifier: Modifier = Modifier) {
    Text(
        "LUNA", modifier,
        style = TextStyle(
            brush = Shine, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = size, letterSpacing = spacing,
            shadow = Shadow(Color(0x4D000000), Offset(0f, 4f), 20f),
        ),
    )
}

/**
 * The teal gradient with the moon rising from below (two see-through circles, as on the banner and icon) and a soft
 * diagonal shine. [glowX]/[glowY] place the moon's centre as fractions of the screen; [glowR] is its radius as a fraction
 * of the height.
 */
@Composable
fun BrandBackground(
    modifier: Modifier = Modifier, glowX: Float = .5f, glowY: Float = 1.19f, glowR: Float = .53f,
    content: @Composable BoxScope.() -> Unit,
) {
    val bg = LocalTheme.current.colors.bg
    Box(
        modifier.fillMaxSize().background(Brush.linearGradient(bg)).drawBehind {
            val c = Offset(size.width * glowX, size.height * glowY)
            val r = size.height * glowR
            drawCircle(MoonTeal.copy(alpha = .07f), radius = r * 1.37f, center = c)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .32f), MoonTeal.copy(alpha = .22f), MoonTeal.copy(alpha = .10f)),
                    center = Offset(c.x - r * .2f, c.y - r * .5f), radius = r * 1.6f,
                ),
                radius = r, center = c,
            )
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = .14f), .5f to Color.White.copy(alpha = .02f), .51f to Color.Transparent,
                    start = Offset.Zero, end = Offset(size.width * .5f, size.height),
                ),
            )
        },
        content = content,
    )
}

/** The opening screen when Luna starts from closed: the moon rises, LUNA settles in, three dots pulse, then it fades away. */
@Composable
fun LunaSplash(onDone: () -> Unit) {
    val rise = remember { Animatable(1f) }
    val word = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { rise.animateTo(0f, tween(1400, easing = FastOutSlowInEasing)) }
        delay(400)
        word.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        delay(500)
        fade.animateTo(0f, tween(420))
        onDone()
    }
    val pulse = rememberInfiniteTransition(label = "dots")
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        BrandBackground(glowY = 1.19f + .4f * rise.value) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(27.dp)) {
                LunaWordmark(126.sp, (21f + 24f * (1f - word.value)).sp,
                    Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 12.dp.toPx() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 200, 400).forEach { d ->
                        val a by pulse.animateFloat(.25f, 1f, infiniteRepeatable(tween(600, delayMillis = d), RepeatMode.Reverse), label = "dot$d")
                        Box(Modifier.size(8.dp).graphicsLayer { alpha = a * word.value }.background(Color(0xFFEFFBFC), CircleShape))
                    }
                }
            }
        }
    }
}
