package tv.luna.player.ui.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.first
import tv.luna.player.IptvApp

/**
 * Live TV's corner when nothing is previewing: a tilted wall of film and series covers from the
 * playlist, fading into the background like the live picture does.
 */
@Composable
fun PosterWall(modifier: Modifier) {
    val c = (LocalContext.current.applicationContext as IptvApp).container
    val posters by produceState(emptyList<String>()) {
        val p = c.providerRepository.activeProviderId.first() ?: return@produceState
        val films = runCatching { c.db.movieDao().newestPosters(p, 14) }.getOrDefault(emptyList())
        val shows = runCatching { c.db.seriesDao().newestPosters(p, 14) }.getOrDefault(emptyList())
        // Films and series mixed, a few of each per row.
        value = (films.zip(shows).flatMap { listOf(it.first, it.second) } + films.drop(shows.size) + shows.drop(films.size)).distinct().take(21)
    }
    if (posters.size < 6) return
    // Structured motion: tilted columns gliding in a steady loop, alternate columns going up and down
    // (no wandering back and forth). Each column holds PER posters drawn twice, so the loop is seamless.
    val cols = 6
    val per = 6
    val step = 160.dp // poster height + gap
    val columns = List(cols) { c ->
        val mine = posters.filterIndexed { i, _ -> i % cols == c }.ifEmpty { posters }
        List(per) { i -> mine[(i + c) % mine.size] }
    }
    val t by rememberInfiniteTransition(label = "wall").animateFloat(0f, 1f,
        infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "glide")
    Box(
        modifier.clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Gentle, progressive fades: long from the left, and away at the bottom (and a touch at the top).
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, .18f to Color.Black.copy(alpha = .12f),
                    .38f to Color.Black.copy(alpha = .55f), .6f to Color.Black), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .35f), .12f to Color.Black,
                    .5f to Color.Black, .78f to Color.Black.copy(alpha = .4f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Bigger than the corner and tilted, so posters always reach past every edge (no empty gaps).
        Row(
            Modifier.requiredSize(1000.dp, 820.dp).graphicsLayer { rotationZ = -12f; alpha = .9f },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            columns.forEachIndexed { c, list ->
                val up = c % 2 == 0
                Column(
                    Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer {
                        val loop = step.toPx() * per
                        val p = (t + c * .17f) % 1f
                        translationY = if (up) -p * loop else (p - 1f) * loop
                    },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    (list + list).forEach { url ->
                        Box(Modifier.size(100.dp, 150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x401C9AA0))) {
                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(100.dp, 150.dp))
                        }
                    }
                }
            }
        }
    }
}
