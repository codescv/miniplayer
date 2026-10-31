# MiniPlayer v1.0.3 — Verification, Security Audit & Release Report

**Version:** `1.0.3` (`versionCode = 10003`, `applicationId = "com.miniplayer.app"`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Branch Verified (`v1.0.3`):** `wt/coder-ui-4` (`e111d07`) → `wt/tester-4`

---

## 1. Build, Lint & Signed APK Verification (`v1.0.3`)

All Android builds, pure-JVM unit/integration tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, Gradle `8.11.1`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.1` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug lintDebug assembleRelease` | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 36 warnings` (zero `MissingPermission`, `ExportedService`, `UnsafeOptInUsageError`, or `NewApi` errors; informational warnings are SDK/dependency version suggestions) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`; outbound traffic restricted strictly to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB) and HTTPS GitHub Releases auto-updater, plus user-started on-demand LAN/loopback Web Studio (`:8088`) | **PASS** |
| **Terminology & Emoji-Free UI Hygiene** | Strictly **0** `练琴` strings across `app/src/main/**` and user-facing documentation; **0** runtime emojis across Compose screens (`LibraryScreen`, `PlaylistsScreen`, `NowPlayingSheet`, `PracticeDeck`, `VideoPracticeSurface`, `SettingsScreen`, `LanStudioSheet`) and `LanStudioWebApp` | **PASS** |
| **Debug APK (`v1.0.3`)** | `app/build/outputs/apk/debug/app-debug.apk` (`20,402,102` bytes, `versionName = "1.0.3-DEBUG"`, `versionCode = 10003`) | **PASS** |
| **Signed Release APK (`v1.0.3`)** | `MiniPlayer-v1.0.3.apk` (`14,278,802` bytes, SHA-256 `452b7a415dd386b4f90b4c4d389a66cd25a41c70afe1b1429d4b854723ccb1d8`, `zipalign -c 4` verified, APK Signature Scheme v2 + v3 verified, Signer #1 Certificate SHA-256 `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5` — 100% certificate continuity with `v1.0.0`–`v1.0.2`) | **PASS** |

---

## 2. `v1.0.3` Requirement Verification Matrix (All 4 User Requirements)

| # | Requirement | Verified Implementation & Files | Status |
|---:|---|---|---|
| **1** | Remove `"常亮"` text from the Keep-Screen-On button | `KeepScreenOnPillButton` deleted; both `PracticeDeck.kt` (`SpeedAndSecondaryStrip`) and `VideoPracticeSurface.kt` (`LandscapeVideoPlayerView`) now render an icon-only `DeckIconToggleButton` (`Icons.Outlined.LightMode`, `highlighted = keepScreenOn`, `contentDescription = if (keepScreenOn) "练习常亮：已开启" else "练习常亮：已关闭"`) | **PASS** |
| **2** | Remove all 3 icons from the top bar of `PlaylistsScreen` | `PlaylistsScreen.kt` `TopAppBar` sets `title = { Text("播放列表", style = MaterialTheme.typography.titleLarge) }` and `actions = {}` (removing `Search`, `FileOpen`, and `Settings`), cleans up `searchExpanded`, and resets stale `playlistSearchQuery` on entry | **PASS** |
| **3** | Simplify `LibraryScreen` `TopAppBar` to keep ONLY `Settings` icon & always sort by default name order | `LibraryScreen.kt` `TopAppBar` removes `Search`, `Web Studio`, `Sort`, and `Refresh` buttons, retaining **only** `Icons.Outlined.Settings`; `SortOptionsBottomSheet` / `showSortSheet` / `searchExpanded` removed; `val currentSort = SortOrder.NAME_ASC` enforces deterministic natural name sorting across all 4 views | **PASS** |
| **4** | Remove folder manager from `SettingsScreen`, keeping dedicated Web Studio entry | `SettingsScreen.kt` removes `"管理已添加的文件夹"` row, `showFoldersManagerSheet`, and `SafRootsManagerSheet` (folders are managed directly in `媒体库 -> 文件夹`), and retains a clean **`PC 局域网 Web 管理台`** row (`FilledTonalButton("打开")`) under `"本地媒体库与无线连接"` | **PASS** |

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
