package com.miniplayer.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.miniplayer.app.MainActivity
import com.miniplayer.app.core.model.StemMixConfig

/**
 * Process-scoped holder ensuring a single main-thread [ExoPlayer] instance is shared cleanly
 * between [PlaybackService] (for lockscreen, headset/Bluetooth controls, and foreground media
 * notification promotion) and [PlayerController] / [PracticeLoopEngine] (for sample-accurate
 * `PlayerMessage` A-B loop scheduling, adaptive GOP `SeekParameters`, and `TextureView` video
 * attachment without cross-IPC reflection).
 */
@OptIn(UnstableApi::class)
internal object SharedExoPlayerHolder {
    @Volatile
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val realTimeSonicProcessor = RealTimeSonicAudioProcessor()

    fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        realTimeSonicProcessor.setTargetSpeedAndPitch(speed, pitch)
    }

    fun updateDspParameters(preserveFormants: Boolean, stemMix: StemMixConfig) {
        realTimeSonicProcessor.setPreserveFormants(preserveFormants)
        realTimeSonicProcessor.setStemMix(stemMix)
    }

    fun updateStemMaskContext(
        maskGrid: StemTrackMaskGrid?,
        positionMsSupplier: (() -> Long)? = null,
    ) {
        realTimeSonicProcessor.setStemMaskContext(maskGrid, positionMsSupplier)
    }

    fun getOrCreate(context: Context): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SharedExoPlayerHolder.getOrCreate must be invoked on the main thread"
        }
        synchronized(this) {
            player?.let { return it }
            val appContext = context.applicationContext
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val renderersFactory = object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    // Force 16-bit PCM + always-active RealTimeSonicAudioProcessor so rapid speed/pitch
                    // changes never reconfigure AudioProcessingPipeline or tear down AudioTrack.
                    return DefaultAudioSink.Builder(appContext)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(false)
                        .setAudioProcessorChain(RealTimeAudioProcessorChain(realTimeSonicProcessor))
                        .build()
                }
            }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableAudioFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)
            val created = ExoPlayer.Builder(appContext, renderersFactory)
                .setLooper(Looper.getMainLooper())
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
            player = created
            return created
        }
    }

    fun runOnApplicationLooper(context: Context, block: (ExoPlayer) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(getOrCreate(context))
        } else {
            mainHandler.post { block(getOrCreate(context)) }
        }
    }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        setMediaNotificationProvider(notificationProvider)

        val exoPlayer = SharedExoPlayerHolder.getOrCreate(this)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
