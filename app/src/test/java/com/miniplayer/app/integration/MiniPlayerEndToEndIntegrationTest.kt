package com.miniplayer.app.integration

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.miniplayer.app.core.AppUpdateContract
import com.miniplayer.app.core.FolderScannerContract
import com.miniplayer.app.core.MediaLibraryContract
import com.miniplayer.app.core.MetadataEngineContract
import com.miniplayer.app.core.MiniPlayerCoreProvider
import com.miniplayer.app.core.db.InMemoryMediaStoreBackend
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.metadata.AudioTagCodec
import com.miniplayer.app.core.metadata.DefaultMetadataEngine
import com.miniplayer.app.core.metadata.LrclibClient
import com.miniplayer.app.core.metadata.OnlineHttpClient
import com.miniplayer.app.core.metadata.OnlineMetadataCompleter
import com.miniplayer.app.core.metadata.PhysicalTagWriter
import com.miniplayer.app.core.metadata.RawHttpResponse
import com.miniplayer.app.core.model.AddRootResult
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.LyricsSource
import com.miniplayer.app.core.model.MetadataDraft
import com.miniplayer.app.core.model.MetadataField
import com.miniplayer.app.core.model.MetadataSaveOutcome
import com.miniplayer.app.core.model.PlayContext
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.SpeedTrainerConfig
import com.miniplayer.app.core.model.UpdateInfo
import com.miniplayer.app.core.scanner.ExtractedMediaProbe
import com.miniplayer.app.core.scanner.SafChildEntry
import com.miniplayer.app.core.scanner.SafFolderScanner
import com.miniplayer.app.core.scanner.SafGrantInfo
import com.miniplayer.app.core.scanner.SafTreeDataSource
import com.miniplayer.app.core.scanner.SafVolumeProbe
import com.miniplayer.app.core.update.ApkValidator
import com.miniplayer.app.core.update.AppUpdater
import com.miniplayer.app.core.update.UpdateErrorCode
import com.miniplayer.app.core.update.UpdateException
import com.miniplayer.app.lan.LanJson
import com.miniplayer.app.lan.LanServerConfig
import com.miniplayer.app.lan.LanStudioServer
import com.miniplayer.app.lan.LocalLanStorageGateway
import com.miniplayer.app.player.AbLoopUiState
import com.miniplayer.app.player.CountInMetronome
import com.miniplayer.app.player.LoopPlayerPort
import com.miniplayer.app.player.PracticeLoopEngine
import com.miniplayer.app.player.PreviousButtonOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * End-to-End Cross-Module Integration Suite (`DESIGN.md` & `ARCHITECTURE.md`) verifying real
 * wiring between `core` (`MediaRepository`, `SafFolderScanner`, `DefaultMetadataEngine`,
 * `AudioTagCodec`, `OnlineMetadataCompleter`, `AppUpdater`, `ApkValidator`), `player`
 * (`PracticeLoopEngine`, `CountInMetronome`), and `lan` (`LanStudioServer` + `LocalLanStorageGateway`).
 */
class MiniPlayerEndToEndIntegrationTest {

    private lateinit var tempWorkspace: File
    private lateinit var mediaRootDir: File
    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private lateinit var playerScope: CoroutineScope

    @Before
    fun setUp() {
        tempWorkspace = Files.createTempDirectory("miniplayer_e2e_it_").toFile()
        mediaRootDir = File(tempWorkspace, "Music").apply { mkdirs() }
        cacheDir = File(tempWorkspace, "cache").apply { mkdirs() }
        filesDir = File(tempWorkspace, "files").apply { mkdirs() }
        playerScope = CoroutineScope(SupervisorJob())
    }

    @After
    fun tearDown() {
        playerScope.cancel()
        tempWorkspace.deleteRecursively()
    }

    // -----------------------------------------------------------------------------------------
    // 1. Practice Mode State Machine Auto-Activation & Subfolder Inheritance (§5.1, §6.1 #4)
    // -----------------------------------------------------------------------------------------

    @Test
    fun practiceModeStateMachine_autoActivatesForPracticePlaylistSubfolderAndBookmarkJump_andShowsCasualHint() = runBlocking {
        val env = buildIntegratedEnvironment(
            durationOverrides = mapOf(
                "01_constellation_backing.mp3" to 240_000L,
                "commute_pop.mp3" to 200_000L,
            ),
        )

        // Create directory hierarchy:
        // Music/
        //   Backing/J-Rock/01_constellation_backing.mp3
        //   Backing/Tutorials/solo_breakdown_1080p.mp4
        //   Casual/commute_pop.mp3
        val jrockDir = File(mediaRootDir, "Backing/J-Rock").apply { mkdirs() }
        val tutorialDir = File(mediaRootDir, "Backing/Tutorials").apply { mkdirs() }
        val casualDir = File(mediaRootDir, "Casual").apply { mkdirs() }

        writeSyntheticMp3(File(jrockDir, "01_constellation_backing.mp3"), frameCount = 40)
        File(tutorialDir, "solo_breakdown_1080p.mp4").writeBytes(ByteArray(8192) { (it and 0xFF).toByte() })
        writeSyntheticMp3(File(casualDir, "commute_pop.mp3"), frameCount = 30)

        val addRes = env.scanner.addSafRootFromGrant(env.rootGrant)
        assertTrue(addRes is AddRootResult.Added)
        val rootId = (addRes as AddRootResult.Added).root.rootId

        // Mark `Backing` as `🎯 练习文件夹` and verify descendant subfolder inheritance
        val backingDirUri = File(mediaRootDir, "Backing").toURI().toString()
        env.scanner.setFolderPracticeRule(
            rootId = rootId,
            relPath = "Backing",
            folderUri = backingDirUri,
            isPracticeFolder = true,
        )

        assertTrue(env.repository.isPracticeFolder(rootId, "Backing"))
        assertTrue("Descendant subfolder Backing/J-Rock must inherit 🎯 练习文件夹", env.repository.isPracticeFolder(rootId, "Backing/J-Rock"))
        assertTrue("Descendant subfolder Backing/Tutorials must inherit 🎯 练习文件夹", env.repository.isPracticeFolder(rootId, "Backing/Tutorials"))
        assertFalse("Sibling Casual folder must NOT be marked as practice folder", env.repository.isPracticeFolder(rootId, "Casual"))

        val allItems = env.repository.allMediaItems.value
        assertEquals(3, allItems.size)
        val backingTrack = allItems.first { it.fileName == "01_constellation_backing.mp3" }
        val videoLesson = allItems.first { it.fileName == "solo_breakdown_1080p.mp4" }
        val casualTrack = allItems.first { it.fileName == "commute_pop.mp3" }

        assertTrue(backingTrack.isInPracticeFolder)
        assertTrue(videoLesson.isInPracticeFolder && videoLesson.isVideo)
        assertFalse(casualTrack.isInPracticeFolder)

        // Practice Folder isolation (§v1.0.1 #4):
        // With `Backing` marked as a Practice Folder, `backingTrack` and `videoLesson` MUST NOT appear in
        // `albums`, `artists`, or `videoItems`, while remaining browsable in `browseFolder` (TREE & FLAT)!
        assertTrue("Practice folder video must be excluded from videoItems", env.repository.videoItems.value.isEmpty())
        assertEquals("Only casual track should appear in 未知专辑", 1, env.repository.albums.value.single().trackCount)
        assertEquals(
            listOf(casualTrack.itemId),
            env.repository.getAlbumTracks(AlbumSummary.UNKNOWN_ALBUM_KEY).map { it.itemId },
        )
        assertEquals(
            listOf(casualTrack.itemId),
            env.repository.artists.value.flatMap { art -> env.repository.getArtistDetail(art.artistNorm)!!.tracks.map { it.itemId } },
        )
        assertEquals(2, env.repository.browseFolder(rootId, "Backing", flat = true)!!.mediaItems.size)
        assertEquals(1, env.repository.browseFolder(rootId, "Backing/J-Rock", flat = false)!!.mediaItems.size)

        // Marking root (`relPath = ""`) as a Practice Folder hides casual siblings too,
        // and unmarking both root and `Backing` (`isPracticeFolder = false`) restores all audio & video items!
        env.scanner.setFolderPracticeRule(rootId, "", env.rootGrant.treeUri, isPracticeFolder = true)
        assertTrue(env.repository.albums.value.isEmpty())
        assertTrue(env.repository.artists.value.isEmpty())
        assertTrue(env.repository.videoItems.value.isEmpty())
        assertEquals(3, env.repository.browseFolder(rootId, "", flat = true)!!.mediaItems.size)

        env.scanner.setFolderPracticeRule(rootId, "", env.rootGrant.treeUri, isPracticeFolder = false)
        env.scanner.setFolderPracticeRule(rootId, "Backing", backingDirUri, isPracticeFolder = false)
        assertEquals(1, env.repository.videoItems.value.size)
        assertEquals(2, env.repository.albums.value.single().trackCount)

        // Restore Backing = true for the remainder of the practice state machine scenario
        env.scanner.setFolderPracticeRule(rootId, "Backing", backingDirUri, isPracticeFolder = true)

        // Persist PracticeState (0.80x speed, -1 semitone) and 2 Section Bookmarks (`Intro`, `Solo 1`)
        env.repository.savePracticeState(
            PracticeState(
                itemId = backingTrack.itemId,
                practiceSpeed = 0.80f,
                pitchSemitones = -1,
                pitchCents = 0,
                bpm = 132,
            ),
        )
        env.repository.saveBookmark(
            itemId = backingTrack.itemId,
            label = "Intro",
            aMs = 0L,
            bMs = 18_000L,
            savedSpeed = 0.90f,
        )
        val soloBookmark = env.repository.saveBookmark(
            itemId = backingTrack.itemId,
            label = "Solo 1",
            aMs = 134_000L,
            bMs = 158_000L,
            savedSpeed = 0.75f,
        )

        // Create `🎯 练习列表` mixing audio backing + video lesson
        val practicePlaylistId = env.repository.createPlaylist("本周乐队排练", isPracticePlaylist = true)
        env.repository.addItemsToPlaylist(practicePlaylistId, listOf(backingTrack.itemId, videoLesson.itemId))
        val playlistDetail = env.repository.getPlaylistDetail(practicePlaylistId)
        assertNotNull(playlistDetail)
        assertTrue(playlistDetail!!.summary.isPracticePlaylist)
        assertEquals(2, playlistDetail.summary.totalBookmarks)

        // Verify `[🔖 含段落书签]` reactive query surfaces the track & bookmarks
        val bookmarkedTracks = env.repository.bookmarkedTracks.value
        assertEquals(1, bookmarkedTracks.size)
        assertEquals(2, bookmarkedTracks.first().bookmarks.size)

        val port = RecordingLoopPlayerPort(durationMs = 240_000L)
        val metronome = CountInMetronome(playerScope)
        val loopEngine = PracticeLoopEngine(
            port = port,
            metronome = metronome,
            onStatePersisted = { state -> runBlocking { env.repository.savePracticeState(state) } },
        )
        port.attachedEngine = loopEngine
        // Count-in click sound must be enabled by default in both AppSettings and PracticeLoopEngine (§v1.0.1 #2 & #7)
        assertTrue(env.repository.settings.value.countInClickSound)
        assertTrue(loopEngine.isClickSoundEnabled)
        loopEngine.setClickSoundEnabled(false)

        // Case A: Playing from `🎯 练习列表` (`PlayContext.PRACTICE_PLAYLIST`) -> auto-activates Practice Mode
        assertTrue(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_PLAYLIST))
        loopEngine.bindTrack(
            state = env.repository.getPracticeState(backingTrack.itemId),
            isVideo = backingTrack.isVideo,
            activatePracticeMode = PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_PLAYLIST),
        )
        assertTrue(loopEngine.isPracticeModeActive.value)
        assertEquals(0.80f, port.lastSpeed, 0.001f)
        assertEquals(PracticeMath.computePitchFactor(-1, 0), port.lastPitch, 0.0001f)

        // Case B: Playing unpracticed video from `🎯 练习文件夹` (`PlayContext.PRACTICE_FOLDER`) -> auto-activates at 1.0x/0st ready to mark A
        loopEngine.bindTrack(
            state = env.repository.getPracticeState(videoLesson.itemId),
            isVideo = videoLesson.isVideo,
            activatePracticeMode = PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_FOLDER),
        )
        assertTrue(loopEngine.isPracticeModeActive.value)
        assertEquals(AbLoopUiState.IDLE, loopEngine.abUiState)
        assertEquals(1.0f, port.lastSpeed, 0.001f)
        assertEquals(1.0f, port.lastPitch, 0.001f)

        // Case C: Tapping `Solo 1` from `[🔖 含段落书签]` (`PlayContext.BOOKMARK_JUMP`) -> jumps to A (134s), arms B (158s), applies 0.75x
        loopEngine.bindTrack(
            state = env.repository.getPracticeState(backingTrack.itemId),
            isVideo = false,
            activatePracticeMode = PracticeMath.shouldAutoActivatePracticeMode(PlayContext.BOOKMARK_JUMP),
            jumpBookmark = soloBookmark,
            autoTriggerInitialLoop = true,
        )
        assertTrue(loopEngine.isPracticeModeActive.value)
        assertEquals(134_000L, port.currentPositionMs)
        assertEquals(158_000L, port.scheduledBoundaryMs)
        assertEquals(0.75f, port.lastSpeed, 0.001f)

        // Case D: Playing the SAME track from Casual Album/Folder view (`PlayContext.CASUAL`)
        // -> stays in Casual Mode (1.0x, 1.0f pitch, no active loop), while MediaItem exposes hint metadata (`含 2 段`)!
        val refreshedTrack = env.repository.allMediaItems.value.first { it.itemId == backingTrack.itemId }
        assertTrue(refreshedTrack.hasPracticeRecord)
        assertEquals(2, refreshedTrack.bookmarkCount)
        assertFalse(PracticeMath.shouldAutoActivatePracticeMode(PlayContext.CASUAL))

        loopEngine.bindTrack(
            state = env.repository.getPracticeState(refreshedTrack.itemId),
            isVideo = false,
            activatePracticeMode = PracticeMath.shouldAutoActivatePracticeMode(PlayContext.CASUAL),
        )
        assertFalse(loopEngine.isPracticeModeActive.value)
        assertNull("Casual mode must not arm A-B PlayerMessage", port.scheduledBoundaryMs)
        // One-tap `[🎯 练习模式 · 含 2 段]` pill immediately restores saved speed/pitch/loop!
        loopEngine.setPracticeModeActive(true)
        assertTrue(loopEngine.isPracticeModeActive.value)
        assertEquals(0.75f, port.lastSpeed, 0.001f)
        assertEquals(158_000L, port.scheduledBoundaryMs)
    }

    // -----------------------------------------------------------------------------------------
    // 2. A-B Loop Transitions, ±0.5s/±0.1s Nudge, [↺ 回A], Suspend, Speed Trainer, 4-Beat BPM & Pitch Math
    // -----------------------------------------------------------------------------------------

    @Test
    fun abLoopNudgeRestartSuspendSpeedTrainerFourBeatCountInAndPitchMath_endToEnd() = runBlocking {
        val port = RecordingLoopPlayerPort(durationMs = 200_000L)
        val metronome = CountInMetronome(playerScope)
        val persisted = ArrayList<PracticeState>()
        val engine = PracticeLoopEngine(
            port = port,
            metronome = metronome,
            onStatePersisted = { persisted.add(it) },
        )
        port.attachedEngine = engine
        engine.setClickSoundEnabled(false)

        engine.bindTrack(
            state = PracticeState(itemId = 99L, bpm = 120),
            isVideo = false,
            activatePracticeMode = true,
        )

        // 1. Mark A at 20.0s, then mark B earlier at 10.0s -> auto-swaps to [10.0s, 20.0s]
        port.currentPositionMs = 20_000L
        engine.markPointA()
        assertEquals(AbLoopUiState.A_SET_WAITING, engine.abUiState)

        port.currentPositionMs = 10_000L
        engine.markPointBAndLoop()
        assertEquals(AbLoopUiState.ACTIVE_OR_SUSPENDED, engine.abUiState)
        assertEquals(10_000L, engine.practiceState.value.activeAMs)
        assertEquals(20_000L, engine.practiceState.value.activeBMs)
        assertEquals(20_000L, port.scheduledBoundaryMs)

        // 2. Coarse (±0.5s) and Fine (±0.1s) nudges on A and B
        engine.nudgePointA(-PracticeMath.COARSE_NUDGE_MS) // 9_500L
        engine.nudgePointA(PracticeMath.FINE_NUDGE_MS)    // 9_600L
        engine.nudgePointB(PracticeMath.COARSE_NUDGE_MS)  // 20_500L
        engine.nudgePointB(-PracticeMath.FINE_NUDGE_MS)   // 20_400L
        assertEquals(9_600L, engine.practiceState.value.activeAMs)
        assertEquals(20_400L, engine.practiceState.value.activeBMs)
        assertEquals(20_400L, port.scheduledBoundaryMs)

        // 3. `[↺ 回A]` jumps immediately back to 9_600L without waiting for B
        port.currentPositionMs = 16_200L
        engine.restartFromA()
        assertEquals(9_600L, port.currentPositionMs)

        // Single-tap `⏮` restarts from A; double-tap within 400ms escapes to 00:00
        port.currentPositionMs = 15_000L
        assertEquals(PreviousButtonOutcome.RESTARTED_FROM_A, engine.handlePreviousButtonTap(nowElapsedMs = 5_000L))
        assertEquals(9_600L, port.currentPositionMs)
        assertEquals(PreviousButtonOutcome.JUMPED_TO_TRACK_START, engine.handlePreviousButtonTap(nowElapsedMs = 5_250L))
        assertEquals(0L, port.currentPositionMs)

        // 4. `[循环: 开/挂起]` suspends loop without losing A/B coordinates, then resumes cleanly
        engine.toggleLoopSuspended()
        assertFalse(engine.practiceState.value.loopEnabled)
        assertNull(port.scheduledBoundaryMs)
        assertFalse(port.abLoopOwnsRepeatSnapshot)
        assertEquals(9_600L, engine.practiceState.value.activeAMs)
        assertEquals(20_400L, engine.practiceState.value.activeBMs)

        engine.toggleLoopSuspended()
        assertTrue(engine.practiceState.value.loopEnabled)
        assertEquals(20_400L, port.scheduledBoundaryMs)
        assertTrue(port.abLoopOwnsRepeatSnapshot)

        // 5. Speed Trainer: start 0.60x -> target 0.70x, +0.05x every 2 loops, manual nudge resets loop counter
        engine.configureSpeedTrainer(
            SpeedTrainerConfig(
                enabled = true,
                startSpeed = 0.60f,
                targetSpeed = 0.70f,
                stepSpeed = 0.05f,
                loopsPerStep = 2,
            ),
        )
        assertEquals(0.60f, port.lastSpeed, 0.001f)
        port.fireBoundary() // loop 1/2 at 0.60x
        assertEquals(1, engine.speedTrainer.value.currentLoopInStep)
        engine.stepPracticeSpeed(-0.05f) // manual change to 0.55x resets currentLoopInStep to 0
        assertEquals(0, engine.speedTrainer.value.currentLoopInStep)
        assertEquals(0.55f, port.lastSpeed, 0.001f)

        port.fireBoundary() // 1/2
        port.fireBoundary() // 2/2 -> steps up +0.05x to 0.60x
        assertEquals(0.60f, port.lastSpeed, 0.001f)
        port.fireBoundary()
        port.fireBoundary() // -> 0.65x
        port.fireBoundary()
        port.fireBoundary() // -> 0.70x (target reached)
        assertEquals(0.70f, port.lastSpeed, 0.001f)

        // 6. `4拍` Count-In BPM scaling: `round(60000 / (bpm * speed))` & Tap Tempo median
        // 120 BPM @ 0.75x =等效 90 BPM -> 667 ms per beat; 150 BPM @ 0.80x = 500 ms per beat
        assertEquals(667L, PracticeMath.computeClickIntervalMs(bpm = 120, practiceSpeed = 0.75f))
        assertEquals(500L, PracticeMath.computeClickIntervalMs(bpm = 150, practiceSpeed = 0.80f))
        val tappedBpm = PracticeMath.computeTapTempoBpm(listOf(1000L, 1500L, 2000L, 2500L))
        assertEquals(120, tappedBpm)

        engine.setPracticeSpeed(0.75f)
        engine.setBpm(120)
        engine.setCountInMode(CountInMode.BEAT_4)
        engine.restartFromA()
        assertTrue(metronome.isRunning)
        waitUntil(timeoutMs = 1_000L) { metronome.progress.value != null }
        assertEquals(CountInMode.BEAT_4, metronome.progress.value?.mode)
        assertEquals(4, metronome.progress.value?.totalBeats)
        metronome.skipImmediately()
        assertFalse(metronome.isRunning)
        assertTrue(port.isPlaying)

        // 7. Semitone (-6..+6) + Cents (-50..+50) Sonic `PlaybackParameters` math
        engine.setPitchShift(semitones = -1, cents = -25) // Eb Standard tuning + -25 cents vintage calibration
        val expectedPitch = 2.0.pow((-1.0 + (-25.0 / 100.0)) / 12.0).toFloat()
        assertEquals(expectedPitch, port.lastPitch, 0.0001f)
        assertEquals(0.75f, port.lastSpeed, 0.0001f)
    }

    // -----------------------------------------------------------------------------------------
    // 3. Album/Artist Grouping + `未知专辑` + Dual-Layer Tag Editor + Zero-Key Online Auto-Completion
    // -----------------------------------------------------------------------------------------

    @Test
    fun albumArtistGroupingUnknownAlbumDualLayerTagWriteBackAndZeroKeyOnlineCompletion_endToEnd() = runBlocking {
        // Mock zero-key HTTP pipeline:
        // - MusicBrainz returns official release (`rel-continuum`)
        // - CoverArtArchive stalls > 3.0s (3_350ms), triggering strict 3s fallback to iTunes 1000x1000bb CDN!
        // - LRCLIB returns `[00:14.20]` synced `.lrc` lyrics
        val fakeOnlineHttp = object : OnlineHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
                maxBytes: Int,
                headOnly: Boolean,
                totalTimeoutMs: Long,
            ): RawHttpResponse {
                return when {
                    url.contains("musicbrainz.org/ws/2/recording") -> {
                        assertTrue("MusicBrainz request must carry custom User-Agent", headers["User-Agent"]?.contains("MiniPlayer") == true)
                        assertFalse("Backing suffix must be stripped from online search query", url.lowercase(Locale.ROOT).contains("backing"))
                        RawHttpResponse(
                            statusCode = 200,
                            finalUrl = url,
                            contentType = "application/json",
                            body = """
                                {
                                  "recordings": [
                                    {
                                      "id": "rec-neon-1",
                                      "title": "Neon",
                                      "length": 262000,
                                      "artist-credit": [{"name": "John Mayer"}],
                                      "releases": [
                                        {
                                          "id": "rel-room-for-squares",
                                          "title": "Room for Squares",
                                          "status": "Official",
                                          "date": "2001-06-05",
                                          "media": [{"track": [{"number": "4"}]}]
                                        }
                                      ]
                                    }
                                  ]
                                }
                            """.trimIndent().toByteArray(Charsets.UTF_8),
                        )
                    }
                    url.contains("coverartarchive.org") -> {
                        // Simulate slow archive.org redirect exceeding 3.0s timeout budget
                        delay(3_350L)
                        RawHttpResponse(statusCode = 504, finalUrl = url, contentType = "text/plain", body = ByteArray(0))
                    }
                    url.contains("itunes.apple.com/search") -> RawHttpResponse(
                        statusCode = 200,
                        finalUrl = url,
                        contentType = "application/json",
                        body = """
                            {
                              "resultCount": 1,
                              "results": [
                                {
                                  "trackName": "Neon",
                                  "artistName": "John Mayer",
                                  "collectionName": "Room for Squares",
                                  "collectionArtistName": "John Mayer",
                                  "trackNumber": 4,
                                  "releaseDate": "2001-06-05T07:00:00Z",
                                  "trackTimeMillis": 262000,
                                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/neon/100x100bb.jpg"
                                }
                              ]
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                    url.contains("lrclib.net/api/") -> RawHttpResponse(
                        statusCode = 200,
                        finalUrl = url,
                        contentType = "application/json",
                        body = """
                            {
                              "id": 404,
                              "trackName": "Neon",
                              "artistName": "John Mayer",
                              "albumName": "Room for Squares",
                              "duration": 262.0,
                              "syncedLyrics": "[00:14.20] When sky blue gets dark enough\n[00:18.50] To see the colors of the city",
                              "plainLyrics": "When sky blue gets dark enough"
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                    url.contains("mzstatic.com") -> RawHttpResponse(
                        statusCode = 200,
                        finalUrl = url,
                        contentType = "image/jpeg",
                        body = buildFakeJpeg(2048),
                    )
                    else -> RawHttpResponse(statusCode = 404, finalUrl = url, contentType = "text/plain", body = ByteArray(0))
                }
            }
        }

        val env = buildIntegratedEnvironment(
            onlineHttp = fakeOnlineHttp,
            durationOverrides = mapOf(
                "[320k] 04. John Mayer - Neon (Backing Track).mp3" to 262_000L,
                "01 - Collaboration.mp3" to 210_000L,
                "02 - Back in Black.mp3" to 255_000L,
            ),
        )

        val backingMp3 = File(mediaRootDir, "[320k] 04. John Mayer - Neon (Backing Track).mp3")
        val collabMp3 = File(mediaRootDir, "01 - Collaboration.mp3")
        val bandMp3 = File(mediaRootDir, "02 - Back in Black.mp3")
        writeSyntheticMp3(backingMp3, frameCount = 45, durationOverrideMs = 262_000L)
        writeSyntheticMp3(collabMp3, frameCount = 35, durationOverrideMs = 210_000L)
        writeSyntheticMp3(bandMp3, frameCount = 35, durationOverrideMs = 255_000L)

        env.scanner.addSafRootFromGrant(env.rootGrant)

        // Initially all 3 untagged MP3s land in `未知专辑` at the tail of `albums`
        val initialAlbums = env.repository.albums.value
        assertEquals(1, initialAlbums.size)
        assertTrue(initialAlbums.single().isUnknownAlbum)
        assertEquals(AlbumSummary.UNKNOWN_ALBUM_TITLE, initialAlbums.single().title)
        assertEquals(3, initialAlbums.single().trackCount)

        val itemsByName = env.repository.allMediaItems.value.associateBy { it.fileName }
        val neonItem = itemsByName.getValue(backingMp3.name)
        val collabItem = itemsByName.getValue(collabMp3.name)
        val bandItem = itemsByName.getValue(bandMp3.name)
        assertTrue("SmartFilenameParser must flag `(Backing Track)`", neonItem.isBackingTrack)

        // Stage A: Zero-Key Online Auto-Completion with 3.0s CAA fallback -> iTunes 1000x1000bb + LRCLIB
        val startMs = System.currentTimeMillis()
        val candidates = env.metadata.searchOnlineCandidates(neonItem)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("Online search must return high-confidence candidates", candidates.isNotEmpty())
        assertTrue("CAA 3.0s timeout guard must prevent hanging (elapsed=${elapsedMs}ms)", elapsedMs < 5_500L)

        val best = candidates.first()
        assertEquals("Neon", best.title)
        assertEquals("John Mayer", best.artist)
        assertEquals("Room for Squares", best.album)
        assertTrue(
            "CAA timeout must seamlessly fall back to iTunes 1000x1000bb CDN",
            best.coverUrl?.contains("1000x1000bb.jpg") == true,
        )
        assertTrue("LRCLIB synced lyrics must be attached", best.syncedLrc?.contains("[00:14.20]") == true)

        // Apply candidate while `neonItem` is currently playing -> Layer 1 commits instantly, Layer 2 defers file write!
        val applyOutcome = env.metadata.applyCandidate(
            itemId = neonItem.itemId,
            candidate = best,
            preserveBackingSuffix = true,
            writeBackToFile = true,
            currentlyPlayingItemId = neonItem.itemId,
        )
        assertEquals(MetadataSaveOutcome.SAVED_DB_DEFERRED_FILE, applyOutcome)

        val updatedNeon = env.repository.allMediaItems.value.first { it.itemId == neonItem.itemId }
        assertTrue("`preserveBackingSuffix = true` must keep (Backing Track) in title", updatedNeon.title.contains("Backing Track"))
        assertEquals("Room for Squares", updatedNeon.album)
        assertNull("Physical MP3 must NOT be rewritten while actively playing", AudioTagCodec.readTags(backingMp3).album)

        // Lyrics must be cached locally in `lyrics_cache`
        val cachedLyrics = env.metadata.getLyrics(updatedNeon, fetchOnlineIfMissing = false)
        assertNotNull(cachedLyrics)
        assertEquals(LyricsSource.LRCLIB, cachedLyrics!!.source)
        assertEquals(2, cachedLyrics.lines.size)

        // Simulate track switch (`savePlaybackPosition` on next track) -> flushes deferred ID3v2.4 + APIC write to MP3!
        env.repository.savePlaybackPosition(collabItem.itemId, 1_000L)
        waitUntil(timeoutMs = 2_000L) {
            AudioTagCodec.readTags(backingMp3).album == "Room for Squares"
        }
        val diskTags = AudioTagCodec.readTags(backingMp3)
        assertEquals("John Mayer", diskTags.artist)
        assertEquals("Room for Squares", diskTags.album)
        assertEquals(4, diskTags.trackNumber)
        assertNotNull("Downloaded 1000x1000 cover must be embedded into ID3v2.4 APIC", diskTags.coverBytes)

        // Stage B: Multi-Artist Splitting + Band Allowlist + Batch Dirty-Field Lock
        env.metadata.saveMetadata(
            itemIds = listOf(collabItem.itemId),
            draft = MetadataDraft(
                title = "星座になれたら",
                artist = "结束バンド feat. 喜多郁代 / 山田リョウ",
                album = "结束バンド",
                albumArtist = "结束バンド",
                trackNumber = 12,
                year = 2022,
                writeBackToFile = true,
            ),
            currentlyPlayingItemId = null,
        )
        env.metadata.saveMetadata(
            itemIds = listOf(bandItem.itemId),
            draft = MetadataDraft(
                title = "Back in Black",
                artist = "AC/DC",
                album = "Back in Black",
                albumArtist = "AC/DC",
                trackNumber = 6,
                year = 1980,
                writeBackToFile = false,
            ),
            currentlyPlayingItemId = null,
        )

        // Batch edit both tracks' Year & AlbumArtist: Title and Track# MUST remain untouched (`dirtyFields` lock)
        env.metadata.saveMetadata(
            itemIds = listOf(collabItem.itemId, bandItem.itemId),
            draft = MetadataDraft(
                title = "SHOULD_NEVER_OVERWRITE_BATCH_TITLES",
                trackNumber = 99,
                year = 2026,
                dirtyFields = setOf(MetadataField.TITLE, MetadataField.TRACK_NUMBER, MetadataField.YEAR),
                writeBackToFile = false,
            ),
            currentlyPlayingItemId = null,
        )
        val afterBatchCollab = env.repository.allMediaItems.value.first { it.itemId == collabItem.itemId }
        val afterBatchBand = env.repository.allMediaItems.value.first { it.itemId == bandItem.itemId }
        assertEquals("星座になれたら", afterBatchCollab.title)
        assertEquals(12, afterBatchCollab.trackNumber)
        assertEquals(2026, afterBatchCollab.year)
        assertEquals("Back in Black", afterBatchBand.title)
        assertEquals(6, afterBatchBand.trackNumber)
        assertEquals(2026, afterBatchBand.year)

        // Verify Artist auto-grouping: `AC/DC` stayed intact while `结束バンド / 喜多郁代 / 山田リョウ` split into 3 artist entries
        val artistNames = env.repository.artists.value.map { it.displayName }.toSet()
        assertTrue("AC/DC must not be split on '/'", "AC/DC" in artistNames)
        assertTrue("结束バンド" in artistNames && "喜多郁代" in artistNames && "山田リョウ" in artistNames)

        // All 3 tracks now have albums, so `未知专辑` bucket disappears cleanly
        assertTrue(env.repository.albums.value.none { it.isUnknownAlbum })
        assertEquals(3, env.repository.albums.value.size)
    }

    // -----------------------------------------------------------------------------------------
    // 4. In-App GitHub Auto-Updater + ApkValidator Security Invariants (§6)
    // -----------------------------------------------------------------------------------------

    @Test
    fun inAppGitHubAutoUpdaterAndApkValidator_loopbackServerRedirectsSha256AndDowngradeRejection() {
        val apkBytes = buildDummyApkZip("MiniPlayer-v1.1.0-Payload")
        val apkFileForHash = File(cacheDir, "seed.apk").apply { writeBytes(apkBytes) }
        val validSha256 = AppUpdater.sha256HexOfFile(apkFileForHash)

        MockLoopbackServer().use { mockServer ->
            val base = mockServer.baseUrl
            val shaSumsBody = "$validSha256  MiniPlayer-v1.1.0.apk\n"

            mockServer.handler = { path ->
                when (path) {
                    "/releases/latest" -> MockReply(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """
                            {
                              "tag_name": "v1.1.0",
                              "prerelease": false,
                              "published_at": "2026-10-31T04:00:00Z",
                              "body": "### What's New\n- Guitar Practice Speed Trainer",
                              "assets": [
                                {
                                  "name": "MiniPlayer-v1.1.0.apk",
                                  "size": ${apkBytes.size},
                                  "browser_download_url": "$base/hop1"
                                },
                                {
                                  "name": "SHA256SUMS.txt",
                                  "size": ${shaSumsBody.toByteArray().size},
                                  "browser_download_url": "$base/SHA256SUMS.txt"
                                }
                              ]
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                    "/SHA256SUMS.txt" -> MockReply(200, mapOf("Content-Type" to "text/plain"), shaSumsBody.toByteArray())
                    "/hop1" -> MockReply(302, mapOf("Location" to "$base/hop2"))
                    "/hop2" -> MockReply(307, mapOf("Location" to "$base/cdn/MiniPlayer-v1.1.0.apk"))
                    "/cdn/MiniPlayer-v1.1.0.apk" -> MockReply(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/vnd.android.package-archive"),
                        body = apkBytes,
                    )
                    "/too-many-hops" -> MockReply(302, mapOf("Location" to "$base/too-many-hops"))
                    else -> MockReply(404)
                }
            }

            val updater = AppUpdater(
                latestUrl = "$base/releases/latest",
                listUrl = "$base/releases",
                allowInsecureLoopback = true,
            )

            // 1. Check `/releases/latest` + `SHA256SUMS.txt` extraction
            val check = updater.checkLatestRelease(currentVersion = "1.0.0")
            assertTrue(check.updateAvailable)
            assertEquals("v1.1.0", check.latest.tagName)
            assertEquals("MiniPlayer-v1.1.0.apk", check.apk?.name)
            assertEquals(validSha256, check.expectedSha256)

            val certA = ApkValidator.digestSignatureBytes(listOf("miniplayer-release-signing-cert-v1".toByteArray()))
            val certEvil = ApkValidator.digestSignatureBytes(listOf("attacker-self-signed-cert".toByteArray()))

            // 2. Multi-hop redirect (`302 -> 307 -> 200`) + SHA-256 + ApkValidator happy path
            val destApk = File(cacheDir, "updates/MiniPlayer-v1.1.0.apk")
            val downloaded = updater.downloadApk(
                url = check.apk!!.url,
                dest = destApk,
                expectedSize = check.apk!!.size,
                expectedSha256 = check.expectedSha256,
                validator = {
                    ApkValidator.validateParsedMetadata(
                        expectedPackageName = ApkValidator.EXPECTED_APPLICATION_ID,
                        installedVersionCode = 10_000L,
                        installedCertDigests = certA,
                        candidatePackageName = "com.miniplayer.app",
                        candidateVersionCode = 10_100L,
                        candidateCertDigests = certA,
                    )
                },
            )
            assertTrue(downloaded.isFile)
            assertArrayEquals(apkBytes, downloaded.readBytes())
            assertFalse("Temporary .tmp file must not linger", File(destApk.path + ".tmp").exists())

            // 3. Tampered SHA-256 must fail with CORRUPT_DOWNLOAD and clean up `.tmp`
            val badDest = File(cacheDir, "updates/tampered.apk")
            try {
                updater.downloadApk(
                    url = check.apk!!.url,
                    dest = badDest,
                    expectedSize = check.apk!!.size,
                    expectedSha256 = "0".repeat(64),
                )
                fail("Expected SHA-256 mismatch to throw UpdateException")
            } catch (e: UpdateException) {
                assertEquals(UpdateErrorCode.CORRUPT_DOWNLOAD, e.code)
                assertFalse(badDest.exists())
                assertFalse(File(badDest.path + ".tmp").exists())
            }

            // 4. Exceeding 5 redirect hops must be rejected
            try {
                updater.downloadApk(url = "$base/too-many-hops", dest = File(cacheDir, "updates/loop.apk"))
                fail("Expected >5 redirects to be rejected")
            } catch (e: UpdateException) {
                assertEquals(UpdateErrorCode.NETWORK, e.code)
            }

            // 5. HTTPS -> HTTP downgrade & plain HTTP when allowInsecureLoopback = false must be blocked
            val prodUpdater = AppUpdater(allowInsecureLoopback = false)
            try {
                prodUpdater.downloadApk(url = "http://api.github.com/evil.apk", dest = File(cacheDir, "updates/http.apk"))
                fail("Expected cleartext HTTP URL to be rejected in production mode")
            } catch (e: UpdateException) {
                assertEquals(UpdateErrorCode.INSECURE_URL, e.code)
            }

            // 6. ApkValidator strictly blocks versionCode downgrade/replay, package mismatch, and cert mismatch
            assertThrowsVerifyFailed {
                ApkValidator.validateParsedMetadata(
                    expectedPackageName = "com.miniplayer.app",
                    installedVersionCode = 10_000L,
                    installedCertDigests = certA,
                    candidatePackageName = "com.miniplayer.app",
                    candidateVersionCode = 10_000L, // replay / non-upgrade!
                    candidateCertDigests = certA,
                )
            }
            assertThrowsVerifyFailed {
                ApkValidator.validateParsedMetadata(
                    expectedPackageName = "com.miniplayer.app",
                    installedVersionCode = 10_000L,
                    installedCertDigests = certA,
                    candidatePackageName = "com.miniplayer.app",
                    candidateVersionCode = 9_999L, // downgrade!
                    candidateCertDigests = certA,
                )
            }
            assertThrowsVerifyFailed {
                ApkValidator.validateParsedMetadata(
                    expectedPackageName = "com.miniplayer.app",
                    installedVersionCode = 10_000L,
                    installedCertDigests = certA,
                    candidatePackageName = "com.attacker.trojan",
                    candidateVersionCode = 20_000L,
                    candidateCertDigests = certA,
                )
            }
            assertThrowsVerifyFailed {
                ApkValidator.validateParsedMetadata(
                    expectedPackageName = "com.miniplayer.app",
                    installedVersionCode = 10_000L,
                    installedCertDigests = certA,
                    candidatePackageName = "com.miniplayer.app",
                    candidateVersionCode = 10_100L,
                    candidateCertDigests = certEvil,
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 5. Live PC LAN Web Studio <-> Real Core Engine <-> Phone Practice Engine Integration (§4.5, §7)
    // -----------------------------------------------------------------------------------------

    @Test
    fun lanWebStudioToPhoneCoreAndPracticeEngine_liveCrossComponentIntegration() = runBlocking {
        val env = buildIntegratedEnvironment(
            durationOverrides = mapOf("little_wing_backing.mp3" to 200_000L),
        )
        val addRes = env.scanner.addSafRootFromGrant(env.rootGrant) as AddRootResult.Added
        val rootId = addRes.root.rootId

        val lanServer = LanStudioServer(
            config = LanServerConfig(port = 0, bindAddress = "127.0.0.1"),
            core = env.coreProvider,
            storage = LocalLanStorageGateway(mapOf(rootId to mediaRootDir)),
        )
        lanServer.start()
        try {
            val lanBase = "http://127.0.0.1:${lanServer.boundPort}"

            // 1. Wireless upload a real MP3 backing track from PC browser -> auto-rescans into real MediaRepository!
            val mp3Bytes = buildSyntheticMp3Bytes(frameCount = 50)
            val uploadConn = (URL("$lanBase/api/upload?rootId=$rootId&fileName=little_wing_backing.mp3").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "audio/mpeg")
            }
            uploadConn.outputStream.use { it.write(mp3Bytes) }
            assertTrue("Expected 200/201 Created from /api/upload", uploadConn.responseCode in 200..201)
            uploadConn.disconnect()

            val uploadedItem = env.repository.allMediaItems.value.firstOrNull { it.fileName == "little_wing_backing.mp3" }
            assertNotNull("Uploaded MP3 must immediately appear in phone MediaRepository", uploadedItem)

            // 2. Edit metadata & create `🎯 练习列表` + `Solo 1` A-B bookmark + 0.75x/-1st practice state via PC Web Studio REST API
            postJson(
                "$lanBase/api/metadata/update",
                """{"itemId":${uploadedItem!!.itemId},"title":"Little Wing (Backing)","artist":"Jimi Hendrix","album":"Axis: Bold as Love","year":1967}""",
            )
            val plJson = LanJson.parseObject(
                postJson(
                    "$lanBase/api/playlists",
                    """{"name":"PC 编排练习曲单","isPracticePlaylist":true,"itemIds":[${uploadedItem.itemId}]}""",
                ),
            )
            assertTrue(plJson.bool("ok"))

            postJson(
                "$lanBase/api/practice/state",
                """{"itemId":${uploadedItem.itemId},"practiceSpeed":0.75,"pitchSemitones":1,"bpm":70,"loopEnabled":true,"activeAMs":12000,"activeBMs":28000}""",
            )
            postJson(
                "$lanBase/api/practice/bookmark",
                """{"itemId":${uploadedItem.itemId},"label":"Intro Chords","aMs":12000,"bMs":28000,"savedSpeed":0.75}""",
            )

            // 3. Stream HTTP `206 Partial Content` byte range to verify PC `<audio>` scrubbing against the uploaded file
            val rangeConn = (URL("$lanBase/api/stream?itemId=${uploadedItem.itemId}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=100-499")
            }
            assertEquals(206, rangeConn.responseCode)
            val slice = rangeConn.inputStream.use { it.readBytes() }
            rangeConn.disconnect()
            assertEquals(400, slice.size)
            assertArrayEquals(mp3Bytes.copyOfRange(100, 500), slice)

            // 4. Open the PC-created `🎯 练习列表` on the phone -> PracticeLoopEngine auto-activates 0.75x, +1st, and [12s..28s] loop!
            val phonePlaylists = env.repository.playlists.value
            assertEquals(1, phonePlaylists.size)
            assertTrue(phonePlaylists.single().isPracticePlaylist)

            val savedBookmarks = env.repository.getBookmarks(uploadedItem.itemId)
            assertEquals(1, savedBookmarks.size)
            assertEquals("Intro Chords", savedBookmarks.single().label)

            val port = RecordingLoopPlayerPort(durationMs = 200_000L)
            val metronome = CountInMetronome(playerScope)
            val loopEngine = PracticeLoopEngine(port = port, metronome = metronome, onStatePersisted = {})
            port.attachedEngine = loopEngine
            loopEngine.bindTrack(
                state = env.repository.getPracticeState(uploadedItem.itemId),
                isVideo = false,
                activatePracticeMode = PracticeMath.shouldAutoActivatePracticeMode(PlayContext.PRACTICE_PLAYLIST),
            )
            assertTrue(loopEngine.isPracticeModeActive.value)
            assertEquals(0.75f, port.lastSpeed, 0.001f)
            assertEquals(PracticeMath.computePitchFactor(1, 0), port.lastPitch, 0.0001f)
            assertEquals(28_000L, port.scheduledBoundaryMs)
        } finally {
            lanServer.stop()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test Harness Helpers
    // -----------------------------------------------------------------------------------------

    private data class IntegratedEnv(
        val repository: MediaRepository,
        val scanner: SafFolderScanner,
        val metadata: DefaultMetadataEngine,
        val coreProvider: MiniPlayerCoreProvider,
        val rootGrant: SafGrantInfo,
    )

    private fun buildIntegratedEnvironment(
        onlineHttp: OnlineHttpClient = object : OnlineHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
                maxBytes: Int,
                headOnly: Boolean,
                totalTimeoutMs: Long,
            ): RawHttpResponse = RawHttpResponse(404, url, "text/plain", ByteArray(0))
        },
        durationOverrides: Map<String, Long> = emptyMap(),
    ): IntegratedEnv {
        val backend = InMemoryMediaStoreBackend()
        val repository = MediaRepository(
            backend = backend,
            cacheRootDir = cacheDir,
            filesRootDir = filesDir,
        )
        val localSaf = LocalDirectorySafDataSource(mediaRootDir, durationOverrides)
        val scanner = SafFolderScanner(repository = repository, dataSource = localSaf)

        val tagWriter = PhysicalTagWriter { itemRow, spec ->
            val targetFile = File(URI(itemRow.documentUri))
            val staged = File(cacheDir, "tag_stage/it_${itemRow.itemId}.tmp")
            AudioTagCodec.writeTagsToTempFile(targetFile, staged, spec, itemRow.fileName, itemRow.mimeType)
            Files.move(staged.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            targetFile.length() to targetFile.lastModified()
        }

        val metadataEngine = DefaultMetadataEngine(
            context = null,
            repository = repository,
            onlineCompleter = OnlineMetadataCompleter(http = onlineHttp),
            lrclibClient = LrclibClient(onlineHttp),
            coversDir = File(filesDir, "covers").apply { mkdirs() },
            physicalTagWriter = tagWriter,
        )

        val dummyUpdater = object : AppUpdateContract {
            override suspend fun checkForUpdates(includePreRelease: Boolean): UpdateInfo =
                UpdateInfo("1.0.0", "v1.0.0", "", "", "", "", 0L, null, false)
            override suspend fun downloadAndVerifyUpdate(
                updateInfo: UpdateInfo,
                onProgress: (Long, Long) -> Unit,
            ): File = File(cacheDir, "noop.apk")
            override fun buildInstallIntent(apkFile: File): Intent = Intent()
            override fun canRequestPackageInstalls(): Boolean = true
        }

        val provider = object : MiniPlayerCoreProvider {
            override val library: MediaLibraryContract = repository
            override val scanner: FolderScannerContract = scanner
            override val metadata: MetadataEngineContract = metadataEngine
            override val updater: AppUpdateContract = dummyUpdater
        }

        val grant = SafGrantInfo(
            treeUri = mediaRootDir.toURI().toString(),
            authority = "local.filesystem",
            treeDocId = "primary:Music",
            displayName = "Music",
            isWritable = true,
        )

        return IntegratedEnv(repository, scanner, metadataEngine, provider, grant)
    }

    private class LocalDirectorySafDataSource(
        private val rootDir: File,
        private val durationOverridesByName: Map<String, Long> = emptyMap(),
    ) : SafTreeDataSource {
        override fun takePersistableGrant(treeUri: Uri): SafGrantInfo =
            throw UnsupportedOperationException("Use addSafRootFromGrant in JVM integration tests")

        override fun releasePersistableGrant(treeUri: String) = Unit

        override fun probeRootMountState(
            treeUri: String,
            treeDocId: String,
            fallbackWritable: Boolean,
        ): SafVolumeProbe = if (rootDir.isDirectory) {
            SafVolumeProbe.Mounted(isWritable = true, rootDocUri = rootDir.toURI().toString())
        } else {
            SafVolumeProbe.OfflineUnmounted
        }

        override fun listChildren(treeUri: String, dirDocId: String): List<SafChildEntry> {
            val rel = dirDocId.substringAfter("primary:Music", "").trimStart('/')
            val dir = if (rel.isEmpty()) rootDir else File(rootDir, rel)
            val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            return children.map { f ->
                val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                val childDocId = "primary:Music/$childRel"
                val mime = when {
                    f.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
                    f.name.endsWith(".mp3", true) -> "audio/mpeg"
                    f.name.endsWith(".flac", true) -> "audio/flac"
                    f.name.endsWith(".mp4", true) -> "video/mp4"
                    f.name.endsWith(".lrc", true) -> "text/plain"
                    f.name.endsWith(".jpg", true) -> "image/jpeg"
                    else -> "application/octet-stream"
                }
                SafChildEntry(
                    documentId = childDocId,
                    documentUri = f.toURI().toString(),
                    displayName = f.name,
                    mimeType = mime,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified(),
                )
            }
        }

        override fun buildDocumentUri(treeUri: String, docId: String): String {
            val rel = docId.substringAfter("primary:Music", "").trimStart('/')
            val f = if (rel.isEmpty()) rootDir else File(rootDir, rel)
            return f.toURI().toString()
        }

        override fun rebindDocumentUriToNewTree(newTreeUri: String, oldDocumentUri: String): String = oldDocumentUri

        override fun readSmallFileBytes(documentUri: String, maxBytes: Int): ByteArray? {
            val f = File(URI(documentUri))
            return if (f.isFile && f.length() <= maxBytes) f.readBytes() else null
        }

        override fun extractMediaMetadata(entry: SafChildEntry, isVideo: Boolean): ExtractedMediaProbe {
            val f = File(URI(entry.documentUri))
            if (isVideo) {
                return ExtractedMediaProbe(durationMs = 300_000L, width = 1920, height = 1080)
            }
            val tags = AudioTagCodec.readTags(f, entry.displayName, entry.mimeType)
            val dur = durationOverridesByName[entry.displayName]
                ?: tags.durationMs?.takeIf { it > 0L }
                ?: 262_000L
            return ExtractedMediaProbe(
                durationMs = dur,
                rawTitle = tags.title,
                rawArtist = tags.artist,
                rawAlbum = tags.album,
                rawAlbumArtist = tags.albumArtist,
                rawTrackNum = tags.trackNumber,
                rawYear = tags.year,
            )
        }
    }

    private class RecordingLoopPlayerPort(override var durationMs: Long) : LoopPlayerPort {
        var attachedEngine: PracticeLoopEngine? = null
        override var currentPositionMs: Long = 0L
        override var isPlaying: Boolean = true
        var scheduledBoundaryMs: Long? = null
        var boundaryCallback: (() -> Unit)? = null
        var lastSpeed: Float = 1.0f
        var lastPitch: Float = 1.0f
        var abLoopOwnsRepeatSnapshot: Boolean = false

        override fun scheduleBoundaryMessage(positionMs: Long, onReached: () -> Unit) {
            scheduledBoundaryMs = positionMs
            boundaryCallback = onReached
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun cancelBoundaryMessage() {
            scheduledBoundaryMs = null
            boundaryCallback = null
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun applyPlaybackParameters(speed: Float, pitch: Float) {
            lastSpeed = speed
            lastPitch = pitch
            abLoopOwnsRepeatSnapshot = attachedEngine?.abLoopOwnsRepeat == true
        }

        override fun configureExactSeek() = Unit
        override fun configureFastVideoKeyframeSeek() = Unit
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

    private data class MockReply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private class MockLoopbackServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        @Volatile
        var handler: (String) -> MockReply = { MockReply(404) }

        val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

        init {
            pool.execute {
                while (!serverSocket.isClosed) {
                    val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    pool.execute { serve(sock) }
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 5_000
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val reqLine = reader.readLine() ?: return
                val path = reqLine.split(' ').getOrElse(1) { "/" }
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val reply = handler(path)
                val out = s.getOutputStream()
                val headerText = buildString {
                    append("HTTP/1.1 ${reply.status} Status\r\n")
                    append("Connection: close\r\n")
                    append("Content-Length: ${reply.body.size}\r\n")
                    for ((k, v) in reply.headers) append("$k: $v\r\n")
                    append("\r\n")
                }
                out.write(headerText.toByteArray(Charsets.ISO_8859_1))
                if (reply.body.isNotEmpty()) out.write(reply.body)
                out.flush()
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }

    private fun buildSyntheticMp3Bytes(frameCount: Int): ByteArray {
        val frame = ByteArray(417).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xFB.toByte()
            this[2] = 0x90.toByte()
            this[3] = 0x00
            for (i in 4 until size) this[i] = ((i * 19 + 3) and 0xFF).toByte()
        }
        val out = ByteArray(frame.size * frameCount)
        for (idx in 0 until frameCount) {
            System.arraycopy(frame, 0, out, idx * frame.size, frame.size)
        }
        return out
    }

    @Suppress("UNUSED_PARAMETER")
    private fun writeSyntheticMp3(dest: File, frameCount: Int, durationOverrideMs: Long = 262_000L) {
        dest.parentFile?.mkdirs()
        dest.writeBytes(buildSyntheticMp3Bytes(frameCount))
    }

    private fun buildFakeJpeg(size: Int): ByteArray {
        val b = ByteArray(size.coerceAtLeast(8))
        b[0] = 0xFF.toByte()
        b[1] = 0xD8.toByte()
        b[2] = 0xFF.toByte()
        b[3] = 0xE0.toByte()
        b[b.size - 2] = 0xFF.toByte()
        b[b.size - 1] = 0xD9.toByte()
        return b
    }

    private fun buildDummyApkZip(marker: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest package=\"com.miniplayer.app\"/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(marker.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun postJson(url: String, jsonBody: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        conn.disconnect()
        assertTrue("POST $url failed ($status): $body", status in 200..201)
        return body
    }

    private fun assertThrowsVerifyFailed(block: () -> Unit) {
        try {
            block()
            fail("Expected UpdateException(VERIFY_FAILED)")
        } catch (e: UpdateException) {
            assertEquals(UpdateErrorCode.VERIFY_FAILED, e.code)
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25L)
        }
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", predicate())
    }
}
