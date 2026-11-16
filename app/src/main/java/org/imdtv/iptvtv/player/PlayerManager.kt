package org.imdtv.iptvtv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.imdtv.iptvtv.data.security.Login
import org.imdtv.iptvtv.data.security.withLogin
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.imdtv.iptvtv.data.settings.AudioDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The app's single ExoPlayer. Create and use on the main thread. */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context) {
    private val avOffset = AvOffset()

    /** The mode [player] was built with. A change waits until the player is idle (spec §4.4). */
    var audioDecoder: AudioDecoder = AudioDecoder.SOFTWARE
        private set
    private var wantedDecoder = audioDecoder

    /** Rebuilt when the audio decoder changes: read it at use time, don't keep a reference. */
    var player: ExoPlayer = build(audioDecoder)
        private set

    private fun build(d: AudioDecoder): ExoPlayer = ExoPlayer.Builder(
        context,
        // SOFTWARE (PREFER): the FFmpeg audio renderer handles AC3/E-AC3/DTS that many TV boxes can't decode.
        // HARDWARE (OFF): the platform decoders, or passthrough to the TV/receiver.
        // Video stays on the hardware decoders either way: the FFmpeg artifact also ships a software video renderer.
        object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                super.buildVideoRenderers(context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
                // The same renderer, but able to show the picture a little early or late (the Audio delay control).
                val i = out.indexOfLast { it is androidx.media3.exoplayer.video.MediaCodecVideoRenderer }
                if (i >= 0) out[i] = OffsetVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    enableDecoderFallback, eventHandler, eventListener, avOffset)
            }
        }.setExtensionRendererMode(
            if (d == AudioDecoder.SOFTWARE) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        ),
    ).build()

    fun setAudioDecoder(d: AudioDecoder) {
        wantedDecoder = d
        if (player.playbackState != Player.STATE_IDLE || d == audioDecoder) return
        // Idle with a stream (a failed one waiting to retry): the new player must load it again, or it stays empty.
        val u = url
        if (u != null) { this.url = null; reload(u, userAgent, login, vod, null) } else applyDecoder()
    }

    /** Swaps in a player built for [wantedDecoder], keeping the listener and the attached view. */
    private fun applyDecoder() {
        if (wantedDecoder == audioDecoder) return
        val old = player
        old.removeListener(listener)
        player = build(wantedDecoder).also { it.addListener(listener); applySubtitles(it) }
        audioDecoder = wantedDecoder
        view?.let { PlayerView.switchTargetView(player, null, it) }
        old.release()
        Log.i(TAG, "audio decoder $audioDecoder")
    }

    private var subtitlesOn = false
    private var subtitleLanguage = java.util.Locale.getDefault().language

    /** The Subtitles setting; [language] null = the TV's language. Applies now and to every title played after. */
    fun setSubtitles(on: Boolean, language: String?) {
        subtitlesOn = on
        subtitleLanguage = language ?: java.util.Locale.getDefault().language
        applySubtitles(player)
    }

    /** Movies and series follow the Subtitles setting; live channels keep Media3's own text defaults (spec: live unchanged). */
    private fun applySubtitles(p: ExoPlayer) {
        p.trackSelectionParameters = if (vod) subtitleParams(p.trackSelectionParameters, subtitlesOn, subtitleLanguage)
            else liveTextParams(p.trackSelectionParameters, liveDefaults)
    }
    private val liveDefaults by lazy { TrackSelectionParameters.Builder(context).build() }
    /** The stream loaded now is a movie/episode (else live). */
    private var vod = false

    private val _errors = MutableSharedFlow<PlaybackException>(extraBufferCapacity = 1)
    val errors: SharedFlow<PlaybackException> = _errors

    private val handler = Handler(Looper.getMainLooper())
    private var url: String? = null
        set(v) { field = v; _playingUrl.value = v }
    private val _playingUrl = MutableStateFlow<String?>(null)
    /** The stream loaded now; null once stopped (including while the app is in the background). */
    val playingUrl: StateFlow<String?> = _playingUrl

    private val _formats = MutableStateFlow(StreamFormats.UNKNOWN)
    /** The selected video/audio formats of the stream playing now; all-null after [play] of a new URL and [stop]. */
    val formats: StateFlow<StreamFormats> = _formats

    private fun readFormats() {
        fun selected(type: Int): Format? = player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val v = selected(C.TRACK_TYPE_VIDEO)
        val a = selected(C.TRACK_TYPE_AUDIO)
        val size = player.videoSize.height.takeIf { it > 0 && v != null } // the old stream's size lingers until the new one decodes
        val f = StreamFormats(
            height = size ?: v?.height?.takeIf { it != Format.NO_VALUE },
            frameRate = v?.frameRate?.takeIf { it > 0f },
            audioChannels = a?.channelCount?.takeIf { it != Format.NO_VALUE },
        )
        if (f != _formats.value) Log.i(TAG, "formats $f ${a?.sampleMimeType} ${tail(url)}")
        _formats.value = f
    }
    private val _pictureShowing = MutableStateFlow(false)
    /** True once the window the stream is attached to has drawn a frame of it (false again whenever a window takes over). */
    val pictureShowing: StateFlow<Boolean> = _pictureShowing

    private val _paused = MutableStateFlow(false)
    /** True while playback is paused by the viewer. */
    val paused: StateFlow<Boolean> = _paused
    private var pausedAt = 0L

    private val _tracks = MutableStateFlow(StreamTracks.NONE)
    /** The audio tracks, video qualities and subtitles of the stream playing now. */
    val tracks: StateFlow<StreamTracks> = _tracks

    private val _pictureSize = MutableStateFlow(PictureSize.FIT)
    val pictureSize: StateFlow<PictureSize> = _pictureSize

    private val _audioDelayMs = MutableStateFlow(0)
    /** Audio delay in milliseconds: plus plays the sound later, minus earlier. Kept until the app closes. */
    val audioDelayMs: StateFlow<Int> = _audioDelayMs

    private fun readTracks() {
        val audio = ArrayList<TrackOption>(); val video = ArrayList<TrackOption>(); val text = ArrayList<TrackOption>()
        for ((gi, g) in player.currentTracks.groups.withIndex()) {
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val on = g.isTrackSelected(ti)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> TrackLabels.audio(f.language, f.label, f.sampleMimeType, f.channelCount, audio.size + 1)
                        .let { (name, detail) -> audio += TrackOption(name, detail, on, gi, ti) }
                    C.TRACK_TYPE_VIDEO -> TrackLabels.video(f.width, f.height, f.frameRate, f.bitrate)
                        .let { (name, detail) -> video += TrackOption(name, detail, on, gi, ti) }
                    C.TRACK_TYPE_TEXT -> TrackLabels.text(f.language, f.label, text.size + 1)
                        .let { (name, detail) -> text += TrackOption(name, detail, on, gi, ti) }
                }
            }
        }
        val params = player.trackSelectionParameters
        _tracks.value = StreamTracks(
            audio = audio,
            video = video.sortedByDescending { player.currentTracks.groups[it.group].getTrackFormat(it.track).height },
            text = text,
            autoQuality = params.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO },
            textOff = text.none { it.selected },
        )
    }

    private fun override(o: TrackOption, type: Int) {
        val g = player.currentTracks.groups.getOrNull(o.group) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.track))
            .build()
    }

    fun selectAudio(o: TrackOption) = override(o, C.TRACK_TYPE_AUDIO)

    /** A video quality, or null for automatic. */
    fun selectVideo(o: TrackOption?) {
        if (o == null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        else override(o, C.TRACK_TYPE_VIDEO)
    }

    /** A subtitle track, or null for none. */
    fun selectText(o: TrackOption?) {
        if (o == null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else override(o, C.TRACK_TYPE_TEXT)
    }

    fun setPictureSize(size: PictureSize) {
        _pictureSize.value = size
        view?.let { it.resizeMode = size.mode; applyShape(it) }
    }

    fun setAudioDelayMs(ms: Int) {
        val v = ms.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        _audioDelayMs.value = v
        avOffset.videoLeadUs = v * 1000L
    }

    /** Pause, or play again. After a long pause a live stream that can't be held starts over at the live picture. */
    fun togglePause() {
        if (url == null) return
        val p = player
        if (p.playWhenReady) {
            p.pause()
        } else if (SystemClock.elapsedRealtime() - pausedAt > LONG_PAUSE_MS && !p.isCurrentMediaItemSeekable && !vod) {
            restart()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    /** Milliseconds behind the live picture; 0 when not known or not live. */
    fun behindLiveMs(): Long {
        if (!player.isCurrentMediaItemLive) return 0L
        return player.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /** Jump back to the live picture. */
    fun goLive() {
        if (player.isCurrentMediaItemSeekable) { player.seekToDefaultPosition(); player.play() } else restart()
    }

    /** The same stream, started again from scratch. */
    private fun restart() {
        val u = url ?: return
        val ua = userAgent; val lg = login; val v = vod
        stop()
        play(u, ua, lg, v, guarded = false)
    }

    private var userAgent: String? = null
    private var retries = 0
    private val retry = Runnable { player.prepare() }
    private var view: PlayerView? = null

    private val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "state ${arrayOf("?", "IDLE", "BUFFERING", "READY", "ENDED")[state]} ${tail(url)}")
                if (state == Player.STATE_READY) retries = 0
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)

            override fun onRenderedFirstFrame() { _pictureShowing.value = true }

            override fun onTracksChanged(tracks: Tracks) { readFormats(); readTracks() }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = readTracks()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _paused.value = !playWhenReady
                if (!playWhenReady) pausedAt = SystemClock.elapsedRealtime()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) { readFormats(); view?.let(::applyShape) }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Paused (or slow) for longer than the stream keeps: jump to the live picture rather than retrying the old spot.
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                Log.w(TAG, "error ${error.errorCodeName} ${tail(url)} (retry ${retries + 1}/$MAX_RETRIES)", error)
                // A refusal from the server (503, 403, 404...) is an answer, not a dropped connection: asking again straight away
                // only looks like flooding to the provider, so report it. Real connection drops are still retried.
                val refused = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                if (!refused && retries < MAX_RETRIES) {
                    handler.postDelayed(retry, 1000L shl retries++) // 1s, 2s, 4s
                } else {
                    retries = 0
                    _errors.tryEmit(error)
                }
            }
        }

    init { player.addListener(listener) }

    /** [login] fills the Xtream placeholders in [url] (saved links hold no login); [url] itself stays the key for comparisons. */
    fun play(url: String, userAgent: String?, login: Login? = null, vod: Boolean = false) = play(url, userAgent, login, vod, guarded = true)

    /** Asked before a provider stream starts (a recording may hold the login's only connection); true = not now. It
     *  gets a retry that plays without asking again. */
    var guard: ((retry: () -> Unit) -> Boolean)? = null

    private fun play(url: String, userAgent: String?, login: Login?, vod: Boolean, guarded: Boolean) {
        if (url == this.url && player.playbackState != Player.STATE_IDLE) { player.playWhenReady = true; return }
        if (guarded && guard?.invoke { play(url, userAgent, login, vod, guarded = false) } == true) return
        handler.removeCallbacks(retry)
        retries = 0
        applyDecoder() // a new stream: a pending decoder change takes effect now, before playWhenReady is set on it
        player.playWhenReady = true
        this.vod = vod
        applySubtitles(player) // a new title follows the setting again, not the last title's subtitles-button choice
        this.url = url
        this.userAgent = userAgent
        this.login = login
        _formats.value = StreamFormats.UNKNOWN
        _tracks.value = StreamTracks.NONE
        _paused.value = false
        Log.i(TAG, "play ${tail(url)}")
        // Headers on the link (M3U #EXTVLCOPT or Kodi "url|User-Agent=…") win over the playlist's user agent.
        val req = org.imdtv.iptvtv.data.source.streamRequest(url, userAgent, login)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        req.headers["User-Agent"]?.let { http.setUserAgent(it) }
        (req.headers - "User-Agent").takeIf { it.isNotEmpty() }?.let { http.setDefaultRequestProperties(it) }
        // A stream format with no module in the app (e.g. Smooth Streaming) throws here: report it, don't crash.
        val source = try {
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(MediaItem.fromUri(req.url))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "unsupported stream format ${tail(url)}", e)
            this.url = null
            player.stop(); player.clearMediaItems()
            _errors.tryEmit(PlaybackException("Unsupported stream format", e, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
            return
        }
        player.setMediaSource(source)
        player.prepare()
    }

    /** Network-share login for smb:// recordings (set by the app container). */
    var smbSettings: () -> org.imdtv.iptvtv.recording.SmbSettings = { org.imdtv.iptvtv.recording.SmbSettings("", "", "", "", "") }

    /** A recording (file path or smb://) from [startMs]; no guard: it uses no provider connection. */
    fun playRecording(location: String, startMs: Long) {
        handler.removeCallbacks(retry)
        retries = 0
        applyDecoder()
        player.playWhenReady = true
        this.vod = true
        applySubtitles(player)
        this.url = location
        this.userAgent = null
        this.login = null
        _formats.value = StreamFormats.UNKNOWN
        val smb = location.startsWith("smb://")
        val factory: androidx.media3.datasource.DataSource.Factory =
            if (smb) androidx.media3.datasource.DataSource.Factory { org.imdtv.iptvtv.recording.SmbDataSource(smbSettings) } else DefaultDataSource.Factory(context)
        val uri = if (smb) android.net.Uri.parse(location) else android.net.Uri.fromFile(java.io.File(location))
        player.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)))
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
    }

    private fun reload(u: String, userAgent: String?, login: Login?, vod: Boolean, positionMs: Long?) {
        if (org.imdtv.iptvtv.recording.isRecordingLocation(u)) playRecording(u, positionMs ?: 0)
        else { play(u, userAgent, login, vod); positionMs?.let { player.seekTo(it) } }
    }

    /** Moves the player to [view] without re-preparing, so playback continues uninterrupted. */
    fun attach(view: PlayerView) {
        if (this.view === view) return
        _pictureShowing.value = false // the new window has not drawn the picture yet
        PlayerView.switchTargetView(player, this.view, view)
        this.view?.keepScreenOn = false
        this.view = view
        view.resizeMode = _pictureSize.value.mode
        view.keepScreenOn = player.isPlaying
        applyShape(view)
        // The view works the shape out again from the stream a moment after it takes over, which briefly undoes ours: keep
        // setting it through that moment, so the picture is never shown in the wrong shape.
        for (wait in longArrayOf(60, 150, 300, 500, 800, 1300)) view.postDelayed({ if (this.view === view) applyShape(view) }, wait)
    }

    /**
     * Tells [view] the shape of the picture. A view only learns it when the picture's size changes, so one that takes over
     * a playing stream (corner preview <-> full screen) would otherwise keep a blank shape and stretch the picture to its box.
     */
    private fun applyShape(view: PlayerView) {
        val v = player.videoSize
        if (v.width <= 0 || v.height <= 0) return
        val ratio = _pictureSize.value.ratio ?: pictureRatio(v.width, v.height, v.pixelWidthHeightRatio)
        val frame = view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        frame.setAspectRatio(ratio)
        // The view sets the shape the stream states each time it learns the size; set ours again just after, so ours wins.
        view.post { frame.setAspectRatio(ratio) }
    }

    fun detach(view: PlayerView) {
        if (this.view !== view) return
        view.player = null
        view.keepScreenOn = false
        this.view = null
    }

    /** Stops the TV screensaver (which would also stop playback via onStop) only while video plays. */
    fun onPlayingChanged(isPlaying: Boolean) { view?.keepScreenOn = isPlaying }

    /** The stream playing now, or the one [toForeground] will resume. */
    val currentUrl: String? get() = url ?: resume?.url

    private data class Resume(val url: String, val userAgent: String?, val login: Login?, val positionMs: Long?, val vod: Boolean)
    private var login: Login? = null
    private var resume: Resume? = null

    /** Releases the provider connection (accounts allow only 1–2) when the app leaves the screen. */
    fun toBackground() {
        val u = url ?: return
        val vod = player.isCurrentMediaItemSeekable && player.duration != C.TIME_UNSET
        resume = Resume(u, userAgent, login, if (vod) player.currentPosition else null, this.vod)
        stop()
    }

    /** Live restarts at the live edge; VOD continues where it was. */
    fun toForeground() {
        val r = resume ?: return
        resume = null
        reload(r.url, r.userAgent, r.login, r.vod, r.positionMs)
    }

    fun stop() {
        handler.removeCallbacks(retry)
        retries = 0
        url = null
        _formats.value = StreamFormats.UNKNOWN
        _tracks.value = StreamTracks.NONE
        _paused.value = false
        player.stop()
        player.clearMediaItems()
        applyDecoder()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val LONG_PAUSE_MS = 20_000L
        const val MAX_DELAY_MS = 300
        const val TAG = "IptvPlayer"
        /** Last path segment only: Xtream URLs carry the username and password. */
        fun tail(url: String?) = url?.substringBefore('|')?.substringAfterLast('/') // headers may hold tokens too
    }
}

/**
 * Subtitle track selection for the Subtitles setting: on → only a track in [language]; off → none. Either way a track
 * the file flags default is not picked by itself (it may be any language), forced tracks still are, and the last
 * title's subtitles-button choice is dropped.
 */
fun subtitleParams(base: TrackSelectionParameters, on: Boolean, language: String): TrackSelectionParameters =
    base.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .setSelectUndeterminedTextLanguage(false)
        .setPreferredTextLanguage(if (on) language else null)
        .build()

/** Live: the text selection a fresh player has ([defaults]), whatever the Subtitles setting; the last title's choice dropped. */
fun liveTextParams(base: TrackSelectionParameters, defaults: TrackSelectionParameters): TrackSelectionParameters =
    base.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setPreferredTextLanguages(*defaults.preferredTextLanguages.toTypedArray())
        .setPreferredTextRoleFlags(defaults.preferredTextRoleFlags)
        // A stream marking subtitles "default" doesn't switch them on (user, 2026-11-14); forced ones still show.
        .setIgnoredTextSelectionFlags(defaults.ignoredTextSelectionFlags or C.SELECTION_FLAG_DEFAULT)
        .setSelectUndeterminedTextLanguage(defaults.selectUndeterminedTextLanguage)
        .build()
