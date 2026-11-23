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
 * Live TV's corner when nothing is previewing: a tilted, slowly drifting wall of film and series covers from the
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
                        Box(Modifier.size(100.dp, 150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x403459CA))) {
                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(100.dp, 150.dp))
                        }
                    }
                }
            }
        }
    }
}
