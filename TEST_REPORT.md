# MiniPlayer v1.0.0 — Verification, Security Audit & Release Report

**Version:** `1.0.0` (`versionCode = 10000`, `applicationId = "com.miniplayer.app"`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Verified Branches Merged:** `feat/core` (`3657876`) + `feat/ui-player` (`6bc8210`) + `feat/lan-studio` (`303536c`) → `feat/verify` → `main`

---

## 1. Build, Lint & APK Signature Verification

All Android builds, pure-JVM unit/integration tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, Gradle `8.11.1`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.0` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug assembleRelease lintDebug` | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 38 warnings` (zero `MissingPermission`, `ExportedService`, `UnsafeOptInUsageError`, or `NewApi` errors; informational warnings are SDK/dependency version suggestions) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"` in `AndroidManifest.xml`; outbound traffic restricted strictly to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB) and HTTPS GitHub Releases auto-updater, plus user-started on-demand LAN/loopback Web Studio (`:8088`) | **PASS** |
| **Terminology Compliance** | Strictly **0** `练琴` strings across `app/src/main/**` and user-facing documentation; 100% unified instrument-inclusive `练习模式` / `练习列表` / `练习文件夹` / `练习工作台` terminology | **PASS** |
| **Debug APK** | `app/build/outputs/apk/debug/app-debug.apk` (~20.8 MB, `versionName = "1.0.0"`, `versionCode = 10000`) | **PASS** |
| **Release APK (R8 + Resource Shrinking)** | `app/build/outputs/apk/release/app-release.apk` (~14.2 MB, `isMinifyEnabled = true`, `isShrinkResources = true`, `versionName = "1.0.0"`, `versionCode = 10000`) | **PASS** |
| **APK Signature Verification** | `apksigner verify --verbose --print-certs` passed; APK Signature Scheme v2 verified (`CN=Android Debug`, certificate SHA-256: `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5`) | **PASS** |

---

## 2. Unit & End-to-End Integration Test Suites

### 2.1 Offline JVM Test Summary (`./gradlew --offline testDebugUnitTest --rerun-tasks`)

**Summary:** `9` test suites, **`50/50` tests passed (`0` failures, `0` errors, `0` skipped)**.

| Test Suite | Tests | Passed | Coverage Scope |
|---|---:|---:|---|
| `core.db.DatabaseAndScannerTest` | 5 | 5 | 10-table SQLite DDL & FK cascades, 5-level metadata priority chain + `CLEARED_TEXT`/`CLEARED_INT` clear sentinels, multi-artist album grouping, SAF ancestor/child overlap dedup & `item_id`-preserving merge, nearest-ancestor `folder_rules` inheritance, `OfflineVolumeGuard` on unplugged OTG, `<10ms` incremental skip, `.nomedia` & sidecar `.lrc`/`cover.jpg`, moved-file `fingerprint_key` migration, and 8-way concurrent `fileWriteMutex` serialization |
| `core.metadata.AudioTagCodecTest` | 5 | 5 | Crash-safe read/write & unknown-frame preservation across **MP3 ID3v2.3/v2.4 + APIC**, **FLAC Vorbis Comments + PICTURE**, **Ogg Vorbis / Opus** (`granulePosition = 0L` & valid Ogg CRC-32), **MP4/M4A `ilst`** (`stco`/`co64` chunk offset recalculation + 64-bit `co64` overflow upgrade when `moov` precedes `mdat`), and zero-staging `PosixFdByteSource` (`Os.pread` partial/EINTR handling without reading full audio payload) |
| `core.metadata.MetadataParsingAndM3uTest` | 5 | 5 | `SmartFilenameParser` (`[320k]`/`[FLAC]`/`(2001)` noise stripping, hierarchy Artist/Album inference, `(Backing Track)`/`(伴奏)` preservation), `ArtistSplitter` (`/` · `;` · `、` · `feat.` with band allowlist `AC/DC`, `Guns N' Roses`), `.lrc` parser (multi-timestamps, `[offset:]`, UTF-8/GB18030/UTF-16), and `.m3u8` `#EXT-X-MINIPLAYER-PRACTICE:1` 3-tier import/export |
| `core.metadata.OnlineMetadataCompleterTest` | 4 | 4 | MusicBrainz `1 req/s` rate limiter + custom `User-Agent`, Cover Art Archive `3.0s` total wall-clock timeout racing cleanly to iTunes `1000x1000bb` CDN fallback + trusted `*.archive.org` HTTPS redirect upgrade, `(Backing Track)` query stripping & suffix restoration, batch `>= 85%` confidence threshold, and default cleartext HTTP block |
| `core.update.AppUpdaterAndPracticeMathTest` | 5 | 5 | `SemanticVersion` & `Sha256SumsParser`, GitHub `/releases/latest` → `/releases` fallback, `≤ 5` HTTPS redirect hop cap & HTTPS→HTTP downgrade block, `403` rate-limit handling, 4-stage `ApkValidator` (`PK\x03\x04` + manifest, package match, `versionCode > installed` anti-downgrade, signing cert SHA-256 match), and `PracticeMath` (`2^((st + cents/100)/12)`, `round(60000 / (bpm * speed))`, Tap Tempo, A-B normalization) |
| `player.PracticeLoopEngineTest` | 10 | 10 | 3-state A-B loop (`NONE` → `A_SET` → `ACTIVE_LOOP` → `SUSPENDED`), `exoPlayer.repeatMode` ownership sync, `±0.5s`/`±0.1s` boundary rescheduling, single-tap `⏮` restart-from-`A` vs. `400ms` double-tap guard, `SpeedTrainer` step-up & target clamp, `Count-In` frame pre-priming & pause cancellation, initial saved loop `Pre-Roll` + `Count-In`, `>80ms` adaptive video keyframe fallback, PCM float/16-bit RMS waveform + natural sort, and `ArtworkLoader` trusted `*.archive.org` HTTPS redirect upgrade |
| `lan.LanStudioServerTest` | 8 | 8 | Self-contained SPA serving, library filters (`all`/`video`/`unknown_album`/search), HTTP `200` + `206 Partial Content` byte-range streaming, streaming binary file upload + `mkdir` + `folder-rule` toggle, single/batch/online metadata persistence, playlist CRUD + reorder + `.m3u8` round-trip, practice state & section bookmark CRUD, and RFC 1918/loopback firewall + `Host` DNS-rebinding + `Origin` (`Origin: null` rejected) CSRF + session PIN guards |
| `lan.QrCodeGeneratorTest` | 3 | 3 | Pure-Kotlin ISO/IEC 18004 QR Version 1–10 capacity table compliance, GF(256) Reed-Solomon syndrome verification (`0` syndromes) & round-trip matrix decode on LAN URLs, and oversized payload rejection |
| `integration.MiniPlayerEndToEndIntegrationTest` | 5 | 5 | Full cross-module E2E verification across `core` (`MediaRepository` + `SafFolderScanner` + `DefaultMetadataEngine` + `AppUpdater`), `player` (`PracticeLoopEngine` + `CountInMetronome`), and `lan` (`LanStudioServer`) — detailed in §2.2 |
| **Total** | **50** | **50** | **100% pass (`0` failures, `0` errors)** |

### 2.2 End-to-End Integration Flows (`MiniPlayerEndToEndIntegrationTest`)

1. **Practice Mode State Machine & Progressive Disclosure (`practiceModeStateMachine_autoActivatesForPracticePlaylistSubfolderAndBookmarkJump_andShowsCasualHint`)**:
   - Verifies everyday album/folder playback starts in **Casual Mode** (`practiceModeActive = false`, `1.0x`), while surfacing a gentle `hasSavedPracticeHistory` hint (`🎯 练习模式 · 含 N 段`) when historical bookmarks exist.
   - Verifies deterministic **Practice Deck** auto-activation across all 4 triggers: (a) playing from a **`🎯 练习列表`** (`PRACTICE_PLAYLIST`), (b) playing inside a **`🎯 练习文件夹`** or nested subfolder via ancestor rule inheritance (`PRACTICE_FOLDER`), (c) tapping **`[🔖 含段落书签]`** (`BOOKMARK_JUMP`, activating exact `[A, B]` + bookmark speed), and (d) explicit manual toggle (`MANUAL_TOGGLE`).
2. **A-B Loop, Nudges, Speed Trainer, `4拍` Count-In & Pitch Math (`abLoopNudgeRestartSuspendSpeedTrainerFourBeatCountInAndPitchMath_endToEnd`)**:
   - Exercises `NONE` → `A_SET` → `ACTIVE_LOOP` transitions, `±0.5s` coarse & `±0.1s` fine nudges, `[↺ 回A]` instant restart, `[循环: 开/挂起]` non-destructive suspend & resume (`exoPlayer.repeatMode` restored), `SpeedTrainer` stepping `0.75x → 0.80x → 0.85x → 0.90x` every 2 loops and clamping at target, `4拍` `CountInMetronome` scaling (`120 BPM @ 0.80x` → `625ms/beat` × 4 beats = `2500ms`) with immediate skip, and `PlaybackParameters` pitch shift (`-1st` Eb standard = `0.943874f`, `-1st - 25c` = `0.930339f`, `+5st` = `1.334840f`).
3. **Album/Artist Grouping, Dual-Layer Metadata Write-Back & Zero-Key Online Auto-Completion (`albumArtistGroupingUnknownAlbumDualLayerTagWriteBackAndZeroKeyOnlineCompletion_endToEnd`)**:
   - Verifies `(Album, AlbumArtist)` grouping (`John Mayer` + `John Mayer / B.B. King` unified under `Continuum`), `未知专辑` fallback bucket, `ArtistSplitter` preserving `AC/DC` while splitting collaborations, active-playback SQLite override deferral (`SAVED_DB_DEFERRED_FILE`) followed by automatic physical MP3 ID3v2.4 tag flush upon track transition, batch edit locking `TITLE` & `TRACK_NUMBER`, and keyless `MusicBrainz` + `CoverArtArchive` (`>3.0s` stall auto-falling back to `iTunes 1000x1000bb` CDN) + `LRCLIB` synced `.lrc` completion while preserving `(Backing Track)` suffixes.
4. **In-App GitHub Auto-Updater & `ApkValidator` (`inAppGitHubAutoUpdaterAndApkValidator_loopbackServerRedirectsSha256AndDowngradeRejection`)**:
   - Drives `AppUpdater` + `ApkValidator` against a local loopback HTTP server testing `/releases/latest`, multi-hop redirects (`302` → `307` → asset), `>5` redirect hop rejection, cleartext `http://` rejection (`INSECURE_URL`), `SHA256SUMS.txt` verification (`CHECKSUM_MISMATCH` on tampered bytes + `.part` cleanup), `versionCode` anti-downgrade (`DOWNGRADE_REJECTED`), package mismatch (`PACKAGE_MISMATCH`), signing certificate mismatch (`SIGNATURE_MISMATCH`), and valid upgrade approval (`10100 > 10000`).
5. **Live PC LAN Web Studio ↔ Phone Core & Practice Engine (`lanWebStudioToPhoneCoreAndPracticeEngine_liveCrossComponentIntegration`)**:
   - Boots a live `LanStudioServer` (`127.0.0.1:<ephemeral>`, PIN-authenticated) backed by real `MediaRepository`, `SafFolderScanner`, `DefaultMetadataEngine`, and `PracticeLoopEngine`: creates a subfolder, toggles `🎯 练习文件夹`, uploads a valid MP3 via `POST /api/upload`, verifies HTTP `206` byte-range streaming, edits tags via `POST /api/metadata/update`, creates `[A=2.5s, B=9.75s @ 0.80x]` bookmark via `POST /api/bookmarks/create`, exports/imports `#EXT-X-MINIPLAYER-PRACTICE:1` `.m3u8`, and verifies the phone `PracticeLoopEngine` auto-activates `PRACTICE_FOLDER` and jumps cleanly to the PC-authored bookmark.

---

## 3. Security, Media3 Threading & Architecture Audit Matrix

| Requirement / Architectural Invariant | Verification Evidence | Status |
|---|---|---|
| **Media3 Main-Looper Thread Discipline (`ARCHITECTURE.md` §4.1)** | All `ExoPlayer` / `MediaSession` calls execute on `Looper.getMainLooper()`; `PracticeLoopEngine` schedules `PlayerMessage` on `exoPlayer.applicationLooper` with `setDeleteAfterDelivery(false)` and cancels stale boundaries before rescheduling | **PASS** |
| **`fdsan`-Safe Zero-Staging SAF Tag I/O (`AudioTagCodec.kt`)** | `PosixFdByteSource` inspects `Os.fstat(fd)` and reads tag headers via positional `Os.pread` (with partial-read reassembly + `EINTR` retry), and `writeTagsToSafUri` writes via `Os.write` + `Os.ftruncate` + `Os.fsync` inside `pfd.use`—zero `FileInputStream`/`FileOutputStream` wrappers over `pfd.fileDescriptor` (preventing Android 10+ `fdsan` `SIGABRT`) and zero full-audio temp copies during scans | **PASS** |
| **Crash-Safe Binary Tag Rewriting & `fileWriteMutex` (`DefaultMetadataEngine.kt`)** | Stages tag rewrites to `cacheDir/tag_stage/*.tmp`, validates container headers + MP4 `stco`/`co64` chunk offsets before streaming into SAF `"rw"` FD, defers physical writes on the currently playing item (`SAVED_DB_DEFERRED_FILE`), and serializes all physical writes/flushes under `fileWriteMutex` | **PASS** |
| **PC LAN Web Studio Security (`LanStudioServer.kt`)** | `accept()`-level RFC 1918 (`10/8`, `172.16/12`, `192.168/16`, `169.254/16`) + loopback IP firewall, `Host` header DNS-rebinding allowlist, mutating `Origin` CSRF validation (explicitly rejecting `Origin: null`), `escJs` DOM XSS escaping, filename path-separator sanitization, and optional 4-digit PIN cookie/header auth | **PASS** |
| **GitHub Auto-Updater Supply-Chain Verification (`AppUpdater.kt`, `ApkValidator.kt`)** | Enforces HTTPS on `api.github.com` / `github.com` / `objects.githubusercontent.com`, manual redirects `≤ 5` hops with zero HTTPS→HTTP downgrade, `SHA256SUMS.txt` digest match, ZIP + `AndroidManifest.xml` check, exact `packageName` match, `versionCode > installed`, and signing certificate SHA-256 equality, served via `${applicationId}.fileprovider` | **PASS** |

---

## 4. Bugs Caught & Resolved During Multi-Agent Swarm Audit

Before `v1.0.0` sign-off, independent audits by `@bug-finder` and `@critic` across `feat/core`, `feat/ui-player`, `feat/lan-studio`, and `feat/verify` identified and resolved the following subtle edge cases (all covered by regression tests):

1. **Android 10+ `fdsan` FD Ownership Violation in SAF Tag Reader (`3657876`)**: Replaced `FileInputStream(pfd.fileDescriptor).use` with direct `PosixFdByteSource` (`Os.fstat` + `Os.pread`) and `writeFullyToFd` (`Os.write`) so `pfd.use` retains sole FD close ownership.
2. **Zero-Staging Seekable SAF Tag Parsing (`cd6dcb2`)**: Eliminated full-file temp staging during SAF folder scans so multi-hundred-MB FLAC/MP4 files read only metadata blocks / `moov` boxes in $O(1)$ I/O.
3. **Metadata Clear Sentinels & Concurrent Deferred Flush Race (`cd6dcb2`, `3657876`)**: Added `CLEARED_TEXT` / `CLEARED_INT` sentinels so clearing optional fields erases tags instead of falling back to stale `raw_*` columns, wired paused track-transition flushes in `PlayerController` & `MiniPlayerViewModel`, and serialized SAF writes with `fileWriteMutex`.
4. **CAA `3.0s` Total Deadline & Trusted `*.archive.org` HTTPS Redirect Upgrade (`cd6dcb2`, `6bc8210`)**: Upgraded `http://archive.org/...` 307 redirect hops from Cover Art Archive to `https://` in both `OnlineMetadataCompleter` and `ArtworkLoader` while keeping untrusted `http://` hosts blocked, bounded by a `3,000ms` total wall-clock budget.
5. **Media3 `repeatMode` Ownership, `Count-In` Cleanup & Initial Pre-Roll (`18c1447`)**: Restored user `repeatMode` when suspending/clearing A-B loops, cancelled active woodblock `Count-In` on external `MediaSession` pause/seek, and applied `Pre-Roll` + `Count-In` on initial saved loop activation.
6. **LAN Studio DOM XSS Escaping & `Origin: null` CSRF Guard (`303536c`)**: Escaped inline JS string arguments via `escJs(...)`, rejected `Origin: null` sandboxed POSTs (`403`), silenced `seekTo` loop resets during bookmark jumps, and preserved candidate `.lrc` lyrics in single-item web auto-match.

---

## 5. Release & GitHub Publication (`codescv/miniplayer` `v1.0.0`)

- **Repository URL:** `https://github.com/codescv/miniplayer`
- **Release URL:** `https://github.com/codescv/miniplayer/releases/tag/v1.0.0`
- **Release Tag & Version:** `v1.0.0` (`versionName = "1.0.0"`, `versionCode = 10000`)
- **Signing Certificate SHA-256:** `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5`
- **Published Assets:** `MiniPlayer-v1.0.0.apk`, `app-release.apk`, and `SHA256SUMS.txt` (verified live against `https://api.github.com/repos/codescv/miniplayer/releases/latest`).
