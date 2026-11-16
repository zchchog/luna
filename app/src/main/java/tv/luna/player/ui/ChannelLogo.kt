package tv.luna.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.logos.shownLogos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tv.luna.player.data.settings.LogoKind

/** The channel's logo per the Channel logos setting; the number tile while loading, on error and when there is none. */
@Composable
fun ChannelLogo(ch: ChannelEntity, source: List<LogoKind>, modifier: Modifier = Modifier, fontSize: TextUnit = 16.sp) {
    val tile = @Composable {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text((ch.customNumber ?: ch.number)?.toString() ?: "TV", fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
    val urls = shownLogos(ch, source)
    var tried by remember(urls) { mutableIntStateOf(0) } // failed loads so far: e.g. tv-logos unreachable → playlist logo
    val url = urls.getOrNull(tried)
    if (url == null) Box(modifier) { tile() }
    else SubcomposeAsyncImage(url, null, modifier, contentScale = ContentScale.Fit, loading = { tile() }, error = { tile() },
        onError = { if (tried < urls.size) tried++ })
}
