# MiniPlayer v1.0.6 — Verification, Security Audit & Release Report

**Version:** `1.0.6` (`versionCode = 10006`, `applicationId = "com.miniplayer.app"`, app label `Play`)  
**Repository:** `https://github.com/codescv/miniplayer`  
**Branch Verified (`v1.0.6`):** `wt/coder-7` (`fabd4dc`), re-verified \& release-signed on `wt/tester-7`  
**Release APK:** `MiniPlayer-v1.0.6.apk` — `14,295,186` bytes, SHA-256 `5a30f654c1767f66880ccd854d6d32f8e6b65812002905b08dbcf569fe390e52`

---

## 1. Build, Lint & APK Verification (`v1.0.6`)

All Android builds and pure-JVM unit/integration tests succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, AGP `8.7.1`, Kotlin `2.0.21`, Jetpack Compose Material 3, AndroidX Media3 `1.5.1` | **PASS** |
| **Offline Verification Command** | `./gradlew --offline testDebugUnitTest assembleDebug lintDebug assembleRelease` (`BUILD SUCCESSFUL`, `ANDROID_HOME=~/Android/Sdk`) | **PASS** |
| **Android Lint (`lintDebug`)** | **0** errors, `36` warnings (`app/build/reports/lint-results-debug.html`) | **PASS** |
| **Zero Broad Storage Permissions** | Strictly **0** `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE` permissions in `AndroidManifest.xml`; pure SAF (`ACTION_OPEN_DOCUMENT_TREE`) folder grants | **PASS** |
| **Network Surface Hygiene** | `android:usesCleartextTraffic="false"`; outbound traffic limited to HTTPS keyless metadata APIs (MusicBrainz / Cover Art Archive / iTunes CDN / LRCLIB), HTTPS GitHub Releases auto-updater, and the user-started LAN/loopback Web Studio (`:8088`). Staged `coverRemoteUrl` downloads reuse `OnlineMetadataCompleter.downloadCoverBytes` | **PASS** |
| **Terminology & Emoji Hygiene** | **0** `练琴` strings in `app/src/main/**`; **0** emojis in Compose screens and `LanStudioWebApp`. Only the plain `✓` text marks remain (`歌词✓` / `封面✓`) | **PASS** |
| **Debug APK (`v1.0.6`)** | `app/build/outputs/apk/debug/app-debug.apk` (`20,435,210` bytes, `versionName = "1.0.6-DEBUG"`, `versionCode = 10006`) | **PASS** |
| **Release APK (`v1.0.6`)** | `MiniPlayer-v1.0.6.apk` (byte-identical to `app/build/outputs/apk/release/app-release.apk`): **`14,295,186` bytes**, SHA-256 **`5a30f654c1767f66880ccd854d6d32f8e6b65812002905b08dbcf569fe390e52`** (recorded in `SHA256SUMS.txt`); `zipalign -f -p 4` + `apksigner sign` with `~/.android/miniplayer-release.jks` (alias `miniplayer`); `zipalign -c -p 4` OK | **PASS** |
| **`apksigner verify -v --print-certs`** | `Verifies`; v2 `true`, v3 `true`; `1` signer; certificate SHA-256 `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5` (same signing identity as `v1.0.5`, so in-place upgrade works) | **PASS** |
| **`aapt2 dump badging`** | `package: name='com.miniplayer.app' versionCode='10006' versionName='1.0.6'`, `application-label:'Play'`, `minSdkVersion:'26'`, `targetSdkVersion:'35'` | **PASS** |
| **Adaptive Icon Resources in APK** | `aapt2 dump xmltree` of the compiled foreground in the signed APK shows the exact `0.60x` pick + play path data. Independent Bézier sampling: pick bbox `38.40dp × 37.78dp`, `x ∈ [34.80, 73.20]`, `y ∈ [36.50, 74.28]`, area centroid `(54.00, 53.13)` (optically centered on `(54, 54)`), max radius `20.48dp` from center (≥ `15.5dp` margin inside the `r = 36dp` mask); play-triangle centroid `(54.16, 52.40)`. `drawable/ic_launcher_foreground.xml` (`108×108dp` canvas, `38.4dp × 37.8dp` `0.60x` wide-shoulder 351 pick `x ∈ [34.8, 73.2]`, `y ∈ [36.5, 74.3]`, `r_shoulder = 19.6dp`, `r_tip = 20.3dp`, ~`15dp–16dp` uniform margin in `72×72dp` circular mask); `color/ic_launcher_background` `()` `#F8FAFC` / `(night)` `#0F172A`; `color/ic_launcher_pick` `#F97316` / `#FB923C`; `color/ic_launcher_play` `#FFFFFF` / `#FFFBEB` | **PASS** |

---

## 2. `v1.0.6` Requirement Verification Matrix

| # | Requirement | Verified Implementation & Files | Status |
|---:|---|---|---|
| **1** | Scale layout-A wide-shoulder 351 guitar pick + centered rounded play triangle to `0.60x` (`38.4dp × 37.8dp`) and optically center for circular launcher masks | `app/src/main/res/drawable/ic_launcher_foreground.xml`: pick silhouette `M54,36.5 C66.6,36.5 73.2,41.3 73.2,50.0 C73.2,59.6 60.9,70.4 56.3,73.6 C54.9,74.5 53.1,74.5 51.7,73.6 C47.1,70.4 34.8,59.6 34.8,50.0 C34.8,41.3 41.4,36.5 54,36.5 Z` (`x ∈ [34.8, 73.2]`, `y ∈ [36.5, 74.3]`, shoulders at `(34.8, 50.0)` / `(73.2, 50.0)` with `r = 19.6dp` from `(54, 54)`, bottom tip at `(54, 74.3)` with `r = 20.3dp` from `(54, 54)`, leaving ~`15dp–16dp` margin inside the `r = 35dp..36dp` circular adaptive mask) + centered rounded play triangle `M48.9,45.5 C48.9,44.4 50.2,43.6 51.1,44.2 L62.5,51.1 C63.5,51.7 63.5,53.1 62.5,53.7 L51.1,60.6 C50.2,61.2 48.9,60.4 48.9,59.3 Z` | **PASS** |
| **2** | Version bump (`v1.0.6` / `10006`) & documentation update | `app/build.gradle.kts` (`versionCode = 10006`, `versionName = "1.0.6"`); `README.md`, `DESIGN.md`, `ARCHITECTURE.md`, and `TEST_REPORT.md` updated to `v1.0.6` (`10006`) and documenting the `38.4dp × 37.8dp` (`0.60x`) circular-mask optical centering | **PASS** |

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
