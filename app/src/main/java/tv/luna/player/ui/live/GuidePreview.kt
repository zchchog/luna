package tv.luna.player.ui.live

import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import tv.luna.player.ui.theme.LocalTheme
import tv.luna.player.ui.theme.ThemeColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.BlendMode
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import tv.luna.player.data.settings.LogoKind
import tv.luna.player.ui.ChannelLogo
import kotlinx.coroutines.flow.first
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.ui.clockTime
import tv.luna.player.ui.duration

/** Draws the guide background as it appears at [origin] of a [screen]-sized backdrop, into a bitmap of [size]. */
internal fun backgroundBitmap(colors: ThemeColors, density: androidx.compose.ui.unit.Density, size: Size, origin: Offset, screenSize: Size): ImageBitmap {
    val screen = if (screenSize.width > 0f && screenSize.height > 0f) screenSize else size // not measured yet on the first frame
    val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), size) {
        translate(-origin.x, -origin.y) {
            drawRect(Brush.linearGradient(colors.bg, end = Offset(screen.width, screen.height)), size = screen)
            drawRect(Brush.radialGradient(listOf(Color(0x5978A0FF), Color.Transparent), center = Offset(screen.width * .85f, screen.height * .1f), radius = screen.width * .68f), size = screen)
        }
    }
    return img
}

/** FADE blends the picture into the background (Glass); NONE is a plain box (Plus); RIM a glass edge (Liquid Glass). */
enum class PreviewEdge { FADE }

@Composable
fun Preview(vm: LiveViewModel, visible: Boolean, screen: Size, modifier: Modifier, edge: PreviewEdge = PreviewEdge.FADE) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val colors = LocalTheme.current.colors
    Box(modifier.size(530.dp, 300.dp).onGloballyPositioned { origin = it.positionInRoot() }) {
        // SurfaceView (PlayerView default): video goes to a hardware overlay, so playing it doesn't redraw the UI.
        if (visible) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // the video surface isn't clipped to the box: zoom would spill a 4:3 picture outside it
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    vm.player.attach(this)
                } },
                onRelease = { vm.player.detach(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Fade the picture into the background (left and bottom edges) by drawing the matching patch of background over it
        // with a transparency gradient. Static, so it's rasterised once.
        if (edge == PreviewEdge.FADE) Box(Modifier.fillMaxSize().drawWithCache {
            val bg = backgroundBitmap(colors, this, size, origin, screen)
            val left = ImageBitmap(size.width.toInt(), size.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(left), size) {
                drawImage(bg)
                drawRect(Brush.horizontalGradient(0f to Color.Black, .45f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
            val bottom = ImageBitmap(size.width.toInt(), size.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bottom), size) {
                drawImage(bg)
                drawRect(Brush.verticalGradient(.6f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstIn)
            }
            onDrawBehind { drawImage(left); drawImage(bottom) }
        })
    }
}

@Composable
fun Hero(ch: ChannelEntity?, name: String, progs: List<GuideProgramme>, now: Long, logoSource: List<LogoKind>, badges: List<String>, modifier: Modifier,
         /** The moment the highlight is on (later than [now] after moving along the guide). */ at: Long = now, descLines: Int = 3) {
    if (ch == null) return
    val cur = progs.firstOrNull { it.start <= at && at < it.stop }
    val notStarted = cur != null && cur.start > now
    Column(modifier) {
        Box(Modifier.size(60.dp, 43.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            ChannelLogo(ch, logoSource, Modifier.fillMaxSize().padding(4.dp))
        }
        Text(cur?.title ?: name, fontSize = 31.sp, color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, bottom = 5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cur != null) {
                Text("${duration(cur.stop - cur.start)} · ${if (notStarted) "Starts" else "Started"} at ${clockTime(cur.start)}" + if (badges.isNotEmpty()) " ·" else "",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            } else {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("No guide information", fontSize = 13.sp, color = LocalTheme.current.colors.muted)
            }
            badges.forEach { b ->
                Text(b, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.border(1.dp, Color(0xB3FFFFFF), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        cur?.desc?.let {
            Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = Color(0xFFEAF0FF), maxLines = descLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp))
        }
        if (cur != null && !notStarted) {
            val f = ((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 9.dp).size(210.dp, 2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x24FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
            }
        }
    }
}
