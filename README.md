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
    - **Row 1 — Bar-Snapped A-B Loop, Measure Ticks & Bookmarks**: Media3 `PlayerMessage` scheduled directly on `exoPlayer.applicationLooper`, `44 dp` 240-bar RMS waveform overlaid with detected bar/measure grid ticks (`BeatGrid`, every 4th bar highlighted) and bar-count loop header (`A 00:08.0 ━━● 4小节 ●━━ B 00:16.0`), `48×48 dp` rounded pill handles with **1:1 float finger tracking** and automatic bar-boundary snapping (`小节对齐`, `barSnapEnabled = true`), clean **`[A]` / `[B]`** timestamp buttons, instant restart-from-`A` (`⏮` single-tap guard with `400 ms` double-tap to `00:00`/previous), non-destructive loop suspend/clear, and an icon-only **Save Bookmark** button (`Intro`, `Verse`, `Pre-Chorus`, `Chorus`, `Bridge`, `Solo 1`, `Solo 2`, `Outro`, or custom label).
    - **Row 2 — Speed Stepper, HTDemucs 5-Stem Mixer, Beat-Only Count-In & Icon-Only Toggles**: `0.25x–2.00x` pitch-preserving speed stepper (`[-5%]` · `1.00x` reset · `[+5%]`) paired with **Pure Time-Domain Sonic Pitch Shift (`升降调`: `-6..+6 st` + `-50..+50 ct`)**, **On-Device Meta `HTDemucs` (`htdemucs_6s`) 5-Stem Offline Separator & Real-Time Mixer (`分轨乐器音量`: `人声 / 吉他 / Bass / 鼓 / 其他`, `0%–150%` + `M` / `S`)**, **1-Tap Beat-Only Count-In (`预备拍`: `关 ↔ 4拍`)**, anytime **`BPM <bpm>`** chip (expands `BpmQuickBar` with `小节对齐` toggle, `对齐第1拍` downbeat calibration, 1-tap **`"自动"`** auto-detection, and `Tap` tempo), icon-only **Keep-Screen-On (`练习常亮` `DeckIconToggleButton`)**, and **Video Mirror (`水平镜像`)**.
    - **Pure Sonic Real-Time Pitch Shift & On-Device `HTDemucs` 5-Stem Time-Domain Mixer (`RealTimeSonicAudioProcessor` + `StemSeparationEngine` + `HtdemucsStft`)**: `-6` to `+6` semitones + `-50` to `+50` cents fine tuning backed by pure time-domain `SonicAudioProcessor` (zero WOLA STFT phase/envelope artifacts, bit-exact passthrough at unity), paired with genuine **Meta Research `htdemucs_6s` (`htdemucs_6s.onnx`, `~105 MB`)** on-demand download + ONNX Runtime offline separation into 5 memory-mapped linear `BFP10` stereo stems (`.stems`, `~61 dB` SNR) mixed at zero latency in the time domain with sample-accurate cross-correlation playhead lock.
    - **Automatic BPM & Bar/Downbeat Grid Detection (`BpmDetector` + `WaveformExtractor.loadOrDetectBeatGrid`, `v1.0.12`)**: Automatically detects `BeatGrid(bpm, firstBeatMs, firstDownbeatMs, beatsPerBar = 4)` from a 3-band (`Low < 155 Hz` Kick/Bass, `Mid 155–2400 Hz` Snare/Chords, `High > 2400 Hz` Hi-Hat/Pick transients) log-compressed `100 Hz` onset-flux accumulator, fractional-lag multi-harmonic (`1x, 2x, 3x, 4x, 8x`) bar-aware autocorrelation with `< 2400 Hz` pulse vs `> 2400 Hz` subdivision octave disambiguation, multi-bar (`m = 1, 2, 3, 4, 6, 8, 12, 16`) `0.05 BPM` fine comb refinement, 128-phase parabolic beat alignment, and 4-phase Kick/Bass vs Snare backbeat downbeat scoring (`cacheDir/waveforms/bpm_v3_<id>_<mtime>.dat`), auto-populating `bpm` and `firstDownbeatMs` for instant measure-aligned A-B loops.
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

## What's New in `v1.0.12` (`versionCode = 10012`)

1. **Multi-Band Onset Flux, Multi-Bar Comb BPM Refinement & Snare-Backbeat-Aware Bar/Downbeat Detection (`BpmDetector.kt`, `WaveformExtractor.kt`, `PracticeLoopEngine.kt`, `PlayerController.kt`, `PracticeDeck.kt`, `MiniPlayerDatabase.kt`)**:
   - **3-Band IIR Filter Bank & Log-Compressed Onset Flux (`OnsetFluxAccumulator`)**: Splits incoming PCM into `Low (< 155 Hz)` Kick/Bass, `Mid (155–2400 Hz)` Snare body/Chords, and `High (> 2400 Hz)` Hi-Hat/Pick transients with log-compressed (`ln(1 + 24 * rms/p90)`) zero-phase onset flux and active groove window selection so quiet/rubato intros or loud crash peaks never skew detection.
   - **Fractional-Lag Multi-Harmonic Autocorrelation & Octave Disambiguation**: Evaluates `1x, 2x, 3x, 4x, 8x` beat-period harmonics and compares `< 2400 Hz` Low+Mid pulse correlation against `> 2400 Hz` High-band subdivisions so `65–85 BPM` 8th-note hi-hat grooves are never falsely doubled to `130–170 BPM`, followed by multi-bar (`m = 1, 2, 3, 4, 6, 8, 12, 16`) `0.05 BPM` fine comb refinement to eliminate cumulative bar-line drift across full songs.
   - **Kick/Bass Downbeat vs. Snare Backbeat (`2 & 4`) Bar Phase Scoring**: Scores all 4 candidate bar phases using Low-band Kick/Bass accents on Beat 1 (& Beat 3), explicit Snare backbeat contrast (`Mid+High > Low`) on Beats 2 & 4, bass/harmonic transitions at bar boundaries, and weak-pickup (anacrusis) entrance awareness—fixing the classic rock/pop/blues failure mode where a louder Snare on Beat 2 or 4 was mistaken for Beat 1 (`firstDownbeatMs`).
   - **Extended `45 s` + Mid-Track Decode Fallback, Acoustic `TBPM` Verification & Cache/DB Migration (`DATABASE_VERSION = 4`, `bpm_v3_*.dat`)**: Expands `MediaCodec` decoding to `45 s` from `0:00` with a 2-pass middle-section (`35%` offset) fallback when the intro has low rhythmic clarity (`< 0.22`), cross-checks embedded `TBPM` tags against audio correlation, bypasses disk cache on manual `"自动"` re-detection, re-snaps active A-B loops when `BeatGrid` updates, aligns 4-bar phrase highlights relative to `firstDownbeatMs`, and resets stale `v1.0.11` auto-detected `(bpm, first_downbeat_ms)` rows on upgrade to `DATABASE_VERSION = 4`.

---

## What's New in `v1.0.11` (`versionCode = 10011`)

1. **Local-Only Agent Feedback Email & 1-Click Send Feedback (`Agent 反馈邮箱` / `发送反馈给 Agent`, `AgentFeedbackHelper.kt`, `AgentFeedbackLauncher.kt`, `MiniPlayerViewModel.kt`, `SettingsScreen.kt`)**: Added a zero-leakage local Agent Feedback configuration (`SharedPreferences` key `agent_feedback_email`, default `""`, placeholder `agent@example.com`) and an in-app Material 3 feedback composer sheet under `关于与自动更新`. Pre-fills email subjects as **`play: [v1.0.11] <summary>`** (RFC 6068 `%20` percent-encoded `mailto:` / `FileProvider` `Intent.ACTION_SEND`), supports optional Android Photo Picker screenshot attachments (`PickVisualMedia`) and optional playback/practice state diagnostics without exposing local folder paths.

---

## What's New in `v1.0.10` (`versionCode = 10010`)

1. **Pure Time-Domain Sonic Pitch Shifting (`PracticeDeck.kt`, `RealTimeSonicAudioProcessor.kt`, `PracticeLoopEngine.kt`)**: Removed the `保持人声音色 (共振峰保护)` switch from `PitchShiftInlinePanel` and deleted the real-time WOLA STFT formant-preservation DSP path (`FormantStemDspProcessor.kt`) so semitone (`-6 .. +6 st`) and fine cents (`-50 .. +50 ct`) adjustments run exclusively through crisp, phase-coherent time-domain `SonicAudioProcessor` with zero STFT latency or spectral-envelope artifacts.
2. **Genuine Meta `HTDemucs` (`htdemucs_6s`) On-Device Offline 5-Stem Separation (`HtdemucsStft.kt`, `StemSeparationEngine.kt`, `RealTimeSonicAudioProcessor.kt`, `PracticeDeck.kt`)**: Replaced the `64 KB` heuristic filter (`miniplayer-stem-net-v1.bin`) with Meta Research's official pre-trained **`htdemucs_6s`** hybrid transformer exported to self-contained `htdemucs_6s.onnx` (`105.1 MB`, `cac=True` real `float32` STFT/waveform branches summing `other + piano` into 5 stems `[VOCALS, GUITAR, BASS, DRUMS, OTHER]`) executed on-device via **Microsoft ONNX Runtime Android (`1.20.0`)**. Features a clear 3-step UX in `StemMixerInlinePanel` (Step 1: on-demand model download with live byte progress & cancel and zero fake fallback; Step 2: `7.8 s` overlapping-segment separation with `1/N` segment progress & cancel into memory-mappable linear `BFP10` `.stems` cache at `~61 dB` SNR; Step 3: instant zero-latency time-domain 5-stem `0%–150%` / `M` / `S` mixing with sample-accurate cross-correlated playhead alignment).

---

## What's New in `v1.0.9` (`versionCode = 10009`)

1. **Formant-Preserving Pitch Shifting (`保持人声音色 / 共振峰保护`, `FormantStemDspProcessor.kt`, `RealTimeSonicAudioProcessor.kt`, `Models.kt`, `MiniPlayerDatabase.kt`, `PracticeDeck.kt`)**: Added a real-time **Zero-Phase Overlap-Add STFT Spectral Envelope Formant Corrector** (`N = 1024`, `hop = 256`, `75%` overlap periodic Hann analysis + synthesis windows, COLA factor `1 / 1.5`) right after `SonicAudioProcessor`. Using fast prefix-sum moving-average log-magnitude formant envelope extraction (`W = max(6, round(320 Hz * N / fs))`), inverse pitch-ratio envelope lookup (`E_orig[k] = E_shifted[clamp(k * p, 0, N/2)]`), raised-cosine vocal formant band weighting (`130–220 Hz` fade-in, `220–4500 Hz` unity, `4500–6200 Hz` fade-out), `[-2.2, +2.2]` (`±9.5 dB`) gain clamping, temporal smoothing (`0.65` / `0.35`), and RMS frame energy preservation, lowering or raising pitch preserves natural female/male vocal timbre without phase smearing or drum/guitar transient loss. Controlled via **`保持人声音色 (共振峰保护)`** (`preserveFormants = true` by default) in `PitchShiftInlinePanel` and persisted in SQLite `DATABASE_VERSION = 3`.
2. **On-Device AI / Hybrid 5-Stem Separation & Independent Volume Mixer (`人声 / 吉他 / Bass / 鼓 / 其他`, `StemSeparationEngine.kt`, `FormantStemDspProcessor.kt`, `PracticeDeck.kt`)**: Added independent `0%–150%` volume sliders, `M` (Mute), `S` (Solo), and `重置` controls for **5 stems** (`VOCALS("人声")`, `GUITAR("吉他")`, `BASS("Bass")`, `DRUMS("鼓")`, `OTHER("其他")`) in `StemMixerInlinePanel` (`Icons.Outlined.Tune` toggle in Row 2). Combines on-demand neural model management (`StemModelManager`, `64 KB` quantized 2-layer Mel/Bark + Mid/Side + HPSS MLP weights in `miniplayer-stem-net-v1.bin` downloadable from GitHub Releases `v1.0.9` with deterministic offline/bundled-asset fallback) and per-track offline `25 Hz` (`40 ms` hop × 16 Bark bands × 5 stems) `.stm` GZIP mask caching (`StemSeparationEngine`) with real-time STFT soft-mask Wiener Mid/Side separation ($\sum_s M_s[k] \equiv 1.0$, separate Mid/Side gains `G_M[k]` & `G_S[k]`, bit-transparent at unity).
3. **Automatic Bar / Downbeat Detection & Bar-Snap A-B Loop (`BeatGrid`, `BpmDetector.kt`, `WaveformExtractor.kt`, `PracticeLoopEngine.kt`, `PracticeDeck.kt`)**: Extended `OnsetFluxAccumulator` (`computeBeatGrid`) and `WaveformExtractor` (`loadOrDetectBeatGrid`) to detect `firstBeatMs` and `4/4` `firstDownbeatMs` alongside `bpm`. `WaveformLoopRuler` now renders subtle vertical measure tick lines (highlighting every 4th bar) and displays the exact bar span in the loop header (`A 00:08.0 ━━● 4小节 ●━━ B 00:16.0`). When **`小节对齐`** (`barSnapEnabled = true` by default) is active, `[A]`, `[B]`, live handle dragging, and loop-mode ruler seeks automatically snap to integer bar boundaries, with 1-tap **`对齐第1拍`** downbeat calibration in `BpmQuickBar`.
4. **1-Tap Beat-Only Count-In (`关 ↔ 4拍`, `PracticeLoopEngine.kt`, `CountInMetronome.kt`, `PracticeDeck.kt`, `VideoPracticeSurface.kt`, `MiniPlayerDatabase.kt`)**: Simplified `Count-In` to a direct 1-tap toggle between `OFF` and `BEAT_4` (`"4拍"` badge, woodblock accent on Beat 1 + clicks on Beats 2–4 scaled to `60000 / (bpm * speed)`), automatically migrating legacy `"SEC_1"` / `"SEC_2"` database states to `BEAT_4` and keeping `BpmQuickBar` collapsed unless explicitly opened via the `BPM` chip or long-pressing Count-In.

---

## What's New in `v1.0.8` (`versionCode = 10008`)

1. **Self-Drawn Frosted Charcoal Dark Icon Plate (`values/colors.xml`, restored `values-night/colors.xml`, `ic_launcher_background.xml`)**: Real-device testing on a OnePlus 13T (ColorOS 15) showed that the launcher's built-in "暗色图标" (Dark Icon) auto-conversion only applies to whitelisted Play Store package IDs such as Google Maps and Discord; a custom `applicationId` (`com.miniplayer.app`) gets no substitution, so after `v1.0.7` dropped `values-night` the icon stayed on its light `#FFFFFF` plate in Dark Mode. `v1.0.8` brings back `res/values-night/colors.xml` and reproduces the ColorOS / Discord / Google Maps frosted dark charcoal plate ourselves: `ic_launcher_background.xml` is now a two-layer `<vector>` (`xmlns:aapt`) — a vertical linear gradient across the visible `[18, 90]` circle (`startX/endX = 54`, `startY = 16`, `endY = 92`; stops `@color/ic_launcher_bg_top` `0.0` / `@color/ic_launcher_bg_mid` `0.48` / `@color/ic_launcher_bg_bottom` `1.0`) plus a soft radial frosted-glass diffusion glow (`centerX = 48`, `centerY = 32`, `gradientRadius = 46`; stops `@color/ic_launcher_bg_glow_start` `0.0` / `@color/ic_launcher_bg_glow_mid` `0.55` / `#00FFFFFF` `1.0`). **Light `1D`** (`values/colors.xml`): `ic_launcher_background #F8FAFC`, `bg_top #FFFFFF`, `bg_mid #F8FAFC`, `bg_bottom #F1F5F9`, glow fully transparent (`#00FFFFFF` / `#00FFFFFF`), pick `#F97316`, play `#FFFFFF`. **Dark Frosted Charcoal** (`values-night/colors.xml`): `ic_launcher_background #282A30`, `bg_top #363940`, `bg_mid #282A30`, `bg_bottom #1E2025`, glow `#1AFFFFFF` → `#09FFFFFF`, pick `#FB923C`, play `#FFFBEB`. The system switches palettes automatically with the Dark theme.

---

## What's New in `v1.0.7` (`versionCode = 10007`)

1. **System Frosted/Blurred Dark Icon Plate on ColorOS 15 / OxygenOS 15, Same as Google Maps & Discord (`values/colors.xml`, removed `values-night/colors.xml`)**: Deleted the `values-night` launcher palette and set the light `<background>` to pure **`#FFFFFF`** (matching Google Maps). On ColorOS 15 / OxygenOS 15 "暗色图标" (Dark Icons) launchers, the system detects a bright/colored adaptive-icon `<background>` and replaces it with its own semi-translucent frosted/blurred dark glass plate — exactly what Google Maps and Discord get. When `values-night/colors.xml` supplied a pre-darkened solid `#0F172A`, the launcher skipped that replacement and drew a flat dark circle instead. The `#F97316` pick and `#FFFFFF` play triangle are now the same in both light and dark system themes.
2. **Dedicated `evenOdd` Themed-Icon Cutout (`ic_launcher_monochrome.xml`, `mipmap-anydpi-v26/ic_launcher.xml`)**: `<monochrome>` now points to a new `@drawable/ic_launcher_monochrome` containing a single `android:fillType="evenOdd"` compound path (the `0.60x` guitar-pick contour + the inner play-triangle contour). Android 13+ / 15 themed-icon and Material You engines tint the whole drawable one color, so two overlapping paths used to merge into a solid pick; the even-odd path cuts the play triangle out cleanly.
3. **Elevated In-App Dark Surfaces (`Theme.kt`)**: `DefaultDarkScheme` now uses Google Material "Charcoal Dark" surfaces (`background` / `surface = #1E1F24`, `surfaceContainerLowest = #181A1F`, `surfaceContainerLow = #26282F`, `surfaceContainer = #2B2E36`, `surfaceContainerHigh = #32353E`, `surfaceContainerHighest = #3B3E48`) instead of near-black `#111318`. On Android 12+ with dynamic color, `elevateDynamicDarkSurfaces()` shifts the Material You dark palette one tonal step brighter (`background` / `surface = surfaceContainer`, `surfaceContainerLowest = surface`, `surfaceContainerLow = surfaceContainerHigh`, `surfaceContainer = surfaceContainerHighest`, `surfaceContainerHigh = surfaceBright`) so cards, sheets, and the now-playing bar keep visible tonal separation in dark mode.

---

## What's New in `v1.0.6` (`versionCode = 10006`)

1. **Circular-Mask Optical Centering & `0.60x` Scale for Launcher Icon (`ic_launcher_foreground.xml`)**: Scaled the layout-A wide-shoulder 351 guitar pick and centered rounded play triangle down to **`0.60x`** (`38.4dp × 37.8dp` on the `108×108dp` adaptive icon canvas, `x ∈ [34.8, 73.2]`, `y ∈ [36.5, 74.3]`), placing the left/right shoulders (`(34.8, 50.0)` / `(73.2, 50.0)`) at radius `19.6dp` from `(54, 54)` and the bottom tip (`(54, 74.3)`) at radius `20.3dp` from `(54, 54)` so circular launcher masks (`72×72dp`, `r = 35dp..36dp`) retain a balanced ~`15dp–16dp` background margin all around instead of clipping near the edge.

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
| `MiniPlayer-v1.0.12.apk` | Signed Android Release APK (`versionCode = 10012`, app label `Play`, `minSdk 26` / Android 8.0+, `targetSdk 35` / Android 15) |
| `app-release.apk` | Identical signed Android Release APK artifact |
| `htdemucs_6s.onnx` | Meta Research `htdemucs_6s` hybrid transformer ONNX model (`105.1 MB` / `110,242,365` bytes) downloaded on-demand in-app for 5-stem offline separation |
| `SHA256SUMS.txt` | SHA-256 checksum manifest verified by the in-app auto-updater |

---

## Building & Testing from Source

### Prerequisites
- **JDK 21** (`JAVA_HOME`)
- **Android SDK Platform 35** & Build-Tools `35.0.0` (`ANDROID_HOME`)

### Build, Unit/Integration Tests & Lint
```bash
export JAVA_HOME="/path/to/jdk21"
export ANDROID_HOME="/path/to/Android/Sdk"

# Run full JVM unit, real ONNX Runtime E2E & cross-module integration tests (94/94), debug/release APK builds, and Android Lint
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Output APKs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## Documentation

- [`DESIGN.md`](DESIGN.md) — Product & UX/UI specification (wireframes, M3 tonal system, Practice Mode state machine, `v1.0.4` folder isolation, `v1.0.5` staged candidate save / renamed-folder dedup, `v1.0.8` frosted charcoal dark icon plate, `v1.0.9` automatic bar-snap A-B loop & beat-only `4拍` count-in, `v1.0.10` pure Sonic pitch shifting & genuine Meta `HTDemucs` `htdemucs_6s.onnx` 5-stem separation + real-time time-domain mixer, `v1.0.11` local Agent Feedback sender, and `v1.0.12` 3-band onset-flux & Snare-backbeat-aware BPM/bar grid detection)
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — System architecture, 10-table SQLite DDL (`DATABASE_VERSION = 4`), Media3 threading/GOP/`RealTimeSonicAudioProcessor`/`HtdemucsStft`/`StemSeparationEngine`/`StemSeparatedTrackCache` (`BFP10`)/`BeatGrid` specs, LAN Web Studio & Auto-Updater security invariants
- [`TEST_REPORT.md`](TEST_REPORT.md) — Full unit, real `OrtEnvironment` `htdemucs_6s.onnx` E2E & cross-module integration (`94/94` tests across `15` suites), Lint, and `v1.0.12` verification report
