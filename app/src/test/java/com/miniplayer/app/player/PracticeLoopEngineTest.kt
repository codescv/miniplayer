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
import kotlinx.coroutines.asCoroutineDispatcher
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

        // 5. Calibrate first downbeat to 2500ms -> stored UNFOLDED (v1.0.13) and re-snaps active A/B to [4500..10500]
        engine.alignDownbeatToCurrentPosition(posMs = 2_500L)
        assertEquals(2_500L, engine.practiceState.value.firstDownbeatMs)
        assertEquals(BeatGridSource.MANUAL, engine.practiceState.value.beatGridSource)
        assertEquals(4_500L, engine.practiceState.value.activeAMs)
        assertEquals(10_500L, engine.practiceState.value.activeBMs)

        // 6. Toggle bar-snap off -> raw ms marking works without snapping
        engine.toggleBarSnap()
        assertFalse(engine.practiceState.value.barSnapEnabled)
        engine.markPointA(3_333L)
        assertEquals(3_333L, engine.practiceState.value.activeAMs)
        engine.markPointBAndLoop(10_500L)
        assertEquals(3_333L, engine.practiceState.value.activeAMs)
        assertEquals(10_500L, engine.practiceState.value.activeBMs)

        // 7. Re-enable bar-snap and verify that applyAutoDetectedBeatGrid and setBpm
        // automatically re-snap an active A-B loop and reschedule the boundary message!
        engine.toggleBarSnap() // snaps [3333..10500] onto [2500..10500] at 120 BPM (bar=2000ms, downbeat=500ms)
        assertTrue(engine.practiceState.value.barSnapEnabled)
        assertEquals(2_500L, engine.practiceState.value.activeAMs)
        assertEquals(10_500L, engine.practiceState.value.activeBMs)

        // Reset track to default [120 BPM, 0ms] and mark [2000..10000], then simulate auto-detected BeatGrid arriving
        engine.bindTrack(
            state = PracticeState(
                itemId = 90L,
                bpm = 120,
                firstDownbeatMs = 0L,
                barSnapEnabled = true,
                activeAMs = 2_000L,
                activeBMs = 10_000L,
                loopEnabled = true,
            ),
            isVideo = false,
            activatePracticeMode = true,
        )
        // Auto-detected BeatGrid arrives: 100 BPM (bar = 2400ms), firstDownbeatMs = 300ms
        // Nearest bars to 2000ms and 10000ms on 300 + k*2400 grid ([300, 2700, 5100, 7500, 9900]) are 2700ms and 9900ms!
        engine.applyAutoDetectedBeatGrid(
            BeatGrid(bpm = 100, firstBeatMs = 300L, firstDownbeatMs = 300L, beatsPerBar = 4),
            force = false,
        )
        assertEquals(100, engine.practiceState.value.bpm)
        assertEquals(300L, engine.practiceState.value.firstDownbeatMs)
        assertEquals(2_700L, engine.practiceState.value.activeAMs)
        assertEquals(9_900L, engine.practiceState.value.activeBMs)
        assertEquals(9_900L, fakePort.scheduledBoundaryMs)
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

    @Test
    fun beatGridV2_applyAutoDetectedGridIsAtomicAndNeverOverwritesManual() {
        engine.bindTrack(
            state = PracticeState(itemId = 120L, bpm = 128, firstDownbeatMs = 340L), // v1.0.12 row: AUTO_LEGACY
            isVideo = false,
            activatePracticeMode = true,
        )
        persistedStates.clear()
        val mixGrid = BeatGrid(
            bpmPrecise = 120.4,
            firstBeatMs = -250L,
            firstDownbeatMs = -250L,
            beatsPerBar = 3,
            phraseOffsetBars = 1,
            source = BeatGridSource.AUTO_MIX,
        )
        assertTrue(engine.applyAutoDetectedBeatGrid(mixGrid))
        val s1 = engine.practiceState.value
        assertEquals(120.4, s1.bpmPrecise, 1e-9)
        assertEquals(120, s1.bpm)
        assertEquals(-250L, s1.firstDownbeatMs) // unfolded, never clamped / modulo-folded
        assertEquals(3, s1.beatsPerBar)
        assertEquals(1, s1.phraseOffsetBars)
        assertEquals(BeatGridSource.AUTO_MIX, s1.beatGridSource)
        assertEquals("one atomic persisted transition", 1, persistedStates.size)
        // Re-applying the identical grid is a no-op (no persistence churn).
        assertFalse(engine.applyAutoDetectedBeatGrid(mixGrid))
        assertEquals(1, persistedStates.size)

        // AUTO_MIX -> AUTO_STEMS upgrade path.
        val stemsGrid = mixGrid.copy(bpmPrecise = 120.38, firstDownbeatMs = -244L, source = BeatGridSource.AUTO_STEMS)
        assertTrue(engine.applyAutoDetectedBeatGrid(stemsGrid))
        assertEquals(BeatGridSource.AUTO_STEMS, engine.practiceState.value.beatGridSource)
        // ...and a later mix-only analysis never downgrades it unless forced.
        assertFalse(engine.applyAutoDetectedBeatGrid(mixGrid))
        assertEquals(BeatGridSource.AUTO_STEMS, engine.practiceState.value.beatGridSource)

        // Manual BPM edit marks MANUAL; auto grids never overwrite it...
        engine.setBpm(126)
        assertEquals(BeatGridSource.MANUAL, engine.practiceState.value.beatGridSource)
        assertEquals(126.0, engine.practiceState.value.exactBpm, 0.0)
        assertFalse(engine.applyAutoDetectedBeatGrid(stemsGrid.copy(bpmPrecise = 131.0)))
        assertFalse(engine.applyAutoDetectedBeatGrid(stemsGrid, force = false))
        engine.applyAutoDetectedBpm(140)
        assertEquals(126, engine.practiceState.value.bpm)
        assertEquals(BeatGridSource.MANUAL, engine.practiceState.value.beatGridSource)
        // ...except an explicit "重新检测" (force = true).
        assertTrue(engine.applyAutoDetectedBeatGrid(stemsGrid, force = true))
        assertEquals(BeatGridSource.AUTO_STEMS, engine.practiceState.value.beatGridSource)
        assertEquals(120.38, engine.practiceState.value.exactBpm, 1e-9)
    }

    @Test
    fun beatGridV2_shiftNudgeAlignAndAdjustMarkManualWithoutFolding() {
        engine.bindTrack(
            state = PracticeState(itemId = 121L, barSnapEnabled = true)
                .withBpmPrecise(120.0)
                .copy(firstDownbeatMs = 500L, beatGridSource = BeatGridSource.AUTO_MIX, activeAMs = 4_500L, activeBMs = 12_500L, loopEnabled = true),
            isVideo = false,
            activatePracticeMode = true,
        )
        // +2 beats = half-bar flip at 120 BPM (beat = 500 ms); a loop snapped to the old grid moves with it.
        engine.shiftDownbeatByBeats(2)
        var s = engine.practiceState.value
        assertEquals(1_500L, s.firstDownbeatMs)
        assertEquals(BeatGridSource.MANUAL, s.beatGridSource)
        assertEquals(5_500L, s.activeAMs)
        assertEquals(13_500L, s.activeBMs)
        assertEquals(13_500L, fakePort.scheduledBoundaryMs)

        engine.shiftDownbeatByBeats(-1)
        assertEquals(1_000L, engine.practiceState.value.firstDownbeatMs)
        engine.shiftDownbeatByBeats(-1)
        engine.shiftDownbeatByBeats(-1)
        engine.shiftDownbeatByBeats(-1)
        // Anchor may go negative: no clamping, no modulo folding.
        assertEquals(-500L, engine.practiceState.value.firstDownbeatMs)

        engine.nudgeDownbeatMs(10L)
        engine.nudgeDownbeatMs(10L)
        engine.nudgeDownbeatMs(-10L)
        s = engine.practiceState.value
        assertEquals(-490L, s.firstDownbeatMs)
        assertEquals(3_510L, s.activeAMs) // loop followed every edit since it stayed on the grid

        // ±1 BPM keeps the fractional part of a detected tempo.
        engine.applyAutoDetectedBeatGrid(BeatGrid(bpmPrecise = 120.4, firstBeatMs = 0L, firstDownbeatMs = 0L), force = true)
        engine.adjustBpmBy(1)
        assertEquals(121.4, engine.practiceState.value.exactBpm, 1e-9)
        assertEquals(121, engine.practiceState.value.bpm)
        assertEquals(BeatGridSource.MANUAL, engine.practiceState.value.beatGridSource)

        // Align on a detector grid snaps to the nearest existing beat (fixes only the bar phase)...
        engine.applyAutoDetectedBeatGrid(BeatGrid(bpmPrecise = 120.0, firstBeatMs = 100L, firstDownbeatMs = 100L), force = true)
        engine.alignDownbeatToCurrentPosition(posMs = 61_640L) // nearest beat: 100 + 123 * 500 = 61_600
        assertEquals(61_600L, engine.practiceState.value.firstDownbeatMs)
        assertEquals(BeatGridSource.MANUAL, engine.practiceState.value.beatGridSource)
        // ...and is taken verbatim (unfolded) otherwise.
        engine.alignDownbeatToCurrentPosition(posMs = 61_640L, snapToExistingBeat = false)
        assertEquals(61_640L, engine.practiceState.value.firstDownbeatMs)
    }

    @Test
    fun beatGridV2_preciseTempoHasNoLongTrackDriftAndNegativePhraseIndices() {
        // 120.40 BPM at 3:30: integer 120 BPM would drift by ~700 ms; Double math stays exact.
        val grid = PracticeState(itemId = 1L).withBpmPrecise(120.4).copy(firstDownbeatMs = -812L).beatGrid()
        val bar = 4 * 60_000.0 / 120.4
        val boundaries = grid.generateBarBoundaries(210_000L)
        assertTrue(boundaries.first() >= 0L)
        for (t in boundaries) {
            val k = Math.round((t + 812.0) / bar)
            assertEquals(-812.0 + k * bar, t.toDouble(), 0.51)
        }
        assertTrue(grid.isPhraseStart(0))
        assertTrue(grid.isPhraseStart(-4))
        assertFalse(grid.isPhraseStart(-1))
        assertEquals(498.34, CountInMetronome.computePreciseClickIntervalMs(120.4, 1.0f), 0.01)
    }

    @Test
    fun beatGridV2_stemsRegridPolicyFollowsDesign39() {
        val confident = BeatGridConfidence(downbeatMargin = 6f, halfBarAmbiguous = false)
        val weak = BeatGridConfidence(downbeatMargin = 0.5f)
        val state = PracticeState(itemId = 5L).withBpmPrecise(120.0)
            .copy(firstDownbeatMs = 1_000L, beatGridSource = BeatGridSource.AUTO_MIX)
        val beat = 500L
        // Same phase (anchor moved by whole bars + < T/4) and |Δbpm| < 0.3 -> silent.
        val samePhase = BeatGrid(bpmPrecise = 120.2, firstBeatMs = 0L, firstDownbeatMs = 1_000L + 2 * 2_000L + beat / 4 - 5, source = BeatGridSource.AUTO_STEMS)
        assertEquals(StemsRegridDecision.REPLACE_SILENTLY, PracticeLoopEngine.decideStemsRegrid(state, confident, samePhase))
        // Half-bar flip on a confident grid -> offer; on a weak grid -> replace + re-snap.
        val flipped = samePhase.copy(bpmPrecise = 120.0, firstDownbeatMs = 1_000L + 2 * beat)
        assertEquals(StemsRegridDecision.OFFER_TO_USER, PracticeLoopEngine.decideStemsRegrid(state, confident, flipped))
        assertEquals(StemsRegridDecision.REPLACE_AND_RESNAP, PracticeLoopEngine.decideStemsRegrid(state, weak, flipped))
        assertEquals(StemsRegridDecision.REPLACE_AND_RESNAP, PracticeLoopEngine.decideStemsRegrid(state, null, flipped))
        val ambiguous = confident.copy(halfBarAmbiguous = true)
        assertEquals(StemsRegridDecision.REPLACE_AND_RESNAP, PracticeLoopEngine.decideStemsRegrid(state, ambiguous, flipped))
        // Tempo off by >= 0.3 is never silent.
        assertEquals(StemsRegridDecision.OFFER_TO_USER, PracticeLoopEngine.decideStemsRegrid(state, confident, samePhase.copy(bpmPrecise = 120.35)))
        // MANUAL is never touched.
        val manual = state.copy(beatGridSource = BeatGridSource.MANUAL)
        assertEquals(StemsRegridDecision.KEEP_MANUAL, PracticeLoopEngine.decideStemsRegrid(manual, weak, flipped))
    }

    @Test
    fun beatGridV2_serializedPracticeStateWriterConflatesPerItemLatestWins() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val gate = java.util.concurrent.CountDownLatch(1)
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val written = java.util.Collections.synchronizedList(mutableListOf<PracticeState>())
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val writer = com.miniplayer.app.ui.SerializedPracticeStateWriter(scope, dispatcher) { state ->
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                if (written.isEmpty()) {
                    firstStarted.countDown()
                    gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                written += state
                inFlight.decrementAndGet()
            }
            writer.submit(PracticeState(itemId = 1L, bpm = 100))
            assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // While the first write blocks, queue several updates for two items.
            for (bpm in 101..110) writer.submit(PracticeState(itemId = 1L, bpm = bpm))
            writer.submit(PracticeState(itemId = 2L, bpm = 90))
            writer.submit(PracticeState(itemId = -1L, bpm = 77)) // unbound: ignored
            gate.countDown()
            val deadline = System.currentTimeMillis() + 5_000L
            while (written.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(50)
            assertEquals(listOf(100, 110, 90), written.map { it.bpm })
            assertEquals(1, maxInFlight.get())
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
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
