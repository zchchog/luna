package tv.luna.player.player

import kotlin.math.roundToInt

/** What the playing stream is, as far as the player knows; null = not known (yet). */
data class StreamFormats(val height: Int?, val frameRate: Float?, val audioChannels: Int?) {
    companion object { val UNKNOWN = StreamFormats(null, null, null) }
}

/** Overlay badges (spec §4.2): resolution, frame rate, audio layout — unknowns left out. */
fun resolutionLabel(height: Int): String = when { height < 720 -> "SD"; height < 1080 -> "HD"; height < 2160 -> "FULL HD"; else -> "4K" }

fun badges(f: StreamFormats): List<String> = listOfNotNull(
    f.height?.let(::resolutionLabel),
    f.frameRate?.let { "${it.roundToInt()} FPS" },
    when (f.audioChannels) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
)
