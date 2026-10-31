# MiniPlayer (`Play`)

Minimalist, offline-first **Android Local Music & Video Player** built with **Jetpack Compose Material Design 3 (Material You)** and **AndroidX Media3**, combining distraction-free everyday listening/viewing with a progressive-disclosure **Practice Mode (`练习模式`)** for guitarists, musicians, and transcribers—plus a zero-key **Online Metadata & Lyrics Completer**, a built-in **PC LAN Web Studio (`:8088`)**, and **SHA-256 Verified GitHub Auto-Updates**.

---

## Key Features

- **Strictly Scoped Local Folders (`只扫描显式添加的文件夹`) & Folder Isolation (`练习文件夹` / `不自动归类`)**:
  - **Zero `MediaStore` spam, zero broad storage permissions & zero `.nomedia` skipping**: Never requests `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, or `MANAGE_EXTERNAL_STORAGE`—no ringtones, call recordings, or chat voice notes polluting your library—and always scans all files inside explicitly added folders regardless of `.nomedia` marker files.
  - Mount specific folders on internal storage, SD cards, or USB OTG drives via Android SAF (`ACTION_OPEN_DOCUMENT_TREE`) with ancestor/child overlap deduplication, `<10ms` incremental cursor rescans, sidecar `.lrc` & `cover.jpg` indexing, moved-file fingerprint bookmark migration, and **Offline Volume Guard (`未挂载`)** that preserves playlists and A-B bookmarks when OTG drives are unplugged.
  - **Practice Folder (`练习文件夹`) & Exclude-from-Auto-Grouping (`不自动归类`) Isolation**: Marking any SAF root or subfolder as **`练习文件夹`** or **`不自动归类`** (each inherited automatically by descendant subfolders, with child-subfolder override support) keeps tracks cleanly accessible in **`文件夹`** (with 1-tap **`全部添加到播放列表`** on root folders, subfolders, and the active folder header), **`播放列表`**, and **`含段落书签`**, while automatically excluding them from **`专辑`**, **`歌手`**, and **`视频`**. Folders marked **`不自动归类`** (without **`练习文件夹`**) play in everyday **Casual Mode (`PlayContext.CASUAL`)**.
  - **2 Bottom Tabs + 4 Pure-Text M3 Views**: Ultra-clean `LibraryScreen` top bar keeping **only** the `设置` icon button and sorting strictly by natural name order (`Track 2 < Track 10`), clean `PlaylistsScreen` top bar with zero action icons, `文件夹` (icon-only tree `⇄` flat view toggle and icon-only Add Folder FAB), `专辑` (`(Album, AlbumArtist)` grouping + `未知专辑` bucket), `歌手` (multi-artist `/` · `;` · `、` · `feat.` splitting with band allowlist like `AC/DC`), and `视频` (16:9 keyframe thumbnails, resolution badges, bookmark counts, and watch progress).

- **Unified Audio/Video Player & Compact 2-Row Practice Deck (`练习模式 / 练习工作台`)**:
  - **Deterministic Progressive Disclosure & Guitar Icon (`GuitarIcon`)**: Everyday albums/folders play in **Casual Mode** (`1.0x`, clean cover/video canvas, synced `.lrc` lyrics via dead-centered `词` toggle, custom **Guitar Icon (`GuitarIcon`)** Practice Mode toggle, icon-only Add-to-Playlist button, sleep timer, and symmetric 5-button transport). Playing from a **`练习列表`**, **`练习文件夹`**, or **`含段落书签`** automatically opens the **Practice Deck** and restores saved speed, pitch, BPM, and A-B loops.
  - **Ergonomic 2-Row Practice Deck (`360 dp` portrait friendly)**:
    - **Row 1 — Butter-Smooth A-B Loop & Bookmarks**: Media3 `PlayerMessage` scheduled directly on `exoPlayer.applicationLooper`, `44 dp` 240-bar RMS waveform with `48×48 dp` rounded pill handles and **1:1 float finger tracking** (`pointerInput(safeDuration)` keyed strictly on track duration so active playback never interrupts handle drags), clean **`[A]` / `[B]`** timestamp buttons (tap anytime to mark or update `A` / `B` at current playhead), instant restart-from-`A` (`⏮` single-tap guard with `400 ms` double-tap to `00:00`/previous), non-destructive loop suspend/clear, and an icon-only **Save Bookmark** button (`Intro`, `Verse`, `Pre-Chorus`, `Chorus`, `Bridge`, `Solo 1`, `Solo 2`, `Outro`, or custom label).
    - **Row 2 — Speed Stepper, Anytime BPM Auto-Detect & Icon-Only Toggles**: `0.25x–2.00x` pitch-preserving speed stepper (`[-5%]` · `1.00x` reset · `[+5%]`) paired with **Pitch Shift (`升降调`)**, **Tempo-Scaled Count-In (`预备倒计时`: `关 / 1s / 2s / 4拍`)**, anytime **`BPM <bpm>`** chip (expands `BpmQuickBar` with 1-tap **`"自动"`** auto-detection + `Tap` tempo), icon-only **Keep-Screen-On (`练习常亮` `DeckIconToggleButton`)**, and **Video Mirror (`水平镜像`)**.
    - **Zero-Seek Real-Time Sonic Pitch Shift (`RealTimeSonicAudioProcessor`)**: `-6` to `+6` semitones + `-50` to `+50` cents fine tuning backed by a custom always-active 16-bit PCM `RealTimeSonicAudioProcessor` (`RealTimeAudioProcessorChain`). Rapid multi-click pitch shifts (`-1 半音` / `+1 半音` / `-5c` / `+5c`) drain outgoing `SonicAudioProcessor` frames seamlessly into a direct staging buffer and swap parameters in-place—**zero `exoPlayer.seekTo` calls, zero `AudioTrack` / `MediaCodec` resets, and zero audio dropouts**.
    - **Automatic BPM Detection (`BpmDetector` + `WaveformExtractor.loadOrDetectBpm`)**: Automatically populates `practiceState.bpm` when a track loads at default `120` BPM (checking disk cache, embedded `TBPM`, or 100 Hz onset-envelope autocorrelation over decoded PCM skipping silent intros), with 1-tap **`"自动"`** re-detection anytime in `BpmQuickBar`.
  - **Video Practice Enhancements**: `1.0x–4.0x` pinch-to-zoom & pan on fretboard/tab details, horizontal mirror flip, adaptive long-GOP video loop seeking (`>80 ms` keyframe fallback with `AUTO/EXACT` toggle), top/bottom dockable translucent landscape HUD, per-mode `FLAG_KEEP_SCREEN_ON`, and seamless screen-off audio-only continuation.

- **Dual-Layer Metadata Editor & Zero-Key Online Auto-Completion**:
  - Edit the **6 core fields** (`Title`, `Artist`, `Album`, `AlbumArtist`, `Track#`, `Year` + Cover) with instant `<5 ms` SQLite non-destructive overrides and per-edit optional crash-safe temp-staged SAF binary write-back (**ID3v2.4 + APIC** for `.mp3`, **Vorbis Comments + PICTURE** for `.flac/.ogg/.opus`, and **MP4 `ilst`** with `stco`/`co64` chunk offset recalculation for `.m4a/.mp4`, plus active-playback write deferral).
  - Batch editing locks per-track `Title` and `Track#` (`dirtyFields` tracking) to prevent accidental overwrites.
  - **Zero-Key Online Pipeline**: Smart filename/folder parser strips `[320k]`/`[FLAC]` noise and preserves `(Backing Track)` / `(伴奏)` suffixes while querying **MusicBrainz** (`1 req/s` rate-limited) + **Cover Art Archive** (strict **`3.0 s` timeout** auto-falling back to **iTunes Search `1000×1000` CDN**) + **LRCLIB** synced `.lrc` lyrics.

- **Built-in PC LAN Web Studio (`PC 局域网 Web 管理台`, Port `8088`)**:
  - On-demand pure-Kotlin HTTP/1.1 desktop companion (scan QR or open `http://<phone-lan-ip>:8088` in any PC browser, zero desktop install required):
    1. **Wireless Upload & Folders**: Drag-and-drop `.mp3/.flac/.wav/.m4a/.mp4/.mkv/.lrc` directly into phone SAF folders with automatic incremental indexing and Practice Folder toggles.
    2. **Spreadsheet Metadata & Online Auto-Match**: Inline table editing, batch metadata updates, and 1-click keyless online auto-completion.
    3. **Playlists & Practice Playlists (`练习列表`)**: Create/reorder mixed audio+video practice playlists and import/export UTF-8 `.m3u8` (`#EXT-X-MINIPLAYER-PRACTICE:1`).
    4. **PC Preview & Keyboard A-B Bookmark Studio**: HTTP `206 Partial Content` byte-range streaming to `<audio>`/`<video>` with keyboard `Space` (Play/Pause), `[` (Set A), `]` (Set B) to build section bookmarks on a big screen.
  - Protected by an `accept()`-level RFC 1918 LAN/loopback IP firewall, DNS-rebinding `Host` & CSRF `Origin` checks, and an optional 4-digit session PIN.

- **Verified In-App GitHub Auto-Updater**:
  - Checks [`codescv/miniplayer` Releases](https://github.com/codescv/miniplayer/releases/latest) over HTTPS (`≤ 5` manual redirect hops, zero HTTP downgrade), verifies `SHA256SUMS.txt` digests, runs 4-stage `ApkValidator` checks (`PK\x03\x04` + `AndroidManifest.xml`, `com.miniplayer.app` package match, strict `versionCode > installed` anti-downgrade, and signing certificate SHA-256 equality), and launches `FileProvider` installation.

---

## What's New in `v1.0.5` (`versionCode = 10005`)

1. **Stage Online Metadata Candidates, Save Manually (`Models.kt`, `DefaultMetadataEngine.kt`, `MetadataEditorSheet.kt`, `MiniPlayerViewModel.kt`, `LanStudioServer.kt`, `LanStudioWebApp.kt`)**: Tapping an online match card in the metadata editor no longer auto-saves or closes the sheet. It fills `Title`, `Artist`, `Album`, `AlbumArtist`, `Track#`, `Year`, cover preview, and lyrics into the form, highlights the selected card (`已填入 · 请点上方保存`), and only commits when you tap **`保存`**. `MetadataDraft` carries the staged `coverRemoteUrl`, `syncedLrc`, and `plainLyrics`; the cover is downloaded outside the file-write lock (a local gallery image still wins), and lyrics are cached on save. The PC Web Studio edit modal works the same way: candidates appear inline in `#ed-candidates-box` (new `GET /api/metadata/candidates` / `"candidatesOnly": true`) and nothing is saved until **`保存更改`**.
2. **No More Stale / Duplicate Renamed Folders (`SafFolderScanner.kt`, `MiniPlayerDatabase.kt`, `MediaRepository.kt`, `MiniPlayerViewModel.kt`, `MainActivity.kt`)**: Renaming a subfolder `rock` → `Rock` (case-only, whether or not SAF keeps the same document URIs) or `rock` → `metal` now leaves only the new folder in **`文件夹`**. Tracks are migrated in place (keeping IDs, playlists, bookmarks, and overrides), `folder_rules` are moved to the new path or pruned, `folder_rules` can no longer create ghost folders, an open breadcrumb on the old path follows the rename, and returning to the app triggers a debounced incremental rescan.
3. **App Name `Play` & Theme-Adaptive Guitar Pick Icon (`strings.xml`, `LibraryScreen.kt`, `values/colors.xml`, `values-night/colors.xml`, `ic_launcher_*.xml`)**: The launcher label and Library top bar now read **`Play`**. The new adaptive icon is a wide-shoulder 351 guitar pick with a centered rounded play triangle (no outer circle): Light `1D` (`#F8FAFC` / `#F97316` / `#FFFFFF`) and Dark `1A` (`#0F172A` / `#FB923C` / `#FFFBEB`), switched automatically by the system theme.

---

## What's New in `v1.0.4` (`versionCode = 10004`)

1. **`"不自动归类"` (`Exclude from Auto-Grouping`) on Folders (`Models.kt`, `MiniPlayerDatabase.kt`, `MediaRepository.kt`, `SafFolderScanner.kt`, `MiniPlayerViewModel.kt`, `LibraryScreen.kt`, `LanStudioServer.kt`)**: Mark any SAF root folder or subfolder as **`"不自动归类"`** (`DATABASE_VERSION = 2` `exclude_from_grouping`, automatically inherited by descendant subfolders with child override support) so its audio/video files are hidden from **`专辑`**, **`歌手`**, and **`视频`** while remaining browsable in **`文件夹`**, **`播放列表`**, and **`含段落书签`**, and playing in everyday **Casual Mode (`PlayContext.CASUAL`)** unless also marked as a Practice Folder. Includes `"不自动归类"` badge pills, `"设为不自动归类"` / `"取消不自动归类"` `⋮` menu actions, and an active-folder status banner.
2. **`"全部添加到播放列表"` (`Add All to Playlist`) on Folders (`MiniPlayerViewModel.kt`, `LibraryScreen.kt`)**: Added 1-tap **`"全部添加到播放列表"`** (`Icons.AutoMirrored.Outlined.PlaylistAdd`) across all 3 folder surfaces—Root folder `⋮` menu, Subfolder `⋮` menu, and the Currently Browsed Folder breadcrumb header—collecting all media items recursively under that folder in deterministic natural title order (`Track 2 < Track 10`) into the playlist picker.
3. **Removed All `.nomedia` Settings & Always Ignore `.nomedia` (`SettingsScreen.kt`, `LibraryScreen.kt`, `SafFolderScanner.kt`, `MediaRepository.kt`)**: Removed `"遵循子目录 .nomedia 跳过扫描"` from Settings and all `.nomedia` badges/menu items from `LibraryScreen`; `SafFolderScanner` and `MediaRepository` now unconditionally scan all files inside user-added SAF folders regardless of `.nomedia` marker files.

---

## What's New in `v1.0.3` (`versionCode = 10003`)

1. **Icon-Only Keep-Screen-On Toggle (`PracticeDeck.kt`, `VideoPracticeSurface.kt`)**: Removed the `"常亮"` text label from the Keep-Screen-On button in both portrait `PracticeDeck` and landscape `LandscapeVideoPlayerView`, converting it to a clean icon-only `DeckIconToggleButton` (`Icons.Outlined.LightMode`) with `"练习常亮：已开启"` / `"练习常亮：已关闭"` accessibility semantics.
2. **Zero-Icon `PlaylistsScreen` Top Bar (`PlaylistsScreen.kt`)**: Removed all 3 top-bar action icons (`Search`, `.m3u8` `FileOpen`, and `Settings`) from `PlaylistsScreen` (`actions = {}`) for an uncluttered playlist hub.
3. **Single-Icon `LibraryScreen` Top Bar & Default Natural Name Sort (`LibraryScreen.kt`)**: Simplified `LibraryScreen`'s `TopAppBar` to keep **only** the `设置` (`Settings`) icon button (removing `Search`, `Web Studio`, `Sort`, and `Refresh`), and unified all 4 library views on deterministic natural name sorting (`SortOrder.NAME_ASC`, `Track 2 < Track 10`).
4. **Streamlined `SettingsScreen` with Dedicated Web Studio Entry (`SettingsScreen.kt`)**: Removed the redundant `"管理已添加的文件夹"` row and `SafRootsManagerSheet` from Settings (folders are managed directly in `媒体库 -> 文件夹`) while keeping a dedicated **`PC 局域网 Web 管理台`** (`打开`) row under `本地媒体库与无线连接`.

---

## What's New in `v1.0.2` (`versionCode = 10002`)

1. **Guitar Icon for Practice Mode (`GuitarIcon.kt`, `NowPlayingSheet.kt`, `VideoPracticeSurface.kt`, `LibraryScreen.kt`, `PlaylistsScreen.kt`)**: Replaced generic slider/tune icons with a custom Material-styled diagonal acoustic/electric **Guitar Icon (`GuitarIcon`)** across Casual Mode, Practice Mode top bar, landscape video HUD, and Practice Folder / Practice Playlist actions.
2. **Zero-Seek Multi-Click Pitch Shift (`RealTimeSonicAudioProcessor.kt`, `PlaybackService.kt`, `PlayerController.kt`)**: Introduced an always-active `RealTimeSonicAudioProcessor` (`RealTimeAudioProcessorChain`) that drains outgoing `SonicAudioProcessor` tail samples into a direct staging buffer and updates pitch/speed in-place—eliminating `exoPlayer.seekTo` pipeline flushes and `AudioTrack` re-creations so rapid multi-click `-1 半音` / `+1 半音` / `-5c` / `+5c` adjustments never stall playback.
3. **Labeled `"常亮"` Keep-Screen-On Pill (`PracticeDeck.kt`, `VideoPracticeSurface.kt`)**: Replaced the bare sun icon with a self-explanatory **`"常亮"`** pill button (`KeepScreenOnPillButton` with `Role.Switch` semantics) in both portrait Practice Deck and landscape video HUD.
4. **Simplified `[A]` `[B]` Strip & Butter-Smooth Handle Dragging (`PracticeDeck.kt`, `VideoPracticeSurface.kt`)**: Removed cluttered `A+ / A- / B+ / B-` nudge buttons and the left `Replay` button in favor of clean `[A]` / `[B]` timestamp buttons, and keyed `WaveformLoopRuler` `.pointerInput(safeDuration)` strictly on duration with 1:1 float grab-offset tracking so dragging `A` and `B` handles stays rock-steady during active loop playback.
5. **Automatic BPM Detection & Anytime BPM Bar (`BpmDetector.kt`, `WaveformExtractor.kt`, `PracticeDeck.kt`)**: Added 100 Hz onset-envelope autocorrelation BPM detection (`loadOrDetectBpm` with disk cache and silent-intro skip) that auto-populates `practiceState.bpm` when loading tracks at default `120` BPM, plus an anytime `BPM <bpm>` chip and 1-tap **`"自动"`** button in `BpmQuickBar`.

---

## What's New in `v1.0.1` (`versionCode = 10001`)

1. **Compact 2-Row Practice Deck (`PracticeDeck.kt`)**: Removed the redundant speed preset chip row and progressive Speed Trainer panel; streamlined into strictly **2 rows** below the waveform ruler with concise `A` / `B` / `A -` / `+ A` / `B -` / `+ B` loop controls and icon-only toggles for Pitch Shift, Count-In, Keep Screen On, Video Mirror, and Save Bookmark.
2. **Pitch Shift Audio-Loss Bugfix (`PlaybackService.kt`, `PlayerController.kt`)**: Configured `DefaultAudioSink` for 16-bit PCM (`enableFloatOutput = false`, `enableAudioTrackPlaybackParams = false`) so `SonicAudioProcessor` never drops float PCM streams when `pitch != 1.0f`, paired with an in-place audio pipeline flush on pitch or Sonic active-state transitions.
3. **Practice Folder Library Isolation (`MediaRepository.kt`, `LanStudioServer.kt`)**: Files inside Practice Folders (root or inherited subfolders) are excluded from `专辑`, `歌手`, and `视频` views while remaining fully browsable in `文件夹` and playable in `播放列表` / `含段落书签`.
4. **Icon-Only & Emoji-Free M3 UI Polish (`NowPlayingSheet.kt`, `LibraryScreen.kt`, `PlaylistsScreen.kt`, `SettingsScreen.kt`, `LanStudioWebApp.kt`)**:
   - Casual Player: icon-only Practice Mode & Add-to-Playlist buttons, plus optical vertical centering on `"词"` (`includeFontPadding = false`).
   - Library & Playlists: pure-text filter chips (`文件夹`, `专辑`, `歌手`, `视频`; `全部`, `练习列表`, `含段落书签`), zero emojis across lists/menus/Web Studio, and icon-only FABs & tree/flat view toggle.
   - Settings Streamlining: moved `保持常亮` directly into the Practice Deck, enabled Count-In click sound (`true`) by default, and kept file tag write-back exclusively per-edit in `MetadataEditorSheet`.

---

## Download & Install

Download the latest signed APK from [**GitHub Releases**](https://github.com/codescv/miniplayer/releases/latest):

| Asset | Description |
|---|---|
| `MiniPlayer-v1.0.5.apk` | Signed Android Release APK (`versionCode = 10005`, app label `Play`, `minSdk 26` / Android 8.0+, `targetSdk 35` / Android 15) |
| `app-release.apk` | Identical signed Android Release APK artifact |
| `SHA256SUMS.txt` | SHA-256 checksum manifest verified by the in-app auto-updater |

---

## Building & Testing from Source

### Prerequisites
- **JDK 21** (`JAVA_HOME`)
- **Android SDK Platform 35** & Build-Tools `35.0.0` (`ANDROID_HOME`)

### Offline Build, Unit/Integration Tests & Lint
```bash
export JAVA_HOME="/path/to/jdk21"
export ANDROID_HOME="/path/to/Android/Sdk"

# Run full pure-JVM unit & cross-module integration tests (71/71), debug/release APK builds, and Android Lint
./gradlew --offline testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Output APKs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## Documentation

- [`DESIGN.md`](DESIGN.md) — Product & UX/UI specification (wireframes, M3 tonal system, Practice Mode state machine, `v1.0.4` folder isolation, and `v1.0.5` staged candidate save / renamed-folder dedup / `Play` adaptive icon)
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — System architecture, 10-table SQLite DDL (`DATABASE_VERSION = 2`), Media3 threading/GOP/`RealTimeSonicAudioProcessor`/`BpmDetector` specs, LAN Web Studio & Auto-Updater security invariants
- [`TEST_REPORT.md`](TEST_REPORT.md) — Full unit & cross-module E2E integration (`71/71` tests), Lint, and `v1.0.5` verification report
