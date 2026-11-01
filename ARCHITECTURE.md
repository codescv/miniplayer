# MiniPlayer — 系统架构与模块契约规范 (Architecture & Module Contracts)

> **版本**：v1.0.9 (`versionCode = 10009`, 应用显示名 `Play`, `applicationId = "com.miniplayer.app"`, `minSdk = 26`, `targetSdk = 35`)  
> **配套产品设计文档**：[DESIGN.md](./DESIGN.md)  
> **构建与离线环境**：Kotlin `2.0.21` · AGP `8.7.1` · Jetpack Compose BOM `2024.10.01` (Material 3 `1.3.1`) · AndroidX Media3 `1.5.1` · SQLite (`SQLiteOpenHelper` `DATABASE_VERSION = 3` + Kotlin `StateFlow`)

---

## 1. 模块分层与并行开发边界 (Module Ownership & Worktree Isolation)

为支持多模块零冲突并行开发，代码库按严格包边界切分为 **共享契约层 (`main` 基座)**、**核心数据与元数据层 (`feat/core`)** 与 **Media3 播放引擎及 Compose M3 界面层 (`feat/ui-player`)**：

```mermaid
flowchart TD
    subgraph UI_Player["feat/ui-player (@coder-ui)"]
        UI["com.miniplayer.app.ui.*\nM3 Screens · GuitarIcon · PracticeDeck · VideoPracticeSurface\nMetadataEditorSheet · SettingsScreen"]
        VM["MiniPlayerViewModel"]
        PL["com.miniplayer.app.player.*\nPlaybackService · RealTimeSonicAudioProcessor · FormantStemDspProcessor\nStemSeparationEngine · PlayerController · PracticeLoopEngine\nCountInMetronome · WaveformExtractor · BpmDetector"]
    end

    subgraph LAN_Studio["feat/lan-studio (@coder-lan)"]
        LAN["com.miniplayer.app.lan.*\nLanStudioServer (:8088 HTTP/1.1 + 206 Range)\nQrCodeGenerator · LanStudioSheet · Web SPA"]
    end

    subgraph Shared["main 基座共享契约 (禁止破坏已有签名)"]
        Models["com.miniplayer.app.core.model.*\nModels.kt (不可变领域模型与枚举)"]
        Contracts["com.miniplayer.app.core.*\nCoreContracts.kt & CoreRegistry"]
    end

    subgraph Core_Impl["feat/core (@coder-core)"]
        Impl["DefaultMiniPlayerCore"]
        DB["core.db.*\nMiniPlayerDatabase (SQLite 10 表)\nMediaRepository (响应式 Flow)"]
        Scan["core.scanner.*\nSafFolderScanner (游标增量扫描 + 防重 + 离线保护)"]
        Meta["core.metadata.*\nAudioTagCodec · SmartFilenameParser · ArtistSplitter\nOnlineMetadataCompleter · LrcParser · M3uPlaylistCodec"]
        Upd["core.update.*\nAppUpdater · ApkValidator"]
    end

    UI --> VM
    UI -. "💻 入口呼出" .-> LAN
    LAN --> Contracts
    VM --> PL
    VM --> Contracts
    PL --> Contracts
    Contracts -. "CoreRegistry 绑定" .-> Impl
    Impl --> DB
    Impl --> Scan
    Impl --> Meta
    Impl --> Upd
```

### 1.1 工作树目录与文件独占归属规则（零合并冲突保证）

| 角色 / 分支 | 隔离工作树路径 | 独占新增/修改范围 | 严禁触碰的文件（防止合并冲突） |
|---|---|---|---|
| **基座 (`main`)** | `/usr/local/google/home/chii/Developer/miniplayer` | Gradle 构建脚本、`AndroidManifest.xml`、`res/` 基础资源、`core/model/Models.kt`、`core/CoreContracts.kt` | — |
| **`@coder-core` (`feat/core`)** | `.worktrees/coder-core` | `core/DefaultMiniPlayerCore.kt`、`core/db/**`、`core/scanner/**`、`core/metadata/**`、`core/update/**`、`app/src/test/java/com/miniplayer/app/core/**` | **严禁修改** `MainActivity.kt`、`MiniPlayerApplication.kt`、`player/**`、`ui/**`、`lan/**`、`AndroidManifest.xml`、`build.gradle.kts` |
| **`@coder-ui` (`feat/ui-player`)** | `.worktrees/coder-ui` | `MainActivity.kt`、`MiniPlayerApplication.kt`、`player/**`、`ui/**`（预留 `💻` 顶栏按钮与设置入口回调） | **严禁修改** `core/db/**`、`core/scanner/**`、`core/metadata/**`、`core/update/**`、`lan/**`、`build.gradle.kts` |
| **`@coder-lan` (`feat/lan-studio`)** | `.worktrees/coder-lan` | `lan/LanStudioServer.kt`、`lan/QrCodeGenerator.kt`、`lan/LanStudioSheet.kt` 及 `lan/**` 内部辅助类、`app/src/test/java/com/miniplayer/app/lan/**` | **严禁修改** `core/**`、`player/**`、`ui/**`、`MainActivity.kt`、`build.gradle.kts` |
| **`@tester` (`feat/verify`)** | `.worktrees/tester` | 合并 `feat/core` + `feat/ui-player` + `feat/lan-studio`、挂接 `LanStudioSheet`、集成测试 `app/src/test/**`、`README.md`、`TEST_REPORT.md`、Release 打包与 GitHub 发布 | — |

> **关键离线构建约束**：本地 Gradle 离线缓存已预置 `media3-exoplayer:1.5.1`、`media3-session:1.5.1`、`media3-common:1.5.1`、`media3-extractor:1.5.1`、`media3-datasource:1.5.1`、`media3-decoder:1.5.1`、`media3-container:1.5.1`（已全部配入 `app/build.gradle.kts`）。**注意本地离线缓存不含 `media3-ui`**：视频渲染统一使用 Jetpack Compose `AndroidView(factory = { TextureView(it) })` 配合 `exoPlayer.setVideoTextureView(textureView)`，天然完美支持 `1.0x–4.0x` 缩放平移变换与 `scaleX = -1f` 水平镜像翻转！

---

## 2. SQLite 数据库完整 DDL 规范 (`core/db/MiniPlayerDatabase.kt`)

采用原生高性能 `SQLiteOpenHelper`（开启 `WAL` 并发读写模式与 `PRAGMA foreign_keys = ON`）配合协程 `Dispatchers.IO` 与 `MutableStateFlow` 失效通知机制（避免离线环境 KSP 注解处理器依赖问题）：

```sql
-- 1. 受控 SAF 根目录表
CREATE TABLE saf_roots (
    root_id INTEGER PRIMARY KEY AUTOINCREMENT,
    tree_uri TEXT NOT NULL UNIQUE,
    tree_doc_id TEXT NOT NULL,           -- 用于父子目录重叠检测 (如 primary:Music vs primary:Music/Lossless)
    display_name TEXT NOT NULL,
    is_writable INTEGER NOT NULL DEFAULT 0,
    mount_state TEXT NOT NULL DEFAULT 'MOUNTED', -- MOUNTED | OFFLINE_UNMOUNTED | PERMISSION_REVOKED
    last_scan_at INTEGER NOT NULL DEFAULT 0
);

-- 2. 任意层级文件夹规则表 (DATABASE_VERSION = 2; 支持根或任意子文件夹标记练习文件夹与「不自动归类」，并通过 onUpgrade/onOpen 安全幂等迁移列)
CREATE TABLE folder_rules (
    folder_uri TEXT PRIMARY KEY,
    root_id INTEGER NOT NULL REFERENCES saf_roots(root_id) ON DELETE CASCADE,
    rel_path TEXT NOT NULL,              -- 相对根目录路径，如 "" (根) 或 "Backing/J-Rock"
    is_practice_folder INTEGER NOT NULL DEFAULT 0,
    exclude_from_grouping INTEGER NOT NULL DEFAULT 0, -- 1 表示该目录及继承子目录不参与专辑/歌手/视频自动归类
    respect_nomedia INTEGER              -- 历史兼容保留列；扫描器始终忽略 .nomedia 完整索引媒体
);
CREATE INDEX idx_folder_rules_root_path ON folder_rules(root_id, rel_path);

-- 3. 音视频媒体主表
CREATE TABLE media_items (
    item_id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_uri TEXT NOT NULL UNIQUE,
    root_id INTEGER NOT NULL REFERENCES saf_roots(root_id) ON DELETE CASCADE,
    parent_rel_path TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    is_video INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    last_modified INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    raw_title TEXT,
    raw_artist TEXT,
    raw_album TEXT,
    raw_album_artist TEXT,
    raw_track_num INTEGER,
    raw_year INTEGER,
    parsed_title TEXT NOT NULL,          -- SmartFilenameParser 预清洗标题
    parsed_artist TEXT,                  -- SmartFilenameParser 路径/文件名推断歌手
    parsed_album TEXT,                   -- SmartFilenameParser 路径推断专辑
    is_backing_track INTEGER NOT NULL DEFAULT 0,
    embedded_cover_path TEXT,            -- 缓存的内嵌封面或视频关键帧缩略图路径
    folder_cover_uri TEXT,               -- 同目录 cover.jpg / folder.jpg URI
    fingerprint_key TEXT NOT NULL,       -- "${file_name.lowercase()}|${size_bytes}|${duration_ms}" 用于移动后迁移书签
    last_position_ms INTEGER NOT NULL DEFAULT 0,
    last_played_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_media_root_parent ON media_items(root_id, parent_rel_path);
CREATE INDEX idx_media_fingerprint ON media_items(fingerprint_key);
CREATE INDEX idx_media_is_video ON media_items(is_video);

-- 4. 第 1 层本地元数据覆盖表 (<5ms 瞬时生效，非破坏性)
CREATE TABLE media_metadata_overrides (
    item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
    title TEXT,
    artist TEXT,
    album TEXT,
    album_artist TEXT,
    track_num INTEGER,
    year INTEGER,
    cover_local_path TEXT,
    updated_at INTEGER NOT NULL,
    synced_to_file INTEGER NOT NULL DEFAULT 0
);

-- 5. 多歌手拆分交叉索引表
CREATE TABLE media_item_artists (
    item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
    artist_norm TEXT NOT NULL,
    artist_display TEXT NOT NULL,
    ord INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (item_id, artist_norm)
);
CREATE INDEX idx_artist_norm ON media_item_artists(artist_norm);

-- 6. 播放列表与明细表 (支持普通列表与 🎯 练习列表，支持音视频混排)
CREATE TABLE playlists (
    playlist_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    is_practice_playlist INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE playlist_items (
    playlist_id INTEGER NOT NULL REFERENCES playlists(playlist_id) ON DELETE CASCADE,
    item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, item_id)
);
CREATE INDEX idx_playlist_sort ON playlist_items(playlist_id, sort_order);

-- 7. 单曲练习状态记忆表 (DATABASE_VERSION = 3; onUpgrade/onOpen 幂等迁移新增列，旧 SEC_1/SEC_2 自动归一为 BEAT_4)
CREATE TABLE track_practice_states (
    item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
    practice_speed REAL NOT NULL DEFAULT 1.0,
    pitch_semitones INTEGER NOT NULL DEFAULT 0,
    pitch_cents INTEGER NOT NULL DEFAULT 0,
    preserve_formants INTEGER NOT NULL DEFAULT 1, -- 1 开启零相位 STFT 共振峰保护 (保持人声音色)
    bpm INTEGER NOT NULL DEFAULT 120,
    count_in_mode TEXT NOT NULL DEFAULT 'OFF',    -- OFF | BEAT_4 (读取旧库 SEC_1/SEC_2 自动映射为 BEAT_4)
    pre_roll_enabled INTEGER NOT NULL DEFAULT 0,
    loop_enabled INTEGER NOT NULL DEFAULT 0,
    bar_snap_enabled INTEGER NOT NULL DEFAULT 1,  -- 1 开启 A-B 循环与标尺小节边界自动吸附
    first_downbeat_ms INTEGER NOT NULL DEFAULT 0, -- 第 1 小节重拍起点相位 (ms)
    stem_mix TEXT NOT NULL DEFAULT '',            -- 5 轨混音紧凑编码 (vocals,guitar,bass,drums,other|muted|solo)
    active_a_ms INTEGER,
    active_b_ms INTEGER,
    video_mirror INTEGER NOT NULL DEFAULT 0,
    video_zoom REAL NOT NULL DEFAULT 1.0,
    hud_dock_top INTEGER NOT NULL DEFAULT 0,
    video_seek_mode TEXT NOT NULL DEFAULT 'AUTO', -- AUTO | EXACT
    updated_at INTEGER NOT NULL
);

-- 8. 命名段落书签表 (Intro / Verse / Chorus / Solo 1 等)
CREATE TABLE practice_bookmarks (
    bookmark_id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id INTEGER NOT NULL REFERENCES media_items(item_id) ON DELETE CASCADE,
    label TEXT NOT NULL,
    a_ms INTEGER NOT NULL,
    b_ms INTEGER NOT NULL,
    saved_speed REAL,
    created_at INTEGER NOT NULL
);
CREATE INDEX idx_bookmarks_item_a ON practice_bookmarks(item_id, a_ms);

-- 9. 歌词缓存表 (内嵌 / 同目录 .lrc / LRCLIB 联网)
CREATE TABLE lyrics_cache (
    item_id INTEGER PRIMARY KEY REFERENCES media_items(item_id) ON DELETE CASCADE,
    synced_lrc TEXT,
    plain_lyrics TEXT,
    source TEXT NOT NULL,                -- EMBEDDED | LOCAL_LRC | LRCLIB
    source_modified_at INTEGER NOT NULL DEFAULT 0
);

-- 10. 极简全局偏好键值表
CREATE TABLE app_settings (
    pref_key TEXT PRIMARY KEY,
    pref_value TEXT NOT NULL
);
```

---

## 3. 共享契约与动态服务注册 (`Models.kt` & `CoreContracts.kt`)

为保证 `@coder-core` 与 `@coder-ui` 在各自独立工作树内**无需等待对方即可直接编译通过并运行测试**：
1. `core/model/Models.kt` 完整定义了所有不可变模型与纯函数辅助工具（包含半音/音分转 `PlaybackParameters.pitch` 的精确数学工具 `PracticeMath.computePitchFactor(semitones, cents)`、节拍间隔计算 `PracticeMath.computeClickIntervalMs(bpm, speed)`、`tnum` 时间格式化等）。
2. `core/CoreContracts.kt` 定义了四大核心接口与总门面 `MiniPlayerCoreProvider`：
   - `MediaLibraryContract`：响应式 `StateFlow` 观察文件夹树/平铺、专辑聚合（含 `未知专辑`）、歌手聚合、视频列表、播放列表（含 `🎯 练习列表`）、书签聚合、单曲练习状态与全局设置，以及增删改方法。
   - `FolderScannerContract`：添加 SAF 根目录（返回 `AddRootResult`：成功 / 已包含在父目录 / 已自动合并旧子目录）、增量重扫全部或单个根目录、移除根目录、切换任意目录/子目录 `练习文件夹` 与 `不自动归类 (exclude_from_grouping)` 规则（扫描始终忽略 `.nomedia`）。
   - `MetadataEngineContract`：保存单曲或批量 `MetadataDraft`（第 1 层数据库覆盖 + 可选第 2 层 SAF 原子写回）、恢复原始标签、单曲免 Key 联网候选检索（`searchOnlineCandidates`）、文件夹/`未知专辑`批量匹配预览（`previewBatchCompletion`）与批量提交、获取同步 `.lrc` 歌词、`.m3u/.m3u8` 导入导出。
   - `AppUpdateContract`：检查 GitHub Releases 最新版本、带进度与 SHA-256 + 签名校验下载 APK、触发 `FileProvider` 安装 Intent。
3. **`CoreRegistry.get(context)` 零冲突解耦绑定**：
   - `@coder-core` 在 `feat/core` 分支实现 `com.miniplayer.app.core.DefaultMiniPlayerCore(context: Context) : MiniPlayerCoreProvider`。
   - `CoreRegistry` 通过反射优先加载 `DefaultMiniPlayerCore(context)`；当 `@coder-ui` 在隔离分支单独编译调试时自动回退至内置内存态 `InMemoryPreviewCore`，合入 `feat/verify` 后自动零改动激活真实引擎！

---

## 4. `feat/core` 关键工程实现契约 (`@coder-core`)

1. **有效元数据计算 (`COALESCE` 链)**：
   - `effectiveTitle = override.title?.takeIf { it.isNotBlank() } ?: rawTitle?.takeIf { it.isNotBlank() } ?: parsedTitle`
   - `effectiveArtist = override.artist?.takeIf { it.isNotBlank() } ?: rawArtist?.takeIf { it.isNotBlank() } ?: parsedArtist ?: "未知歌手"`
   - `effectiveAlbum = override.album?.takeIf { it.isNotBlank() } ?: rawAlbum?.takeIf { it.isNotBlank() } ?: parsedAlbum`（若为空或空白，归入 `AlbumSummary.UNKNOWN_ALBUM_KEY`，标题显示 `"未知专辑"`，`isUnknownAlbum = true`）。
   - 每次写入/清除 `media_metadata_overrides` 或新文件入库时，同步通过 `ArtistSplitter.split(effectiveArtist, effectiveAlbumArtist)` 重建该曲目的 `media_item_artists` 行，并发出响应式刷新信号。
2. **子目录 `folder_rules` 最近祖先继承算法**：
   - 判断某文件 `(root_id, parent_rel_path)` 是否属于 `练习文件夹` (`isPracticeFolder`) 或 `不自动归类` (`isExcludedFromAutoGrouping`) 时，沿路径前缀向上匹配 `folder_rules`（例如 `"Backing/J-Rock"` 依次匹配 `"Backing/J-Rock"` → `"Backing"` → `""`），命中最近一条显式规则即采用其对应字段值；子目录切换单项开关时自动继承并保留另一项有效属性。
3. **SAF 扫描离线保护与防重 (`SafFolderScanner.kt`)**：
   - 先校验 `contentResolver.persistedUriPermissions` 是否包含该 `treeUri`（缺失则标为 `PERMISSION_REVOKED`）；再尝试查询根文档游标（若抛出异常或返回 `null`/不可读则标为 `OFFLINE_UNMOUNTED`），**严禁删除该 `root_id` 下的任何 `media_items` 或书签**。
   - 仅在 `mount_state == MOUNTED` 且游标完整遍历成功时，对消失的 URI 先尝试 `fingerprint_key` 迁移绑定，确系删除的文件再清理记录。
   - **重命名文件夹去重 (`v1.0.5`)**：子目录改名（含仅大小写改名 `rock` → `Rock`，及完整改名 `rock` → `metal`）后，文件夹视图只保留新目录，绝不同时出现 `Rock` 与 `rock`：
     - `SafFolderScanner.performSingleRootScan`：增量命中（`documentUri` 相同、`sizeBytes`/`lastModified > 0` 相同）时，仅当 `parentRelPath` 与 `fileName` 也一致才视为零变更；否则发出 `MovedMediaUpdate` 原地迁移 `parent_rel_path`/`file_name`。若 SAF 改变了子文档 URI，则在未见旧条目中按 `(fileName 忽略大小写, sizeBytes, lastModified > 0)` 唯一匹配并迁移，保留 `itemId`、播放列表、书签、覆盖层与已提取元数据，无需重新调用 `MediaMetadataRetriever`。
     - `SafOverlapDetector` 同卷 `subPath` 忽略大小写比较；`AlreadyAdded` 时同步更新根目录 `treeUri`/`treeDocId`/`displayName` 并全量重扫，`AlreadyCoveredByParent` 时先全量重扫父根；`SafVolumeProbe.Mounted.liveDisplayName` 用于同步根目录自身改名。
     - `reconcileRootScan`（`SqliteMediaStoreBackend` 与 `InMemoryMediaStoreBackend`）：按实时 `scannedFolders` 规范化仅大小写不同的 `media_items.parent_rel_path`，并将 `folder_rules` 迁移到改名后的路径（含嵌套前缀 `rock/classic` → `Rock/classic`）或删除已消失目录的陈旧规则。
     - `MediaRepository.browseFolder`：子目录**只**来自实时 `scanned_folders` 与 `media_items`（`folder_rules` 不再合成幽灵目录），按小写去重并优先采用 `scanned_folders` 的大小写；面包屑旧路径 `rock` 自动解析为 `Rock`；`rulesMap` 查找支持忽略大小写回退。
     - `MediaRepository.snapshotVersion: StateFlow<Long>` 在每次 `refreshNow()` 后递增，`MiniPlayerViewModel` 据此刷新当前文件夹下钻；`MainActivity` 在非首次 `ON_START` 调用防抖（`1500 ms`、扫描中跳过）的 `onAppForegrounded()` 后台增量重扫，外部文件管理器改名后切回即自动更新。
4. **二进制音频标签安全编解码 (`AudioTagCodec.kt`)**：
   - 支持纯 JVM 解析与重写：`.mp3` (**ID3v2.3 / ID3v2.4** `TIT2/TPE1/TALB/TPE2/TRCK/TDRC/APIC`)、`.flac` (**FLAC Metadata Blocks** `VORBIS_COMMENT` type=4 + `PICTURE` type=6)、`.ogg/.opus` (Vorbis Comment packet)、`.m4a/.mp4` (`moov.udta.meta.ilst` `©nam/©ART/©alb/aART/trkn/©day/covr`)。
   - **MP4 偏移修正铁律**：当 `moov` 原子位于 `mdat` 音频载荷之前且改写 `ilst` 导致 `moov` 字节长度变化 $\Delta$ 时，必须遍历所有 `trak.mdia.minf.stbl` 下的 `stco`（32位偏移 +$\Delta$；若溢出则升级）与 `co64`（64位偏移 +$\Delta$）同步修正偏移表！
   - **SAF 落盘铁律**：先在 `cacheDir/tag_stage/<itemId>.tmp` 生成完整新文件并二次读取校验头部合法性，再通过 `contentResolver.openFileDescriptor(docUri, "rw")` 覆写并调用 `Os.ftruncate(pfd.fileDescriptor, tmpFile.length())` + `Os.fsync(pfd.fileDescriptor)`。
5. **免 Key 自动联网补全 (`OnlineMetadataCompleter.kt`)**：
   - **候选暂存、手动保存 (`v1.0.5`)**：`MetadataDraft` 新增 `coverRemoteUrl`、`syncedLrc`、`plainLyrics`。`DefaultMetadataEngine.saveMetadata` 在进入 `fileWriteMutex.withLock` **之前**解析封面字节（本地 `coverBytes` 优先，否则 `onlineCompleter.downloadCoverBytes(coverRemoteUrl)`；下载失败时保留原覆盖封面），锁内写入覆盖封面与 `TagWriteSpec`，并在单曲保存时以 `LyricsSource.LRCLIB` upsert `LyricsCacheRow`。`MiniPlayerViewModel.saveMetadataDraft` 保存成功后刷新播放队列元数据、`_currentCoverBytes` 与 `_currentLyrics`，再关闭编辑器。
   - `MusicBrainzClient`：携带 `User-Agent: MiniPlayer/1.0.0 (https://github.com/codescv/miniplayer)`，内置 `Mutex + lastRequestMs` 实现 `1000 ms` 最小请求间隔。
   - **CAA `3.0s` 超时竞速回退**：查询封面时对 `coverartarchive.org/release/{mbid}/front-500` 设置 `connectTimeout = 3000, readTimeout = 3000`；若超时或非 `200`，立即采用并发/备选 `ItunesSearchClient` 返回的 `1000x1000bb.jpg` URL。

---

## 5. `feat/ui-player` 关键工程实现契约 (`@coder-ui`)

1. **`PlaybackService.kt` (`MediaSessionService`) & `RealTimeSonicAudioProcessor.kt` (`v1.0.2`)**：
   - Manifest 声明 `android:foregroundServiceType="mediaPlayback"` 与 `androidx.media3.session.MediaSessionService` Intent Filter。
   - `ExoPlayer.Builder(this)` 配置 `AudioAttributes` (`USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_MUSIC`)、`handleAudioFocus = true`、**`setHandleAudioBecomingNoisy(true)`**。
   - **零寻址实时变调与零相位 STFT 共振峰保护 / 5 轨软掩码混音管线 (`RealTimeSonicAudioProcessor` + `FormantStemDspProcessor`, `v1.0.2` / `v1.0.9`)**：`DefaultAudioSink` 关闭浮点输出与硬件 `AudioTrack` 倍速参数，注入常驻激活（`isActive() == true`）的 `RealTimeSonicAudioProcessor`。连续点击升降调时，处理器在 `queueInput` 内将旧 `SonicAudioProcessor` 残余采样排空拼接至直接内存 `stagingBuffer` 并原地切换参数。当 `(preserveFormants && |pitch - 1.0| >= 0.005) || !stemMix.isUnity` 时，`Sonic` 输出紧接进入 **`FormantStemDspProcessor`**（`N = 1024`, `hop = 256`, `75%` 重叠周期 Hann 分析+合成窗，COLA 归一化系数 `1 / 1.5`）：
     1. **共振峰频谱包络校正 (`preserveFormants = true`)**：对每帧幅度谱求对数幅度 $L[k] = \ln(|X[k]| + 10^{-7})$，利用前缀和滑动均值（半宽 $W = \max(6, \text{round}(320\text{ Hz}\cdot N/f_s))$）提取平滑共振峰包络 $E_{\text{shifted}}[k]$，按变调比逆映射插值恢复原声道包络 $E_{\text{orig}}[k] = E_{\text{shifted}}[\text{clamp}(k \cdot p, 0, N/2)]$，乘以人声共振峰频带余弦权重 $\alpha(k)$（`130–220 Hz` 渐入、`220–4500 Hz` 为 `1.0`、`4500–6200 Hz` 渐出）、限幅 $[-2.2, +2.2]$ 并做帧间指数平滑（`0.65` / `0.35`），施加**纯实数零相位增益**并匹配补偿前帧 RMS 能量——既消除变调导致的男女声异化/花栗鼠效应，又 100% 保留 `Sonic` 的相位一致性与鼓/拨弦瞬态冲击力。
     2. **端侧 AI + 实时 STFT 5 轨软掩码混音 (`StemModelManager` + `StemSeparationEngine` + `FormantStemDspProcessor`)**：`StemModelManager` 管理 `filesDir/models/miniplayer-stem-net-v1.bin`（`64 KB` 量化双层 16-Bark 频带 + Mid/Side + HPSS 神经掩码网络权重，支持从 GitHub Release `v1.0.9` 按需下载并内置 APK 资源/确定性权重离线兜底）；`StemSeparationEngine` 以 `25 Hz`（`40 ms` 帧移）解码全曲生成 `stem_<id>_<mtime>.stm` GZIP 软掩码缓存（单曲约 `30–90 KB`）。实时混音对每个频点合成严格满足单位分解 $\sum_{s \in \{\text{vocals,guitar,bass,drums,other}\}} M_s[k] \equiv 1.0$ 的软掩码，并分别作用于立体声 Mid 与 Side 分量（$G_M[k], G_S[k]$），在全 `100%` 增益时严格恒等透传。
   - 视频熄屏续播：当 Activity 进入后台/熄屏时仅调用 `exoPlayer.clearVideoTextureView(surface)` 而不调用 `pause()`，使视频音轨在后台零中断继续播放；Activity 回到前台时重新绑定 `TextureView`。
2. **`PracticeLoopEngine.kt`、`WaveformLoopRuler`、`BeatGrid` & `BpmDetector.kt` (`v1.0.2` / `v1.0.9`)**：
   - 所有 `ExoPlayer` API 调用严格运行在主线程 (`exoPlayer.applicationLooper`)。
   - A-B 循环边界通过 `exoPlayer.createMessage { _, _ -> handleLoopWrap() }.setLooper(exoPlayer.applicationLooper).setPosition(bMs).setDeleteAfterDelivery(false).send()` 注册；任何修改 `aMs`/`bMs`、挂起循环、切换曲目或手动 `seekTo` 前必须先执行 `activeMessage?.cancel()`。
   - **1:1 浮点手柄拖拽与小节刻度吸附 (`WaveformLoopRuler` + `BeatGrid`)**：`.pointerInput(safeDuration)` 严格仅以 `safeDuration` 为 Key；开启 `barSnapEnabled`（默认 `true`）时，`markOrUpdateA`、`markOrUpdateB`、`setLoopPointsFromDrag` 及循环态标尺点击寻址统一经 `BeatGrid` 吸附至最近小节边界（保证 `B >= A + 1 bar`），标尺画布渲染每小节/每 4 小节竖向刻度并在顶部显示 `A mm:ss.d ━━● N小节 ●━━ B mm:ss.d`，`BpmQuickBar` 支持一键切换 `小节对齐` 及 `对齐第1拍`（`alignDownbeatToCurrentPosition`）。
   - **自动 BPM 与小节重拍网格检测 (`BpmDetector.computeBeatGrid` + `WaveformExtractor.loadOrDetectBeatGrid`)**：优先命中向后兼容的 `cacheDir/waveforms/bpm_<id>_<mtime>.dat` 缓存（`bpm,firstBeatMs,firstDownbeatMs,beatsPerBar`）；否则通过 `100 Hz` 起始通量包络自相关求 `bpm`，再经节拍相位网格搜索定位 `firstBeatMs`，并对 4 个候选小节重拍相位结合低频重拍强调评分确定 `firstDownbeatMs`。
   - **一键 `关 ↔ 4拍` 纯节拍预备拍 (`CountInMode` & `CountInMetronome`, `v1.0.9`)**：`PracticeLoopEngine.cycleCountInMode()` 直接在 `OFF` 与 `BEAT_4` 间一键切换，所有非 `OFF` 状态统一执行 `runFourBeatCountdown`（第 1 拍重音木鱼 + 第 2–4 拍轻击，拍距 `60000 / (bpm * speed)`），且切换预备拍不再强制弹出 `BpmQuickBar`。
   - 视频连续循环（`countInMode == OFF`）：记录 `seekStartRealtimeMs` 至 `Player.Listener.onRenderedFirstFrame`（或 `onPlaybackStateChanged(STATE_READY)`）耗时；若 `> 80 ms` 且 `videoSeekMode == AUTO`，自动将 `exoPlayer.setSeekParameters(SeekParameters(1_500_000L, 0L))` 应用于后续循环绕回。
3. **练习模式确定性状态机与专属视觉标识 (`MiniPlayerViewModel.kt` & `GuitarIcon.kt`)**：
   - 全应用（日常播放页、练习顶栏、横屏视频 HUD、曲库/列表菜单）统一采用自绘矢量吉他图标 **`GuitarIcon`** 标识练习模式，屏幕常亮采用纯图标切换按钮（`DeckIconToggleButton` + `Icons.Outlined.LightMode`）。
   - 点播入口携带 `PlayContext` 枚举：`PRACTICE_PLAYLIST` | `PRACTICE_FOLDER` | `BOOKMARK_JUMP(bookmarkId)` | `CASUAL`。
   - 前三者立即置 `isPracticeModeActive = true`、展开 `PracticeDeck` 并应用 `PracticeState`（倍速 + 半音/音分 + BPM + A/B 区间）；`CASUAL` 置 `isPracticeModeActive = false`、播放参数保持 `(1.0f, 1.0f)`。
4. **自适应启动图标圆形蒙版光学居中 (`res/drawable/ic_launcher_foreground.xml`, `v1.0.6`)**：
   - 针对 Android `AdaptiveIconDrawable` 将 `108×108dp` 画布裁剪为内圈 `72×72dp`（`cx = 54, cy = 54, r = 36dp`，光学安全区 `r = 22dp..24dp`）的圆形桌面遮罩，`ic_launcher_foreground.xml` 将「版型 A」宽肩 351 拨片与居中圆角播放三角按 **`0.60x`（`38.4dp × 37.8dp`）** 缩放并光学居中于 `x ∈ [34.8, 73.2]`、`y ∈ [36.5, 74.3]`（左右肩半径 `19.6dp`、底尖半径 `20.3dp`，四周留出 `~15dp–16dp` 均匀边距）。
5. **`evenOdd` 主题图标镂空与（已被 `v1.0.8` 取代的）系统毛玻璃深色图标底板 (`res/drawable/ic_launcher_monochrome.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml`, `v1.0.7`)**：
   - （`v1.0.7` 历史方案，`v1.0.8` 已恢复 `values-night` 并自绘深色底板，见第 6 条）**删除 `res/values-night/colors.xml`**，`color/ic_launcher_background` 仅保留默认限定符且固定为纯白 `#FFFFFF`（与 Google Maps 相同），`ic_launcher_pick = #F97316`、`ic_launcher_play = #FFFFFF`。ColorOS 15 / OxygenOS 15「暗色图标」桌面检测到明亮/彩色的 `<background>` 时会用系统半透明磨砂毛玻璃深色底板替换之（Google Maps / Discord 行为）；若 APK 自带 `(night)` 限定的预压暗实色背景，桌面会跳过替换直接绘制实心深色圆。`aapt2 dump resources` 中 `color/ic_launcher_background` 必须只出现 `()` 一项，不得出现 `(night)`。
   - `<monochrome>` 指向独立的 `drawable/ic_launcher_monochrome.xml`：单条 `android:fillType="evenOdd"`（编译后 `fillType = 1`）复合路径，先描 `0.60x` 拨片外轮廓，再描内部播放三角轮廓。Android 13+ (`API 33`) 主题图标引擎对整个 drawable 统一着色（`Themed Icons` / Material You），双路径叠加会被染成实心拨片，偶奇填充保证三角区域被镂空透出底板。`ic_launcher_foreground.xml` 保持双 `<path>` 双色绘制不变。
6. **自绘磨砂炭灰深色图标底板 (`res/values/colors.xml`, `res/values-night/colors.xml`, `res/drawable/ic_launcher_background.xml`, `v1.0.8`)**：
   - OnePlus 13T（ColorOS 15）真机验证：桌面「暗色图标」自动转换仅对 Google Maps / Discord 等白名单 Play 商店包名生效，自定义 `applicationId = "com.miniplayer.app"` 不会被替换，故 `v1.0.7` 删除 `values-night` 后深色模式下仍显示浅色 `#FFFFFF` 底板。`v1.0.8` **恢复 `res/values-night/colors.xml`**，由资源限定符 `(night)` 自动切换深色底板。
   - `drawable/ic_launcher_background.xml`（`108×108dp`，`xmlns:aapt="http://schemas.android.com/aapt"`）由两条全画布 `<path android:pathData="M0,0h108v108h-108z">` 叠加而成，`android:fillColor` 均通过 `<aapt:attr>` 内联 `<gradient>`：第一层 `type="linear"` 纵向渐变（`startX = 54`、`startY = 16`、`endX = 54`、`endY = 92`，覆盖可见 `[18, 90]` 圆区），色标 `@color/ic_launcher_bg_top` `0.0` / `@color/ic_launcher_bg_mid` `0.48` / `@color/ic_launcher_bg_bottom` `1.0`；第二层 `type="radial"` 毛玻璃漫射高光（`centerX = 48`、`centerY = 32`、`gradientRadius = 46`），色标 `@color/ic_launcher_bg_glow_start` `0.0` / `@color/ic_launcher_bg_glow_mid` `0.55` / `#00FFFFFF` `1.0`。`mipmap-anydpi-v26/ic_launcher.xml` 的 `<background android:drawable="@drawable/ic_launcher_background" />` 与 `ic_launcher_round.xml` 保持不变。
   - 色值契约（`values/colors.xml` 浅色 `1D`）：`ic_launcher_background #F8FAFC`、`ic_launcher_bg_top #FFFFFF`、`ic_launcher_bg_mid #F8FAFC`、`ic_launcher_bg_bottom #F1F5F9`、`ic_launcher_bg_glow_start #00FFFFFF`、`ic_launcher_bg_glow_mid #00FFFFFF`、`ic_launcher_pick #F97316`、`ic_launcher_play #FFFFFF`；（`values-night/colors.xml` 深色磨砂炭灰）：`ic_launcher_background #282A30`、`ic_launcher_bg_top #363940`、`ic_launcher_bg_mid #282A30`、`ic_launcher_bg_bottom #1E2025`、`ic_launcher_bg_glow_start #1AFFFFFF`、`ic_launcher_bg_glow_mid #09FFFFFF`、`ic_launcher_pick #FB923C`、`ic_launcher_play #FFFBEB`。`aapt2 dump resources` 中上述 8 个 `color/` 条目均须同时存在 `()` 与 `(night)` 两个配置。
7. **深色主题抬升表面与动态色板上抬 (`ui/theme/Theme.kt`, `v1.0.7`)**：
   - `DefaultDarkScheme`（Android 8.0–11 或禁用动态取色时）采用 Google Material「Charcoal Dark」炭灰阶梯：`background` / `surface = #1E1F24`、`surfaceContainerLowest = #181A1F`、`surfaceContainerLow = #26282F`、`surfaceContainer = #2B2E36`、`surfaceContainerHigh = #32353E`、`surfaceContainerHighest = #3B3E48`。
   - Android 12+ (`API 31+`) 深色动态取色路径：`MiniPlayerTheme` 以 `elevateDynamicDarkSurfaces(dynamicDarkColorScheme(context))` 代替裸 `dynamicDarkColorScheme`，通过 `ColorScheme.copy` 将 Material You 深色色板整体上抬一个色调台阶：`background` / `surface ← surfaceContainer`、`surfaceContainerLowest ← surface`、`surfaceContainerLow ← surfaceContainerHigh`、`surfaceContainer ← surfaceContainerHighest`、`surfaceContainerHigh ← surfaceBright`；`surfaceContainerHighest` 与所有 `on*` 文字色保持原值，故 WCAG AA 对比度不受影响。`rememberAnimatedColorScheme` 的播放页封面取色色板不经过此函数。

---

## 6. 应用内 GitHub 自动更新安全规范 (`core/update/`)

1. **接口与配置**：读取 `BuildConfig.UPDATE_API_BASE`（默认 `"https://api.github.com/repos/codescv/miniplayer"`）：
   - `latestUrl = "${BuildConfig.UPDATE_API_BASE}/releases/latest"`
   - `listUrl = "${BuildConfig.UPDATE_API_BASE}/releases?per_page=5"`
2. **网络与重定向防护 (`AppUpdater.kt`)**：
   - 默认强制 `https://`（仅单元测试注入 `allowInsecureLoopback = true` 时允许 `http://127.0.0.1` / `localhost`）。
   - 关闭 `HttpURLConnection.instanceFollowRedirects`，手动处理 `301/302/303/307/308`，最多 **5 跳**，严禁 `HTTPS -> HTTP` 协议降级；JSON 响应上限 `2 MiB`。
   - 若 Release Assets 中存在 `SHA256SUMS.txt`，自动拉取并比对所选 `.apk` 文件的 SHA-256 摘要（不匹配立即删除 `.tmp` 并抛出 `CORRUPT_DOWNLOAD`）。
3. **APK 安装包四重校验 (`ApkValidator.kt`)**：
   - ① ZIP 魔数 `PK\x03\x04` 且内含 `AndroidManifest.xml`；
   - ② `PackageManager.getPackageArchiveInfo` 解析出的 `packageName == context.packageName` (`com.miniplayer.app`)；
   - ③ 新包 `longVersionCode > currentVersionCode`（严防版本降级攻击）；
   - ④ 签名证书集合与当前已安装宿主应用签名证书完全一致。
4. **安全拉起安装**：落盘至 `context.cacheDir/updates/MiniPlayer-<tag>.apk`，通过 `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)` 构造 `Intent.ACTION_VIEW`（若 `canRequestPackageInstalls()` 为 `false` 则先引导跳转 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`）。

---

## 7. 内置 PC 局域网 Web 管理台协议规范 (`com.miniplayer.app.lan.*`, `@coder-lan`)

1. **核心组件**：
   - `QrCodeGenerator.kt`：零第三方依赖的纯 Kotlin ISO/IEC 18004 二维码矩阵生成器，在手机端渲染 `http://<lan-ip>:8088` 扫码图。
   - `LanStudioServer.kt`：基于纯 JVM `ServerSocket` + 协程线程池实现的轻量 HTTP/1.1 服务（默认端口 `8088`，单元测试支持传 `0` 绑定随机空闲端口），直接桥接 `MiniPlayerCoreProvider`。
   - `LanStudioSheet.kt`：Jetpack Compose M3 模态面板，展示服务运行状态、局域网 IP 地址、二维码画布、一键复制地址、请求计数与启停开关。
2. **网络访问控制与安全边界**：
   - **私有局域网 IP 防火墙**：`accept()` 后立即校验对端 `InetAddress`，仅放行回环与局域网私网段（`127.0.0.0/8` / `::1`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、链路本地 `169.254.0.0/16` / `fe80::/10`），一切公网 IP 连接立即返回 `403 Forbidden` 并断开。
   - 支持可选会话 PIN / Bearer Token 校验与上传文件名路径穿越清洗（剥离 `..`、`/`、`\` 与控制字符）。
3. **REST API 与 HTTP `206` 媒体流端点规范**：
   - `GET /`：返回单页内联响应式 M3 风格 **PC Web Studio SPA**（纯原生 HTML5/CSS3/ES2022，支持深浅色自适应）。
   - **目录与无线上传**：
     - `GET /api/roots` · `GET /api/folders?rootId=&relPath=`：列出 SAF 根目录与子目录内容。
     - `POST /api/upload?rootId=&relPath=&fileName=`：流式接收二进制或 `multipart/form-data` 上传文件写入目标 SAF 目录（或测试目录），写入完成后自动调用 `scanner.rescanRoot(rootId, incremental = true)` 即刻入库。
     - `POST /api/folders/mkdir` · `POST /api/folders/rule`：新建子文件夹、一键切换子文件夹 `🎯 设为练习文件夹` (`isPracticeFolder`)。
   - **曲库表格与免 Key 联网补全**：
     - `GET /api/items?view=all|albums|artists|videos&q=`：返回曲库列表与有效标签、练习摘要。
     - `POST /api/metadata/update` · `POST /api/metadata/batch-update`：单曲或批量（遵循 `dirtyFields` 保护单曲标题/音轨号）保存标签。
     - `POST /api/metadata/auto-match?itemId=` · `POST /api/metadata/batch-auto-complete`：调用 `MetadataEngineContract` 执行免 Key 联网候选检索与批量自动补全。
     - `GET /api/metadata/candidates?itemId=`（及 `POST /api/metadata/auto-match` 的 `"candidatesOnly": true`，`v1.0.5`）：仅返回候选列表（`title`/`artist`/`album`/`albumArtist`/`trackNumber`/`year`/`coverUrl`/`syncedLrc`/`plainLyrics`/`confidence`/`source`），**不自动保存**；Web 编辑弹窗在 `#ed-candidates-box` 内点选候选仅填入表单并暂存 `coverRemoteUrl`/`syncedLrc`/`plainLyrics`，直到点击「保存更改」才经 `POST /api/metadata/update` 提交（服务端在 `coverRemoteUrl` 非空时自动加入 `MetadataField.COVER` 脏字段）。
   - **播放列表与 `🎯 练习列表` 编排**：
     - `GET /api/playlists` · `POST /api/playlists` · `PUT /api/playlists/{id}` · `DELETE /api/playlists/{id}`：增删改播放列表、切换 `isPracticePlaylist`、调整曲序。
     - `POST /api/playlists/import` · `GET /api/playlists/export?id=`：`.m3u8` 导入导出。
   - **PC 在线预听与键盘 `[` / `]` 段落书签工作台**：
     - `GET /api/stream?itemId=`：支持标准 **HTTP `206 Partial Content`**（解析 `Range: bytes=start-end` 请求头，返回 `Content-Range: bytes start-end/total` 与 `Accept-Ranges: bytes`），使 PC 浏览器 `<audio>` / `<video>` 元素可毫秒级任意拖拽进度条！
     - `GET /api/practice?itemId=` · `POST /api/practice/state` · `POST /api/practice/bookmark` · `DELETE /api/practice/bookmark?id=`：读写单曲 `PracticeState`（倍速、半音、音分、BPM）与命名 `PracticeBookmark`（PC 端支持快捷键 `Space` 播停、`[` 设 A、`]` 设 B 一键存书签，手机端打开即用）。
