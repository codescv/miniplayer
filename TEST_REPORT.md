# MiniPlayer v1.0.1 — Verification, Security Audit & Release Report

**Version:** `1.0.1` (`versionCode = 10001`, `applicationId = "com.miniplayer.app"`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Verified Branches Merged (`v1.0.1`):** `feat/v1.0.1-core` (`5e7876a`) + `feat/v1.0.1-ui` (`961a680`) → `feat/v1.0.1-verify`

---

## 1. Build, Lint & APK Verification (`v1.0.1`)

All Android builds, pure-JVM unit/integration tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, Gradle `8.11.1`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.0` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug lintDebug` | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 36 warnings` (zero `MissingPermission`, `ExportedService`, `UnsafeOptInUsageError`, or `NewApi` errors; informational warnings are SDK/dependency version suggestions) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`; outbound traffic restricted strictly to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB) and HTTPS GitHub Releases auto-updater, plus user-started on-demand LAN/loopback Web Studio (`:8088`) | **PASS** |
| **Terminology & Emoji-Free UI Hygiene** | Strictly **0** `练琴` strings across `app/src/main/**` and user-facing documentation; **0** runtime emojis across Compose screens (`LibraryScreen`, `PlaylistsScreen`, `NowPlayingSheet`, `PracticeDeck`, `VideoPracticeSurface`, `SettingsScreen`, `LanStudioSheet`) and `LanStudioWebApp` | **PASS** |
| **Debug APK (`v1.0.1`)** | `app/build/outputs/apk/debug/app-debug.apk` (`20,385,718` bytes / ~19.4 MiB, `versionName = "1.0.1-DEBUG"`, `versionCode = 10001`) | **PASS** |

---

## 2. `v1.0.1` Requirement Verification Matrix (All 9 User Requirements)

| # | Requirement | Verified Implementation & Files | Status |
|---:|---|---|---|
| **1** | Remove speed preset row (`0.5x`, `0.65x`, etc.) from Practice Mode | Completely removed from `PracticeDeck.kt`, `VideoPracticeSurface.kt`, and `LanStudioWebApp.kt`; speed control uses compact `[-5%]` · `1.00x` · `[+5%]` stepper + long-press continuous slider | **PASS** |
| **2** | Remove Speed Trainer (`渐进加速`) from Practice Mode | `SpeedTrainerInlinePanel` and all Speed Trainer UI controls completely removed from `PracticeDeck.kt` | **PASS** |
| **3** | Fix Pitch Shift (`升降调`) audio loss / silence bug | `PlaybackService.kt` overrides `DefaultRenderersFactory.buildAudioSink` with `DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false).build()` (forcing 16-bit PCM compatible with `SonicAudioProcessor`); `PlayerController.kt` evaluates `evaluatePlaybackParametersUpdate` and flushes `DefaultAudioSink` in-place via `performInternalSeek` whenever pitch changes or Sonic active state flips | **PASS** |
| **4** | Exclude Practice Folders (`练习文件夹`) from `专辑`, `歌手`, and `视频` while keeping in `文件夹` & `播放列表` | `MediaRepository.kt` (`buildComputedSnapshot`), `LibraryScreen.kt` (`AlbumsGridContent`), `LanStudioServer.kt` (`/api/items`), and `LanStudioWebApp.kt` filter `!it.isInPracticeFolder` across `albums`, `artists`, and `videoItems` (including inherited subfolders), while keeping all items in `safRoots`, `browseFolder` (`TREE` & `FLAT`), `playlists`, and `bookmarkedTracks` | **PASS** |
| **5** | Player UI simplification (Casual Mode + strictly 2-row Practice Deck) | Casual Mode (`NowPlayingSheet.kt`, `VideoPracticeSurface.kt`): icon-only Practice Mode (`Tune`), dead-centered `"词"` (`PlatformTextStyle(includeFontPadding = false)` + `Box(Alignment.Center)`), icon-only Add-to-Playlist (`PlaylistAdd`). Practice Mode (`PracticeDeck.kt`): strictly **2 rows** (`44.dp` height, fits `360dp` portrait), simplified `A` / `B` / `A -` / `+ A` / `B -` / `+ B` loop controls, and icon-only buttons for Save Bookmark, Pitch Shift, Count-In, Keep Screen On, and Video Mirror | **PASS** |
| **6** | Main Library Screen (`LibraryScreen.kt`) icon-only controls & pure-text tabs | `添加文件夹` FAB (`CreateNewFolder`) and tree `⇄` flat toggle (`AccountTree` / `ViewList`) are icon-only; top 4 `FilterChip`s (`文件夹`, `专辑`, `歌手`, `视频`) are pure text with zero emojis | **PASS** |
| **7** | Move `保持常亮` to Practice Mode; enable Count-In click sound by default & remove both rows from `SettingsScreen.kt` | `保持常亮` icon toggle (`LightMode`) wired in `PracticeDeck.kt`, `LandscapeVideoPlayerView`, and `MainActivity.kt` (`KeepScreenOnEffect`); `AppSettings.countInClickSoundEnabled` defaults to `true`; both rows removed from `SettingsScreen.kt` | **PASS** |
| **8** | Remove global `"保存标签时尝试同时写入原文件"` from `SettingsScreen.kt` while keeping per-edit checkbox in `MetadataEditorSheet.kt` | Global setting row removed from `SettingsScreen.kt`; per-edit `"同时写入原音频文件标签"` checkbox retained in `MetadataEditorSheet.kt` | **PASS** |
| **9** | Playlists Screen (`PlaylistsScreen.kt`) zero emojis & icon-only `+` FAB | Zero emojis anywhere in `PlaylistsScreen.kt` (`全部` / `练习列表` / `含段落书签`); bottom-right FAB is an icon-only `FloatingActionButton` (`Icons.Outlined.Add`) | **PASS** |

---

## 3. Unit & End-to-End Integration Test Suites

### 3.1 Offline JVM Test Summary (`./gradlew --offline testDebugUnitTest`)

**Summary:** `9` test suites, **`52/52` tests passed (`0` failures, `0` errors, `0` skipped)**.

| Test Suite | Tests | Passed | Coverage Scope |
|---|---:|---:|---|
| `core.db.DatabaseAndScannerTest` | 6 | 6 | 10-table SQLite DDL & FK cascades, 5-level metadata priority chain + clear sentinels, multi-artist album grouping, SAF ancestor/child overlap dedup & merge, nearest-ancestor `folder_rules` inheritance, **Practice Folder (`isInPracticeFolder`) isolation from `albums`/`artists`/`videoItems` while preserving `safRoots`/`browseFolder`/`playlists`/`bookmarkedTracks`**, `OfflineVolumeGuard` on unplugged OTG, `<10ms` incremental skip, `.nomedia` & sidecar `.lrc`/`cover.jpg`, moved-file fingerprint migration, and 8-way concurrent `fileWriteMutex` serialization |
| `core.metadata.AudioTagCodecTest` | 5 | 5 | Crash-safe read/write & unknown-frame preservation across **MP3 ID3v2.3/v2.4 + APIC**, **FLAC Vorbis Comments + PICTURE**, **Ogg Vorbis / Opus** (`granulePosition = 0L` & valid Ogg CRC-32), **MP4/M4A `ilst`** (`stco`/`co64` chunk offset recalculation + 64-bit `co64` overflow upgrade when `moov` precedes `mdat`), and zero-staging `PosixFdByteSource` (`Os.pread` partial/EINTR handling) |
| `core.metadata.MetadataParsingAndM3uTest` | 5 | 5 | `SmartFilenameParser` (`[320k]`/`[FLAC]`/`(2001)` noise stripping, hierarchy Artist/Album inference, `(Backing Track)`/`(伴奏)` preservation), `ArtistSplitter` (`/` · `;` · `、` · `feat.` with band allowlist `AC/DC`, `Guns N' Roses`), `.lrc` parser (multi-timestamps, `[offset:]`, UTF-8/GB18030/UTF-16), and `.m3u8` `#EXT-X-MINIPLAYER-PRACTICE:1` 3-tier import/export |
| `core.metadata.OnlineMetadataCompleterTest` | 4 | 4 | MusicBrainz `1 req/s` rate limiter + custom `User-Agent`, Cover Art Archive `3.0s` total wall-clock timeout racing cleanly to iTunes `1000x1000bb` CDN fallback + trusted `*.archive.org` HTTPS redirect upgrade, `(Backing Track)` query stripping & suffix restoration, batch `>= 85%` confidence threshold, and default cleartext HTTP block |
| `core.update.AppUpdaterAndPracticeMathTest` | 5 | 5 | `SemanticVersion` & `Sha256SumsParser`, GitHub `/releases/latest` → `/releases` fallback, `≤ 5` HTTPS redirect hop cap & HTTPS→HTTP downgrade block, `403` rate-limit handling, 4-stage `ApkValidator` (`PK\x03\x04` + manifest, package match, `versionCode > installed` anti-downgrade, signing cert SHA-256 match), and `PracticeMath` (`2^((st + cents/100)/12)`, `round(60000 / (bpm * speed))`, Tap Tempo, A-B normalization) |
| `player.PracticeLoopEngineTest` | 11 | 11 | 3-state A-B loop (`NONE` → `A_SET` → `ACTIVE_LOOP` → `SUSPENDED`), `exoPlayer.repeatMode` ownership sync, `±0.5s`/`±0.1s` boundary rescheduling, single-tap `⏮` restart-from-`A` vs. `400ms` double-tap guard, **`PlayerController.evaluatePlaybackParametersUpdate` pitch-change & Sonic identity-flip audio-sink flush invariants**, `Count-In` frame pre-priming & pause cancellation, initial saved loop `Pre-Roll` + `Count-In`, `>80ms` adaptive video keyframe fallback, PCM float/16-bit RMS waveform + natural sort, and `ArtworkLoader` trusted `*.archive.org` HTTPS redirect upgrade |
| `lan.LanStudioServerTest` | 8 | 8 | Self-contained emoji-free SPA serving, library filters (`all`/`video`/`unknown_album`/search), HTTP `200` + `206 Partial Content` byte-range streaming, streaming binary file upload + `mkdir` + `folder-rule` toggle, single/batch/online metadata persistence, playlist CRUD + reorder + `.m3u8` round-trip, practice state & section bookmark CRUD, and RFC 1918/loopback firewall + `Host` DNS-rebinding + `Origin` (`Origin: null` rejected) CSRF + session PIN guards |
| `lan.QrCodeGeneratorTest` | 3 | 3 | Pure-Kotlin ISO/IEC 18004 QR Version 1–10 capacity table compliance, GF(256) Reed-Solomon syndrome verification (`0` syndromes) & round-trip matrix decode on LAN URLs, and oversized payload rejection |
| `integration.MiniPlayerEndToEndIntegrationTest` | 5 | 5 | Full cross-module E2E verification across `core` (`MediaRepository` + `SafFolderScanner` + `DefaultMetadataEngine` + `AppUpdater`), `player` (`PracticeLoopEngine` + `CountInMetronome`), and `lan` (`LanStudioServer`) |
| **Total** | **52** | **52** | **100% pass (`0` failures, `0` errors)** |

---

## 4. Security, Media3 Audio Pipeline & Architecture Audit Matrix

| Requirement / Architectural Invariant | Verification Evidence | Status |
|---|---|---|
| **Sonic 16-bit PCM Pitch-Shift Pipeline (`PlaybackService.kt`, `PlayerController.kt`)** | `DefaultAudioSink` configured with `setEnableFloatOutput(false)` and `setEnableAudioTrackPlaybackParams(false)` so 24-bit/float sources down-convert cleanly to 16-bit PCM for `SonicAudioProcessor`, plus in-place `performInternalSeek` pipeline flush whenever pitch or Sonic identity state changes | **PASS** |
| **Practice Folder Isolation (`MediaRepository.kt`, `LanStudioServer.kt`)** | `isInPracticeFolder` items excluded from `albums`, `artists`, and `videoItems` across phone UI and LAN REST/SPA filters without affecting `safRoots`, `browseFolder`, `playlists`, or `bookmarkedTracks` | **PASS** |
| **Media3 Main-Looper Thread Discipline (`ARCHITECTURE.md` §4.1)** | All `ExoPlayer` / `MediaSession` calls execute on `Looper.getMainLooper()`; `PracticeLoopEngine` schedules `PlayerMessage` on `exoPlayer.applicationLooper` with `setDeleteAfterDelivery(false)` and cancels stale boundaries before rescheduling | **PASS** |
| **`fdsan`-Safe Zero-Staging SAF Tag I/O (`AudioTagCodec.kt`)** | `PosixFdByteSource` reads tag headers via `Os.fstat` + `Os.pread`, and `writeTagsToSafUri` writes via `Os.write` + `Os.ftruncate` + `Os.fsync` inside `pfd.use`—zero `FileInputStream`/`FileOutputStream` wrappers over `pfd.fileDescriptor` | **PASS** |
| **PC LAN Web Studio Security (`LanStudioServer.kt`)** | `accept()`-level RFC 1918 + loopback IP firewall, `Host` header DNS-rebinding allowlist, mutating `Origin` CSRF validation (explicitly rejecting `Origin: null`), `escJs` DOM XSS escaping, filename path-separator sanitization, and optional 4-digit PIN auth | **PASS** |
| **GitHub Auto-Updater Supply-Chain Verification (`AppUpdater.kt`, `ApkValidator.kt`)** | Enforces HTTPS on `api.github.com` / `github.com` / `objects.githubusercontent.com`, manual redirects `≤ 5` hops with zero HTTPS→HTTP downgrade, `SHA256SUMS.txt` digest match, ZIP + `AndroidManifest.xml` check, exact `packageName` match, `versionCode > installed`, and signing certificate SHA-256 equality | **PASS** |

---

## 5. Additional Integration Bugs Caught & Fixed During `v1.0.1` Verification Audit

During final cross-branch integration verification on `feat/v1.0.1-verify`, `@tester-2` identified and resolved four subtle integration edge cases:

1. **`KeepScreenOnEffect` Video Override in Practice Mode (`MainActivity.kt`, `NowPlayingSheet.kt`, `VideoPracticeSurface.kt`)**: Previously `(snapshot.isPlaying && isVideo) || (practiceActive && keepScreenOnInPractice)` kept `FLAG_KEEP_SCREEN_ON` forced ON even when a user tapped the `保持常亮` icon OFF while practicing a video, and `LandscapeVideoPlayerView` lacked the `保持常亮` toggle. Fixed `KeepScreenOnEffect` so `practiceActive` exclusively respects `keepScreenOnInPractice`, and wired the icon-only `保持常亮` toggle into the landscape Practice HUD as well.
2. **Album `DATE_MODIFIED_DESC` Sort Timestamp Skew (`LibraryScreen.kt`)**: Added `|| item.isInPracticeFolder` guard when scanning `allMediaItems` for album modification timestamps in `AlbumsGridContent`.
3. **LAN `/api/items` Fallback Leak on Empty `videoItems` / `UNKNOWN_ALBUM_KEY` (`LanStudioServer.kt`)**: Removed `.ifEmpty { allMediaItems.filter { ... } }` fallbacks on `"video"` and `"unknown_album"` filters and filtered `!it.isVideo && !it.isInPracticeFolder` on unfiltered `"album"` / `"artist"` queries so Practice Folder files never leak into LAN album/artist/video queries when casual collections are empty.
4. **LAN Web Studio UI & Filter Alignment (`LanStudioWebApp.kt`, `LanStudioSheet.kt`)**: Removed all emojis across `LanStudioWebApp.kt` and `LanStudioSheet.kt`, replaced Web Studio's legacy `0.5x..1.25x` preset buttons with `[-5%]` / `[1.00x 复位]` / `[+5%]`, and aligned `getFilteredMetaItems()` to exclude `isInPracticeFolder` from `video`, `album`, `unknown_album`, and `artist` filters.
