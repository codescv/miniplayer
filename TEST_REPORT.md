# MiniPlayer v1.0.5 — Verification, Security Audit & Release Report

**Version:** `1.0.5` (`versionCode = 10005`, `applicationId = "com.miniplayer.app"`, app label `Play`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Branch Verified (`v1.0.5`):** `wt/coder-6` (`e02695a`) → `wt/tester-6`

---

## 1. Build, Lint & Signed APK Verification (`v1.0.5`)

All Android builds, pure-JVM unit/integration tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.1` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug lintDebug assembleRelease` (`BUILD SUCCESSFUL`) | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 36 warnings` (same as `v1.0.4`; informational SDK/dependency version suggestions) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"`; outbound traffic limited to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB), HTTPS GitHub Releases auto-updater, and the user-started LAN/loopback Web Studio (`:8088`). Staged `coverRemoteUrl` downloads reuse `OnlineMetadataCompleter.downloadCoverBytes` | **PASS** |
| **Terminology & Emoji Hygiene** | **0** `练琴` strings in `app/src/main/**`; **0** emojis in Compose screens. **Note:** `v1.0.5` adds one emoji to `LanStudioWebApp.kt` (edit-modal button `🌐 在线自动补全当前曲目`, line 1110, as specified in board #64) plus `✓` text marks (`歌词✓` / `封面✓`) in candidate rows. Flagged for the coordinator to decide; not changed by tester | **NOTE** |
| **Debug APK (`v1.0.5`)** | `app/build/outputs/apk/debug/app-debug.apk` (`20,435,210` bytes, `versionName = "1.0.5-DEBUG"`, `versionCode = 10005`) | **PASS** |
| **Signed Release APK (`v1.0.5`)** | `MiniPlayer-v1.0.5.apk` (`14,295,186` bytes, SHA-256 `b40ea92663d6b8d5430231521f487573d5bd6c663284a47d60cf0e5dcd4faae6`, `zipalign -c -p 4` verified, APK Signature Scheme v2 + v3 verified, Signer #1 Certificate SHA-256 `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5`, same certificate as `v1.0.0`–`v1.0.4`). `app/build/outputs/apk/release/app-release.apk` is byte-identical | **PASS** |
| **`aapt2 dump badging`** | `package: name='com.miniplayer.app' versionCode='10005' versionName='1.0.5'`, `application-label:'Play'`, `minSdkVersion:'26'`, `targetSdkVersion:'35'` | **PASS** |
| **Adaptive Icon Resources in APK** | `aapt2 dump resources`: `color/ic_launcher_background` `()` `#F8FAFC` / `(night)` `#0F172A`; `color/ic_launcher_pick` `#F97316` / `#FB923C`; `color/ic_launcher_play` `#FFFFFF` / `#FFFBEB` | **PASS** |

---

## 2. `v1.0.5` Requirement Verification Matrix

| # | Requirement | Verified Implementation & Files | Status |
|---:|---|---|---|
| **1** | Stage the selected online metadata candidate without auto-saving/closing; save only on `保存` | `Models.kt` (`MetadataDraft.coverRemoteUrl` / `syncedLrc` / `plainLyrics`); `DefaultMetadataEngine.saveMetadata` (resolves cover bytes **before** `fileWriteMutex.withLock`: local `coverBytes` win, else `onlineCompleter.downloadCoverBytes(coverRemoteUrl)`; a failed download keeps the existing override cover; single-item save upserts `LyricsCacheRow(source = LyricsSource.LRCLIB)`); `MiniPlayerViewModel.saveMetadataDraft` (refreshes queue metadata, `_currentCoverBytes`, `_currentLyrics` before `closeMetadataEditor()`); `MetadataEditorSheet.kt` (`CandidateItemCard` click stages `title` (with `(伴奏)` suffix handling), `artist`, `album`, `albumArtist`, `trackNumText`, `yearText`, `previewCoverUri`, `stagedCoverRemoteUrl`, `stagedSyncedLrc`, `stagedPlainLyrics`, `dirtyFields`; highlights with `primaryContainer.copy(alpha = 0.55f)` + `BorderStroke(1.5.dp, primary)` + `已填入 · 请点上方保存`; `onApplyCandidateDirectly` is only a declared parameter and never called inside the sheet; local image pick clears `stagedCoverRemoteUrl`; top-right `保存` passes staged fields into `MetadataDraft`); `LanStudioServer.kt` (`GET /api/metadata/candidates`, `"candidatesOnly": true`, `coverRemoteUrl` → `MetadataField.COVER`); `LanStudioWebApp.kt` (`openEditItemModal` → `searchEditModalCandidates` fills `#ed-title`…`#ed-year` and renders `#ed-candidates-box`; `selectEditModalCandidate` stages fields only; `submitSingleMetadata` (`保存更改`) is the only path that posts `/api/metadata/update` and closes the modal) | **PASS** |
| **2** | Eliminate stale / duplicate renamed folders (`rock` → `Rock`, `rock` → `metal`) | `SafFolderScanner.kt` (incremental cache hit counts as zero-change only if `parentRelPath` and `fileName` also match, otherwise `MovedMediaUpdate`; changed-URI unique match on `(fileName ignoreCase, sizeBytes, lastModified > 0)` → `MovedMediaUpdate`; case-insensitive `SafOverlapDetector`; `SafVolumeProbe.Mounted.liveDisplayName`); `MiniPlayerDatabase.kt` (`reconcileRootScan` in SQLite and in-memory backends canonicalizes case-only `parent_rel_path` and migrates/prunes `folder_rules`, including nested prefixes); `MediaRepository.kt` (`browseFolder` no longer synthesizes folders from `folder_rules`, dedups children case-insensitively preferring `scanned_folders` casing, canonicalizes the requested path, case-insensitive `findFolderRule` fallback, `snapshotVersion`); `MiniPlayerViewModel.kt` (observes `snapshotVersion` → `refreshActiveDrillDowns()`, follows canonical `currentRelPath`, debounced `onAppForegrounded()`); `MainActivity.kt` (non-initial `ON_START` → `onAppForegrounded()`). Covered by the 2 new `DatabaseAndScannerTest` cases (identical and changed document URIs, with `folder_rules`; full rename and root case-rename) | **PASS** |
| **3** | App display name `Play` & theme-adaptive wide guitar pick launcher icon | `values/strings.xml` `<string name="app_name">Play</string>`; `LibraryScreen.kt` `TopAppBar` `Text("Play", style = MaterialTheme.typography.titleLarge)`; `values/colors.xml` Light `1D` (`#F8FAFC` / `#F97316` / `#FFFFFF`); `values-night/colors.xml` Dark `1A` (`#0F172A` / `#FB923C` / `#FFFBEB`); `drawable/ic_launcher_background.xml` (`@color/ic_launcher_background`); `drawable/ic_launcher_foreground.xml` (layout A wide-shoulder 351 pick `M54,23 C75,23 86,31 86,45.5 …` + centered rounded play triangle, no outer circle); `mipmap-anydpi-v26/ic_launcher.xml` references both drawables; no `mipmap-night-*` PNGs | **PASS** |
| **4** | Version bump & release artifacts (`v1.0.5` / `10005`) | `app/build.gradle.kts` (`versionCode = 10005`, `versionName = "1.0.5"`); `MiniPlayer-v1.0.5.apk` & `app-release.apk` aligned and signed (`apksigner verify -v --print-certs`); `SHA256SUMS.txt` generated | **PASS** |

### Reviewer Observations (non-blocking)

- **Candidate re-selection edge case** (`MetadataEditorSheet.kt`): if the user picks a candidate with a cover and then a second candidate **without** `coverUrl`, the first candidate's `stagedCoverRemoteUrl` and `COVER` dirty flag stay staged. Low impact; the preview still shows the first cover, so what you see is what gets saved.
- **Case-sensitive volumes**: `browseFolder` and `reconcileRootScan` now treat sibling folders that differ only by case (`rock` and `Rock`) as the same folder. That is right for Android emulated storage and FAT/exFAT SD/OTG media, but on a case-sensitive volume (e.g. ext4 OTG) two real sibling folders differing only by case would be merged into one entry.

---

## 3. Unit & End-to-End Integration Test Suites

### 3.1 Offline JVM Test Summary (`./gradlew --offline testDebugUnitTest`)

**Summary:** `13` test suites, **`71/71` tests passed (`0` failures, `0` errors, `0` skipped)**.

| Test Suite | Tests | Passed | Coverage Scope |
|---|---:|---:|---|
| `core.db.DatabaseAndScannerTest` | 9 | 9 | Subfolder rename dedup (`rock` → `Rock` with identical and changed SAF document URIs plus `folder_rules` migration; `rock` → `metal` and SAF root case-rename), 10-table SQLite DDL (`DATABASE_VERSION = 2`) & FK cascades, 5-level metadata priority chain + clear sentinels, multi-artist album grouping, SAF ancestor/child overlap dedup & merge, nearest-ancestor `folder_rules` inheritance, Practice Folder (`isInPracticeFolder`) & Exclude-from-Auto-Grouping (`isExcludedFromAutoGrouping`) isolation from `albums`/`artists`/`videoItems` with child override, `OfflineVolumeGuard`, `<10ms` incremental skip, unconditional `.nomedia` scanning (never skipping `.nomedia` folders) & sidecar `.lrc`/`cover.jpg`, moved-file fingerprint migration, and concurrent `fileWriteMutex` serialization |
| `core.metadata.AudioTagCodecTest` | 5 | 5 | Crash-safe read/write & unknown-frame preservation across MP3 ID3v2.3/v2.4 + APIC, FLAC Vorbis Comments + PICTURE, Ogg Vorbis / Opus (`granulePosition = 0L` & valid Ogg CRC-32), MP4/M4A `ilst` (`stco`/`co64` chunk offset recalculation + 64-bit `co64` overflow upgrade), and zero-staging `PosixFdByteSource` |
| `core.metadata.MetadataAndLanTest` | 2 | 2 | `DefaultMetadataEngine.saveMetadata` persists staged candidate `coverRemoteUrl` cover bytes, `syncedLrc`/`plainLyrics` lyrics cache and tags on save; LAN `GET /api/metadata/candidates` and `"candidatesOnly": true` return candidates without auto-saving until `POST /api/metadata/update` |
| `core.metadata.MetadataParsingAndM3uTest` | 5 | 5 | `SmartFilenameParser`, `ArtistSplitter` (`AC/DC` allowlist), `.lrc` parser (UTF-8/GB18030/UTF-16), and `.m3u8` `#EXT-X-MINIPLAYER-PRACTICE:1` 3-tier import/export |
| `core.metadata.OnlineMetadataCompleterTest` | 4 | 4 | MusicBrainz `1 req/s` rate limiter, Cover Art Archive `3.0s` deadline racing to iTunes `1000x1000bb` CDN fallback + trusted `*.archive.org` HTTPS redirect upgrade, `(Backing Track)` suffix preservation, and batch `>= 85%` confidence threshold |
| `core.update.AppUpdaterAndPracticeMathTest` | 5 | 5 | `SemanticVersion` & `Sha256SumsParser`, GitHub `/releases/latest` → `/releases` fallback, `≤ 5` HTTPS redirect hop cap, 4-stage `ApkValidator`, and `PracticeMath` |
| `player.PracticeLoopEngineTest` | 11 | 11 | A-B loop state machine (`NONE` → `A_SET` → `ACTIVE_LOOP` → `SUSPENDED`), `exoPlayer.repeatMode` sync, single-tap `⏮` restart-from-`A` vs. `400ms` double-tap guard, `applyAutoDetectedBpm`, `Count-In` frame pre-priming & pause cancellation, `>80ms` adaptive video keyframe fallback, PCM RMS waveform, and `ArtworkLoader` |
| `player.RealTimeSonicAudioProcessorTest` | 4 | 4 | Always-active `isActive() == true` across identity & non-identity states, 24 rapid multi-click pitch shifts (`-6st..+6st`, `±5c`) draining `SonicAudioProcessor` buffers with zero dropped samples, `Sonic -> Identity -> Sonic` transitions, and idempotent `queueEndOfStream` |
| `player.PlaybackParametersDecisionTest` | 3 | 3 | Speed/pitch clamping (`0.25f..2.0f`, `0.5f..2.0f`), ExoPlayer speed-only propagation (`pitch = 1.0f` invariant), and zero-seek pitch transitions |
| `player.BpmDetectorTest` | 5 | 5 | Synthetic drum/pulse onset-autocorrelation accuracy across `80`, `100`, `120`, `140`, and `160` BPM, stereo interleaved PCM handling, silence/short-clip rejection, and octave ambiguity resolution |
| `lan.LanStudioServerTest` | 9 | 9 | Emoji-free SPA serving, library filters (`isInPracticeFolder` & `isExcludedFromAutoGrouping` isolation), HTTP `200` + `206 Partial Content` byte-range streaming, streaming upload + `mkdir` + `folder-rule` (`isPractice` & `excludeFromGrouping`), metadata persistence, `.m3u8` round-trip, bookmark CRUD, and RFC 1918/loopback + `Host` + `Origin` + PIN security guards |
| `lan.QrCodeGeneratorTest` | 3 | 3 | Pure-Kotlin ISO/IEC 18004 QR Version 1–10 capacity compliance, GF(256) Reed-Solomon syndrome verification, and oversized payload rejection |
| `integration.MiniPlayerEndToEndIntegrationTest` | 6 | 6 | Full cross-module E2E verification across `core`, `player`, and `lan` (including folder `"不自动归类"` inheritance/casual-playback invariants, recursive folder-to-playlist natural sort collection, and unconditional `.nomedia` scanning) |
| **Total** | **71** | **71** | **100% pass (`0` failures, `0` errors)** |
