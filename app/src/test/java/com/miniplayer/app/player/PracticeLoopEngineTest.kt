package com.miniplayer.app.player

import android.media.AudioFormat
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.compareNaturalStrings
import com.miniplayer.app.ui.components.ArtworkLoader
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
    private var stemSliderActivationCount = 0

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob())
        fakePort = FakeLoopPlayerPort(durationMs = 240_000L)
        metronome = CountInMetronome(testScope)
        stemSliderActivationCount = 0
        engine = PracticeLoopEngine(
            port = fakePort,
            metronome = metronome,
            onStatePersisted = { persistedStates += it },
            onStemSliderActivated = { stemSliderActivationCount++ },
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
            state = PracticeState(itemId = 10L, barSnapEnabled = false),
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

    @Test
    fun artworkLoader_upgradesTrustedArchiveHttpRedirectsAndRejectsUntrustedHttp() {
        assertEquals(
            "https://coverartarchive.org/release/123/front-500",
            ArtworkLoader.normalizeTrustedCoverUrl("https://coverartarchive.org/release/123/front-500"),
        )
        assertEquals(
            "https://archive.org/download/mbid-123/front-500.jpg",
            ArtworkLoader.normalizeTrustedCoverUrl("http://archive.org/download/mbid-123/front-500.jpg"),
        )
        assertEquals(
            "https://ia800500.us.archive.org/19/items/mbid-123/front-500.jpg",
            ArtworkLoader.normalizeTrustedCoverUrl("http://ia800500.us.archive.org/19/items/mbid-123/front-500.jpg"),
        )
        assertEquals(
            "https://coverartarchive.org/release/123/front-250",
            ArtworkLoader.normalizeTrustedCoverUrl("http://coverartarchive.org/release/123/front-250"),
        )
        // Untrusted cleartext hosts and lookalike domains must still be rejected
        assertNull(ArtworkLoader.normalizeTrustedCoverUrl("http://evil-archive.org/front.jpg"))
        assertNull(ArtworkLoader.normalizeTrustedCoverUrl("http://example.com/front.jpg"))
    }

    @Test
    fun pitchShiftDecisionAndAutoDetectedBpm_behaveDeterministically() {
        assertTrue(AppSettings().countInClickSound)
        assertTrue(engine.isClickSoundEnabled)

        // 1. Identity -> Identity: no flush, no ExoPlayer speed update
        val idToId = PlayerController.evaluatePlaybackParametersUpdate(1.0f, 1.0f, 1.0f, 1.0f)
        assertFalse(idToId.shouldFlushAudioSink)
        assertFalse(idToId.shouldUpdateExoPlaybackParameters)
        assertEquals(1.0f, idToId.exoPlaybackPitch, 0.0001f)

        // 2. Identity -> +1 semitone: RealTimeSonicAudioProcessor handles pitch directly without ExoPlayer flush or pitch update
        val plusOnePitch = PracticeMath.computePitchFactor(1, 0)
        val idToPitch = PlayerController.evaluatePlaybackParametersUpdate(1.0f, plusOnePitch, 1.0f, 1.0f)
        assertFalse(idToPitch.shouldFlushAudioSink)
        assertFalse(idToPitch.shouldUpdateExoPlaybackParameters)
        assertEquals(plusOnePitch, idToPitch.safePitch, 0.0001f)
        assertEquals(1.0f, idToPitch.exoPlaybackPitch, 0.0001f)

        // 3. +1 semitone -> +2 semitones: still zero sink flush and pitch=1.0f on ExoPlayer
        val plusTwoPitch = PracticeMath.computePitchFactor(2, 0)
        val pitchToPitch = PlayerController.evaluatePlaybackParametersUpdate(1.0f, plusTwoPitch, 1.0f, plusOnePitch)
        assertFalse(pitchToPitch.shouldFlushAudioSink)
        assertFalse(pitchToPitch.shouldUpdateExoPlaybackParameters)
        assertEquals(1.0f, pitchToPitch.exoPlaybackPitch, 0.0001f)

        // 4. Identity -> 0.85x speed: ExoPlayer speed updates (with pitch=1.0f), zero manual seek flush
        val idToSlow = PlayerController.evaluatePlaybackParametersUpdate(0.85f, plusTwoPitch, 1.0f, plusTwoPitch)
        assertFalse(idToSlow.shouldFlushAudioSink)
        assertTrue(idToSlow.shouldUpdateExoPlaybackParameters)
        assertEquals(0.85f, idToSlow.exoPlaybackSpeed, 0.0001f)
        assertEquals(1.0f, idToSlow.exoPlaybackPitch, 0.0001f)

        // 5. Out-of-bounds clamping
        val clamped = PlayerController.evaluatePlaybackParametersUpdate(0.05f, 4.0f, 1.0f, 1.0f)
        assertEquals(0.25f, clamped.safeSpeed, 0.0001f)
        assertEquals(2.0f, clamped.safePitch, 0.0001f)
        assertFalse(clamped.shouldFlushAudioSink)

        // 6. Auto-detected BPM updates PracticeLoopEngine and persists state
        engine.bindTrack(
            state = PracticeState(itemId = 77L, bpm = 120),
            isVideo = false,
            activatePracticeMode = true,
        )
        persistedStates.clear()
        engine.applyAutoDetectedBpm(138)
        assertEquals(138, engine.practiceState.value.bpm)
        assertEquals(1, persistedStates.size)
        assertEquals(138, persistedStates.last().bpm)
    }

    @Test
    fun barSnapAbLoop_snapsMarkAMarkBDragManualSeekAndCalibratesFirstDownbeat() {
        // Default PracticeState has barSnapEnabled = true!
        engine.bindTrack(
            state = PracticeState(itemId = 88L, bpm = 120, firstDownbeatMs = 200L), // bar = 2000ms
            isVideo = false,
            activatePracticeMode = true,
        )
        assertTrue(engine.practiceState.value.barSnapEnabled)

        // 1. Mark A at 2750ms (inside [2200..4200]) -> snaps to 2200ms
        fakePort.currentPositionMs = 2_750L
        engine.markPointA()
        assertEquals(2_200L, engine.practiceState.value.activeAMs)

        // 2. Mark B at 9950ms -> snaps to nearest bar boundary 10200ms (exact 4 bars = 8000ms!)
        fakePort.currentPositionMs = 9_950L
        engine.markPointBAndLoop()
        assertEquals(2_200L, engine.practiceState.value.activeAMs)
        assertEquals(10_200L, engine.practiceState.value.activeBMs)
        assertEquals(4, engine.practiceState.value.beatGrid().countBarsBetween(2_200L, 10_200L))
        assertEquals(2_200L, fakePort.currentPositionMs)

        // 3. Dragging handle A to 4050ms snaps to 4200ms
        engine.setLoopPointsFromDrag(aMs = 4_050L, bMs = 10_200L, commitPersist = true, draggingHandle = "A")
        assertEquals(4_200L, engine.practiceState.value.activeAMs)
        assertEquals(10_200L, engine.practiceState.value.activeBMs)

        // 4. Manual seek on waveform ruler while loop is active snaps to nearest bar boundary (6450ms -> 6200ms)
        engine.onUserManualSeek(6_450L)
        assertEquals(6_200L, fakePort.currentPositionMs)

        // 5. Calibrate first downbeat to 2500ms -> folds to 500ms and re-snaps active A/B to [4500..10500]
        engine.alignDownbeatToCurrentPosition(posMs = 2_500L)
        assertEquals(500L, engine.practiceState.value.firstDownbeatMs)
        assertEquals(4_500L, engine.practiceState.value.activeAMs)
        assertEquals(10_500L, engine.practiceState.value.activeBMs)

        // 6. Toggle bar-snap off -> raw ms marking works without snapping
        engine.toggleBarSnap()
        assertFalse(engine.practiceState.value.barSnapEnabled)
        engine.markPointA(3_333L)
        assertEquals(3_333L, engine.practiceState.value.activeAMs)
    }

    @Test
    fun beatOnlyCountInToggleAndStemMixPropagation_behaveDeterministically() {
        engine.bindTrack(
            state = PracticeState(itemId = 89L),
            isVideo = false,
            activatePracticeMode = true,
        )
        // Beat-only 1-tap toggle: OFF -> BEAT_4 -> OFF (never SEC_1 or SEC_2)
        assertEquals(CountInMode.OFF, engine.practiceState.value.countInMode)
        engine.cycleCountInMode()
        assertEquals(CountInMode.BEAT_4, engine.practiceState.value.countInMode)
        engine.cycleCountInMode()
        assertEquals(CountInMode.OFF, engine.practiceState.value.countInMode)

        // Legacy SEC_1 / SEC_2 normalize directly to BEAT_4
        engine.setCountInMode(CountInMode.SEC_1)
        assertEquals(CountInMode.BEAT_4, engine.practiceState.value.countInMode)
        assertEquals(CountInMode.BEAT_4, CountInMode.fromStoredValue("SEC_2"))
        assertEquals(CountInMode.OFF, CountInMode.fromStoredValue("OFF"))

        // 5-stem mixer propagation to LoopPlayerPort + auto-analyze callback trigger
        assertTrue(fakePort.lastStemMix.isUnity)
        assertEquals(0, stemSliderActivationCount)

        engine.setStemVolume(StemType.VOCALS, 0.25f)
        assertEquals(0.25f, fakePort.lastStemMix.effectiveGain(StemType.VOCALS), 1e-4f)
        assertEquals(1, stemSliderActivationCount)

        engine.toggleStemMute(StemType.DRUMS)
        assertEquals(0f, fakePort.lastStemMix.effectiveGain(StemType.DRUMS), 1e-4f)
        assertEquals(2, stemSliderActivationCount)

        engine.toggleStemSolo(StemType.GUITAR)
        assertEquals(1.0f, fakePort.lastStemMix.effectiveGain(StemType.GUITAR), 1e-4f)
        assertEquals(0f, fakePort.lastStemMix.effectiveGain(StemType.VOCALS), 1e-4f)
        assertEquals(3, stemSliderActivationCount)

        engine.resetStemMix()
        assertTrue(fakePort.lastStemMix.isUnity)
        assertEquals(3, stemSliderActivationCount)

        // Exiting Practice Mode resets live stem mix parameters to casual bit-transparent defaults
        engine.setStemVolume(StemType.BASS, 1.40f)
        assertFalse(fakePort.lastStemMix.isUnity)
        engine.setPracticeModeActive(false)
        assertTrue(fakePort.lastStemMix.isUnity)
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
        var lastStemMix: StemMixConfig = StemMixConfig()
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

        override fun applyStemMix(stemMix: StemMixConfig) {
            lastStemMix = stemMix
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
