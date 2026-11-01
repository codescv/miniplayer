package com.miniplayer.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.CasualRepeatMode
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.PlayContext
import com.miniplayer.app.core.model.PracticeBookmark
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.StemMixConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackSnapshot(
    val currentItem: MediaItem? = null,
    val queue: List<MediaItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: CasualRepeatMode = CasualRepeatMode.REPEAT_ALL,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val sleepTimerRemainingMs: Long? = null,
    val pauseAtEndOfCurrentTrack: Boolean = false,
) {
    val videoAspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) {
            (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.4f, 2.6f)
        } else {
            16f / 9f
        }
}

/**
 * Coordinates Media3 [ExoPlayer] + [PlaybackService] foreground session, [PracticeLoopEngine],
 * [CountInMetronome], [WaveformExtractor], sleep timer, and screen-off audio-only video playback.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val mainScope: CoroutineScope,
    private val loadPracticeState: suspend (Long) -> PracticeState,
    private val persistPracticeState: (PracticeState) -> Unit,
    private val persistPlaybackPosition: (Long, Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val exoPlayer: ExoPlayer = SharedExoPlayerHolder.getOrCreate(appContext)
    private val appHandler = Handler(exoPlayer.applicationLooper)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var activeBoundaryMessage: PlayerMessage? = null

    private var activeTextureView: TextureView? = null
    private var isUiInForeground: Boolean = true
    private var positionTickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var trackTransitionJob: Job? = null
    private var suppressAutoTransitionLoad: Boolean = false
    private var internalPauseDepth: Int = 0
    private var internalSeekDepth: Int = 0
    private var lastAppliedSpeed: Float = 1.0f
    private var lastAppliedPitch: Float = 1.0f
    private var consecutiveErrorRecoveryCount: Int = 0

    val metronome = CountInMetronome(mainScope)
    val waveformExtractor = WaveformExtractor(appContext)
    val stemModelManager = StemModelManager(appContext)
    val stemSeparationEngine = StemSeparationEngine(appContext, stemModelManager)
    val stemAnalysisState: StateFlow<StemAnalysisState> = stemSeparationEngine.stemAnalysisState
    private var stemJob: Job? = null

    private val _hapticTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val hapticTicks: SharedFlow<Unit> = _hapticTicks.asSharedFlow()

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val _waveformBars = MutableStateFlow<List<Float>>(emptyList())
    val waveformBars: StateFlow<List<Float>> = _waveformBars.asStateFlow()
    private var waveformJob: Job? = null

    private val _detectedBpm = MutableStateFlow<Int?>(null)
    val detectedBpm: StateFlow<Int?> = _detectedBpm.asStateFlow()

    private val _detectedBeatGrid = MutableStateFlow<BeatGrid?>(null)
    val detectedBeatGrid: StateFlow<BeatGrid?> = _detectedBeatGrid.asStateFlow()

    private val _isDetectingBpm = MutableStateFlow(false)
    val isDetectingBpm: StateFlow<Boolean> = _isDetectingBpm.asStateFlow()
    private var bpmJob: Job? = null
    private var forceApplyPendingBpmItemId: Long? = null

    /**
     * A `.stems`-derived grid that disagrees with a *confident* current grid (§3.9). The UI shows a
     * non-blocking "检测到更准的节拍网格 — 应用?" chip; [applyPendingStemsBeatGrid] accepts it.
     */
    private val _pendingStemsBeatGrid = MutableStateFlow<BeatGrid?>(null)
    val pendingStemsBeatGrid: StateFlow<BeatGrid?> = _pendingStemsBeatGrid.asStateFlow()
    private var stemsRegridJob: Job? = null

    private val loopPort = object : LoopPlayerPort {
        override val currentPositionMs: Long
            get() = if (Looper.myLooper() == exoPlayer.applicationLooper) {
                exoPlayer.currentPosition.coerceAtLeast(0L)
            } else {
                _snapshot.value.positionMs
            }

        override val durationMs: Long
            get() {
                val playerDur = if (Looper.myLooper() == exoPlayer.applicationLooper) {
                    exoPlayer.duration.takeIf { it > 0L }
                } else {
                    null
                }
                return playerDur ?: _snapshot.value.durationMs.coerceAtLeast(0L)
            }

        override val isPlaying: Boolean
            get() = if (Looper.myLooper() == exoPlayer.applicationLooper) {
                exoPlayer.isPlaying
            } else {
                _snapshot.value.isPlaying
            }

        override fun scheduleBoundaryMessage(positionMs: Long, onReached: () -> Unit) {
            runOnPlayerThread {
                activeBoundaryMessage?.cancel()
                activeBoundaryMessage = null
                if (exoPlayer.mediaItemCount > 0) {
                    activeBoundaryMessage = exoPlayer.createMessage { _, _ ->
                        onReached()
                    }
                        .setLooper(exoPlayer.applicationLooper)
                        .setPosition(positionMs.coerceAtLeast(0L))
                        .setDeleteAfterDelivery(false)
                        .send()
                }
                syncEffectiveRepeatModeOnPlayerThread()
            }
        }

        override fun cancelBoundaryMessage() {
            runOnPlayerThread {
                activeBoundaryMessage?.cancel()
                activeBoundaryMessage = null
                syncEffectiveRepeatModeOnPlayerThread()
            }
        }

        override fun applyPlaybackParameters(speed: Float, pitch: Float) {
            runOnPlayerThread {
                val decision = evaluatePlaybackParametersUpdate(
                    requestedSpeed = speed,
                    requestedPitch = pitch,
                    previousSpeed = lastAppliedSpeed,
                    previousPitch = lastAppliedPitch,
                )
                SharedExoPlayerHolder.updateSpeedAndPitch(decision.safeSpeed, decision.safePitch)
                if (decision.shouldUpdateExoPlaybackParameters) {
                    exoPlayer.playbackParameters = PlaybackParameters(
                        decision.exoPlaybackSpeed,
                        decision.exoPlaybackPitch,
                    )
                }
                lastAppliedSpeed = decision.safeSpeed
                lastAppliedPitch = decision.safePitch
                syncEffectiveRepeatModeOnPlayerThread()
            }
        }

        override fun applyStemMix(stemMix: StemMixConfig) {
            runOnPlayerThread {
                SharedExoPlayerHolder.updateStemMix(stemMix)
            }
        }

        override fun configureExactSeek() {
            runOnPlayerThread {
                exoPlayer.setSeekParameters(SeekParameters.EXACT)
            }
        }

        override fun configureFastVideoKeyframeSeek() {
            runOnPlayerThread {
                exoPlayer.setSeekParameters(FAST_VIDEO_KEYFRAME_SEEK)
            }
        }

        override fun seekToPosition(positionMs: Long) {
            runOnPlayerThread {
                performInternalSeek {
                    exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
                }
                updateSnapshotFromPlayer()
            }
        }

        override fun play() {
            runOnPlayerThread {
                ensureMediaServiceBound()
                exoPlayer.play()
                updateSnapshotFromPlayer()
            }
        }

        override fun pause() {
            runOnPlayerThread {
                performInternalPause {
                    exoPlayer.pause()
                }
                updateSnapshotFromPlayer()
            }
        }

        override fun skipToPreviousItem(): Boolean {
            return if (Looper.myLooper() == exoPlayer.applicationLooper && exoPlayer.hasPreviousMediaItem()) {
                performInternalSeek {
                    exoPlayer.seekToPreviousMediaItem()
                }
                true
            } else {
                false
            }
        }
    }

    val loopEngine = PracticeLoopEngine(
        port = loopPort,
        metronome = metronome,
        onStatePersisted = persistPracticeState,
        onHapticEvent = { _hapticTicks.tryEmit(Unit) },
        onStemSliderActivated = { maybeAutoAnalyzeCurrentTrackStems() },
    )

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateSnapshotFromPlayer()
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
                saveCurrentTrackPosition()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && internalPauseDepth == 0 && !suppressAutoTransitionLoad) {
                loopEngine.onUserPaused()
            }
            updateSnapshotFromPlayer()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                internalPauseDepth == 0 &&
                !suppressAutoTransitionLoad
            ) {
                loopEngine.onUserPaused()
            }
            updateSnapshotFromPlayer()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                internalSeekDepth == 0 &&
                !suppressAutoTransitionLoad &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                loopEngine.onExternalSeek()
                updateSnapshotFromPlayer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveErrorRecoveryCount = 0
                loopEngine.onVideoFrameOrReadyRendered()
            } else if (playbackState == Player.STATE_ENDED) {
                saveCurrentTrackPosition()
            }
            updateSnapshotFromPlayer()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (exoPlayer.mediaItemCount > 0 && consecutiveErrorRecoveryCount < MAX_AUTO_RECOVERY_ATTEMPTS) {
                consecutiveErrorRecoveryCount++
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                exoPlayer.prepare()
                performInternalSeek {
                    exoPlayer.seekTo(pos)
                }
                exoPlayer.play()
            }
            updateSnapshotFromPlayer()
        }

        override fun onRenderedFirstFrame() {
            loopEngine.onVideoFrameOrReadyRendered()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _snapshot.value = _snapshot.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
            )
        }

        override fun onMediaItemTransition(mediaItem: ExoMediaItem?, reason: Int) {
            if (_snapshot.value.pauseAtEndOfCurrentTrack &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                performInternalPause {
                    exoPlayer.pause()
                }
                _snapshot.value = _snapshot.value.copy(pauseAtEndOfCurrentTrack = false)
            }
            if (!suppressAutoTransitionLoad) {
                saveCurrentTrackPosition()
                metronome.cancel()
                activeBoundaryMessage?.cancel()
                activeBoundaryMessage = null
                trackTransitionJob?.cancel()
                trackTransitionJob = null
            }
            val newIndex = exoPlayer.currentMediaItemIndex
            val queue = _snapshot.value.queue
            val nextItem = queue.getOrNull(newIndex)
            val nextPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            _snapshot.value = _snapshot.value.copy(
                currentItem = nextItem,
                currentQueueIndex = newIndex,
                positionMs = nextPosMs,
                durationMs = nextItem?.durationMs ?: 0L,
            )
            if (!suppressAutoTransitionLoad) {
                if (nextItem != null) {
                    if (nextItem.itemId > 0L) {
                        persistPlaybackPosition(nextItem.itemId, nextPosMs)
                    }
                    loadWaveformFor(nextItem)
                    val targetItemId = nextItem.itemId
                    trackTransitionJob = mainScope.launch {
                        val savedState = loadPracticeState(targetItemId)
                        runOnPlayerThread {
                            if (_snapshot.value.currentItem?.itemId != targetItemId) return@runOnPlayerThread
                            loopEngine.bindTrack(
                                state = savedState,
                                isVideo = nextItem.isVideo,
                                activatePracticeMode = loopEngine.isPracticeModeActive.value,
                                jumpBookmark = null,
                                autoTriggerInitialLoop = loopEngine.isPracticeModeActive.value && exoPlayer.playWhenReady,
                            )
                            val cachedGrid = _detectedBeatGrid.value
                            val shouldForce = forceApplyPendingBpmItemId == targetItemId
                            if (shouldForce && cachedGrid != null) {
                                forceApplyPendingBpmItemId = null
                            }
                            if (cachedGrid != null &&
                                loopEngine.practiceState.value.itemId == targetItemId
                            ) {
                                loopEngine.applyAutoDetectedBeatGrid(cachedGrid, force = shouldForce)
                            }
                            syncEffectiveRepeatModeOnPlayerThread()
                        }
                    }
                } else {
                    bpmJob?.cancel()
                    bpmJob = null
                    stemJob?.cancel()
                    stemJob = null
                    _detectedBpm.value = null
                    _detectedBeatGrid.value = null
                    _isDetectingBpm.value = false
                    forceApplyPendingBpmItemId = null
                    stemsRegridJob?.cancel()
                    stemsRegridJob = null
                    _pendingStemsBeatGrid.value = null
                    SharedExoPlayerHolder.updateSeparatedStemsContext(null, null)
                    mainScope.launch { stemSeparationEngine.onTrackBound(null) }
                    loopEngine.bindTrack(
                        state = PracticeState(itemId = -1L),
                        isVideo = false,
                        activatePracticeMode = false,
                    )
                    syncEffectiveRepeatModeOnPlayerThread()
                }
            }
        }
    }

    init {
        runOnPlayerThread {
            lastAppliedSpeed = exoPlayer.playbackParameters.speed
            lastAppliedPitch = 1.0f
            SharedExoPlayerHolder.updateSpeedAndPitch(lastAppliedSpeed, 1.0f)
            exoPlayer.addListener(playerListener)
            exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
            ensureMediaServiceBound()
        }
    }

    fun ensureMediaServiceBound() {
        if (controllerFuture != null) return
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        mediaController = future.get()
                    } catch (_: Throwable) {
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } catch (_: Throwable) {
        }
    }

    suspend fun playQueue(
        items: List<MediaItem>,
        startIndex: Int,
        context: PlayContext,
        targetBookmark: PracticeBookmark? = null,
    ) {
        if (items.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, items.lastIndex)
        val targetItem = items[safeIdx]
        val savedPracticeState = loadPracticeState(targetItem.itemId)
        val activatePractice = PracticeMath.shouldAutoActivatePracticeMode(context)

        saveCurrentTrackPosition()
        trackTransitionJob?.cancel()
        trackTransitionJob = null
        metronome.cancel()

        runOnPlayerThread {
            consecutiveErrorRecoveryCount = 0
            ensureMediaServiceBound()
            suppressAutoTransitionLoad = true
            try {
                activeBoundaryMessage?.cancel()
                activeBoundaryMessage = null

                val exoItems = items.map { it.toExoMediaItem() }
                val hasActiveSavedLoop = activatePractice &&
                    savedPracticeState.loopEnabled &&
                    savedPracticeState.activeAMs != null &&
                    savedPracticeState.activeBMs != null &&
                    savedPracticeState.activeBMs > savedPracticeState.activeAMs
                val rawLoopStartMs = when {
                    targetBookmark != null -> targetBookmark.aMs
                    hasActiveSavedLoop -> savedPracticeState.activeAMs!!
                    else -> null
                }
                val initialStartPosMs = when {
                    rawLoopStartMs != null -> if (savedPracticeState.preRollEnabled) {
                        (rawLoopStartMs - PracticeLoopEngine.PRE_ROLL_LEAD_MS).coerceAtLeast(0L)
                    } else {
                        rawLoopStartMs
                    }
                    targetItem.isVideo && targetItem.lastPositionMs in 2_000L..(targetItem.durationMs - 3_000L).coerceAtLeast(2_000L) ->
                        targetItem.lastPositionMs
                    else -> 0L
                }

                _snapshot.value = _snapshot.value.copy(
                    currentItem = targetItem,
                    queue = items,
                    currentQueueIndex = safeIdx,
                    positionMs = initialStartPosMs,
                    durationMs = targetItem.durationMs,
                    videoWidth = targetItem.width,
                    videoHeight = targetItem.height,
                )

                performInternalSeek {
                    exoPlayer.setMediaItems(exoItems, safeIdx, initialStartPosMs)
                }
                exoPlayer.prepare()

                loopEngine.bindTrack(
                    state = savedPracticeState,
                    isVideo = targetItem.isVideo,
                    activatePracticeMode = activatePractice,
                    jumpBookmark = targetBookmark,
                    autoTriggerInitialLoop = true,
                )
                loadWaveformFor(targetItem)
                syncEffectiveRepeatModeOnPlayerThread()

                if (!metronome.isRunning) {
                    exoPlayer.play()
                }
            } finally {
                suppressAutoTransitionLoad = false
            }
        }
    }

    fun refreshMetadataInQueue(allUpdatedItems: Map<Long, MediaItem>) {
        val currentQueue = _snapshot.value.queue
        if (currentQueue.isEmpty()) return
        val updatedQueue = currentQueue.map { allUpdatedItems[it.itemId] ?: it }
        val updatedCurrent = _snapshot.value.currentItem?.let { allUpdatedItems[it.itemId] ?: it }
        _snapshot.value = _snapshot.value.copy(
            queue = updatedQueue,
            currentItem = updatedCurrent,
        )
    }

    fun togglePlayPause() {
        runOnPlayerThread {
            if (metronome.isRunning) {
                loopEngine.onUserPaused()
                performInternalPause {
                    exoPlayer.pause()
                }
            } else if (exoPlayer.isPlaying) {
                loopEngine.onUserPaused()
                performInternalPause {
                    exoPlayer.pause()
                }
            } else {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    performInternalSeek {
                        exoPlayer.seekTo(0L)
                    }
                }
                ensureMediaServiceBound()
                exoPlayer.play()
            }
            updateSnapshotFromPlayer()
        }
    }

    fun pause() {
        runOnPlayerThread {
            loopEngine.onUserPaused()
            performInternalPause {
                exoPlayer.pause()
            }
            updateSnapshotFromPlayer()
        }
    }

    fun skipToNext() {
        runOnPlayerThread {
            metronome.cancel()
            if (exoPlayer.hasNextMediaItem()) {
                performInternalSeek {
                    exoPlayer.seekToNextMediaItem()
                }
                exoPlayer.play()
            } else if (_snapshot.value.queue.isNotEmpty()) {
                performInternalSeek {
                    exoPlayer.seekTo(0, 0L)
                }
                exoPlayer.play()
            }
        }
    }

    fun skipToPreviousOrRestartA() {
        runOnPlayerThread {
            loopEngine.handlePreviousButtonTap()
        }
    }

    fun jumpToQueueIndex(index: Int) {
        val queue = _snapshot.value.queue
        if (index !in queue.indices) return
        runOnPlayerThread {
            metronome.cancel()
            performInternalSeek {
                exoPlayer.seekTo(index, 0L)
            }
            exoPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        runOnPlayerThread {
            loopEngine.onUserManualSeek(positionMs)
        }
    }

    fun seekByDelta(deltaMs: Long) {
        runOnPlayerThread {
            val dur = exoPlayer.duration.takeIf { it > 0L } ?: _snapshot.value.durationMs
            val next = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, dur.coerceAtLeast(0L))
            loopEngine.onUserManualSeek(next)
        }
    }

    fun toggleShuffle() {
        runOnPlayerThread {
            val next = !exoPlayer.shuffleModeEnabled
            exoPlayer.shuffleModeEnabled = next
            _snapshot.value = _snapshot.value.copy(shuffleEnabled = next)
        }
    }

    fun cycleCasualRepeatMode() {
        runOnPlayerThread {
            val next = when (_snapshot.value.repeatMode) {
                CasualRepeatMode.REPEAT_ALL -> CasualRepeatMode.REPEAT_ONE
                CasualRepeatMode.REPEAT_ONE -> CasualRepeatMode.PLAY_ONCE
                CasualRepeatMode.PLAY_ONCE -> CasualRepeatMode.REPEAT_ALL
            }
            _snapshot.value = _snapshot.value.copy(repeatMode = next)
            syncEffectiveRepeatModeOnPlayerThread()
        }
    }

    fun togglePracticeMode() {
        runOnPlayerThread {
            val next = !loopEngine.isPracticeModeActive.value
            loopEngine.setPracticeModeActive(next)
            syncEffectiveRepeatModeOnPlayerThread()
        }
    }

    /**
     * Attaches a [TextureView] for video rendering while the UI is visible.
     */
    fun attachVideoTextureView(textureView: TextureView) {
        runOnPlayerThread {
            activeTextureView = textureView
            if (isUiInForeground) {
                exoPlayer.setVideoTextureView(textureView)
            }
        }
    }

    fun detachVideoTextureView(textureView: TextureView) {
        runOnPlayerThread {
            if (activeTextureView === textureView) {
                activeTextureView = null
            }
            exoPlayer.clearVideoTextureView(textureView)
        }
    }

    /**
     * Screen-off / background audio continuation (§5.3 #3 & ARCHITECTURE.md §5.1):
     * Unbinds the video surface WITHOUT pausing ExoPlayer so audio continues seamlessly in the
     * foreground `MediaSessionService`.
     */
    fun onUiStarted() {
        runOnPlayerThread {
            isUiInForeground = true
            activeTextureView?.let { exoPlayer.setVideoTextureView(it) }
        }
    }

    fun onUiStopped() {
        runOnPlayerThread {
            isUiInForeground = false
            activeTextureView?.let { exoPlayer.clearVideoTextureView(it) }
            saveCurrentTrackPosition()
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes == null || minutes <= 0) {
            _snapshot.value = _snapshot.value.copy(
                sleepTimerRemainingMs = null,
                pauseAtEndOfCurrentTrack = false,
            )
            return
        }
        val totalMs = minutes * 60_000L
        _snapshot.value = _snapshot.value.copy(
            sleepTimerRemainingMs = totalMs,
            pauseAtEndOfCurrentTrack = false,
        )
        sleepTimerJob = mainScope.launch {
            var remaining = totalMs
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining = (remaining - 1_000L).coerceAtLeast(0L)
                _snapshot.value = _snapshot.value.copy(sleepTimerRemainingMs = remaining)
            }
            if (isActive) {
                _snapshot.value = _snapshot.value.copy(sleepTimerRemainingMs = null)
                pause()
            }
        }
    }

    fun setSleepAtEndOfCurrentTrack(enabled: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _snapshot.value = _snapshot.value.copy(
            sleepTimerRemainingMs = null,
            pauseAtEndOfCurrentTrack = enabled,
        )
    }

    /**
     * "重新检测": ALWAYS runs a fresh analysis (`ignoreCache = true`, which also picks up a `.stems`
     * file that appeared since the last run) and force-applies the result, even over a MANUAL grid.
     * Never short-circuits on a previously detected grid.
     */
    fun autoDetectBpm() {
        runOnPlayerThread {
            val item = _snapshot.value.currentItem ?: return@runOnPlayerThread
            stemsRegridJob?.cancel()
            stemsRegridJob = null
            startBpmDetectionFor(item, forceApply = true, ignoreCache = true)
        }
    }

    /** Moves Beat 1 by whole beats (-1 / +1 / +2 half-bar flip); marks the grid MANUAL. */
    fun shiftDownbeatByBeats(deltaBeats: Int) {
        runOnPlayerThread {
            loopEngine.shiftDownbeatByBeats(deltaBeats)
        }
    }

    /** Fine-nudges Beat 1 by [deltaMs] (±10 ms); marks the grid MANUAL. */
    fun nudgeDownbeatMs(deltaMs: Long) {
        runOnPlayerThread {
            loopEngine.nudgeDownbeatMs(deltaMs)
        }
    }

    /** Accepts the `.stems` grid offered via [pendingStemsBeatGrid]. */
    fun applyPendingStemsBeatGrid() {
        runOnPlayerThread {
            val grid = _pendingStemsBeatGrid.value ?: return@runOnPlayerThread
            _pendingStemsBeatGrid.value = null
            if (loopEngine.practiceState.value.itemId != _snapshot.value.currentItem?.itemId) return@runOnPlayerThread
            loopEngine.applyAutoDetectedBeatGrid(grid, force = true)
            _detectedBeatGrid.value = grid
            _detectedBpm.value = grid.bpm
            _hapticTicks.tryEmit(Unit)
        }
    }

    fun dismissPendingStemsBeatGrid() {
        _pendingStemsBeatGrid.value = null
    }

    /**
     * §3.9: `.stems` just became available for [item]. Unless the grid is MANUAL, re-analyse from the
     * stems (`ignoreCache = true`; the extractor prefers `.stems` and returns `AUTO_STEMS`) and either
     * replace silently, replace + re-snap, or offer the new grid to the user.
     */
    private fun refreshBeatGridFromStems(item: MediaItem) {
        if (loopEngine.practiceState.value.beatGridSource == BeatGridSource.MANUAL) return
        stemsRegridJob?.cancel()
        stemsRegridJob = mainScope.launch {
            val grid = runCatching { waveformExtractor.loadOrDetectBeatGrid(item, ignoreCache = true) }
                .getOrNull() ?: return@launch
            runOnPlayerThread {
                if (_snapshot.value.currentItem?.itemId != item.itemId) return@runOnPlayerThread
                val state = loopEngine.practiceState.value
                if (state.itemId != item.itemId) return@runOnPlayerThread
                val oldConfidence = _detectedBeatGrid.value?.confidence
                when (PracticeLoopEngine.decideStemsRegrid(state, oldConfidence, grid)) {
                    StemsRegridDecision.KEEP_MANUAL -> Unit
                    StemsRegridDecision.REPLACE_SILENTLY -> {
                        loopEngine.applyAutoDetectedBeatGrid(grid, force = false, reSnapLoops = false)
                        _detectedBeatGrid.value = grid
                        _detectedBpm.value = grid.bpm
                    }
                    StemsRegridDecision.REPLACE_AND_RESNAP -> {
                        loopEngine.applyAutoDetectedBeatGrid(grid, force = false)
                        _detectedBeatGrid.value = grid
                        _detectedBpm.value = grid.bpm
                        _hapticTicks.tryEmit(Unit)
                    }
                    StemsRegridDecision.OFFER_TO_USER -> _pendingStemsBeatGrid.value = grid
                }
            }
        }
    }

    fun downloadStemModelAndAnalyzeCurrentTrack() {
        val current = _snapshot.value.currentItem
        val autoSeparate = !loopEngine.practiceState.value.stemMix.isUnity && current != null
        stemJob?.cancel()
        stemJob = mainScope.launch {
            val separated = stemSeparationEngine.downloadModelOnlyOrSeparate(
                item = current,
                autoSeparateAfterDownload = autoSeparate,
            )
            runOnPlayerThread {
                val activeId = _snapshot.value.currentItem?.itemId
                if (separated != null && activeId == separated.itemId) {
                    SharedExoPlayerHolder.updateSeparatedStemsContext(separated) { _snapshot.value.positionMs }
                    _snapshot.value.currentItem?.let(::refreshBeatGridFromStems)
                }
                _hapticTicks.tryEmit(Unit)
            }
        }
    }

    fun analyzeCurrentTrackStems() {
        val current = _snapshot.value.currentItem ?: return
        stemJob?.cancel()
        stemJob = mainScope.launch {
            val separated = stemSeparationEngine.analyzeTrackStems(current)
            runOnPlayerThread {
                val activeId = _snapshot.value.currentItem?.itemId
                if (separated != null && activeId == separated.itemId) {
                    SharedExoPlayerHolder.updateSeparatedStemsContext(separated) { _snapshot.value.positionMs }
                    refreshBeatGridFromStems(current)
                    _hapticTicks.tryEmit(Unit)
                }
            }
        }
    }

    fun cancelStemOperation() {
        val activeJob = stemJob
        stemJob = null
        activeJob?.cancel()
        stemSeparationEngine.onOperationCancelled(_snapshot.value.currentItem)
    }

    private fun maybeAutoAnalyzeCurrentTrackStems() {
        val current = _snapshot.value.currentItem ?: return
        if (!stemModelManager.isModelReady()) return
        if (stemJob?.isActive == true) return
        val state = stemSeparationEngine.stemAnalysisState.value
        if (state is StemAnalysisState.Ready && state.itemId == current.itemId) return
        if (state is StemAnalysisState.AnalyzingTrack && state.itemId == current.itemId) return
        analyzeCurrentTrackStems()
    }

    fun releaseUiResources() {
        positionTickerJob?.cancel()
        sleepTimerJob?.cancel()
        waveformJob?.cancel()
        bpmJob?.cancel()
        stemsRegridJob?.cancel()
        stemJob?.cancel()
        trackTransitionJob?.cancel()
        metronome.cancel()
        runOnPlayerThread {
            exoPlayer.removeListener(playerListener)
            activeTextureView?.let { exoPlayer.clearVideoTextureView(it) }
            activeTextureView = null
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    private fun loadWaveformFor(item: MediaItem) {
        waveformJob?.cancel()
        _waveformBars.value = waveformExtractor.buildFallbackPreview(item)
        waveformJob = mainScope.launch {
            val extracted = waveformExtractor.loadOrExtract(item)
            if (_snapshot.value.currentItem?.itemId == item.itemId) {
                _waveformBars.value = extracted
            }
        }
        startBpmDetectionFor(item, forceApply = false)
        bindStemStateFor(item)
    }

    private fun bindStemStateFor(item: MediaItem) {
        stemJob?.cancel()
        SharedExoPlayerHolder.updateSeparatedStemsContext(null) { _snapshot.value.positionMs }
        stemJob = mainScope.launch {
            stemSeparationEngine.onTrackBound(item)
            val readyCache = stemSeparationEngine.activeSeparatedCacheForItem(item.itemId)
            runOnPlayerThread {
                if (_snapshot.value.currentItem?.itemId == item.itemId) {
                    SharedExoPlayerHolder.updateSeparatedStemsContext(readyCache) { _snapshot.value.positionMs }
                    if (readyCache == null &&
                        stemModelManager.isModelReady() &&
                        !loopEngine.practiceState.value.stemMix.isUnity
                    ) {
                        maybeAutoAnalyzeCurrentTrackStems()
                    }
                }
            }
        }
    }

    private fun startBpmDetectionFor(item: MediaItem, forceApply: Boolean, ignoreCache: Boolean = false) {
        bpmJob?.cancel()
        stemsRegridJob?.cancel()
        stemsRegridJob = null
        _pendingStemsBeatGrid.value = null
        _detectedBpm.value = null
        _detectedBeatGrid.value = null
        _isDetectingBpm.value = true
        if (forceApply) {
            forceApplyPendingBpmItemId = item.itemId
        } else if (forceApplyPendingBpmItemId != item.itemId) {
            forceApplyPendingBpmItemId = null
        }
        bpmJob = mainScope.launch {
            var detectedGrid: BeatGrid? = null
            try {
                detectedGrid = waveformExtractor.loadOrDetectBeatGrid(item, ignoreCache = ignoreCache)
            } finally {
                runOnPlayerThread {
                    if (_snapshot.value.currentItem?.itemId == item.itemId) {
                        _detectedBeatGrid.value = detectedGrid
                        _detectedBpm.value = detectedGrid?.bpm
                        _isDetectingBpm.value = false
                        val shouldForce = forceApplyPendingBpmItemId == item.itemId
                        if (shouldForce) {
                            forceApplyPendingBpmItemId = null
                        }
                        if (detectedGrid != null &&
                            loopEngine.practiceState.value.itemId == item.itemId
                        ) {
                            loopEngine.applyAutoDetectedBeatGrid(detectedGrid, force = shouldForce)
                            if (shouldForce) {
                                _hapticTicks.tryEmit(Unit)
                            }
                        }
                    } else if (_snapshot.value.currentItem == null) {
                        _isDetectingBpm.value = false
                    }
                }
            }
        }
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = mainScope.launch {
            var tickCount = 0
            while (isActive) {
                runOnPlayerThread {
                    updateSnapshotFromPlayer()
                    tickCount++
                    if (tickCount % 80 == 0) {
                        saveCurrentTrackPosition()
                    }
                }
                delay(40L)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun saveCurrentTrackPosition() {
        val current = _snapshot.value.currentItem ?: return
        val pos = _snapshot.value.positionMs
        if (current.itemId > 0L && pos >= 0L) {
            persistPlaybackPosition(current.itemId, pos)
        }
    }

    private fun syncEffectiveRepeatModeOnPlayerThread() {
        exoPlayer.repeatMode = if (loopEngine.abLoopOwnsRepeat) {
            Player.REPEAT_MODE_ONE
        } else {
            when (_snapshot.value.repeatMode) {
                CasualRepeatMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                CasualRepeatMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                CasualRepeatMode.PLAY_ONCE -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private inline fun performInternalPause(block: () -> Unit) {
        internalPauseDepth++
        try {
            block()
        } finally {
            internalPauseDepth--
        }
    }

    private inline fun performInternalSeek(block: () -> Unit) {
        internalSeekDepth++
        try {
            block()
        } finally {
            internalSeekDepth--
        }
    }

    private fun updateSnapshotFromPlayer() {
        val playerDur = exoPlayer.duration.takeIf { it > 0L }
            ?: _snapshot.value.currentItem?.durationMs
            ?: 0L
        _snapshot.value = _snapshot.value.copy(
            isPlaying = exoPlayer.isPlaying,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = playerDur.coerceAtLeast(0L),
            shuffleEnabled = exoPlayer.shuffleModeEnabled,
        )
    }

    private fun runOnPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == exoPlayer.applicationLooper) {
            block()
        } else {
            appHandler.post(block)
        }
    }

    private fun MediaItem.toExoMediaItem(): ExoMediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(displayAlbum)
            .setAlbumArtist(albumArtist ?: artist)
        coverPathOrUri?.takeIf { it.isNotBlank() }?.let { cover ->
            val artworkUri = if (cover.startsWith("content://") || cover.startsWith("file://") || cover.startsWith("http")) {
                Uri.parse(cover)
            } else {
                Uri.fromFile(java.io.File(cover))
            }
            metadataBuilder.setArtworkUri(artworkUri)
        }
        return ExoMediaItem.Builder()
            .setMediaId(itemId.toString())
            .setUri(Uri.parse(documentUri))
            .setMimeType(mimeType.takeIf { it.isNotBlank() })
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    companion object {
        private const val SONIC_IDENTITY_EPSILON = 0.0001f
        private const val DEFAULT_UNSET_BPM = 120
        private const val MAX_AUTO_RECOVERY_ATTEMPTS = 3

        private val FAST_VIDEO_KEYFRAME_SEEK = SeekParameters(
            /* toleranceBeforeUs = */ 1_500_000L,
            /* toleranceAfterUs = */ 0L,
        )

        internal data class PlaybackParamsDecision(
            val safeSpeed: Float,
            val safePitch: Float,
            val exoPlaybackSpeed: Float,
            val exoPlaybackPitch: Float,
            val shouldUpdateExoPlaybackParameters: Boolean,
            val shouldFlushAudioSink: Boolean = false,
        )

        internal fun evaluatePlaybackParametersUpdate(
            requestedSpeed: Float,
            requestedPitch: Float,
            previousSpeed: Float,
            previousPitch: Float,
        ): PlaybackParamsDecision {
            val safeSpeed = requestedSpeed.coerceIn(0.25f, 2.0f)
            val safePitch = requestedPitch.coerceIn(0.5f, 2.0f)
            val speedChanged = kotlin.math.abs(safeSpeed - previousSpeed) > SONIC_IDENTITY_EPSILON
            return PlaybackParamsDecision(
                safeSpeed = safeSpeed,
                safePitch = safePitch,
                exoPlaybackSpeed = safeSpeed,
                exoPlaybackPitch = 1.0f,
                shouldUpdateExoPlaybackParameters = speedChanged,
                shouldFlushAudioSink = false,
            )
        }
    }
}
