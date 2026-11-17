package tv.luna.player.ui.ondemand

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.luna.player.IptvApp
import tv.luna.player.ui.Montserrat

/**
 * Live TV's corner when nothing is previewing: a tilted, slowly drifting wall of film and series posters (from the
 * Metadata link), fading into the background like the live picture does. Nothing shows until On Demand is unlocked.
 */
@Composable
fun PosterWall(modifier: Modifier) {
    val repo = (LocalContext.current.applicationContext as IptvApp).container.addons
    val posters by produceState(emptyList<String>()) {
        if (!repo.unlocked()) return@produceState
        for ((addon, cat) in repo.homeCatalogs().take(4)) {
            val list = runCatching { repo.catalog(addon, cat) }.getOrDefault(emptyList()).mapNotNull { it.poster }
            if (list.size >= 8) { value = list.take(21); break }
        }
    }
    if (posters.isEmpty()) return
    val drift by rememberInfiniteTransition(label = "wall").animateFloat(0f, 1f,
        infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(
        modifier.clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, .45f to Color.Black), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.requiredSize(760.dp, 520.dp).graphicsLayer { rotationZ = -13f; translationX = (drift - .5f) * 80.dp.toPx(); alpha = .9f },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            posters.chunked(7).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { url ->
                        AsyncImage(url, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(100.dp, 150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x3306323A)))
                    }
                }
            }
        }
    }
}

/** A Luna-made card for a collection folder (the Discover row): glossy teal, faint moons, an icon and the name. */
@Composable
fun BrandedTile(title: String, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(w, h).background(Brush.linearGradient(listOf(Color(0xFF1C9AA0), Color(0xFF0D6F7A), Color(0xFF06323A)),
            start = Offset.Zero, end = Offset(600f, 400f))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension
            drawCircle(Color(0x1AEFFBFC), r * .9f, Offset(size.width * .95f, -r * .15f))
            drawCircle(Color(0x12EFFBFC), r * .55f, Offset(size.width * .78f, r * .1f))
            // A soft sheen over the top half, like the app icon.
            drawRect(Brush.verticalGradient(0f to Color(0x26FFFFFF), .5f to Color.Transparent), size = Size(size.width, size.height * .5f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FolderIcon(title)
            Text(title, fontSize = 15.sp, fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center,
                maxLines = 2, modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp))
        }
    }
}

/** An icon picked from the folder's name: trending, new, top rated, for you; a moon otherwise. */
@Composable
private fun FolderIcon(title: String) {
    val t = title.lowercase()
    val c = Color(0xFFEFFBFC)
    Canvas(Modifier.size(26.dp)) {
        val w = size.width; val h = size.height; val sw = 2.4.dp.toPx()
        when {
            "trend" in t || "popular" in t -> { // rising line with an arrow
                val p = Path().apply { moveTo(w * .08f, h * .78f); lineTo(w * .38f, h * .48f); lineTo(w * .56f, h * .64f); lineTo(w * .9f, h * .26f) }
                drawPath(p, c, style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(c, Offset(w * .9f, h * .26f), Offset(w * .64f, h * .26f), sw, StrokeCap.Round)
                drawLine(c, Offset(w * .9f, h * .26f), Offset(w * .9f, h * .52f), sw, StrokeCap.Round)
            }
            "late" in t || "new" in t || "premier" in t || "release" in t -> { // four-point sparkle
                val p = Path().apply {
                    moveTo(w / 2, 0f); quadraticTo(w * .55f, h * .45f, w, h / 2); quadraticTo(w * .55f, h * .55f, w / 2, h)
                    quadraticTo(w * .45f, h * .55f, 0f, h / 2); quadraticTo(w * .45f, h * .45f, w / 2, 0f); close()
                }
                drawPath(p, c)
            }
            "top" in t || "rated" in t || "best" in t -> { // star
                val p = Path()
                for (i in 0 until 10) {
                    val a = Math.toRadians((-90 + i * 36).toDouble()); val rr = if (i % 2 == 0) w / 2 else w / 4.6f
                    val x = w / 2 + (rr * Math.cos(a)).toFloat(); val y = h / 2 + (rr * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, c)
            }
            "recommend" in t || "for you" in t -> { // heart
                val p = Path().apply {
                    moveTo(w / 2, h * .88f)
                    cubicTo(w * .05f, h * .55f, w * .05f, h * .12f, w / 2, h * .3f)
                    cubicTo(w * .95f, h * .12f, w * .95f, h * .55f, w / 2, h * .88f); close()
                }
                drawPath(p, c)
            }
            else -> { drawCircle(c, w * .42f, Offset(w / 2, h / 2)); drawCircle(Color(0xFF0D6F7A), w * .36f, Offset(w * .66f, h * .38f)) }
        }
    }
}
