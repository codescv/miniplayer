package com.miniplayer.app.player

import com.miniplayer.app.core.model.BeatGrid
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.PracticeBookmark
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.SpeedTrainerConfig
import com.miniplayer.app.core.model.StemMixConfig
import com.miniplayer.app.core.model.StemType
import com.miniplayer.app.core.model.VideoSeekMode
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AbLoopUiState {
    IDLE,
    A_SET_WAITING,
    ACTIVE_OR_SUSPENDED,
}

enum class PreviousButtonOutcome {
    RESTARTED_FROM_A,
    JUMPED_TO_TRACK_START,
    SKIPPED_TO_PREVIOUS_TRACK,
}

interface LoopPlayerPort {
    val currentPositionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    fun scheduleBoundaryMessage(positionMs: Long, onReached: () -> Unit)
    fun cancelBoundaryMessage()
    fun applyPlaybackParameters(speed: Float, pitch: Float)
    fun applyStemMix(stemMix: StemMixConfig) {}
    fun configureExactSeek()
    fun configureFastVideoKeyframeSeek()
    fun seekToPosition(positionMs: Long)
    fun play()
    fun pause()
    fun skipToPreviousItem(): Boolean
}

/**
 * Sample-accurate A-B practice loop state machine, bar-boundary snap coordinator, formant & 5-stem
 * DSP parameter controller, adaptive GOP seek coordinator, and progressive Speed Trainer engine.
 * All player interactions go through [LoopPlayerPort], which executes strictly on
 * `exoPlayer.applicationLooper`.
 */
class PracticeLoopEngine(
    private val port: LoopPlayerPort,
    private val metronome: CountInMetronome,
    private val onStatePersisted: (PracticeState) -> Unit = {},
    private val onHapticEvent: () -> Unit = {},
    private val onStemSliderActivated: () -> Unit = {},
) {
    private val _practiceState = MutableStateFlow(PracticeState(itemId = -1L))
    val practiceState: StateFlow<PracticeState> = _practiceState.asStateFlow()

    private val _isPracticeModeActive = MutableStateFlow(false)
    val isPracticeModeActive: StateFlow<Boolean> = _isPracticeModeActive.asStateFlow()

    private val _speedTrainer = MutableStateFlow(SpeedTrainerConfig())
    val speedTrainer: StateFlow<SpeedTrainerConfig> = _speedTrainer.asStateFlow()

    private val _completedLoopCount = MutableStateFlow(0)
    val completedLoopCount: StateFlow<Int> = _completedLoopCount.asStateFlow()

    private val _usingFastVideoSeek = MutableStateFlow(false)
    val usingFastVideoSeek: StateFlow<Boolean> = _usingFastVideoSeek.asStateFlow()

    private val _activeBookmarkId = MutableStateFlow<Long?>(null)
    val activeBookmarkId: StateFlow<Long?> = _activeBookmarkId.asStateFlow()

    private var currentIsVideo: Boolean = false
    private var clickSoundEnabled: Boolean = true
    private var pendingLoopSeekStartElapsedMs: Long? = null
    private var lastPrevTapElapsedMs: Long? = null

    val isClickSoundEnabled: Boolean
        get() = clickSoundEnabled

    val abUiState: AbLoopUiState
        get() {
            val s = _practiceState.value
            return when {
                s.activeAMs != null && s.activeBMs != null -> AbLoopUiState.ACTIVE_OR_SUSPENDED
                s.activeAMs != null -> AbLoopUiState.A_SET_WAITING
                else -> AbLoopUiState.IDLE
            }
        }

    val abLoopOwnsRepeat: Boolean
        get() {
            val s = _practiceState.value
            return _isPracticeModeActive.value &&
                s.loopEnabled &&
                s.activeAMs != null &&
                s.activeBMs != null &&
                s.activeBMs > s.activeAMs
        }

    fun setClickSoundEnabled(enabled: Boolean) {
        clickSoundEnabled = enabled
    }

    /**
     * Binds a new track's [PracticeState] and sets whether Practice Mode is active. Always cancels
     * any previous track's `PlayerMessage` and `CountInMetronome` timer first.
     */
    fun bindTrack(
        state: PracticeState,
        isVideo: Boolean,
        activatePracticeMode: Boolean,
        jumpBookmark: PracticeBookmark? = null,
        autoTriggerInitialLoop: Boolean = false,
    ) {
        metronome.cancel()
        metronome.resetTapHistory()
        pendingLoopSeekStartElapsedMs = null
        lastPrevTapElapsedMs = null
        currentIsVideo = isVideo
        _usingFastVideoSeek.value = false
        _completedLoopCount.value = 0
        _speedTrainer.update { it.copy(enabled = false, currentLoopInStep = 0) }

        val effectiveState = if (jumpBookmark != null) {
            state.copy(
                activeAMs = jumpBookmark.aMs,
                activeBMs = jumpBookmark.bMs,
                loopEnabled = true,
                practiceSpeed = jumpBookmark.savedSpeed?.let(PracticeMath::clampSpeed) ?: state.practiceSpeed,
            )
        } else {
            state
        }
        _activeBookmarkId.value = jumpBookmark?.bookmarkId
        _practiceState.value = effectiveState
        _isPracticeModeActive.value = activatePracticeMode

        syncSonicParameters()
        syncSeekModeForTrack()
        syncLoopBoundaryMessage()

        if (jumpBookmark != null && activatePracticeMode) {
            onStatePersisted(effectiveState)
        }
        val hasActiveLoop = effectiveState.loopEnabled &&
            effectiveState.activeAMs != null &&
            effectiveState.activeBMs != null &&
            effectiveState.activeBMs > effectiveState.activeAMs
        if (activatePracticeMode && hasActiveLoop && (jumpBookmark != null || autoTriggerInitialLoop)) {
            triggerJumpToA(autoPlay = true)
        }
    }

    fun setPracticeModeActive(active: Boolean) {
        if (_isPracticeModeActive.value == active) return
        metronome.cancel()
        _isPracticeModeActive.value = active
        if (!active) {
            port.cancelBoundaryMessage()
            port.configureExactSeek()
            port.applyStemMix(StemMixConfig())
            port.applyPlaybackParameters(1.0f, 1.0f)
            _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        } else {
            syncSonicParameters()
            syncSeekModeForTrack()
            syncLoopBoundaryMessage()
        }
    }

    fun markPointA(atMs: Long = port.currentPositionMs) {
        metronome.cancel()
        _activeBookmarkId.value = null
        val current = _practiceState.value
        val rawA = atMs.coerceAtLeast(0L)
        val safeA = if (_isPracticeModeActive.value && current.barSnapEnabled) {
            current.beatGrid().snapPointA(rawA, port.durationMs)
        } else {
            rawA
        }
        updatePracticeState {
            it.copy(
                activeAMs = safeA,
                activeBMs = null,
                loopEnabled = false,
            )
        }
        syncLoopBoundaryMessage()
    }

    fun markPointBAndLoop(atMs: Long = port.currentPositionMs) {
        val current = _practiceState.value
        val rawA = current.activeAMs ?: 0L
        val dur = port.durationMs
        val (candA, candB) = if (_isPracticeModeActive.value && current.barSnapEnabled) {
            val grid = current.beatGrid()
            if (atMs < rawA) {
                val snappedLo = grid.snapPointA(atMs.coerceAtLeast(0L), dur)
                val snappedHi = grid.snapPointB(snappedLo, rawA, dur)
                snappedLo to snappedHi
            } else {
                rawA to grid.snapPointB(rawA, atMs, dur)
            }
        } else {
            rawA to atMs
        }
        val normalized = PracticeMath.normalizeLoopPoints(candA, candB, dur) ?: return
        metronome.cancel()
        _activeBookmarkId.value = null
        _completedLoopCount.value = 0
        _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        updatePracticeState {
            it.copy(
                activeAMs = normalized.first,
                activeBMs = normalized.second,
                loopEnabled = true,
            )
        }
        syncLoopBoundaryMessage()
        triggerJumpToA(autoPlay = true)
    }

    fun setLoopPointsFromDrag(
        aMs: Long,
        bMs: Long,
        commitPersist: Boolean,
        draggingHandle: String? = null,
    ) {
        val current = _practiceState.value
        val dur = port.durationMs
        val (candA, candB) = if (_isPracticeModeActive.value && current.barSnapEnabled) {
            current.beatGrid().snapDraggedLoopPoints(aMs, bMs, draggingHandle, dur)
        } else {
            aMs to bMs
        }
        val normalized = PracticeMath.normalizeLoopPoints(candA, candB, dur) ?: return
        metronome.cancel()
        _activeBookmarkId.value = null
        val next = current.copy(
            activeAMs = normalized.first,
            activeBMs = normalized.second,
            loopEnabled = true,
        )
        _practiceState.value = next
        if (commitPersist) {
            if (next.itemId > 0L) {
                onStatePersisted(next)
            }
            syncLoopBoundaryMessage()
            if (port.currentPositionMs >= normalized.second && _isPracticeModeActive.value) {
                triggerJumpToA(autoPlay = port.isPlaying)
            }
        } else {
            port.cancelBoundaryMessage()
        }
    }

    fun nudgePointA(deltaMs: Long) {
        val current = _practiceState.value
        val aMs = current.activeAMs ?: return
        val dur = port.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val maxA = current.activeBMs?.let { (it - PracticeMath.MIN_LOOP_SPAN_MS).coerceAtLeast(0L) } ?: dur
        val nextA = (aMs + deltaMs).coerceIn(0L, maxA)
        if (nextA == aMs) return

        metronome.cancel()
        _activeBookmarkId.value = null
        updatePracticeState { it.copy(activeAMs = nextA) }
        syncLoopBoundaryMessage()
        onHapticEvent()
    }

    fun nudgePointB(deltaMs: Long) {
        val current = _practiceState.value
        val aMs = current.activeAMs ?: return
        val bMs = current.activeBMs ?: return
        val dur = port.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val minB = (aMs + PracticeMath.MIN_LOOP_SPAN_MS).coerceAtMost(dur)
        val nextB = (bMs + deltaMs).coerceIn(minB, dur)
        if (nextB == bMs) return

        metronome.cancel()
        _activeBookmarkId.value = null
        updatePracticeState { it.copy(activeBMs = nextB) }
        syncLoopBoundaryMessage()
        onHapticEvent()

        if (_isPracticeModeActive.value && _practiceState.value.loopEnabled && port.currentPositionMs >= nextB) {
            triggerJumpToA(autoPlay = port.isPlaying)
        }
    }

    fun toggleLoopSuspended() {
        val current = _practiceState.value
        if (current.activeAMs == null || current.activeBMs == null) return
        val nextEnabled = !current.loopEnabled
        metronome.cancel()
        _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        updatePracticeState { it.copy(loopEnabled = nextEnabled) }
        syncLoopBoundaryMessage()
        if (nextEnabled && _isPracticeModeActive.value) {
            val pos = port.currentPositionMs
            if (pos < current.activeAMs || pos >= current.activeBMs) {
                triggerJumpToA(autoPlay = port.isPlaying)
            }
        }
    }

    fun clearLoop() {
        metronome.cancel()
        _activeBookmarkId.value = null
        _completedLoopCount.value = 0
        _speedTrainer.update { it.copy(enabled = false, currentLoopInStep = 0) }
        updatePracticeState {
            it.copy(
                activeAMs = null,
                activeBMs = null,
                loopEnabled = false,
            )
        }
        syncLoopBoundaryMessage()
    }

    fun restartFromA() {
        val s = _practiceState.value
        val aMs = s.activeAMs
        if (aMs == null) {
            metronome.cancel()
            port.seekToPosition(0L)
            return
        }
        metronome.cancel()
        _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        if (s.activeBMs != null && !s.loopEnabled) {
            updatePracticeState { it.copy(loopEnabled = true) }
        }
        syncLoopBoundaryMessage()
        triggerJumpToA(autoPlay = true)
    }

    fun activateBookmark(bookmark: PracticeBookmark) {
        metronome.cancel()
        port.cancelBoundaryMessage()
        _activeBookmarkId.value = bookmark.bookmarkId
        _completedLoopCount.value = 0
        _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        updatePracticeState { state ->
            state.copy(
                activeAMs = bookmark.aMs,
                activeBMs = bookmark.bMs,
                loopEnabled = true,
                practiceSpeed = bookmark.savedSpeed?.let(PracticeMath::clampSpeed) ?: state.practiceSpeed,
            )
        }
        if (!_isPracticeModeActive.value) {
            _isPracticeModeActive.value = true
        }
        syncSonicParameters()
        syncLoopBoundaryMessage()
        triggerJumpToA(autoPlay = true)
    }

    fun handlePreviousButtonTap(nowElapsedMs: Long = CountInMetronome.monotonicNowMs()): PreviousButtonOutcome {
        val s = _practiceState.value
        val hasActiveLoop = _isPracticeModeActive.value &&
            s.loopEnabled &&
            s.activeAMs != null &&
            s.activeBMs != null

        if (hasActiveLoop) {
            val prevTap = lastPrevTapElapsedMs
            val isDoubleTap = prevTap != null && (nowElapsedMs - prevTap) <= DOUBLE_TAP_WINDOW_MS
            if (isDoubleTap) {
                lastPrevTapElapsedMs = null
                metronome.cancel()
                return if (port.currentPositionMs > 3_000L || !port.skipToPreviousItem()) {
                    port.seekToPosition(0L)
                    syncLoopBoundaryMessage()
                    PreviousButtonOutcome.JUMPED_TO_TRACK_START
                } else {
                    PreviousButtonOutcome.SKIPPED_TO_PREVIOUS_TRACK
                }
            } else {
                lastPrevTapElapsedMs = nowElapsedMs
                restartFromA()
                return PreviousButtonOutcome.RESTARTED_FROM_A
            }
        }

        metronome.cancel()
        return if (port.currentPositionMs > 3_000L || !port.skipToPreviousItem()) {
            port.seekToPosition(0L)
            syncLoopBoundaryMessage()
            PreviousButtonOutcome.JUMPED_TO_TRACK_START
        } else {
            PreviousButtonOutcome.SKIPPED_TO_PREVIOUS_TRACK
        }
    }

    fun setPracticeSpeed(speed: Float, fromManualControl: Boolean = true) {
        val clamped = PracticeMath.clampSpeed(speed)
        if (fromManualControl) {
            _speedTrainer.update { it.copy(currentLoopInStep = 0) }
        }
        updatePracticeState { it.copy(practiceSpeed = clamped) }
        syncSonicParameters()
    }

    fun stepPracticeSpeed(delta: Float) {
        setPracticeSpeed(_practiceState.value.practiceSpeed + delta, fromManualControl = true)
    }

    fun setPitchShift(semitones: Int, cents: Int = _practiceState.value.pitchCents) {
        val st = semitones.coerceIn(-6, 6)
        val ct = cents.coerceIn(-50, 50)
        updatePracticeState { it.copy(pitchSemitones = st, pitchCents = ct) }
        syncSonicParameters()
    }

    fun setStemMix(stemMix: StemMixConfig) {
        val clean = stemMix.sanitized()
        updatePracticeState { it.copy(stemMix = clean) }
        syncSonicParameters()
        if (!clean.isUnity) {
            onStemSliderActivated()
        }
    }

    fun setStemVolume(stem: StemType, gain: Float, commitPersist: Boolean = true) {
        val updatedMix = _practiceState.value.stemMix.withSliderGain(stem, gain)
        val next = _practiceState.value.copy(stemMix = updatedMix)
        _practiceState.value = next
        syncSonicParameters()
        if (commitPersist && next.itemId > 0L) {
            onStatePersisted(next)
        }
        if (!updatedMix.isUnity) {
            onStemSliderActivated()
        }
    }

    fun toggleStemMute(stem: StemType) {
        val cur = _practiceState.value.stemMix
        val nextMuted = if (stem in cur.mutedStems) cur.mutedStems - stem else cur.mutedStems + stem
        val nextSolo = if (cur.soloStem == stem && stem in nextMuted) null else cur.soloStem
        setStemMix(cur.copy(mutedStems = nextMuted, soloStem = nextSolo))
        onHapticEvent()
    }

    fun toggleStemSolo(stem: StemType) {
        val cur = _practiceState.value.stemMix
        val nextSolo = if (cur.soloStem == stem) null else stem
        val nextMuted = if (nextSolo != null) cur.mutedStems - nextSolo else cur.mutedStems
        setStemMix(cur.copy(mutedStems = nextMuted, soloStem = nextSolo))
        onHapticEvent()
    }

    fun resetStemMix() {
        setStemMix(StemMixConfig())
        onHapticEvent()
    }

    /**
     * Beat-only 1-tap toggle (`OFF <-> BEAT_4`).
     */
    fun cycleCountInMode() {
        val next = if (_practiceState.value.countInMode == CountInMode.OFF) {
            CountInMode.BEAT_4
        } else {
            CountInMode.OFF
        }
        metronome.cancel()
        updatePracticeState { it.copy(countInMode = next) }
    }

    fun setCountInMode(mode: CountInMode) {
        metronome.cancel()
        updatePracticeState { it.copy(countInMode = mode.normalizeBeatOnly()) }
    }

    fun togglePreRoll() {
        updatePracticeState { it.copy(preRollEnabled = !it.preRollEnabled) }
    }

    fun toggleBarSnap() {
        setBarSnapEnabled(!_practiceState.value.barSnapEnabled)
    }

    fun setBarSnapEnabled(enabled: Boolean) {
        updatePracticeState { state ->
            if (enabled && state.activeAMs != null && state.activeBMs != null) {
                val snapped = state.beatGrid().snapDraggedLoopPoints(
                    aMs = state.activeAMs,
                    bMs = state.activeBMs,
                    draggingHandle = null,
                    durationMs = port.durationMs,
                )
                val normalized = PracticeMath.normalizeLoopPoints(snapped.first, snapped.second, port.durationMs)
                if (normalized != null) {
                    state.copy(
                        barSnapEnabled = true,
                        activeAMs = normalized.first,
                        activeBMs = normalized.second,
                    )
                } else {
                    state.copy(barSnapEnabled = true)
                }
            } else {
                state.copy(barSnapEnabled = enabled)
            }
        }
        syncLoopBoundaryMessage()
        onHapticEvent()
    }

    fun alignDownbeatToCurrentPosition(posMs: Long = port.currentPositionMs) {
        val dur = port.durationMs
        updatePracticeState { state ->
            val barDur = state.beatGrid().barDurationMs
            val safePos = posMs.coerceAtLeast(0L)
            val newDownbeat = if (barDur > 1.0) {
                (safePos - floor(safePos / barDur) * barDur).roundToLong().coerceAtLeast(0L)
            } else {
                0L
            }
            val updatedGrid = BeatGrid(
                bpm = state.bpm,
                firstBeatMs = newDownbeat,
                firstDownbeatMs = newDownbeat,
                beatsPerBar = 4,
            )
            if (state.barSnapEnabled && state.activeAMs != null && state.activeBMs != null) {
                val snapped = updatedGrid.snapDraggedLoopPoints(
                    aMs = state.activeAMs,
                    bMs = state.activeBMs,
                    draggingHandle = null,
                    durationMs = dur,
                )
                val normalized = PracticeMath.normalizeLoopPoints(snapped.first, snapped.second, dur)
                state.copy(
                    firstDownbeatMs = newDownbeat,
                    activeAMs = normalized?.first ?: state.activeAMs,
                    activeBMs = normalized?.second ?: state.activeBMs,
                )
            } else if (state.barSnapEnabled && state.activeAMs != null) {
                state.copy(
                    firstDownbeatMs = newDownbeat,
                    activeAMs = updatedGrid.snapPointA(state.activeAMs, dur),
                )
            } else {
                state.copy(firstDownbeatMs = newDownbeat)
            }
        }
        syncLoopBoundaryMessage()
        onHapticEvent()
    }

    fun setBpm(bpm: Int) {
        val safe = PracticeMath.clampBpm(bpm)
        updatePracticeState { it.copy(bpm = safe) }
    }

    fun applyAutoDetectedBpm(bpm: Int) {
        val safe = PracticeMath.clampBpm(bpm)
        updatePracticeState { it.copy(bpm = safe) }
    }

    fun applyAutoDetectedBeatGrid(grid: BeatGrid, overwriteCustomBpm: Boolean = false) {
        val safeBpm = PracticeMath.clampBpm(grid.bpm)
        val safeDownbeat = grid.firstDownbeatMs.coerceAtLeast(0L)
        updatePracticeState { state ->
            val nextBpm = if (overwriteCustomBpm || state.bpm == 120) safeBpm else state.bpm
            val nextDownbeat = if (overwriteCustomBpm || state.firstDownbeatMs == 0L) {
                safeDownbeat
            } else {
                state.firstDownbeatMs
            }
            state.copy(bpm = nextBpm, firstDownbeatMs = nextDownbeat)
        }
    }

    fun tapTempo(nowElapsedMs: Long = CountInMetronome.monotonicNowMs()): Int {
        val computed = metronome.recordTapTempo(nowElapsedMs)
        if (computed != null) {
            setBpm(computed)
            onHapticEvent()
            return computed
        }
        return _practiceState.value.bpm
    }

    fun configureSpeedTrainer(config: SpeedTrainerConfig) {
        val start = PracticeMath.clampSpeed(config.startSpeed)
        val target = PracticeMath.clampSpeed(config.targetSpeed).coerceAtLeast(start)
        val step = config.stepSpeed.coerceIn(0.01f, 0.25f)
        val loops = config.loopsPerStep.coerceIn(1, 32)
        val sanitized = config.copy(
            startSpeed = start,
            targetSpeed = target,
            stepSpeed = step,
            loopsPerStep = loops,
            currentLoopInStep = 0,
        )
        _speedTrainer.value = sanitized
        if (sanitized.enabled) {
            setPracticeSpeed(start, fromManualControl = false)
        }
    }

    fun toggleVideoMirror() {
        updatePracticeState { it.copy(videoMirror = !it.videoMirror) }
    }

    fun setVideoZoom(zoom: Float, persist: Boolean = false) {
        val clamped = zoom.coerceIn(1.0f, 4.0f)
        val next = _practiceState.value.copy(videoZoom = clamped)
        _practiceState.value = next
        if (persist && next.itemId > 0L) {
            onStatePersisted(next)
        }
    }

    fun toggleHudDockTop() {
        updatePracticeState { it.copy(hudDockTop = !it.hudDockTop) }
    }

    fun setVideoSeekMode(mode: VideoSeekMode) {
        updatePracticeState { it.copy(videoSeekMode = mode) }
        if (mode == VideoSeekMode.EXACT) {
            _usingFastVideoSeek.value = false
        }
        syncSeekModeForTrack()
    }

    fun onUserPaused() {
        metronome.cancel()
        _speedTrainer.update { it.copy(currentLoopInStep = 0) }
    }

    fun onUserManualSeek(targetMs: Long) {
        metronome.cancel()
        pendingLoopSeekStartElapsedMs = null
        val safeTarget = targetMs.coerceAtLeast(0L)
        val finalTarget = if (abLoopOwnsRepeat && _practiceState.value.barSnapEnabled) {
            _practiceState.value.beatGrid().snapToNearestBar(safeTarget, port.durationMs)
        } else {
            safeTarget
        }
        port.seekToPosition(finalTarget)
        syncLoopBoundaryMessage()
    }

    fun onExternalSeek() {
        metronome.cancel()
        pendingLoopSeekStartElapsedMs = null
        syncLoopBoundaryMessage()
    }

    fun onVideoFrameOrReadyRendered(nowElapsedMs: Long = CountInMetronome.monotonicNowMs()) {
        val seekStart = pendingLoopSeekStartElapsedMs ?: return
        pendingLoopSeekStartElapsedMs = null
        val elapsed = nowElapsedMs - seekStart
        val state = _practiceState.value
        if (currentIsVideo &&
            state.videoSeekMode == VideoSeekMode.AUTO &&
            !_usingFastVideoSeek.value &&
            elapsed > PracticeMath.VIDEO_FAST_SEEK_THRESHOLD_MS
        ) {
            _usingFastVideoSeek.value = true
            port.configureFastVideoKeyframeSeek()
        }
    }

    internal fun onLoopBoundaryReached() {
        val state = _practiceState.value
        if (!_isPracticeModeActive.value || !state.loopEnabled) return
        val aMs = state.activeAMs ?: return
        val bMs = state.activeBMs ?: return
        if (bMs <= aMs) return

        _completedLoopCount.update { it + 1 }
        advanceSpeedTrainerIfEnabled()
        triggerJumpToA(autoPlay = true)
    }

    private fun advanceSpeedTrainerIfEnabled() {
        val trainer = _speedTrainer.value
        if (!trainer.enabled) return
        val currentSpeed = _practiceState.value.practiceSpeed
        if (currentSpeed >= trainer.targetSpeed) {
            _speedTrainer.update { it.copy(currentLoopInStep = 0) }
            return
        }
        val nextLoopCount = trainer.currentLoopInStep + 1
        if (nextLoopCount >= trainer.loopsPerStep) {
            val nextSpeed = PracticeMath.clampSpeed(
                minOf(trainer.targetSpeed, currentSpeed + trainer.stepSpeed),
            )
            _speedTrainer.value = trainer.copy(currentLoopInStep = 0)
            setPracticeSpeed(nextSpeed, fromManualControl = false)
            if (nextSpeed >= trainer.targetSpeed) {
                onHapticEvent()
            }
        } else {
            _speedTrainer.value = trainer.copy(currentLoopInStep = nextLoopCount)
        }
    }

    private fun triggerJumpToA(autoPlay: Boolean) {
        val state = _practiceState.value
        val aMs = state.activeAMs ?: return
        val targetStartMs = if (state.preRollEnabled) {
            (aMs - PRE_ROLL_LEAD_MS).coerceAtLeast(0L)
        } else {
            aMs
        }

        if (_isPracticeModeActive.value && state.countInMode != CountInMode.OFF) {
            // Scenario A (§7.1): Pre-prime exact `A` frame at t=0 of Count-In and pause so play() at t=end is 0ms!
            port.configureExactSeek()
            pendingLoopSeekStartElapsedMs = null
            port.seekToPosition(targetStartMs)
            port.pause()
            syncLoopBoundaryMessage()

            if (autoPlay) {
                metronome.start(
                    mode = state.countInMode,
                    bpm = state.bpm,
                    practiceSpeed = state.practiceSpeed,
                    playClickSound = clickSoundEnabled,
                    onComplete = {
                        syncSeekModeForTrack()
                        syncLoopBoundaryMessage()
                        port.play()
                    },
                )
            }
        } else {
            // Scenario B (§7.1): Continuous loop wrap without Count-In; measure video GOP seek latency!
            syncSeekModeForTrack()
            if (currentIsVideo && state.videoSeekMode == VideoSeekMode.AUTO && !_usingFastVideoSeek.value) {
                pendingLoopSeekStartElapsedMs = CountInMetronome.monotonicNowMs()
            } else {
                pendingLoopSeekStartElapsedMs = null
            }
            port.seekToPosition(targetStartMs)
            syncLoopBoundaryMessage()
            if (autoPlay) {
                port.play()
            }
        }
    }

    private fun syncSonicParameters() {
        if (_isPracticeModeActive.value) {
            val s = _practiceState.value
            val speed = PracticeMath.clampSpeed(s.practiceSpeed)
            val pitch = PracticeMath.computePitchFactor(s.pitchSemitones, s.pitchCents)
            port.applyStemMix(s.stemMix)
            port.applyPlaybackParameters(speed, pitch)
        } else {
            port.applyStemMix(StemMixConfig())
            port.applyPlaybackParameters(1.0f, 1.0f)
        }
    }

    private fun syncSeekModeForTrack() {
        if (!currentIsVideo) {
            port.configureExactSeek()
            return
        }
        val state = _practiceState.value
        if (state.videoSeekMode == VideoSeekMode.AUTO && _usingFastVideoSeek.value) {
            port.configureFastVideoKeyframeSeek()
        } else {
            port.configureExactSeek()
        }
    }

    private fun syncLoopBoundaryMessage() {
        port.cancelBoundaryMessage()
        val s = _practiceState.value
        val aMs = s.activeAMs
        val bMs = s.activeBMs
        if (_isPracticeModeActive.value && s.loopEnabled && aMs != null && bMs != null && bMs > aMs) {
            port.scheduleBoundaryMessage(bMs) {
                onLoopBoundaryReached()
            }
        }
    }

    private fun updatePracticeState(transform: (PracticeState) -> PracticeState) {
        val updated = transform(_practiceState.value)
        _practiceState.value = updated
        if (updated.itemId > 0L) {
            onStatePersisted(updated)
        }
    }

    companion object {
        const val PRE_ROLL_LEAD_MS = 2_000L
        const val DOUBLE_TAP_WINDOW_MS = 400L
    }
}
