package org.imdtv.iptvtv.ui.theme

import androidx.compose.ui.graphics.ClipOp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private fun bitmap(density: Density, size: Size, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
    val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), size, draw)
    return img
}

private val EDGE = Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x26FFFFFF)))

/**
 * A dark, smoked-glass panel for text over the video (the playing menu's lists): soft shadow, dark tint, bright edge. The
 * whole panel is drawn into one bitmap once per size, so each frame is a single blit.
 */
fun Modifier.smokedGlass(shape: Shape = RoundedCornerShape(16.dp)): Modifier = drawWithCache {
    val m = 6.dp.toPx()
    val panel = bitmap(this, Size(size.width + 2 * m, size.height + 2 * m)) {
        translate(m, m) {
            val outline = shape.createOutline(this@drawWithCache.size, layoutDirection, this@drawWithCache)
            val path = Path().apply {
                when (outline) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            val px = 1.dp.toPx()
            // Soft shadow below the panel only (clipped out of the panel, so no lines show inside its top edge).
            clipPath(path, ClipOp.Difference) {
                for (i in 1..3) translate(0f, i * 1.5f * px) { drawPath(path, Color.Black.copy(alpha = .06f)) }
            }
            drawPath(path, Color(0x8C0A0F1E))
            drawPath(path, EDGE, style = Stroke(px))
        }
    }
    onDrawBehind { drawImage(panel, topLeft = Offset(-m, -m)) }
}

/**
 * A floating sheet of frosted glass for panels that slide over the screen (the category list): a translucent [tint],
 * a soft light from the top and a bright edge. Unlike [glass] it needs no backdrop and does not track its position,
 * so it stays cheap while it moves.
 */
fun Modifier.glassSheet(tint: Color, shape: Shape = RoundedCornerShape(18.dp)): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val px = 1.dp.toPx()
    val sheen = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x08FFFFFF)))
    onDrawBehind {
        drawOutline(outline, tint)
        drawOutline(outline, sheen)
        drawOutline(outline, EDGE, style = Stroke(px))
    }
}

/**
 * The category panel's backdrop: [base] colour, nearly solid at the left edge and fading to nothing at the right.
 * Nothing else is drawn across it: anything that stops at the panel's right side would show as a hard edge.
 */
fun Modifier.railBackground(base: Color): Modifier = drawWithCache {
    val fade = Brush.horizontalGradient(
        0f to base.copy(alpha = .98f), .74f to base.copy(alpha = .96f), .9f to base.copy(alpha = .55f), 1f to Color.Transparent,
        endX = size.width,
    )
    onDrawBehind { drawRect(fade) }
}

/**
 * The background for the secondary screens (Settings, Search, Recordings): the same blue gradient as the guide, so they feel
 * like one app.
 */
fun Modifier.guideBackground(): Modifier = composed {
    val colors = LocalTheme.current.colors // read here so a change of theme colour redraws it
    drawWithCache {
        // Drawn into one bitmap once per size, like the guide's background, then just blitted.
        val img = org.imdtv.iptvtv.ui.live.backgroundBitmap(colors, this, size, Offset.Zero, size)
        onDrawBehind { drawImage(img) }
    }
}

/**
 * Fades the list out at the bottom edge (so it is clear there is more to scroll to) and, once scrolled, at the top too.
 * The fade is applied to the list's own picture, so whatever is behind the panel shows through naturally.
 */
fun Modifier.fadeListEdges(state: androidx.compose.foundation.lazy.LazyListState, bottom: androidx.compose.ui.unit.Dp = 84.dp, top: androidx.compose.ui.unit.Dp = 30.dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            if (h <= 0f) return@drawWithContent
            val b = ((h - bottom.toPx()) / h).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(0f to Color.Black, b to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            if (state.canScrollBackward) {
                val t = (top.toPx() / h).coerceIn(0f, 1f)
                drawRect(Brush.verticalGradient(0f to Color.Transparent, t to Color.Black, 1f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            }
        }

