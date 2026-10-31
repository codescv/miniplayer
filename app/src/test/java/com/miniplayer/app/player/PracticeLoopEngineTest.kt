package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.compareNaturalStrings
import com.miniplayer.app.ui.sortMediaItems
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PracticeLoopEngineTest {
    private lateinit var testScope: CoroutineScope
    private lateinit var fakePort: FakeLoopPlayerPort
    private lateinit var metronome: CountInMetronome
    private lateinit var engine: PracticeLoopEngine
    private val persistedStates = mutableListOf<PracticeState>()

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob())
        fakePort = FakeLoopPlayerPort(durationMs = 240_000L)
        metronome = CountInMetronome(testScope)
        engine = PracticeLoopEngine(
            port = fakePort,
            metronome = metronome,
            onStatePersisted = { persistedStates += it },
        )
        fakePort.attachedEngine = engine
    }

    @After
    fun tearDown() {
        metronome.cancel()
        testScope.cancel()
    }

    @Test
    fun abLoopThreeStateFlow_nudgeAndSuspend_cancelsAndReschedulesBoundary() {
        engine.bindTrack(
            state = PracticeState(itemId = 10L),
            isVideo = false,
            activatePracticeMode = true,
        )
        assertEquals(AbLoopUiState.IDLE, engine.abUiState)

        fakePort.currentPositionMs = 15_000L
        engine.markPointA()
        assertEquals(AbLoopUiState.A_SET_WAITING, engine.abUiState)
        assertNull(fakePort.scheduledBoundaryMs)

        // Marking B at 25_000L activates loop, seeks to A, and schedules boundary at 25_000L
        fakePort.currentPositionMs = 25_000L
        engine.markPointBAndLoop()
        assertEquals(AbLoopUiState.ACTIVE_OR_SUSPENDED, engine.abUiState)
        assertEquals(15_000L, engine.practiceState.value.activeAMs)
        assertEquals(25_000L, engine.practiceState.value.activeBMs)
        assertEquals(25_000L, fakePort.scheduledBoundaryMs)
        assertEquals(15_000L, fakePort.currentPositionMs)

        // Nudge B +500ms cancels previous message and re-registers at 25_500L
        val cancelsBefore = fakePort.cancelBoundaryCount
        engine.nudgePointB(PracticeMath.COARSE_NUDGE_MS)
        assertTrue(fakePort.cancelBoundaryCount > cancelsBefore)
        assertEquals(25_500L, fakePort.scheduledBoundaryMs)

        // Suspend loop preserves A & B coordinates while cancelling PlayerMessage
        engine.toggleLoopSuspended()
        assertFalse(engine.practiceState.value.loopEnabled)
        assertEquals(15_000L, engine.practiceState.value.activeAMs)
        assertEquals(25_500L, engine.practiceState.value.activeBMs)
        assertNull(fakePort.scheduledBoundaryMs)

        // Re-enable loop re-arms PlayerMessage
        engine.toggleLoopSuspended()
        assertTrue(engine.practiceState.value.loopEnabled)
        assertEquals(25_500L, fakePort.scheduledBoundaryMs)

        // Clear resets back to IDLE
        engine.clearLoop()
        assertEquals(AbLoopUiState.IDLE, engine.abUiState)
        assertNull(fakePort.scheduledBoundaryMs)
    }

    @Test
    fun previousButtonGuard_singleTapRestartsFromA_doubleTapWithin400msJumpsToStart() {
        engine.bindTrack(
            state = PracticeState(
                itemId = 11L,
                activeAMs = 30_000L,
                activeBMs = 42_000L,
                loopEnabled = true,
            ),
            isVideo = false,
            activatePracticeMode = true,
        )
        fakePort.currentPositionMs = 36_500L

        val firstOutcome = engine.handlePreviousButtonTap(nowElapsedMs = 1_000L)
        assertEquals(PreviousButtonOutcome.RESTARTED_FROM_A, firstOutcome)
        assertEquals(30_000L, fakePort.currentPositionMs)

        val secondOutcome = engine.handlePreviousButtonTap(nowElapsedMs = 1_280L)
        assertEquals(PreviousButtonOutcome.JUMPED_TO_TRACK_START, secondOutcome)
        assertEquals(0L, fakePort.currentPositionMs)
    }

    @Test
    fun speedTrainer_stepsUpEveryNLoopsAndClampsAtTarget() {
        engine.bindTrack(
            state = PracticeState(
                itemId = 12L,
                practiceSpeed = 0.70f,
                activeAMs = 10_000L,
                activeBMs = 20_000L,
                loopEnabled = true,
            ),
            isVideo = false,
            activatePracticeMode = true,
        )
        engine.configureSpeedTrainer(
            SpeedTrainerConfig(
                enabled = true,
                startSpeed = 0.70f,
                targetSpeed = 0.80f,
                stepSpeed = 0.05f,
                loopsPerStep = 2,
            ),
        )

        // Loop 1: still 0.70x
        fakePort.fireBoundary()
        assertEquals(0.70f, engine.practiceState.value.practiceSpeed, 0.001f)

        // Loop 2: steps up +0.05 -> 0.75x
        fakePort.fireBoundary()
        assertEquals(0.75f, engine.practiceState.value.practiceSpeed, 0.001f)
        assertEquals(0.75f, fakePort.lastSpeed, 0.001f)

        // Loops 3 & 4: steps up -> 0.80x target
        fakePort.fireBoundary()
        fakePort.fireBoundary()
        assertEquals(0.80f, engine.practiceState.value.practiceSpeed, 0.001f)

        // Further loops stay clamped at 0.80x
        fakePort.fireBoundary()
        fakePort.fireBoundary()
        assertEquals(0.80f, engine.practiceState.value.practiceSpeed, 0.001f)
    }

    @Test
    fun adaptiveVideoSeek_fallsBackToFastKeyframeSeekWhenLatencyExceeds80ms() {
        engine.bindTrack(
            state = PracticeState(
                itemId = 13L,
                activeAMs = 5_000L,
                activeBMs = 12_000L,
                loopEnabled = true,
                videoSeekMode = VideoSeekMode.AUTO,
            ),
            isVideo = true,
            activatePracticeMode = true,
        )
        assertFalse(engine.usingFastVideoSeek.value)

        // Trigger continuous loop wrap and simulate 140ms GOP decode stall
        fakePort.fireBoundary()
        val futureSimulatedNow = CountInMetronome.monotonicNowMs() + 140L
        engine.onVideoFrameOrReadyRendered(nowElapsedMs = futureSimulatedNow)

        assertTrue(engine.usingFastVideoSeek.value)
        assertTrue(fakePort.fastVideoSeekConfigured)

        // Manual override to EXACT clears fast-seek fallback
        engine.setVideoSeekMode(VideoSeekMode.EXACT)
        assertFalse(engine.usingFastVideoSeek.value)
        assertFalse(fakePort.fastVideoSeekConfigured)
    }

    @Test
    fun countInPrePrimesAFrameAndCancelsCleanlyOnPause() {
        engine.setClickSoundEnabled(false)
        engine.bindTrack(
            state = PracticeState(
                itemId = 14L,
                activeAMs = 8_000L,
                activeBMs = 16_000L,
                loopEnabled = true,
                countInMode = CountInMode.SEC_2,
            ),
            isVideo = true,
            activatePracticeMode = true,
        )

        engine.restartFromA()
        // At t=0 of Count-In, player seeks to A (8_000L) and pauses to pre-prime frame
        assertEquals(8_000L, fakePort.currentPositionMs)
        assertFalse(fakePort.isPlaying)
        assertTrue(metronome.isRunning)

        // User pauses during Count-In -> countdown must abort immediately
        engine.onUserPaused()
        assertFalse(metronome.isRunning)
    }

    @Test
    fun naturalSortAndWoodblockSynthesis_behaveDeterministically() {
        val raw = listOf("Track 10.flac", "Track 2.flac", "Track 1.flac")
        val sorted = raw.sortedWith(::compareNaturalStrings)
        assertEquals(listOf("Track 1.flac", "Track 2.flac", "Track 10.flac"), sorted)

        val pcm = CountInMetronome.synthesizeWoodblockClick(1180.0, 1790.0, 0.78)
        assertTrue(pcm.isNotEmpty())
        assertEquals(0, pcm.first().toInt())
    }

    @Test
    fun repeatModeOwnership_syncsOnMarkBSuspendAndClear() {
        engine.bindTrack(
            state = PracticeState(itemId = 15L),
            isVideo = false,
            activatePracticeMode = true,
        )
        assertFalse(fakePort.abLoopOwnsRepeatSnapshot)

        fakePort.currentPositionMs = 200_000L
        engine.markPointA()
        assertFalse(fakePort.abLoopOwnsRepeatSnapshot)

        fakePort.currentPositionMs = 238_000L
        engine.markPointBAndLoop()
        assertTrue(fakePort.abLoopOwnsRepeatSnapshot)

        // Suspending A-B loop immediately releases REPEAT_MODE_ONE ownership inside cancelBoundaryMessage()
        engine.toggleLoopSuspended()
        assertFalse(fakePort.abLoopOwnsRepeatSnapshot)

        // Resuming A-B loop restores REPEAT_MODE_ONE ownership inside scheduleBoundaryMessage()
        engine.toggleLoopSuspended()
        assertTrue(fakePort.abLoopOwnsRepeatSnapshot)

        // Clearing A-B loop immediately releases REPEAT_MODE_ONE ownership inside cancelBoundaryMessage()
        engine.clearLoop()
        assertFalse(fakePort.abLoopOwnsRepeatSnapshot)
    }

    @Test
    fun autoTriggerInitialLoop_appliesSavedPreRollAndCountInAndHandlesExternalSeek() {
        engine.setClickSoundEnabled(false)
        engine.bindTrack(
            state = PracticeState(
                itemId = 16L,
                activeAMs = 12_000L,
                activeBMs = 20_000L,
                loopEnabled = true,
                preRollEnabled = true,
                countInMode = CountInMode.SEC_1,
            ),
            isVideo = false,
            activatePracticeMode = true,
            jumpBookmark = null,
            autoTriggerInitialLoop = true,
        )

        // Pre-Roll shifts start to 12_000L - 2_000L = 10_000L and Count-In pauses + starts metronome
        assertEquals(10_000L, fakePort.currentPositionMs)
        assertFalse(fakePort.isPlaying)
        assertTrue(metronome.isRunning)

        // External MediaSession seek cancels active Count-In and keeps boundary armed
        engine.onExternalSeek()
        assertFalse(metronome.isRunning)
        assertEquals(20_000L, fakePort.scheduledBoundaryMs)
    }

    @Test
    fun waveformFloatDecodingAndFolderFileNameSort_workCorrectly() {
        val floatBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.5f)
            putFloat(-0.5f)
            putFloat(0.5f)
            putFloat(-0.5f)
        }
        val sumSq = DoubleArray(1)
        val count = IntArray(1)
        val collected = WaveformExtractor.accumulatePcmRms(
            outBuf = floatBytes,
            offset = 0,
            size = 16,
            pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            targetSamples = 16,
            barIdx = 0,
            bucketSumSq = sumSq,
            bucketSampleCount = count,
        )
        assertEquals(4, collected)
        assertEquals(1.0, sumSq[0], 0.001)

        val items = listOf(
            MediaItem(
                itemId = 1L,
                documentUri = "content://1",
                rootId = 1L,
                parentRelPath = "",
                fileName = "10 - Outro.mp3",
                mimeType = "audio/mpeg",
                isVideo = false,
                sizeBytes = 1L,
                lastModified = 1L,
                durationMs = 1000L,
                title = "Alpha",
                artist = "A",
            ),
            MediaItem(
                itemId = 2L,
                documentUri = "content://2",
                rootId = 1L,
                parentRelPath = "",
                fileName = "02 - Verse.mp3",
                mimeType = "audio/mpeg",
                isVideo = false,
                sizeBytes = 1L,
                lastModified = 1L,
                durationMs = 1000L,
                title = "Zulu",
                artist = "A",
            ),
            MediaItem(
                itemId = 3L,
                documentUri = "content://3",
                rootId = 1L,
                parentRelPath = "",
                fileName = "01 - Intro.mp3",
                mimeType = "audio/mpeg",
                isVideo = false,
                sizeBytes = 1L,
                lastModified = 1L,
                durationMs = 1000L,
                title = "Mike",
                artist = "A",
            ),
        )
        val sortedNames = sortMediaItems(items, SortOrder.NAME_ASC).map { it.fileName }
        assertEquals(listOf("01 - Intro.mp3", "02 - Verse.mp3", "10 - Outro.mp3"), sortedNames)
    }

    private class FakeLoopPlayerPort(override var durationMs: Long) : LoopPlayerPort {
        var attachedEngine: PracticeLoopEngine? = null
        override var currentPositionMs: Long = 0L
        override var isPlaying: Boolean = true
        var scheduledBoundaryMs: Long? = null
        var boundaryCallback: (() -> Unit)? = null
        var cancelBoundaryCount: Int = 0
        var lastSpeed: Float = 1.0f
        var lastPitch: Float = 1.0f
        var fastVideoSeekConfigured: Boolean = false
        var abLoopOwnsRepeatSnapshot: Boolean = false

        override fun scheduleBoundaryMessage(positionMs: Long, onReached: () -> Unit) {
            scheduledBoundaryMs = positionMs
            boundaryCallback = onReached
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun cancelBoundaryMessage() {
            cancelBoundaryCount++
            scheduledBoundaryMs = null
            boundaryCallback = null
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun applyPlaybackParameters(speed: Float, pitch: Float) {
            lastSpeed = speed
            lastPitch = pitch
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun configureExactSeek() {
            fastVideoSeekConfigured = false
        }

        override fun configureFastVideoKeyframeSeek() {
            fastVideoSeekConfigured = true
        }

        override fun seekToPosition(positionMs: Long) {
            currentPositionMs = positionMs
        }

        override fun play() {
            isPlaying = true
        }

        override fun pause() {
            isPlaying = false
        }

        override fun skipToPreviousItem(): Boolean = false

        fun fireBoundary() {
            boundaryCallback?.invoke()
        }
    }
}
