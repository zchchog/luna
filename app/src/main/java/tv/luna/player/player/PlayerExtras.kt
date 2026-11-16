package tv.luna.player.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.AspectRatioFrameLayout
import java.nio.ByteBuffer

/** One choice in the Audio, Quality or Subtitles list. [group] and [track] say where it is in the stream. */
data class TrackOption(val label: String, val detail: String, val selected: Boolean, val group: Int, val track: Int)

/** What the stream playing now offers. */
data class StreamTracks(
    val audio: List<TrackOption>,
    val video: List<TrackOption>,
    val text: List<TrackOption>,
    /** True when the player picks the video quality itself. */
    val autoQuality: Boolean,
    /** True when no subtitles are showing. */
    val textOff: Boolean,
) {
    companion object { val NONE = StreamTracks(emptyList(), emptyList(), emptyList(), true, true) }
}

/** How the picture fills the screen. */
enum class PictureSize(val label: String, val detail: String, val mode: Int, val ratio: Float? = null) {
    FIT("Automatic", "Whole picture; standard-definition channels shown widescreen", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    WIDE("16:9", "Force a widescreen shape", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
    CLASSIC("4:3", "Force the old square-ish TV shape", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
    ZOOM("Zoom", "Fills the screen, edges cropped", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", "Fills the screen, picture distorted", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/**
 * The shape to show a picture in. Many standard-definition channels are widescreen but stored as 704x576 or 720x576 with no
 * note saying so, so they would be shown nearly square; those are shown 16:9. Anything else uses what the stream says.
 */
fun pictureRatio(width: Int, height: Int, pixelRatio: Float): Float {
    if (width <= 0 || height <= 0) return 0f
    val stored = width * pixelRatio / height
    val unmarked = kotlin.math.abs(pixelRatio - 1f) < 0.01f
    val sdStorage = height in setOf(576, 480) && width in setOf(720, 704, 544, 528, 480, 352)
    return if (unmarked && sdStorage) 16f / 9f else stored
}

/** The audio-delay setting as the video renderer reads it (from the playback thread). */
class AvOffset {
    /** Microseconds the picture is shown EARLIER than the sound's clock says: the same as playing the sound later. */
    @Volatile var videoLeadUs: Long = 0
}

/**
 * The normal video renderer, except it can show each frame a little early or late. That is how "audio delay" works:
 * the sound's clock keeps time for everything, so moving the picture against it moves it against the sound.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION")
class OffsetVideoRenderer(
    context: Context, selector: MediaCodecSelector, joiningMs: Long, fallback: Boolean,
    handler: Handler?, listener: VideoRendererEventListener?, private val offset: AvOffset,
) : MediaCodecVideoRenderer(context, selector, joiningMs, fallback, handler, listener, 50) {

    override fun processOutputBuffer(
        positionUs: Long, elapsedRealtimeUs: Long, codec: MediaCodecAdapter?, buffer: ByteBuffer?, bufferIndex: Int,
        bufferFlags: Int, sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean, format: Format,
    ): Boolean = super.processOutputBuffer(
        positionUs + offset.videoLeadUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, sampleCount,
        bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format,
    )
}
