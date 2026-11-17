package tv.luna.player.ui.live

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import tv.luna.player.ui.theme.LocalTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithCache
import androidx.tv.material3.Text
import tv.luna.player.ui.Spinner
import tv.luna.player.data.catalogue.displayName
import tv.luna.player.data.db.ChannelEntity

@Composable
fun LiveGuideScreen(vm: LiveViewModel, nav: @Composable () -> Unit, onFullScreen: (ChannelEntity) -> Unit) {
    val state = rememberGuideState(vm)
    val rail by vm.rail.collectAsState()
    val entry by vm.entry.collectAsState()
    val updating by vm.guideUpdating.collectAsState()

    var screen by remember { mutableStateOf(Size.Zero) }
    val colors = LocalTheme.current.colors // read here so a theme change re-rasterises the cached background
    Box(Modifier.fillMaxSize().onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) }.drawWithCache {
        // The Chromecast GPU is the bottleneck: rasterise the full-screen gradients once, then just blit them.
        val img = backgroundBitmap(colors, this, size, Offset.Zero, size)
        onDrawBehind { drawImage(img) }
    }) {
        Preview(vm, visible = state.previewing, screen = screen, edge = PreviewEdge.FADE,
            modifier = Modifier.align(Alignment.TopEnd).size(470.dp, 264.dp))
        // Nothing previewing: a drifting wall of film and series posters in the corner instead of an empty space.
        if (!state.previewing) tv.luna.player.ui.ondemand.PosterWall(Modifier.align(Alignment.TopEnd).size(470.dp, 264.dp))
        Box(Modifier.padding(start = 24.dp, top = 22.dp)) { nav() }
        // Categories open: everything under the top menu moves right to make room for them (moved, not re-laid out,
        // so it stays smooth on slow boxes).
        val shift by androidx.compose.animation.core.animateFloatAsState(if (state.railOpen) 1f else 0f,
            androidx.compose.animation.core.tween(320, easing = FastOutSlowInEasing), label = "railShift")
        val moved = Modifier.graphicsLayer { translationX = (RAIL_WIDTH + 12.dp).toPx() * shift } // clear of the tab on the categories edge
        GuideHero(vm, moved.padding(start = 24.dp, top = 80.dp).width(450.dp))

        if (rail?.isEmpty() == true) {
            Text("All live categories are hidden. Show them again in Settings › Channels & categories.", fontSize = 16.sp,
                color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 380.dp))
        }
        Row(moved.padding(start = 24.dp, top = 266.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "◀ ${entry?.title.orEmpty()}", fontSize = 11.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.cellDim).padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Text("  Press left for categories", fontSize = 11.sp, color = colors.muted)
            if (updating) {
                Spacer(Modifier.width(16.dp)); Spinner(size = 11.dp, color = colors.muted)
                Text("  Updating guide…", fontSize = 11.sp, color = colors.muted)
            }
        }

        // Runs to the bottom of the screen, fading out there to show there is more.
        GuideGrid(vm, state, GLASS_GRID, onFullScreen, onLeftAtStart = { state.railOpen = true },
            modifier = moved.padding(start = 24.dp, top = 290.dp, end = 24.dp).fillMaxSize())
        // Closed: a small tab at the left edge with an arrow, showing the categories are there (press Left to open).
        AnimatedVisibility(!state.railOpen, modifier = Modifier.align(Alignment.CenterStart).padding(top = 58.dp), // centred on the categories panel (top 58dp to the bottom)
             enter = fadeIn(tween(220)), exit = fadeOut(tween(120))) {
            Box(Modifier.size(14.dp, 52.dp).clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)).background(Color(0x2EEFFBFC)),
                contentAlignment = Alignment.Center) {
                Chevron(left = false)
            }
        }
        // Open: the same tab on the categories' edge, arrow pointing back, showing Left/Back puts them away.
        AnimatedVisibility(state.railOpen, modifier = Modifier.align(Alignment.CenterStart).padding(top = 58.dp, start = RAIL_WIDTH),
            enter = fadeIn(tween(260, delayMillis = 160)), exit = fadeOut(tween(100))) {
            Box(Modifier.size(14.dp, 52.dp).clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)).background(Color(0x2EEFFBFC)),
                contentAlignment = Alignment.Center) {
                Chevron(left = true)
            }
        }
        AnimatedVisibility(
            state.railOpen,
            modifier = Modifier.padding(top = 58.dp),
            enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(260)),
            exit = slideOutHorizontally(tween(260, easing = FastOutLinearInEasing)) { -it } + fadeOut(tween(220)),
        ) {
            CategoryRail(vm, state)
        }
        GuideMenus(vm, state, onFullScreen)
    }
}

/** The tab's arrow, drawn (a text arrow sits off-centre because of the font's spacing). */
@Composable
private fun Chevron(left: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(8.dp, 12.dp)) {
        val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
        val tip = if (left) Offset(sw / 2, h / 2) else Offset(w - sw / 2, h / 2)
        val back = if (left) w - sw / 2 else sw / 2
        drawLine(Color.White, Offset(back, sw / 2), tip, strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color.White, Offset(back, h - sw / 2), tip, strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The hero reads focus, guide and stream format itself, so a key press recomposes only it and the rows, not the screen. */
@Composable
private fun GuideHero(vm: LiveViewModel, modifier: Modifier) {
    val channels by vm.channels.collectAsState()
    val programmes by vm.programmes.collectAsState()
    val now by vm.now.collectAsState()
    val focused by vm.focused.collectAsState()
    val strip by vm.strip.collectAsState()
    val logoSource by vm.logoSource.collectAsState()
    val previewingId by vm.previewingId.collectAsState()
    val formats by vm.player.formats.collectAsState()
    val focusAt by vm.focusAt.collectAsState()
    val hero = channels.find { it.id == focused?.id } ?: focused // the list has the renamed copy
    Hero(hero, hero?.displayName(strip).orEmpty(), programmes[focused?.id].orEmpty(), now, logoSource, heroBadges(focused?.id, previewingId, formats), modifier,
        at = focusAt ?: now, descLines = 2)
}
