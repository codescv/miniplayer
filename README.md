# MiniPlayer

Minimalist, offline-first **Android Local Music & Video Player** built with **Jetpack Compose Material Design 3 (Material You)** and **AndroidX Media3**, combining distraction-free everyday listening/viewing with a progressive-disclosure **Practice Mode (`练习模式`)** for guitarists, musicians, and transcribers—plus a zero-key **Online Metadata & Lyrics Completer**, a built-in **PC LAN Web Studio (`:8088`)**, and **SHA-256 Verified GitHub Auto-Updates**.

---

## Key Features

- **Strictly Scoped Local Folders (`只扫描显式添加的文件夹`)**:
  - **Zero `MediaStore` spam & zero broad storage permissions**: Never requests `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, or `MANAGE_EXTERNAL_STORAGE`—no ringtones, call recordings, or chat voice notes polluting your library.
  - Mount specific folders on internal storage, SD cards, or USB OTG drives via Android SAF (`ACTION_OPEN_DOCUMENT_TREE`) with ancestor/child overlap deduplication, `<10ms` incremental cursor rescans, sidecar `.lrc` & `cover.jpg` indexing, moved-file fingerprint bookmark migration, and **Offline Volume Guard (`[⚠️ 未挂载]`)** that preserves playlists and A-B bookmarks when OTG drives are unplugged.
  - **2 Bottom Tabs + 4 Single-Select M3 Views**: `📁 文件夹` (hierarchical breadcrumbs `⇄` flat toggle), `💿 专辑` (`(Album, AlbumArtist)` grouping + `未知专辑` bucket), `🎤 歌手` (multi-artist `/` · `;` · `、` · `feat.` splitting with band allowlist like `AC/DC`), and `🎬 视频` (16:9 keyframe thumbnails, resolution badges, bookmark counts, and watch progress).

- **Unified Audio/Video Player & One-Tap Practice Deck (`练习模式 / 练习工作台`)**:
  - **Deterministic Progressive Disclosure**: Everyday albums/folders play in **Casual Mode** (`1.0x`, clean cover/video canvas, synced `.lrc` lyrics, sleep timer, 5-button transport, and a gentle `[🎯 练习模式 · 含 N 段]` hint pill when practice history exists). Playing from a **`🎯 练习列表`**, **`🎯 练习文件夹`** (with automatic subfolder inheritance), or **`[🔖 含段落书签]`** automatically opens the **Practice Deck** and restores saved speed, pitch, and A-B loops.
  - **Pick-Friendly Guitar/Instrument Ergonomics (`≥ 48–56 dp` targets on `360 dp` screens)**:
    - **Sample-Accurate A-B Loop**: Media3 `PlayerMessage` scheduled directly on `exoPlayer.applicationLooper`, `44 dp` 240-bar RMS waveform with `48×48 dp` waterdrop handles, `[↺ 回A]` instant restart, `[循环: 开/挂起]` non-destructive suspend, `±0.5s` coarse / `±0.1s` long-press fine nudges, and single-tap `⏮` restart-from-`A` guard (`400 ms` double-tap to `00:00`/previous).
    - **Named Section Bookmarks (`段落书签`)**: One-tap preset chips (`Intro`, `Verse`, `Pre-Chorus`, `Chorus`, `Bridge`, `Solo 1`, `Solo 2`, `Outro`) or custom labels saved per audio/video item with optional per-bookmark speed.
    - **Sonic Pitch-Preserving Speed & Speed Trainer (`变速不变调 & 渐进加速`)**: `0.25x–2.00x` (`[-5%]` / `[+5%]` + scrollable `0.5x..1.5x` presets) plus **Speed Trainer** stepping up `+2% / +5% / +10%` every `2 / 4 / 8` completed loops up to target speed.
    - **Tempo-Scaled Count-In & Pre-Roll (`循环前预备倒计时`)**: `关 / 1s / 2s / 4拍` count-in with synthesized zero-pop woodblock clicks scaled to `round(60000 / (bpm * speed))`, inline **Tap Tempo (`点按测速`)**, and optional `2s` original-audio Pre-Roll.
    - **Semitone & Cents Pitch Shift (`升降调微调`)**: `-6` to `+6` semitones (e.g., play `Eb` standard recordings without retuning your guitar) + `-50` to `+50` cents fine tuning via `PlaybackParameters(speed, 2^((st + cents/100)/12))`.
  - **Video Practice Enhancements**: `1.0x–4.0x` pinch-to-zoom & pan on fretboard/tab details (`[🔍 2.4x · 重置]`), `🪞` horizontal mirror flip, adaptive long-GOP video loop seeking (`>80 ms` keyframe fallback with `AUTO/EXACT` toggle), top/bottom dockable translucent landscape HUD (`[⬆ 移至顶部 / ⬇ 置底]` so bottom-burned guitar tabs are never covered), `FLAG_KEEP_SCREEN_ON`, and seamless screen-off audio-only continuation.

- **Dual-Layer Metadata Editor & Zero-Key Online Auto-Completion**:
  - Edit the **6 core fields** (`Title`, `Artist`, `Album`, `AlbumArtist`, `Track#`, `Year` + Cover) with instant `<5 ms` SQLite non-destructive overrides and optional crash-safe temp-staged SAF binary write-back (**ID3v2.4 + APIC** for `.mp3`, **Vorbis Comments + PICTURE** for `.flac/.ogg/.opus`, and **MP4 `ilst`** with `stco`/`co64` chunk offset recalculation for `.m4a/.mp4`, plus active-playback write deferral).
  - Batch editing locks per-track `Title` and `Track#` (`dirtyFields` tracking) to prevent accidental overwrites.
  - **Zero-Key Online Pipeline**: Smart filename/folder parser strips `[320k]`/`[FLAC]` noise and preserves `(Backing Track)` / `(伴奏)` suffixes while querying **MusicBrainz** (`1 req/s` rate-limited) + **Cover Art Archive** (strict **`3.0 s` timeout** auto-falling back to **iTunes Search `1000×1000` CDN**) + **LRCLIB** synced `.lrc` lyrics.

- **Built-in PC LAN Web Studio (`PC 局域网 Web 管理台`, Port `8088`)**:
  - On-demand pure-Kotlin HTTP/1.1 desktop companion (`💻` icon in the top bar -> scan QR or open `http://<phone-lan-ip>:8088` in any PC browser, zero desktop install required):
    1. **📁 Wireless Upload & Folders**: Drag-and-drop `.mp3/.flac/.wav/.m4a/.mp4/.mkv/.lrc` directly into phone SAF folders with automatic incremental indexing and `🎯 设为练习文件夹` toggles.
    2. **🏷️ Spreadsheet Metadata & Online Auto-Match**: Inline table editing, batch metadata updates, and 1-click keyless online auto-completion.
    3. **🎯 Playlists & `🎯 练习列表`**: Create/reorder mixed audio+video practice playlists and import/export UTF-8 `.m3u8` (`#EXT-X-MINIPLAYER-PRACTICE:1`).
    4. **🎸 PC Preview & Keyboard A-B Bookmark Studio**: HTTP `206 Partial Content` byte-range streaming to `<audio>`/`<video>` with keyboard `Space` (Play/Pause), `[` (Set A), `]` (Set B) to build section bookmarks on a big screen.
  - Protected by an `accept()`-level RFC 1918 LAN/loopback IP firewall, DNS-rebinding `Host` & CSRF `Origin` checks, and an optional 4-digit session PIN.

- **Verified In-App GitHub Auto-Updater**:
  - Checks [`codescv/miniplayer` Releases](https://github.com/codescv/miniplayer/releases/latest) over HTTPS (`≤ 5` manual redirect hops, zero HTTP downgrade), verifies `SHA256SUMS.txt` digests, runs 4-stage `ApkValidator` checks (`PK\x03\x04` + `AndroidManifest.xml`, `com.miniplayer.app` package match, strict `versionCode > installed` anti-downgrade, and signing certificate SHA-256 equality), and launches `FileProvider` installation.

---

## Download & Install

Download the latest signed APK from [**GitHub Releases**](https://github.com/codescv/miniplayer/releases/latest):

| Asset | Description |
|---|---|
| `MiniPlayer-v1.0.0.apk` | Signed Android Release APK (`minSdk 26` / Android 8.0+, `targetSdk 35` / Android 15) |
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

# Run full pure-JVM unit & cross-module integration tests, debug/release APK builds, and Android Lint
./gradlew --offline testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Output APKs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## Documentation

- [`DESIGN.md`](DESIGN.md) — Product & UX/UI specification (wireframes, M3 tonal system, Practice Mode state machine & ergonomics)
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — System architecture, 10-table SQLite DDL, Media3 threading/GOP/Sonic specs, LAN Web Studio & Auto-Updater security invariants
- [`TEST_REPORT.md`](TEST_REPORT.md) — Full unit, cross-module E2E integration, Lint, APK signature, and live GitHub Release verification report
