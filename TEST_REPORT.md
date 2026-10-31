# MiniPlayer v1.0.2 — Verification, Security Audit & Release Report

**Version:** `1.0.2` (`versionCode = 10002`, `applicationId = "com.miniplayer.app"`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Verified Branches Merged (`v1.0.2`):** `wt/coder-core-3` (`ba37c73`) + `wt/coder-ui-3` (`9b52921`) → `wt/tester-3`

---

## 1. Build, Lint & Signed APK Verification (`v1.0.2`)

All Android builds, pure-JVM unit/integration tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, Gradle `8.11.1`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.1` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug lintDebug assembleRelease` | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 37 warnings` (zero `MissingPermission`, `ExportedService`, `UnsafeOptInUsageError`, or `NewApi` errors; informational warnings are SDK/dependency version suggestions) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`; outbound traffic restricted strictly to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB) and HTTPS GitHub Releases auto-updater, plus user-started on-demand LAN/loopback Web Studio (`:8088`) | **PASS** |
| **Terminology & Emoji-Free UI Hygiene** | Strictly **0** `练琴` strings across `app/src/main/**` and user-facing documentation; **0** runtime emojis across Compose screens (`LibraryScreen`, `PlaylistsScreen`, `NowPlayingSheet`, `PracticeDeck`, `VideoPracticeSurface`, `SettingsScreen`, `LanStudioSheet`) and `LanStudioWebApp` | **PASS** |
| **Debug APK (`v1.0.2`)** | `app/build/outputs/apk/debug/app-debug.apk` (`20,418,486` bytes, `versionName = "1.0.2-DEBUG"`, `versionCode = 10002`) | **PASS** |
| **Signed Release APK (`v1.0.2`)** | `MiniPlayer-v1.0.2.apk` (`14,295,186` bytes, SHA-256 `2acf04fc77c576f143d1a51802b60b8ae49821d96f5b92d025423155f1796f72`, APK Signature Scheme v2 + v3 verified, Signer #1 Certificate SHA-256 `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5` — 100% certificate continuity with `v1.0.0` & `v1.0.1`) | **PASS** |

---

## 2. `v1.0.2` Requirement Verification Matrix (All 5 User Requirements)

| # | Requirement | Verified Implementation & Files | Status |
|---:|---|---|---|
| **1** | Guitar icon (`GuitarIcon`) used for Practice Mode everywhere | Custom Material-styled `GuitarIcon` (`GuitarIcon.kt`) used across `NowPlayingSheet.kt` (Casual action row + top bar active pill), `VideoPracticeSurface.kt` (Casual landscape + Practice landscape header), `LibraryScreen.kt` (Practice Folder toggles/banner + `"以练习模式打开"` / `"练习播放"`), and `PlaylistsScreen.kt` (`"设为练习列表"`) | **PASS** |
| **2** | Multi-click pitch shift (`-1 半音` / `+1 半音` / `-5c` / `+5c`) never stalls or drops audio | `RealTimeSonicAudioProcessor.kt` (`isActive() == true` for 16-bit PCM) + `RealTimeAudioProcessorChain` wired in `PlaybackService.kt` (`SharedExoPlayerHolder.updateSpeedAndPitch`) and `PlayerController.kt` (`applyPlaybackParameters`): drains outgoing `SonicAudioProcessor` buffers cleanly into a direct `stagingBuffer` and swaps pitch/speed in-place with **zero `exoPlayer.seekTo(...)` calls** and `exoPlayer.playbackParameters` keeping `pitch = 1.0f` | **PASS** |
| **3** | Keep Screen On (`保持常亮`) uses clear labeled `"常亮"` pill button instead of bare sun icon | `KeepScreenOnPillButton` (`PracticeDeck.kt`) renders `LightMode` icon + `"常亮"` label (`44.dp` minHeight, `Role.Switch`, `primaryContainer` active state) in both portrait `PracticeDeck` and landscape `LandscapeVideoPlayerView` | **PASS** |
| **4** | Remove `A+ / A- / B+ / B-` and left `Replay` button; butter-smooth `A`/`B` handle dragging | `SplitNudgeControl` and the left `Replay` button are completely removed from `PracticeDeck.kt` and `VideoPracticeSurface.kt` in favor of clean `[A]` / `[B]` timestamp buttons; `WaveformLoopRuler` keys `.pointerInput(safeDuration)` strictly on `safeDuration` with `rememberUpdatedState` + 1:1 float grab-offset tracking (`commitPersist = false` while dragging, `true` on release) | **PASS** |
| **5** | Automatic BPM detection + anytime BPM bar & 1-tap `"自动"` button | `BpmDetector.kt` (`100 Hz` onset-envelope autocorrelation + harmonic octave scoring) + `WaveformExtractor.loadOrDetectBpm` (disk cache + `TBPM` + `MediaExtractor`/`MediaCodec` PCM decode skipping intros) auto-populates `practiceState.bpm` when loading tracks at default `120` BPM; `NowPlayingSheet.kt` binds directly to `viewModel.detectedBpm` / `isDetectingBpm` / `viewModel::autoDetectBpm`; `PracticeDeck` & `LandscapeVideoPlayerView` provide an anytime `BPM <bpm>` chip and `BpmQuickBar` with 1-tap `"自动"` button | **PASS** |

---

## 3. Unit & End-to-End Integration Test Suites

### 3.1 Offline JVM Test Summary (`./gradlew --offline testDebugUnitTest`)

**Summary:** `12` test suites, **`64/64` tests passed (`0` failures, `0` errors, `0` skipped)**.

| Test Suite | Tests | Passed | Coverage Scope |
|---|---:|---:|---|
| `core.db.DatabaseAndScannerTest` | 6 | 6 | 10-table SQLite DDL & FK cascades, 5-level metadata priority chain + clear sentinels, multi-artist album grouping, SAF ancestor/child overlap dedup & merge, nearest-ancestor `folder_rules` inheritance, Practice Folder (`isInPracticeFolder`) isolation from `albums`/`artists`/`videoItems`, `OfflineVolumeGuard`, `<10ms` incremental skip, `.nomedia` & sidecar `.lrc`/`cover.jpg`, moved-file fingerprint migration, and concurrent `fileWriteMutex` serialization |
| `core.metadata.AudioTagCodecTest` | 5 | 5 | Crash-safe read/write & unknown-frame preservation across MP3 ID3v2.3/v2.4 + APIC, FLAC Vorbis Comments + PICTURE, Ogg Vorbis / Opus (`granulePosition = 0L` & valid Ogg CRC-32), MP4/M4A `ilst` (`stco`/`co64` chunk offset recalculation + 64-bit `co64` overflow upgrade), and zero-staging `PosixFdByteSource` |
| `core.metadata.MetadataParsingAndM3uTest` | 5 | 5 | `SmartFilenameParser`, `ArtistSplitter` (`AC/DC` allowlist), `.lrc` parser (UTF-8/GB18030/UTF-16), and `.m3u8` `#EXT-X-MINIPLAYER-PRACTICE:1` 3-tier import/export |
| `core.metadata.OnlineMetadataCompleterTest` | 4 | 4 | MusicBrainz `1 req/s` rate limiter, Cover Art Archive `3.0s` deadline racing to iTunes `1000x1000bb` CDN fallback + trusted `*.archive.org` HTTPS redirect upgrade, `(Backing Track)` suffix preservation, and batch `>= 85%` confidence threshold |
| `core.update.AppUpdaterAndPracticeMathTest` | 5 | 5 | `SemanticVersion` & `Sha256SumsParser`, GitHub `/releases/latest` → `/releases` fallback, `≤ 5` HTTPS redirect hop cap, 4-stage `ApkValidator`, and `PracticeMath` |
| `player.PracticeLoopEngineTest` | 11 | 11 | A-B loop state machine (`NONE` → `A_SET` → `ACTIVE_LOOP` → `SUSPENDED`), `exoPlayer.repeatMode` sync, single-tap `⏮` restart-from-`A` vs. `400ms` double-tap guard, `applyAutoDetectedBpm`, `Count-In` frame pre-priming & pause cancellation, `>80ms` adaptive video keyframe fallback, PCM RMS waveform, and `ArtworkLoader` |
| `player.RealTimeSonicAudioProcessorTest` | 4 | 4 | Always-active `isActive() == true` across identity & non-identity states, 24 rapid multi-click pitch shifts (`-6st..+6st`, `±5c`) draining `SonicAudioProcessor` buffers with zero dropped samples, `Sonic -> Identity -> Sonic` transitions, and idempotent `queueEndOfStream` |
| `player.PlaybackParametersDecisionTest` | 3 | 3 | Speed/pitch clamping (`0.25f..2.0f`, `0.5f..2.0f`), ExoPlayer speed-only propagation (`pitch = 1.0f` invariant), and zero-seek pitch transitions |
| `player.BpmDetectorTest` | 5 | 5 | Synthetic drum/pulse onset-autocorrelation accuracy across `80`, `100`, `120`, `140`, and `160` BPM, stereo interleaved PCM handling, silence/short-clip rejection, and octave ambiguity resolution |
| `lan.LanStudioServerTest` | 8 | 8 | Emoji-free SPA serving, library filters (`isInPracticeFolder` isolation), HTTP `200` + `206 Partial Content` byte-range streaming, streaming upload + `mkdir` + `folder-rule`, metadata persistence, `.m3u8` round-trip, bookmark CRUD, and RFC 1918/loopback + `Host` + `Origin` + PIN security guards |
| `lan.QrCodeGeneratorTest` | 3 | 3 | Pure-Kotlin ISO/IEC 18004 QR Version 1–10 capacity compliance, GF(256) Reed-Solomon syndrome verification, and oversized payload rejection |
| `integration.MiniPlayerEndToEndIntegrationTest` | 5 | 5 | Full cross-module E2E verification across `core`, `player`, and `lan` |
| **Total** | **64** | **64** | **100% pass (`0` failures, `0` errors)** |
