package org.imdtv.iptvtv.ui.live

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import org.imdtv.iptvtv.ui.theme.LocalTheme

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithCache
import androidx.tv.material3.Text
import org.imdtv.iptvtv.ui.Spinner
import org.imdtv.iptvtv.data.catalogue.displayName
import org.imdtv.iptvtv.data.db.ChannelEntity

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
        Box(Modifier.padding(start = 24.dp, top = 22.dp)) { nav() }
        GuideHero(vm, Modifier.padding(start = 24.dp, top = 80.dp).width(450.dp))

        if (rail?.isEmpty() == true) {
            Text("All live categories are hidden. Show them again in Settings › Channels & categories.", fontSize = 16.sp,
                color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 380.dp))
        }
        Row(Modifier.padding(start = 24.dp, top = 266.dp), verticalAlignment = Alignment.CenterVertically) {
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
            modifier = Modifier.padding(start = 24.dp, top = 290.dp, end = 24.dp).fillMaxSize())
        // The category list slides in from the left over a dimmed guide, and slides back out.
        AnimatedVisibility(state.railOpen, enter = fadeIn(tween(240)), exit = fadeOut(tween(220))) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        }
        AnimatedVisibility(
            state.railOpen,
            enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(260)),
            exit = slideOutHorizontally(tween(260, easing = FastOutLinearInEasing)) { -it } + fadeOut(tween(220)),
        ) {
            CategoryRail(vm, state)
        }
        GuideMenus(vm, state)
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
