package tv.luna.player.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin
import kotlin.random.Random

private class Flake(val x: Float, val y: Float, val r: Float, val speed: Float, val sway: Float, val phase: Float, val alpha: Float)

/** Gentle falling snow for the Christmas theme: small, slow, soft, and thinning out towards the bottom of the screen. */
@Composable
fun Snowfall(modifier: Modifier) {
    val flakes = remember {
        val rnd = Random(25)
        List(70) {
            // Fine, faint and very slow: barely drifting down.
            Flake(rnd.nextFloat(), rnd.nextFloat(), .6f + rnd.nextFloat() * .9f, .004f + rnd.nextFloat() * .006f,
                3f + rnd.nextFloat() * 5f, rnd.nextFloat() * 6.28f, .18f + rnd.nextFloat() * .3f)
        }
    }
    var ms by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { val start = withFrameMillis { it }; while (true) withFrameMillis { ms = it - start } }
    Canvas(modifier) {
        val t = ms / 1000f
        flakes.forEach { f -> flake(f, t) }
    }
}

private fun DrawScope.flake(f: Flake, t: Float) {
    val y = ((f.y + t * f.speed) % 1f) * size.height
    val x = f.x * size.width + sin(t * .15f + f.phase) * f.sway * density
    val fade = 1f - (y / size.height) * .7f
    drawCircle(Color.White.copy(alpha = f.alpha * fade), radius = f.r * density, center = Offset(x, y))
}

/** A soft cap of snow along the top edge of a panel (drawn inside it, so its rounded shape trims the ends). */
fun DrawScope.snowCap() {
    val h = 5f * density
    drawRect(Color.White.copy(alpha = .9f), size = androidx.compose.ui.geometry.Size(size.width, h * .55f))
    var x = 0f
    var i = 0
    while (x < size.width) {
        val w = (14f + (i * 7 % 11)) * density
        drawOval(Color.White.copy(alpha = .9f), topLeft = Offset(x - w * .1f, -h * .25f), size = androidx.compose.ui.geometry.Size(w, h * (1.2f + (i % 3) * .25f)))
        x += w * .8f; i++
    }
}
