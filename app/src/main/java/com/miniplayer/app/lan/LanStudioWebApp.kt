package com.miniplayer.app.lan

/**
 * Self-contained Material Design 3 Single-Page Application served by [LanStudioServer] at `GET /`.
 * Zero external CDN dependencies — runs 100% offline over Wi-Fi / Hotspot / USB Tethering LAN.
 */
internal object LanStudioWebApp {

    fun renderHtml(serverTitle: String = "MiniPlayer · PC 局域网 Web 管理台"): String {
        val safeTitle = escapeHtml(serverTitle)
        return buildString(48_000) {
            append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>").append(safeTitle).append("</title>\n")
            append("<style>\n")
            append(CSS)
            append("\n</style>\n</head>\n<body>\n")
            append(BODY_HTML)
            append("\n<script>\n")
            append(APP_JS)
            append("\n</script>\n</body>\n</html>\n")
        }
    }

    internal fun escapeHtml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private val CSS = """
:root {
  color-scheme: light dark;
  --bg: #f6f8fc;
  --surface: #ffffff;
  --surface-low: #f0f4fa;
  --surface-high: #e7edf6;
  --fg: #191c20;
  --muted: #5a5f68;
  --border: #dce2ec;
  --primary: #0b57d0;
  --on-primary: #ffffff;
  --primary-tonal: #d3e3fd;
  --on-primary-tonal: #041e49;
  --tertiary: #6b3fa0;
  --tertiary-tonal: #eedcff;
  --on-tertiary-tonal: #260058;
  --amber-tonal: #ffdf9e;
  --on-amber-tonal: #553c00;
  --green-tonal: #c4eed0;
  --on-green-tonal: #073818;
  --danger: #b3261e;
  --danger-tonal: #f9dedc;
  --hover: rgba(11, 87, 208, 0.06);
  --shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
}
[data-theme="dark"] {
  --bg: #101318;
  --surface: #181c22;
  --surface-low: #1f242c;
  --surface-high: #282e38;
  --fg: #e2e6ee;
  --muted: #959ca8;
  --border: #2d3440;
  --primary: #a8c7fa;
  --on-primary: #062e6f;
  --primary-tonal: #1d3557;
  --on-primary-tonal: #d3e3fd;
  --tertiary: #d5baff;
  --tertiary-tonal: #42256d;
  --on-tertiary-tonal: #eedcff;
  --amber-tonal: #4d3808;
  --on-amber-tonal: #ffdf9e;
  --green-tonal: #143d22;
  --on-green-tonal: #c4eed0;
  --danger: #f2b8b5;
  --danger-tonal: #601410;
  --hover: rgba(168, 199, 250, 0.08);
  --shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
}
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Noto Sans SC", Roboto, sans-serif;
  background: var(--bg);
  color: var(--fg);
  line-height: 1.45;
}
.tnum { font-variant-numeric: tabular-nums; font-feature-settings: "tnum"; }
.app-shell { max-width: 1440px; margin: 0 auto; padding: 14px 20px 32px; }
.topbar {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between;
  gap: 12px; padding: 12px 18px; background: var(--surface); border: 1px solid var(--border);
  border-radius: 20px; margin-bottom: 14px; box-shadow: var(--shadow);
}
.brand { display: flex; align-items: center; gap: 12px; }
.brand-logo {
  width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
  background: var(--primary-tonal); color: var(--on-primary-tonal); font-size: 20px; font-weight: 700;
}
.brand h1 { margin: 0; font-size: 18px; font-weight: 700; letter-spacing: -0.01em; }
.brand-sub { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dot-live { width: 8px; height: 8px; border-radius: 50%; background: #2e7d32; display: inline-block; }
.nav-tabs { display: flex; flex-wrap: wrap; gap: 6px; }
.nav-tab {
  border: none; background: transparent; color: var(--muted); padding: 9px 16px;
  border-radius: 999px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.15s;
}
.nav-tab:hover { background: var(--hover); color: var(--fg); }
.nav-tab.active { background: var(--primary-tonal); color: var(--on-primary-tonal); }
.top-actions { display: flex; align-items: center; gap: 8px; }
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 8px 15px; border-radius: 999px; font-size: 13px; font-weight: 600;
  border: 1px solid transparent; cursor: pointer; text-decoration: none; white-space: nowrap;
  transition: opacity 0.15s, transform 0.05s;
}
.btn:active { transform: scale(0.98); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn-primary { background: var(--primary); color: var(--on-primary); }
.btn-tonal { background: var(--primary-tonal); color: var(--on-primary-tonal); }
.btn-tertiary { background: var(--tertiary-tonal); color: var(--on-tertiary-tonal); }
.btn-outline { background: transparent; border-color: var(--border); color: var(--fg); }
.btn-danger { background: var(--danger-tonal); color: var(--danger); }
.btn-xs { padding: 4px 10px; font-size: 12px; border-radius: 999px; }
.badge {
  display: inline-flex; align-items: center; gap: 4px; padding: 2px 9px;
  border-radius: 999px; font-size: 11px; font-weight: 600;
}
.badge-practice { background: var(--tertiary-tonal); color: var(--on-tertiary-tonal); }
.badge-warn { background: var(--amber-tonal); color: var(--on-amber-tonal); }
.badge-ok { background: var(--green-tonal); color: var(--on-green-tonal); }
.badge-muted { background: var(--surface-high); color: var(--muted); }
.workspace { display: none; }
.workspace.active { display: block; }
.grid-sidebar { display: grid; grid-template-columns: 280px 1fr; gap: 16px; align-items: start; }
@media (max-width: 960px) { .grid-sidebar { grid-template-columns: 1fr; } }
.card {
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 18px; padding: 16px; box-shadow: var(--shadow);
}
.card-title {
  margin: 0 0 12px; font-size: 15px; font-weight: 700;
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.list-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 12px; border-radius: 12px; cursor: pointer; border: 1px solid transparent;
  margin-bottom: 4px;
}
.list-item:hover { background: var(--hover); }
.list-item.selected { background: var(--primary-tonal); color: var(--on-primary-tonal); }
.dropzone {
  border: 2px dashed var(--primary); border-radius: 16px; padding: 22px 16px;
  text-align: center; background: var(--surface-low); margin-bottom: 14px; transition: background 0.15s;
}
.dropzone.dragover { background: var(--primary-tonal); }
.progress-bar { height: 8px; border-radius: 999px; background: var(--surface-high); overflow: hidden; margin-top: 8px; }
.progress-fill { height: 100%; width: 0%; background: var(--primary); transition: width 0.12s linear; }
.crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 13px; margin-bottom: 12px; }
.crumb-link { color: var(--primary); cursor: pointer; font-weight: 600; background: none; border: none; padding: 2px 4px; }
.crumb-link:hover { text-decoration: underline; }
.chip-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 12px; }
.chip {
  border: 1px solid var(--border); background: var(--surface-low); color: var(--fg);
  padding: 6px 13px; border-radius: 999px; font-size: 13px; font-weight: 600; cursor: pointer;
}
.chip.active { background: var(--primary-tonal); color: var(--on-primary-tonal); border-color: transparent; }
.input, select {
  background: var(--surface-low); color: var(--fg); border: 1px solid var(--border);
  border-radius: 10px; padding: 8px 12px; font-size: 13px; outline: none;
}
.input:focus, select:focus { border-color: var(--primary); }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 14px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 9px 10px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: middle; }
th { background: var(--surface-low); color: var(--muted); font-weight: 600; font-size: 12px; position: sticky; top: 0; }
tr:last-child td { border-bottom: none; }
tr:hover td { background: var(--hover); }
.editable-cell { cursor: text; border-radius: 6px; padding: 3px 6px; display: inline-block; min-width: 48px; }
.editable-cell:hover { background: var(--surface-high); }
.batch-bar {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 14px; border-radius: 14px; background: var(--primary-tonal); color: var(--on-primary-tonal);
  margin-bottom: 12px;
}
.hidden { display: none !important; }
.player-stage {
  background: #0c0e12; border-radius: 16px; overflow: hidden; position: relative;
  display: flex; align-items: center; justify-content: center; min-height: 220px; margin-bottom: 14px;
}
.player-stage video { max-width: 100%; max-height: 420px; transition: transform 0.15s; }
.player-stage video.mirrored { transform: scaleX(-1); }
.audio-hero { color: #e2e6ee; padding: 28px 20px; text-align: center; width: 100%; }
.timeline-box {
  position: relative; height: 44px; background: var(--surface-low); border: 1px solid var(--border);
  border-radius: 12px; cursor: pointer; user-select: none; overflow: hidden; margin-bottom: 12px;
}
.timeline-progress { position: absolute; left: 0; top: 0; bottom: 0; background: rgba(11, 87, 208, 0.18); pointer-events: none; }
.timeline-loop {
  position: absolute; top: 0; bottom: 0; background: rgba(107, 63, 160, 0.30);
  border-left: 3px solid var(--tertiary); border-right: 3px solid var(--tertiary); pointer-events: none;
}
.timeline-playhead { position: absolute; top: 0; bottom: 0; width: 2px; background: var(--primary); pointer-events: none; }
.timeline-bm-pin {
  position: absolute; top: 3px; padding: 1px 5px; font-size: 10px; border-radius: 4px;
  background: var(--tertiary-tonal); color: var(--on-tertiary-tonal); transform: translateX(-2px); pointer-events: none;
}
.kbd {
  display: inline-block; padding: 1px 6px; border-radius: 5px; border: 1px solid var(--border);
  background: var(--surface-low); font-family: monospace; font-size: 11px; font-weight: 700;
}
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.48); display: flex;
  align-items: center; justify-content: center; padding: 16px; z-index: 100;
}
.modal {
  background: var(--surface); border: 1px solid var(--border); border-radius: 22px;
  width: 100%; max-width: 580px; max-height: 88vh; overflow-y: auto; padding: 22px; box-shadow: var(--shadow);
}
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }
.form-field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
.form-field.full { grid-column: 1 / -1; }
.toast {
  position: fixed; right: 20px; bottom: 20px; background: var(--fg); color: var(--bg);
  padding: 10px 18px; border-radius: 999px; font-size: 13px; font-weight: 600;
  box-shadow: var(--shadow); z-index: 200; transition: opacity 0.2s;
}
.drag-row { cursor: grab; }
.drag-row.dragging { opacity: 0.45; }
    """.trimIndent()

    private val BODY_HTML = """
<div class="app-shell">
  <header class="topbar">
    <div class="brand">
      <div class="brand-logo">🎸</div>
      <div>
        <h1>MiniPlayer · PC 局域网 Web 管理台</h1>
        <div class="brand-sub">
          <span><span class="dot-live"></span> <span id="stat-summary">正在连接手机曲库…</span></span>
          <span id="stat-scan" class="badge badge-muted hidden">扫描中…</span>
        </div>
      </div>
    </div>

    <nav class="nav-tabs" role="tablist">
      <button class="nav-tab active" data-tab="folders" onclick="switchTab('folders')">📁 无线传文件与目录</button>
      <button class="nav-tab" data-tab="metadata" onclick="switchTab('metadata')">🏷️ 曲库表格与联网补全</button>
      <button class="nav-tab" data-tab="playlists" onclick="switchTab('playlists')">🎯 播放列表与练习列表</button>
      <button class="nav-tab" data-tab="studio" onclick="switchTab('studio')">🎸 PC 预听与 A-B 打点器</button>
    </nav>

    <div class="top-actions">
      <button class="btn btn-outline" onclick="triggerRescanAll()" title="增量扫描所有已添加文件夹">⟳ 刷新曲库</button>
      <button class="btn btn-outline" onclick="toggleTheme()" id="theme-btn" title="切换深色/浅色主题">🌓 主题</button>
    </div>
  </header>

  <!-- WORKSPACE 1: WIRELESS UPLOAD & FOLDERS -->
  <section id="ws-folders" class="workspace active">
    <div class="grid-sidebar">
      <aside class="card">
        <div class="card-title">
          <span>📱 已挂载文件夹</span>
          <span class="badge badge-muted tnum" id="roots-count">0</span>
        </div>
        <div id="roots-list"></div>
        <p style="font-size:12px;color:var(--muted);margin:12px 0 0;">
          提示：出于 Android SAF 隐私安全规范，新增顶层文件夹请在手机端点击「+ 添加文件夹」授权；授权后即可在此无线上传文件、建子目录或标记练习目录。
        </p>
      </aside>

      <main class="card">
        <div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;">
          <div class="crumbs" id="folder-crumbs" style="margin-bottom:0;"></div>
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
            <label style="display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:600;cursor:pointer;" class="badge badge-practice">
              <input type="checkbox" id="folder-practice-toggle" onchange="toggleCurrentFolderPractice(this.checked)">
              🎯 设为练习文件夹（手机点播自动开练习模式）
            </label>
            <button class="btn btn-tonal" onclick="promptCreateSubfolder()">+ 新建子文件夹</button>
            <label class="btn btn-primary" for="upload-input">⬆ 上传音视频 / 歌词</label>
            <input id="upload-input" type="file" multiple hidden
                   accept=".mp3,.flac,.wav,.m4a,.aac,.ogg,.opus,.mp4,.mkv,.webm,.mov,.lrc,.jpg,.jpeg,.png,.m3u,.m3u8"
                   onchange="uploadFilesToCurrentFolder(this.files); this.value='';">
          </div>
        </div>

        <div id="dropzone" class="dropzone">
          <div style="font-size:15px;font-weight:600;margin-bottom:4px;">
            拖拽 .mp3 / .flac / .wav / .m4a / .mp4 / .mkv / .lrc 文件到此处无线直传手机当前目录
          </div>
          <div style="font-size:12px;color:var(--muted);">
            支持多文件连续流式上传，上传完成后自动触发增量扫描入库
          </div>
          <div id="upload-progress-box" class="hidden" style="margin-top:10px;text-align:left;">
            <div style="display:flex;justify-content:space-between;font-size:12px;" class="tnum">
              <span id="upload-status-text">准备上传…</span>
              <span id="upload-pct-text">0%</span>
            </div>
            <div class="progress-bar"><div id="upload-bar-fill" class="progress-fill"></div></div>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>名称</th>
                <th>类型 / 规格</th>
                <th>歌手 / 专辑</th>
                <th>练习标记</th>
                <th style="text-align:right;">操作</th>
              </tr>
            </thead>
            <tbody id="folder-entries-tbody"></tbody>
          </table>
        </div>
      </main>
    </div>
  </section>

  <!-- WORKSPACE 2: SPREADSHEET METADATA & AUTO-MATCH -->
  <section id="ws-metadata" class="workspace">
    <div class="card">
      <div class="chip-row">
        <button class="chip active" data-filter="all" onclick="setMetaFilter('all')">全部曲库</button>
        <button class="chip" data-filter="folder" onclick="setMetaFilter('folder')">📁 按文件夹</button>
        <button class="chip" data-filter="album" onclick="setMetaFilter('album')">💿 按专辑</button>
        <button class="chip" data-filter="unknown_album" onclick="setMetaFilter('unknown_album')">⚠️ 未知专辑</button>
        <button class="chip" data-filter="artist" onclick="setMetaFilter('artist')">🎤 按歌手</button>
        <button class="chip" data-filter="video" onclick="setMetaFilter('video')">🎬 视频</button>

        <select id="meta-subfilter" class="hidden" onchange="onMetaSubfilterChange()"></select>
        <input id="meta-search" class="input" style="min-width:240px;margin-left:auto;"
               placeholder="搜索标题 / 歌手 / 专辑 / 文件名…" oninput="renderMetadataTable()">
        <button class="btn btn-tertiary" onclick="runBatchAutoCompletePreview()">✨ 批量联网补全当前列表</button>
      </div>

      <div id="meta-batch-bar" class="batch-bar hidden">
        <div>已选中 <strong id="meta-sel-count" class="tnum">0</strong> 项（双击表格单元格可直接行内快改）</div>
        <div style="display:flex;flex-wrap:wrap;gap:8px;">
          <button class="btn btn-primary btn-xs" onclick="openBatchEditModal()">✏️ 批量修改专辑/歌手/年份</button>
          <button class="btn btn-tertiary btn-xs" onclick="runBatchAutoCompleteSelected()">✨ 批量自动联网补全</button>
          <button class="btn btn-outline btn-xs" onclick="openAddSelectedToPlaylistModal()">🎯 加入播放/练习列表</button>
          <button class="btn btn-danger btn-xs" onclick="resetSelectedMetadata()">↺ 恢复原始标签</button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th style="width:36px;"><input type="checkbox" id="meta-select-all" onchange="toggleSelectAllMeta(this.checked)"></th>
              <th style="width:52px;">音轨#</th>
              <th>标题 (双击行内编辑)</th>
              <th>歌手</th>
              <th>专辑</th>
              <th style="width:68px;">年份</th>
              <th style="width:90px;">时长/格式</th>
              <th style="text-align:right;width:230px;">操作</th>
            </tr>
          </thead>
          <tbody id="meta-tbody"></tbody>
        </table>
      </div>
    </div>
  </section>

  <!-- WORKSPACE 3: PLAYLISTS & PRACTICE LISTS -->
  <section id="ws-playlists" class="workspace">
    <div class="grid-sidebar">
      <aside class="card">
        <div class="card-title">
          <span>播放列表</span>
          <div style="display:flex;gap:6px;">
            <button class="btn btn-tonal btn-xs" onclick="openCreatePlaylistModal()">+ 新建</button>
            <label class="btn btn-outline btn-xs" for="m3u-import-input">📥 导入 .m3u8</label>
            <input id="m3u-import-input" type="file" accept=".m3u,.m3u8" hidden onchange="importM3uFile(this.files[0]); this.value='';">
          </div>
        </div>
        <div class="chip-row" style="margin-bottom:8px;">
          <button class="chip active" id="pl-filter-all" onclick="setPlaylistFilter('all')">全部</button>
          <button class="chip" id="pl-filter-practice" onclick="setPlaylistFilter('practice')">🎯 练习列表</button>
        </div>
        <div id="playlists-sidebar"></div>
      </aside>

      <main class="card" id="playlist-detail-pane">
        <div style="padding:36px;text-align:center;color:var(--muted);">请选择左侧播放列表，或点击「+ 新建」创建 🎯 练习列表</div>
      </main>
    </div>
  </section>

  <!-- WORKSPACE 4: PC PREVIEW & A-B BOOKMARK STUDIO -->
  <section id="ws-studio" class="workspace">
    <div class="grid-sidebar">
      <aside class="card">
        <div class="card-title">
          <span>选择曲目 / 视频</span>
          <span class="badge badge-muted tnum" id="studio-track-count">0</span>
        </div>
        <input id="studio-search" class="input" style="width:100%;margin-bottom:10px;"
               placeholder="筛选伴奏或教学视频…" oninput="renderStudioTrackList()">
        <div id="studio-track-list" style="max-height:560px;overflow-y:auto;"></div>
      </aside>

      <main class="card">
        <div id="studio-empty" style="padding:40px;text-align:center;color:var(--muted);">
          请从左侧选择一首伴奏音频或教学视频开始 PC 预听与 A-B 段落书签打点
        </div>

        <div id="studio-main" class="hidden">
          <div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;">
            <div>
              <h2 id="studio-title" style="margin:0;font-size:18px;">—</h2>
              <div id="studio-sub" style="font-size:13px;color:var(--muted);">—</div>
            </div>
            <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
              <label id="studio-mirror-wrap" class="btn btn-outline btn-xs hidden" style="cursor:pointer;">
                <input type="checkbox" id="studio-mirror-chk" onchange="toggleVideoMirror(this.checked)"> 🪞 视频水平镜像
              </label>
              <span style="font-size:12px;color:var(--muted);">
                快捷键: <span class="kbd">Space</span> 播/停 · <span class="kbd">[</span> 设A · <span class="kbd">]</span> 设B · <span class="kbd">\</span> 循环开关 · <span class="kbd">R</span> 回A
              </span>
            </div>
          </div>

          <div class="player-stage">
            <video id="studio-video" class="hidden" controls playsinline></video>
            <div id="studio-audio-box" class="audio-hero">
              <div style="font-size:36px;margin-bottom:8px;">🎵</div>
              <audio id="studio-audio" controls style="width:min(560px,92%);"></audio>
            </div>
          </div>

          <!-- Interactive A-B Timeline -->
          <div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px;" class="tnum">
            <span>当前: <strong id="studio-cur-time">00:00.0</strong></span>
            <span id="studio-ab-readout">A-B 区间: 未设置</span>
            <span>总长: <span id="studio-dur-time">00:00.0</span></span>
          </div>
          <div id="studio-timeline" class="timeline-box" onclick="seekOnTimeline(event)">
            <div id="tl-progress" class="timeline-progress"></div>
            <div id="tl-loop" class="timeline-loop hidden"></div>
            <div id="tl-pins"></div>
            <div id="tl-playhead" class="timeline-playhead"></div>
          </div>

          <!-- A-B Loop Controls & Nudge -->
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:14px;">
            <button class="btn btn-tertiary" onclick="setPointA()">[ 设 A 点 (<span id="btn-a-label" class="tnum">--:--</span>)</button>
            <button class="btn btn-tertiary" onclick="setPointB()">] 设 B 点 (<span id="btn-b-label" class="tnum">--:--</span>)</button>
            <button class="btn btn-tonal" id="btn-loop-toggle" onclick="toggleAbLoop()">🔁 循环: 关</button>
            <button class="btn btn-outline" onclick="jumpToPointA()">↺ 回 A 点</button>
            <button class="btn btn-outline" onclick="clearAbPoints()">✕ 清除 A-B</button>
            <span style="margin-left:auto;display:flex;gap:4px;flex-wrap:wrap;">
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('A', -500)">A -0.5s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('A', -100)">A -0.1s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('A', 100)">A +0.1s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('A', 500)">A +0.5s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('B', -500)">B -0.5s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('B', -100)">B -0.1s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('B', 100)">B +0.1s</button>
              <button class="btn btn-outline btn-xs" onclick="nudgePoint('B', 500)">B +0.5s</button>
            </span>
          </div>

          <div style="display:grid;grid-template-columns:1.2fr 1fr;gap:14px;">
            <!-- Named Bookmarks Card -->
            <div class="card" style="background:var(--surface-low);box-shadow:none;">
              <div class="card-title"><span>🔖 段落书签（直存手机数据库）</span></div>
              <div class="chip-row" id="preset-label-chips"></div>
              <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px;">
                <input id="bm-label-input" class="input" placeholder="书签名称，如 Solo 1" style="flex:1;min-width:130px;">
                <label style="font-size:12px;display:flex;align-items:center;gap:4px;">
                  <input type="checkbox" id="bm-save-speed" checked> 关联当前倍速
                </label>
                <button class="btn btn-primary" onclick="saveCurrentBookmark()">+ 保存段落书签</button>
              </div>
              <div id="studio-bookmarks-list"></div>
            </div>

            <!-- Practice Speed / Pitch / BPM State Card -->
            <div class="card" style="background:var(--surface-low);box-shadow:none;">
              <div class="card-title"><span>🎛️ 手机默认练习状态配置</span></div>
              <div class="form-grid">
                <div class="form-field full">
                  <label>变速不变调倍速 (<strong id="speed-val" class="tnum">1.00x</strong> — 浏览器实时同步预听)</label>
                  <input type="range" id="practice-speed-range" min="0.25" max="2.00" step="0.05" value="1.00"
                         oninput="onSpeedSliderChange(this.value)">
                  <div style="display:flex;gap:4px;flex-wrap:wrap;margin-top:4px;">
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(0.5)">0.5x</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(0.65)">0.65x</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(0.75)">0.75x</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(0.85)">0.85x</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(1.0)">1.0x</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(1.25)">1.25x</button>
                  </div>
                </div>
                <div class="form-field">
                  <label>手机升降调半音 (-6 ~ +6 st)</label>
                  <input type="number" id="practice-pitch-input" class="input tnum" min="-6" max="6" value="0">
                </div>
                <div class="form-field">
                  <label>曲速 BPM (30 ~ 300)</label>
                  <div style="display:flex;gap:4px;">
                    <input type="number" id="practice-bpm-input" class="input tnum" min="30" max="300" value="120" style="width:82px;">
                    <button class="btn btn-outline btn-xs" onclick="tapTempo()">点按测速</button>
                  </div>
                </div>
                <div class="form-field full">
                  <label>循环前预备倒计时 (Count-In)</label>
                  <select id="practice-countin-select">
                    <option value="OFF">关闭（无缝连续循环）</option>
                    <option value="SEC_1">1 秒倒计时</option>
                    <option value="SEC_2">2 秒倒计时</option>
                    <option value="BEAT_4">4 拍木鱼节拍倒计时（随 BPM×倍速自动缩放）</option>
                  </select>
                </div>
              </div>
              <button class="btn btn-primary" style="width:100%;" onclick="savePracticeStateToPhone()">💾 保存练习参数到手机</button>
            </div>
          </div>
        </div>
      </main>
    </div>
  </section>
</div>

<!-- Universal Modal Container -->
<div id="modal-root" class="modal-backdrop hidden" onclick="if(event.target===this)closeModal()">
  <div class="modal" id="modal-box"></div>
</div>

<!-- Toast -->
<div id="toast" class="toast hidden"></div>
    """.trimIndent()

    private val APP_JS = """
const state = {
  roots: [],
  currentRootId: null,
  currentRelPath: '',
  currentFolderBrowse: null,
  items: [],
  albums: [],
  artists: [],
  metaFilter: 'all',
  metaSubKey: '',
  selectedItemIds: new Set(),
  playlists: [],
  playlistFilter: 'all',
  activePlaylistId: null,
  activePlaylistDetail: null,
  studioItemId: null,
  studioBookmarks: [],
  studioPractice: null,
  pointAMs: null,
  pointBMs: null,
  loopEnabled: false,
  tapTimes: [],
  presetLabels: ['Intro', 'Verse', 'Pre-Chorus', 'Chorus', 'Bridge', 'Solo 1', 'Solo 2', 'Outro']
};

function esc(str) {
  const s = String(str == null ? '' : str);
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function fmtMs(ms, tenths) {
  const safe = Math.max(0, Math.round(Number(ms) || 0));
  const totalSec = Math.floor(safe / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const pad = (n) => (n < 10 ? '0' + n : String(n));
  const base = h > 0 ? (h + ':' + pad(m) + ':' + pad(s)) : (pad(m) + ':' + pad(s));
  return tenths ? (base + '.' + Math.floor((safe % 1000) / 100)) : base;
}

function fmtBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}

let toastTimer = null;
function showToast(msg) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2800);
}

function initTheme() {
  const saved = localStorage.getItem('mp_lan_theme');
  const dark = saved ? saved === 'dark' : (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
}
function toggleTheme() {
  const cur = document.documentElement.getAttribute('data-theme');
  const next = cur === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem('mp_lan_theme', next);
}

async function api(path, opts) {
  const options = Object.assign({ headers: {} }, opts || {});
  const token = sessionStorage.getItem('mp_lan_pin') || '';
  if (token) options.headers['X-Lan-Token'] = token;
  if (options.json !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(options.json);
    delete options.json;
  }
  const res = await fetch(path, options);
  if (res.status === 401) {
    promptPinAuth();
    throw new Error('需要输入访问 PIN 码');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.ok === false) {
    throw new Error(data.error || ('请求失败 (' + res.status + ')'));
  }
  return data;
}

function promptPinAuth() {
  const box = document.getElementById('modal-box');
  box.innerHTML = '<h3 style="margin-top:0;">🔒 输入局域网访问 PIN 码</h3>' +
    '<p style="font-size:13px;color:var(--muted);">请在手机端「PC 局域网 Web 管理台」面板查看 4 位 PIN 码：</p>' +
    '<input id="pin-modal-input" class="input tnum" style="width:100%;font-size:18px;margin-bottom:14px;" placeholder="例如 1234">' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-primary" onclick="submitPinAuth()">验证并进入</button></div>';
  document.getElementById('modal-root').classList.remove('hidden');
}

async function submitPinAuth() {
  const pin = document.getElementById('pin-modal-input').value.trim();
  if (!pin) return;
  sessionStorage.setItem('mp_lan_pin', pin);
  try {
    await api('/api/auth', { method: 'POST', json: { pin: pin } });
    closeModal();
    await bootstrapAll();
    showToast('已解锁局域网管理台');
  } catch (e) {
    alert(e.message || 'PIN 码错误');
  }
}

function openModal(html) {
  document.getElementById('modal-box').innerHTML = html;
  document.getElementById('modal-root').classList.remove('hidden');
}
function closeModal() {
  document.getElementById('modal-root').classList.add('hidden');
}

function switchTab(tab) {
  document.querySelectorAll('.nav-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.workspace').forEach(s => s.classList.remove('active'));
  document.getElementById('ws-' + tab).classList.add('active');
}

async function refreshStatus() {
  const st = await api('/api/status');
  document.getElementById('stat-summary').textContent =
    '曲库 ' + st.itemCount + ' 首 (视频 ' + st.videoCount + ') · 列表 ' + st.playlistCount + ' 个 · 书签曲目 ' + st.bookmarkedTrackCount;
  const scanBadge = document.getElementById('stat-scan');
  if (st.scan && st.scan.isScanning) {
    scanBadge.textContent = '扫描中 (' + st.scan.scannedCount + ')…';
    scanBadge.classList.remove('hidden');
  } else {
    scanBadge.classList.add('hidden');
  }
}

async function loadRootsAndCurrentFolder() {
  const data = await api('/api/roots');
  state.roots = data.roots || [];
  document.getElementById('roots-count').textContent = String(state.roots.length);
  if (state.roots.length > 0 && !state.roots.some(r => r.rootId === state.currentRootId)) {
    state.currentRootId = state.roots[0].rootId;
    state.currentRelPath = '';
  }
  renderRootsSidebar();
  if (state.currentRootId != null) {
    await browseFolder(state.currentRootId, state.currentRelPath);
  } else {
    document.getElementById('folder-crumbs').innerHTML = '<span style="color:var(--muted);">尚未在手机上添加本地文件夹</span>';
    document.getElementById('folder-entries-tbody').innerHTML =
      '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">请先在手机端 MiniPlayer 点击「+ 添加文件夹」挂载音乐或练琴伴奏目录</td></tr>';
  }
}

function renderRootsSidebar() {
  const container = document.getElementById('roots-list');
  if (!state.roots.length) {
    container.innerHTML = '<div style="color:var(--muted);font-size:13px;">暂无已挂载目录</div>';
    return;
  }
  container.innerHTML = state.roots.map(r => {
    const sel = r.rootId === state.currentRootId ? ' selected' : '';
    const ro = r.isWritable ? '' : ' <span class="badge badge-warn">只读</span>';
    const pf = r.isPracticeFolder ? ' <span class="badge badge-practice">🎯</span>' : '';
    return '<div class="list-item' + sel + '" onclick="selectRoot(' + r.rootId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">📁 ' +
      esc(r.displayName) + pf + ro + '</div>' +
      '<div style="font-size:11px;opacity:0.78;" class="tnum">' + r.itemCount + ' 项 · ' + esc(r.mountState) + '</div></div></div>';
  }).join('');
}

async function selectRoot(rootId) {
  state.currentRootId = rootId;
  state.currentRelPath = '';
  renderRootsSidebar();
  await browseFolder(rootId, '');
}

async function browseFolder(rootId, relPath) {
  state.currentRootId = rootId;
  state.currentRelPath = relPath || '';
  const data = await api('/api/folders?rootId=' + encodeURIComponent(rootId) + '&relPath=' + encodeURIComponent(state.currentRelPath));
  state.currentFolderBrowse = data;
  document.getElementById('folder-practice-toggle').checked = !!data.isCurrentPracticeFolder;

  // Breadcrumbs
  const rootName = (data.root && data.root.displayName) || '根目录';
  let crumbsHtml = '<button class="crumb-link" onclick="browseFolder(' + rootId + ', \'\')">📁 ' + esc(rootName) + '</button>';
  const parts = state.currentRelPath ? state.currentRelPath.split('/').filter(Boolean) : [];
  let acc = '';
  for (const seg of parts) {
    acc = acc ? (acc + '/' + seg) : seg;
    const safeAcc = acc.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    crumbsHtml += '<span>/</span><button class="crumb-link" onclick="browseFolder(' + rootId + ', \'' + safeAcc + '\')">' + esc(seg) + '</button>';
  }
  document.getElementById('folder-crumbs').innerHTML = crumbsHtml;

  const subRows = (data.subfolders || []).map(sf => {
    const safeRel = (sf.relPath || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    const pfBadge = sf.isPracticeFolder ? '<span class="badge badge-practice">🎯 练习目录</span>' : '<span class="badge badge-muted">普通目录</span>';
    return '<tr>' +
      '<td><a href="javascript:void(0)" onclick="browseFolder(' + rootId + ', \'' + safeRel + '\')" style="color:var(--primary);font-weight:600;text-decoration:none;">📁 ' + esc(sf.name) + '</a></td>' +
      '<td class="tnum">子文件夹 · ' + (sf.itemCount || 0) + ' 项</td>' +
      '<td>—</td>' +
      '<td>' + pfBadge + '</td>' +
      '<td style="text-align:right;"><button class="btn btn-outline btn-xs" onclick="toggleSubfolderPractice(' + rootId + ', \'' + safeRel + '\', ' + (!sf.isPracticeFolder) + ')">' +
      (sf.isPracticeFolder ? '取消练习标记' : '🎯 设为练习目录') + '</button></td></tr>';
  });

  const itemRows = (data.mediaItems || []).map(it => {
    const kind = it.isVideo ? ('🎬 视频' + (it.resolutionBadge ? ' ' + it.resolutionBadge : '')) : '🎵 音频';
    const badges = [];
    if (it.isInPracticeFolder) badges.push('<span class="badge badge-practice">🎯 练习目录</span>');
    if (it.bookmarkCount > 0) badges.push('<span class="badge badge-ok">🔖 ' + it.bookmarkCount + ' 段</span>');
    return '<tr>' +
      '<td><div style="font-weight:600;">' + esc(it.title) + '</div><div style="font-size:11px;color:var(--muted);">' + esc(it.fileName) + '</div></td>' +
      '<td class="tnum">' + esc(kind) + ' · ' + fmtMs(it.durationMs, false) + ' · ' + fmtBytes(it.sizeBytes) + '</td>' +
      '<td>' + esc(it.artist) + ' · <span style="color:var(--muted);">' + esc(it.displayAlbum) + '</span></td>' +
      '<td>' + (badges.join(' ') || '—') + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">🎸 预听/打点</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="openEditItemModal(' + it.itemId + ')">✏️ 标签</button></td></tr>';
  });

  const allRows = subRows.concat(itemRows);
  document.getElementById('folder-entries-tbody').innerHTML = allRows.length
    ? allRows.join('')
    : '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">此目录为空，可直接拖拽音视频文件到上方区域无线上传</td></tr>';
}

async function promptCreateSubfolder() {
  if (state.currentRootId == null) { alert('请先在手机上添加文件夹'); return; }
  const name = window.prompt('新建子文件夹名称（如 Backing 或 J-Rock）：');
  if (!name || !name.trim()) return;
  try {
    await api('/api/folders/mkdir', {
      method: 'POST',
      json: { rootId: state.currentRootId, relPath: state.currentRelPath, name: name.trim() }
    });
    showToast('已创建文件夹: ' + name.trim());
    await browseFolder(state.currentRootId, state.currentRelPath);
    await loadRootsAndCurrentFolder();
  } catch (e) { alert(e.message); }
}

async function toggleCurrentFolderPractice(checked) {
  if (state.currentRootId == null) return;
  try {
    await api('/api/folders/rule', {
      method: 'POST',
      json: { rootId: state.currentRootId, relPath: state.currentRelPath, isPracticeFolder: checked }
    });
    showToast(checked ? '🎯 已标记为练习文件夹' : '已切换为普通文件夹');
    await loadAllItems();
    await loadRootsAndCurrentFolder();
  } catch (e) {
    alert(e.message);
    document.getElementById('folder-practice-toggle').checked = !checked;
  }
}

async function toggleSubfolderPractice(rootId, relPath, isPractice) {
  try {
    await api('/api/folders/rule', {
      method: 'POST',
      json: { rootId: rootId, relPath: relPath, isPracticeFolder: isPractice }
    });
    showToast(isPractice ? '🎯 子目录已设为练习目录' : '已取消练习目录标记');
    await browseFolder(state.currentRootId, state.currentRelPath);
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

function sendSingleUpload(rootId, relPath, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const url = '/api/upload?rootId=' + encodeURIComponent(rootId) +
      '&relPath=' + encodeURIComponent(relPath || '') +
      '&name=' + encodeURIComponent(file.name);
    xhr.open('POST', url, true);
    const token = sessionStorage.getItem('mp_lan_pin') || '';
    if (token) xhr.setRequestHeader('X-Lan-Token', token);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (ev) => { if (ev.lengthComputable) onProgress(ev.loaded); };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else {
        let msg = '上传失败 (' + xhr.status + ')';
        try { msg = JSON.parse(xhr.responseText).error || msg; } catch (_) {}
        reject(new Error(msg));
      }
    };
    xhr.onerror = () => reject(new Error('网络中断，上传失败'));
    xhr.send(file);
  });
}

async function uploadFilesToCurrentFolder(fileList) {
  if (!fileList || !fileList.length) return;
  if (state.currentRootId == null) { alert('请先在手机端挂载至少一个可写文件夹'); return; }
  const files = Array.from(fileList);
  const box = document.getElementById('upload-progress-box');
  const txt = document.getElementById('upload-status-text');
  const pctEl = document.getElementById('upload-pct-text');
  const bar = document.getElementById('upload-bar-fill');
  box.classList.remove('hidden');

  const totalBytes = files.reduce((a, f) => a + (f.size || 0), 0);
  let doneBytes = 0;
  try {
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      txt.textContent = '正在上传 (' + (i + 1) + '/' + files.length + '): ' + f.name;
      await sendSingleUpload(state.currentRootId, state.currentRelPath, f, (loaded) => {
        const pct = totalBytes > 0 ? Math.min(100, Math.round(((doneBytes + loaded) / totalBytes) * 100)) : 100;
        pctEl.textContent = pct + '%';
        bar.style.width = pct + '%';
      });
      doneBytes += (f.size || 0);
    }
    showToast('已成功上传 ' + files.length + ' 个文件并自动入库！');
    await bootstrapAll();
  } catch (e) {
    alert(e.message || '上传失败');
  } finally {
    box.classList.add('hidden');
    bar.style.width = '0%';
  }
}

async function triggerRescanAll() {
  try {
    await api('/api/folders/rescan', { method: 'POST', json: {} });
    await bootstrapAll();
    showToast('已完成曲库增量扫描');
  } catch (e) { alert(e.message); }
}

// ---------------- WORKSPACE 2: METADATA SPREADSHEET ----------------
async function loadAllItems() {
  const data = await api('/api/items?includeFacets=1');
  state.items = data.items || [];
  state.albums = data.albums || [];
  state.artists = data.artists || [];
  renderMetadataTable();
  renderStudioTrackList();
}

function setMetaFilter(filter) {
  state.metaFilter = filter;
  state.metaSubKey = '';
  document.querySelectorAll('#ws-metadata .chip[data-filter]').forEach(c => c.classList.toggle('active', c.dataset.filter === filter));
  const sub = document.getElementById('meta-subfilter');
  if (filter === 'folder') {
    const folders = Array.from(new Set(state.items.map(i => i.rootId + '::' + (i.parentRelPath || '')))).sort();
    sub.innerHTML = '<option value="">全部文件夹</option>' + folders.map(k => {
      const idx = k.indexOf('::');
      const rId = Number(k.slice(0, idx));
      const rel = k.slice(idx + 2);
      const rName = (state.roots.find(r => r.rootId === rId) || {}).displayName || ('Root #' + rId);
      return '<option value="' + esc(k) + '">' + esc(rName + (rel ? '/' + rel : '')) + '</option>';
    }).join('');
    sub.classList.remove('hidden');
  } else if (filter === 'album') {
    sub.innerHTML = '<option value="">全部专辑</option>' + state.albums.map(a =>
      '<option value="' + esc(a.albumKey) + '">' + esc(a.title) + ' (' + a.trackCount + ')</option>'
    ).join('');
    sub.classList.remove('hidden');
  } else if (filter === 'artist') {
    sub.innerHTML = '<option value="">全部歌手</option>' + state.artists.map(a =>
      '<option value="' + esc(a.artistNorm) + '">' + esc(a.displayName) + ' (' + a.trackCount + ')</option>'
    ).join('');
    sub.classList.remove('hidden');
  } else {
    sub.classList.add('hidden');
  }
  renderMetadataTable();
}

function onMetaSubfilterChange() {
  state.metaSubKey = document.getElementById('meta-subfilter').value;
  renderMetadataTable();
}

function getFilteredMetaItems() {
  const q = (document.getElementById('meta-search').value || '').trim().toLowerCase();
  return state.items.filter(it => {
    if (state.metaFilter === 'video' && !it.isVideo) return false;
    if (state.metaFilter === 'unknown_album' && (it.album && it.album.trim() !== '' && it.album !== '未知专辑')) return false;
    if (state.metaFilter === 'folder' && state.metaSubKey) {
      if ((it.rootId + '::' + (it.parentRelPath || '')) !== state.metaSubKey) return false;
    }
    if (state.metaFilter === 'album' && state.metaSubKey) {
      const matchedAlbum = state.albums.find(a => a.albumKey === state.metaSubKey);
      if (matchedAlbum) {
        if (matchedAlbum.isUnknownAlbum) {
          if (it.album && it.album.trim() !== '') return false;
        } else if (it.displayAlbum !== matchedAlbum.title) return false;
      }
    }
    if (state.metaFilter === 'artist' && state.metaSubKey) {
      const artistObj = state.artists.find(a => a.artistNorm === state.metaSubKey);
      const needle = (artistObj ? artistObj.displayName : state.metaSubKey).toLowerCase();
      if (!(it.artist || '').toLowerCase().includes(needle) && !(it.albumArtist || '').toLowerCase().includes(needle)) return false;
    }
    if (q) {
      const hay = ((it.title || '') + ' ' + (it.artist || '') + ' ' + (it.displayAlbum || '') + ' ' + (it.fileName || '')).toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

function renderMetadataTable() {
  const rows = getFilteredMetaItems();
  const tbody = document.getElementById('meta-tbody');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:30px;color:var(--muted);">没有符合当前筛选条件的曲目</td></tr>';
    updateBatchBar();
    return;
  }
  tbody.innerHTML = rows.map(it => {
    const checked = state.selectedItemIds.has(it.itemId) ? ' checked' : '';
    const unknownAlb = !it.album || !it.album.trim();
    const albumHtml = unknownAlb
      ? '<span class="badge badge-warn editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'album\',\'\')">未知专辑</span>'
      : '<span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'album\',\'' + escJs(it.album) + '\')">' + esc(it.album) + '</span>';
    const overrideDot = it.hasMetadataOverride ? ' <span class="badge badge-ok" title="已含本地覆盖标签">已改</span>' : '';
    const backingDot = it.isBackingTrack ? ' <span class="badge badge-practice">伴奏</span>' : '';
    return '<tr>' +
      '<td><input type="checkbox" onchange="toggleSelectItem(' + it.itemId + ', this.checked)"' + checked + '></td>' +
      '<td class="tnum"><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'trackNumber\',\'' + (it.trackNumber || '') + '\')">' + (it.trackNumber || '—') + '</span></td>' +
      '<td><span class="editable-cell" style="font-weight:600;" ondblclick="inlineEditCell(this,' + it.itemId + ',\'title\',\'' + escJs(it.title) + '\')">' + esc(it.title) + '</span>' + overrideDot + backingDot +
      '<div style="font-size:11px;color:var(--muted);">' + esc((it.parentRelPath ? it.parentRelPath + '/' : '') + it.fileName) + '</div></td>' +
      '<td><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'artist\',\'' + escJs(it.artist) + '\')">' + esc(it.artist) + '</span></td>' +
      '<td>' + albumHtml + '</td>' +
      '<td class="tnum"><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'year\',\'' + (it.year || '') + '\')">' + (it.year || '—') + '</span></td>' +
      '<td class="tnum">' + (it.isVideo ? '🎬 ' : '🎵 ') + fmtMs(it.durationMs, false) + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-tertiary btn-xs" onclick="autoMatchSingle(' + it.itemId + ', false)">✨ 自动联网匹配</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="openEditItemModal(' + it.itemId + ')">✏️ 编辑</button> ' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">🎸</button></td></tr>';
  }).join('');
  updateBatchBar();
}

function escJs(s) {
  return String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, ' ');
}

function inlineEditCell(spanEl, itemId, field, currentVal) {
  const input = document.createElement('input');
  input.className = 'input';
  input.style.width = (field === 'trackNumber' || field === 'year') ? '64px' : '100%';
  input.value = currentVal;
  spanEl.replaceWith(input);
  input.focus();
  input.select();
  let saved = false;
  const finish = async (commit) => {
    if (saved) return;
    saved = true;
    if (!commit || input.value === String(currentVal)) {
      renderMetadataTable();
      return;
    }
    const payload = { itemId: itemId };
    if (field === 'trackNumber' || field === 'year') {
      const n = input.value.trim() === '' ? null : parseInt(input.value.trim(), 10);
      payload[field] = Number.isNaN(n) ? null : n;
    } else {
      payload[field] = input.value.trim();
    }
    try {
      await api('/api/metadata/update', { method: 'POST', json: payload });
      showToast('已更新标签');
      await loadAllItems();
    } catch (e) {
      alert(e.message);
      renderMetadataTable();
    }
  };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') finish(true);
    else if (e.key === 'Escape') finish(false);
  });
  input.addEventListener('blur', () => finish(true));
}

function toggleSelectItem(itemId, checked) {
  if (checked) state.selectedItemIds.add(itemId);
  else state.selectedItemIds.delete(itemId);
  updateBatchBar();
}

function toggleSelectAllMeta(checked) {
  const rows = getFilteredMetaItems();
  rows.forEach(r => { if (checked) state.selectedItemIds.add(r.itemId); else state.selectedItemIds.delete(r.itemId); });
  renderMetadataTable();
}

function updateBatchBar() {
  const bar = document.getElementById('meta-batch-bar');
  const cnt = state.selectedItemIds.size;
  document.getElementById('meta-sel-count').textContent = String(cnt);
  bar.classList.toggle('hidden', cnt === 0);
}

function openEditItemModal(itemId) {
  const it = state.items.find(x => x.itemId === itemId);
  if (!it) return;
  openModal(
    '<h3 style="margin-top:0;">✏️ 编辑曲目元数据</h3>' +
    '<div style="font-size:12px;color:var(--muted);margin-bottom:12px;">文件: ' + esc(it.fileName) + '</div>' +
    '<div class="form-grid">' +
    '<div class="form-field full"><label>标题 (Title)</label><input id="ed-title" class="input" value="' + esc(it.title) + '"></div>' +
    '<div class="form-field"><label>歌手 (Artist)</label><input id="ed-artist" class="input" value="' + esc(it.artist) + '"></div>' +
    '<div class="form-field"><label>专辑 (Album)</label><input id="ed-album" class="input" value="' + esc(it.album || '') + '"></div>' +
    '<div class="form-field"><label>专辑歌手 (Album Artist)</label><input id="ed-album-artist" class="input" value="' + esc(it.albumArtist || '') + '"></div>' +
    '<div class="form-field"><label>音轨号 / 年份</label><div style="display:flex;gap:6px;">' +
    '<input id="ed-track" type="number" class="input tnum" placeholder="Track#" value="' + (it.trackNumber || '') + '" style="width:50%;">' +
    '<input id="ed-year" type="number" class="input tnum" placeholder="Year" value="' + (it.year || '') + '" style="width:50%;"></div></div>' +
    '</div>' +
    '<label style="display:flex;align-items:center;gap:6px;font-size:12px;margin-bottom:16px;">' +
    '<input type="checkbox" id="ed-writeback"' + (it.isRootWritable ? '' : ' disabled') + '> 同时尝试写回原文件内嵌标签（默认仅写手机高速覆盖层）</label>' +
    '<div style="display:flex;justify-content:space-between;gap:8px;">' +
    '<button class="btn btn-tertiary" onclick="autoMatchSingle(' + it.itemId + ', true)">🔍 在线搜索候选列表</button>' +
    '<div style="display:flex;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
    '<button class="btn btn-primary" onclick="submitSingleMetadata(' + it.itemId + ')">保存</button></div></div>'
  );
}

async function submitSingleMetadata(itemId) {
  const trk = document.getElementById('ed-track').value.trim();
  const yr = document.getElementById('ed-year').value.trim();
  try {
    await api('/api/metadata/update', {
      method: 'POST',
      json: {
        itemId: itemId,
        title: document.getElementById('ed-title').value.trim(),
        artist: document.getElementById('ed-artist').value.trim(),
        album: document.getElementById('ed-album').value.trim(),
        albumArtist: document.getElementById('ed-album-artist').value.trim(),
        trackNumber: trk ? parseInt(trk, 10) : null,
        year: yr ? parseInt(yr, 10) : null,
        writeBackToFile: document.getElementById('ed-writeback').checked
      }
    });
    closeModal();
    showToast('元数据已保存');
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

function openBatchEditModal() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length) return;
  openModal(
    '<h3 style="margin-top:0;">✏️ 批量修改 ' + ids.length + ' 首曲目元数据</h3>' +
    '<p style="font-size:12px;color:var(--muted);">仅勾选的字段会被批量覆盖，未勾选字段保持每首曲目原值不变：</p>' +
    '<div class="form-grid">' +
    '<div class="form-field full"><label><input type="checkbox" id="b-chk-artist"> 统一修改歌手 (Artist)</label><input id="b-artist" class="input" placeholder="歌手名称"></div>' +
    '<div class="form-field full"><label><input type="checkbox" id="b-chk-album" checked> 统一修改专辑 (Album)</label><input id="b-album" class="input" placeholder="专辑名称"></div>' +
    '<div class="form-field"><label><input type="checkbox" id="b-chk-aa"> 专辑歌手 (Album Artist)</label><input id="b-aa" class="input" placeholder="专辑歌手"></div>' +
    '<div class="form-field"><label><input type="checkbox" id="b-chk-year"> 发行年份 (Year)</label><input id="b-year" type="number" class="input tnum" placeholder="如 2024"></div>' +
    '</div>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
    '<button class="btn btn-primary" onclick="submitBatchMetadata()">批量应用</button></div>'
  );
}

async function submitBatchMetadata() {
  const payload = { itemIds: Array.from(state.selectedItemIds) };
  let anyField = false;
  if (document.getElementById('b-chk-artist').checked) { payload.artist = document.getElementById('b-artist').value.trim(); anyField = true; }
  if (document.getElementById('b-chk-album').checked) { payload.album = document.getElementById('b-album').value.trim(); anyField = true; }
  if (document.getElementById('b-chk-aa').checked) { payload.albumArtist = document.getElementById('b-aa').value.trim(); anyField = true; }
  if (document.getElementById('b-chk-year').checked) {
    const y = document.getElementById('b-year').value.trim();
    payload.year = y ? parseInt(y, 10) : null;
    anyField = true;
  }
  if (!anyField) { alert('请至少勾选一个要批量修改的字段'); return; }
  try {
    const res = await api('/api/metadata/batch-update', { method: 'POST', json: payload });
    closeModal();
    showToast('已批量更新 ' + res.updatedCount + ' 首曲目');
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function resetSelectedMetadata() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length || !confirm('确认恢复所选 ' + ids.length + ' 首曲目的原始标签？')) return;
  try {
    await api('/api/metadata/reset', { method: 'POST', json: { itemIds: ids } });
    showToast('已恢复原始标签');
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function autoMatchSingle(itemId, previewOnly) {
  showToast('正在免 Key 检索 MusicBrainz / iTunes / LRCLIB…');
  try {
    const res = await api('/api/metadata/auto-match?itemId=' + encodeURIComponent(itemId), {
      method: 'POST',
      json: { itemId: itemId, previewOnly: !!previewOnly }
    });
    if (!previewOnly) {
      if (res.applied) {
        showToast('✨ 已自动匹配: ' + (res.selectedCandidate ? (res.selectedCandidate.artist + ' - ' + res.selectedCandidate.title) : '成功'));
        await loadAllItems();
      } else {
        showToast('未找到高置信度在线候选，请打开编辑手动搜索');
      }
      return;
    }
    const cands = res.candidates || [];
    if (!cands.length) {
      alert('未检索到匹配的在线候选结果');
      return;
    }
    state._lastCandidates = cands;
    openModal(
      '<h3 style="margin-top:0;">✨ 选择在线匹配候选</h3>' +
      cands.map((c, idx) =>
        '<div class="list-item" style="border:1px solid var(--border);margin-bottom:8px;" onclick="applyPickedCandidate(' + itemId + ',' + idx + ')">' +
        '<div><div style="font-weight:600;">' + esc(c.title) + ' — ' + esc(c.artist) + '</div>' +
        '<div style="font-size:12px;color:var(--muted);">专辑: ' + esc(c.album || '—') + ' · 年份: ' + (c.year || '—') + ' · 来源: ' + esc(c.source) + '</div></div>' +
        '<span class="badge badge-ok tnum">' + c.confidenceScore + '%</span></div>'
      ).join('') +
      '<div style="text-align:right;"><button class="btn btn-outline" onclick="closeModal()">关闭</button></div>'
    );
  } catch (e) { alert(e.message); }
}

async function applyPickedCandidate(itemId, idx) {
  try {
    await api('/api/metadata/auto-match?itemId=' + encodeURIComponent(itemId), {
      method: 'POST',
      json: { itemId: itemId, candidateIndex: idx, previewOnly: false }
    });
    closeModal();
    showToast('已应用所选在线标签');
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function runBatchAutoCompleteSelected() {
  const ids = Array.from(state.selectedItemIds);
  await executeBatchAutoComplete(ids);
}

async function runBatchAutoCompletePreview() {
  const ids = getFilteredMetaItems().map(i => i.itemId);
  if (!ids.length) { alert('当前筛选列表为空'); return; }
  await executeBatchAutoComplete(ids);
}

async function executeBatchAutoComplete(itemIds) {
  showToast('正在批量联网检索 ' + itemIds.length + ' 首曲目…');
  try {
    const res = await api('/api/metadata/batch-auto-complete', {
      method: 'POST',
      json: { itemIds: itemIds, mode: 'auto' }
    });
    showToast('✨ 批量联网补全完成：成功更新 ' + (res.committedCount || 0) + ' 首曲目');
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

// ---------------- WORKSPACE 3: PLAYLISTS & PRACTICE LISTS ----------------
async function loadPlaylists() {
  const data = await api('/api/playlists');
  state.playlists = data.playlists || [];
  renderPlaylistsSidebar();
  if (state.activePlaylistId != null && state.playlists.some(p => p.playlistId === state.activePlaylistId)) {
    await selectPlaylist(state.activePlaylistId);
  } else if (state.playlists.length > 0) {
    await selectPlaylist(state.playlists[0].playlistId);
  } else {
    state.activePlaylistId = null;
    document.getElementById('playlist-detail-pane').innerHTML =
      '<div style="padding:36px;text-align:center;color:var(--muted);">暂无播放列表，点击左上角「+ 新建」创建普通列表或 🎯 练习列表</div>';
  }
}

function setPlaylistFilter(f) {
  state.playlistFilter = f;
  document.getElementById('pl-filter-all').classList.toggle('active', f === 'all');
  document.getElementById('pl-filter-practice').classList.toggle('active', f === 'practice');
  renderPlaylistsSidebar();
}

function renderPlaylistsSidebar() {
  const list = state.playlists.filter(p => state.playlistFilter !== 'practice' || p.isPracticePlaylist);
  const el = document.getElementById('playlists-sidebar');
  if (!list.length) {
    el.innerHTML = '<div style="font-size:13px;color:var(--muted);padding:12px 0;">无符合条件的播放列表</div>';
    return;
  }
  el.innerHTML = list.map(p => {
    const sel = p.playlistId === state.activePlaylistId ? ' selected' : '';
    const badge = p.isPracticePlaylist ? '<span class="badge badge-practice">🎯 练习</span>' : '';
    return '<div class="list-item' + sel + '" onclick="selectPlaylist(' + p.playlistId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
      esc(p.name) + ' ' + badge + '</div>' +
      '<div style="font-size:11px;opacity:0.78;" class="tnum">' + p.itemCount + ' 首 · 🔖 ' + (p.totalBookmarks || 0) + ' 段</div></div></div>';
  }).join('');
}

async function selectPlaylist(playlistId) {
  state.activePlaylistId = playlistId;
  renderPlaylistsSidebar();
  const data = await api('/api/playlists?id=' + encodeURIComponent(playlistId));
  state.activePlaylistDetail = data.playlist;
  renderPlaylistDetail();
}

function renderPlaylistDetail() {
  const pane = document.getElementById('playlist-detail-pane');
  const d = state.activePlaylistDetail;
  if (!d || !d.summary) return;
  const s = d.summary;
  const items = d.items || [];

  const rowsHtml = items.length ? items.map((it, idx) => {
    const speedTag = it.savedPracticeSpeed && Math.abs(it.savedPracticeSpeed - 1.0) > 0.01
      ? '<span class="badge badge-practice tnum">' + Number(it.savedPracticeSpeed).toFixed(2) + 'x</span> ' : '';
    const bmTag = it.bookmarkCount > 0 ? '<span class="badge badge-ok tnum">🔖 ' + it.bookmarkCount + ' 段</span>' : '';
    return '<tr class="drag-row" draggable="true" data-itemid="' + it.itemId + '" ' +
      'ondragstart="onPlDragStart(event,' + idx + ')" ondragover="event.preventDefault()" ondrop="onPlDrop(event,' + idx + ')">' +
      '<td class="tnum" style="width:44px;color:var(--muted);">☰ ' + (idx + 1) + '</td>' +
      '<td><div style="font-weight:600;">' + (it.isVideo ? '🎬 ' : '🎵 ') + esc(it.title) + '</div>' +
      '<div style="font-size:11px;color:var(--muted);">' + esc(it.artist) + ' · ' + esc(it.displayAlbum) + '</div></td>' +
      '<td>' + speedTag + bmTag + '</td>' +
      '<td class="tnum">' + fmtMs(it.durationMs, false) + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-outline btn-xs" onclick="movePlItem(' + idx + ',-1)"' + (idx === 0 ? ' disabled' : '') + '>↑</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="movePlItem(' + idx + ',1)"' + (idx === items.length - 1 ? ' disabled' : '') + '>↓</button> ' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">🎸 打点</button> ' +
      '<button class="btn btn-danger btn-xs" onclick="removePlItem(' + s.playlistId + ',' + it.itemId + ')">✕</button></td></tr>';
  }).join('') : '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">列表暂无曲目，点击右上角「+ 添加曲目」从曲库选歌或教学视频混排</td></tr>';

  pane.innerHTML =
    '<div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:14px;">' +
    '<div><h2 style="margin:0;font-size:19px;">' + esc(s.name) + '</h2>' +
    '<div style="font-size:12px;color:var(--muted);" class="tnum">' + items.length + ' 个音视频条目（支持鼠标拖拽行排序）</div></div>' +
    '<div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">' +
    '<label class="badge badge-practice" style="cursor:pointer;padding:6px 12px;font-size:12px;">' +
    '<input type="checkbox" ' + (s.isPracticePlaylist ? 'checked' : '') + ' onchange="togglePlaylistPractice(' + s.playlistId + ', this.checked)"> 🎯 练习列表（手机播放自动展开练琴台）</label>' +
    '<button class="btn btn-primary btn-xs" onclick="openAddTracksToActivePlaylist()">+ 添加曲目</button>' +
    '<button class="btn btn-outline btn-xs" onclick="renameActivePlaylist(' + s.playlistId + ', \'' + escJs(s.name) + '\')">重命名</button>' +
    '<a class="btn btn-tonal btn-xs" href="/api/playlists/export?id=' + s.playlistId + '">📤 导出 .m3u8</a>' +
    '<button class="btn btn-danger btn-xs" onclick="deleteActivePlaylist(' + s.playlistId + ')">🗑️ 删除</button>' +
    '</div></div>' +
    '<div class="table-wrap"><table><thead><tr><th>序</th><th>曲目 / 视频</th><th>练习标记</th><th>时长</th><th style="text-align:right;">排序 / 操作</th></tr></thead>' +
    '<tbody>' + rowsHtml + '</tbody></table></div>';
}

let dragSrcIdx = -1;
function onPlDragStart(ev, idx) { dragSrcIdx = idx; ev.dataTransfer.effectAllowed = 'move'; }
async function onPlDrop(ev, targetIdx) {
  ev.preventDefault();
  if (dragSrcIdx < 0 || dragSrcIdx === targetIdx || !state.activePlaylistDetail) return;
  const ids = state.activePlaylistDetail.items.map(i => i.itemId);
  const [moved] = ids.splice(dragSrcIdx, 1);
  ids.splice(targetIdx, 0, moved);
  dragSrcIdx = -1;
  await reorderActivePlaylist(ids);
}
async function movePlItem(idx, delta) {
  if (!state.activePlaylistDetail) return;
  const ids = state.activePlaylistDetail.items.map(i => i.itemId);
  const target = idx + delta;
  if (target < 0 || target >= ids.length) return;
  const tmp = ids[idx]; ids[idx] = ids[target]; ids[target] = tmp;
  await reorderActivePlaylist(ids);
}
async function reorderActivePlaylist(orderedIds) {
  try {
    await api('/api/playlists/items/reorder', {
      method: 'POST',
      json: { playlistId: state.activePlaylistId, orderedItemIds: orderedIds }
    });
    await selectPlaylist(state.activePlaylistId);
  } catch (e) { alert(e.message); }
}

function openCreatePlaylistModal() {
  openModal(
    '<h3 style="margin-top:0;">+ 新建播放列表</h3>' +
    '<div class="form-field" style="margin-bottom:12px;"><label>列表名称</label>' +
    '<input id="new-pl-name" class="input" placeholder="例如：吉他 Solo 每日必练"></div>' +
    '<label style="display:flex;align-items:center;gap:8px;font-size:13px;font-weight:600;margin-bottom:16px;cursor:pointer;">' +
    '<input type="checkbox" id="new-pl-practice" checked> 🎯 设为练习列表（在手机端点击曲目自动开启练习模式）</label>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
    '<button class="btn btn-primary" onclick="submitCreatePlaylist()">创建</button></div>'
  );
}

async function submitCreatePlaylist() {
  const name = document.getElementById('new-pl-name').value.trim();
  const isPractice = document.getElementById('new-pl-practice').checked;
  if (!name) { alert('请输入播放列表名称'); return; }
  try {
    const res = await api('/api/playlists', { method: 'POST', json: { name: name, isPracticePlaylist: isPractice } });
    closeModal();
    state.activePlaylistId = res.playlistId;
    await loadPlaylists();
    await refreshStatus();
    showToast('已创建列表: ' + name);
  } catch (e) { alert(e.message); }
}

async function renameActivePlaylist(id, oldName) {
  const name = prompt('重命名播放列表：', oldName);
  if (!name || !name.trim() || name.trim() === oldName) return;
  try {
    await api('/api/playlists/rename', { method: 'POST', json: { playlistId: id, name: name.trim() } });
    await loadPlaylists();
  } catch (e) { alert(e.message); }
}

async function togglePlaylistPractice(id, isPractice) {
  try {
    await api('/api/playlists/practice-mode', { method: 'POST', json: { playlistId: id, isPracticePlaylist: isPractice } });
    showToast(isPractice ? '🎯 已设为练习列表' : '已切换为普通播放列表');
    await loadPlaylists();
  } catch (e) { alert(e.message); }
}

async function deleteActivePlaylist(id) {
  if (!confirm('确认删除该播放列表？（不会删除手机中的原音视频文件）')) return;
  try {
    await api('/api/playlists?id=' + encodeURIComponent(id), { method: 'DELETE' });
    state.activePlaylistId = null;
    await loadPlaylists();
    await refreshStatus();
    showToast('已删除播放列表');
  } catch (e) { alert(e.message); }
}

async function removePlItem(playlistId, itemId) {
  try {
    await api('/api/playlists/items/remove', { method: 'POST', json: { playlistId: playlistId, itemId: itemId } });
    await loadPlaylists();
  } catch (e) { alert(e.message); }
}

function openAddTracksToActivePlaylist() {
  if (!state.activePlaylistId) return;
  const existing = new Set(((state.activePlaylistDetail && state.activePlaylistDetail.items) || []).map(i => i.itemId));
  const candidates = state.items.filter(i => !existing.has(i.itemId));
  openModal(
    '<h3 style="margin-top:0;">添加曲目/视频到当前列表</h3>' +
    '<div style="max-height:360px;overflow-y:auto;border:1px solid var(--border);border-radius:12px;padding:8px;margin-bottom:14px;">' +
    (candidates.length ? candidates.map(it =>
      '<label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">' +
      '<input type="checkbox" class="pl-add-chk" value="' + it.itemId + '"> ' +
      '<span>' + (it.isVideo ? '🎬 ' : '🎵 ') + '<strong>' + esc(it.title) + '</strong> — ' + esc(it.artist) + '</span></label>'
    ).join('') : '<div style="padding:18px;text-align:center;color:var(--muted);">所有曲库曲目均已在列表中</div>') +
    '</div><div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
    '<button class="btn btn-primary" onclick="submitAddTracksToActivePlaylist()">添加所选</button></div>'
  );
}

async function submitAddTracksToActivePlaylist() {
  const ids = Array.from(document.querySelectorAll('.pl-add-chk:checked')).map(el => Number(el.value));
  if (!ids.length) { closeModal(); return; }
  try {
    await api('/api/playlists/items/add', { method: 'POST', json: { playlistId: state.activePlaylistId, itemIds: ids } });
    closeModal();
    await loadPlaylists();
    showToast('已添加 ' + ids.length + ' 项到列表');
  } catch (e) { alert(e.message); }
}

function openAddSelectedToPlaylistModal() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length) return;
  if (!state.playlists.length) { alert('请先在「播放列表」页新建一个播放列表'); return; }
  openModal(
    '<h3 style="margin-top:0;">🎯 将 ' + ids.length + ' 首曲目加入播放/练习列表</h3>' +
    '<select id="batch-pl-target" style="width:100%;margin-bottom:16px;">' +
    state.playlists.map(p => '<option value="' + p.playlistId + '">' + (p.isPracticePlaylist ? '🎯 ' : '') + esc(p.name) + '</option>').join('') +
    '</select><div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">取消</button>' +
    '<button class="btn btn-primary" onclick="submitBatchAddToPlaylist()">加入列表</button></div>'
  );
}

async function submitBatchAddToPlaylist() {
  const plId = Number(document.getElementById('batch-pl-target').value);
  const ids = Array.from(state.selectedItemIds);
  try {
    await api('/api/playlists/items/add', { method: 'POST', json: { playlistId: plId, itemIds: ids } });
    closeModal();
    await loadPlaylists();
    showToast('已加入列表');
  } catch (e) { alert(e.message); }
}

async function importM3uFile(file) {
  if (!file) return;
  try {
    const content = await file.text();
    const fallback = file.name.replace(/\.(m3u8|m3u)$/i, '') || '导入列表';
    const res = await api('/api/playlists/import', {
      method: 'POST',
      json: { name: fallback, content: content }
    });
    state.activePlaylistId = res.playlistId;
    await loadPlaylists();
    await refreshStatus();
    showToast('成功导入「' + res.playlistName + '」: 匹配 ' + res.matchedCount + ' 首（未找到 ' + res.unmatchedCount + ' 首）');
  } catch (e) { alert(e.message); }
}

// ---------------- WORKSPACE 4: PC PREVIEW & A-B BOOKMARK STUDIO ----------------
function activeMediaEl() {
  const it = state.items.find(x => x.itemId === state.studioItemId);
  return (it && it.isVideo) ? document.getElementById('studio-video') : document.getElementById('studio-audio');
}

function renderStudioTrackList() {
  document.getElementById('studio-track-count').textContent = String(state.items.length);
  const q = (document.getElementById('studio-search').value || '').trim().toLowerCase();
  const filtered = state.items.filter(it => !q || ((it.title + ' ' + it.artist + ' ' + it.fileName).toLowerCase().includes(q)));
  const el = document.getElementById('studio-track-list');
  el.innerHTML = filtered.map(it => {
    const sel = it.itemId === state.studioItemId ? ' selected' : '';
    const bm = it.bookmarkCount > 0 ? ' <span class="badge badge-ok tnum">🔖' + it.bookmarkCount + '</span>' : '';
    return '<div class="list-item' + sel + '" onclick="loadStudioItem(' + it.itemId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
      (it.isVideo ? '🎬 ' : '🎵 ') + esc(it.title) + bm + '</div>' +
      '<div style="font-size:11px;opacity:0.78;">' + esc(it.artist) + ' · ' + fmtMs(it.durationMs, false) + '</div></div></div>';
  }).join('');
}

async function openInStudio(itemId) {
  switchTab('studio');
  await loadStudioItem(itemId);
}

async function loadStudioItem(itemId) {
  const it = state.items.find(x => x.itemId === itemId);
  if (!it) return;
  // Pause both players before switching
  const vid = document.getElementById('studio-video');
  const aud = document.getElementById('studio-audio');
  vid.pause(); aud.pause();

  state.studioItemId = itemId;
  renderStudioTrackList();
  document.getElementById('studio-empty').classList.add('hidden');
  document.getElementById('studio-main').classList.remove('hidden');
  document.getElementById('studio-title').textContent = it.title;
  document.getElementById('studio-sub').textContent = it.artist + ' · ' + it.displayAlbum + ' · ' + it.fileName;

  const token = sessionStorage.getItem('mp_lan_pin') || '';
  const streamUrl = '/api/stream?itemId=' + encodeURIComponent(itemId) + (token ? '&token=' + encodeURIComponent(token) : '');
  if (it.isVideo) {
    vid.classList.remove('hidden');
    document.getElementById('studio-audio-box').classList.add('hidden');
    document.getElementById('studio-mirror-wrap').classList.remove('hidden');
    aud.removeAttribute('src');
    vid.src = streamUrl;
  } else {
    vid.classList.add('hidden');
    document.getElementById('studio-audio-box').classList.remove('hidden');
    document.getElementById('studio-mirror-wrap').classList.add('hidden');
    vid.removeAttribute('src');
    aud.src = streamUrl;
  }

  const pData = await api('/api/practice?itemId=' + encodeURIComponent(itemId));
  state.studioPractice = pData.state;
  state.studioBookmarks = pData.bookmarks || [];
  if (pData.presetLabels && pData.presetLabels.length) state.presetLabels = pData.presetLabels;

  state.pointAMs = pData.state.activeAMs != null ? pData.state.activeAMs : null;
  state.pointBMs = pData.state.activeBMs != null ? pData.state.activeBMs : null;
  state.loopEnabled = !!(pData.state.loopEnabled && state.pointAMs != null && state.pointBMs != null);

  onSpeedSliderChange(pData.state.practiceSpeed || 1.0);
  document.getElementById('practice-pitch-input').value = pData.state.pitchSemitones || 0;
  document.getElementById('practice-bpm-input').value = pData.state.bpm || 120;
  document.getElementById('practice-countin-select').value = pData.state.countInMode || 'OFF';
  document.getElementById('studio-mirror-chk').checked = !!pData.state.videoMirror;
  toggleVideoMirror(!!pData.state.videoMirror);

  renderPresetLabelChips();
  renderStudioBookmarks();
  updateTimelineOverlay();
}

function toggleVideoMirror(mirrored) {
  document.getElementById('studio-video').classList.toggle('mirrored', !!mirrored);
}

function onSpeedSliderChange(val) {
  const spd = Math.max(0.25, Math.min(2.0, Math.round(Number(val) * 100) / 100));
  document.getElementById('practice-speed-range').value = spd;
  document.getElementById('speed-val').textContent = spd.toFixed(2) + 'x';
  const m = activeMediaEl();
  if (m) {
    m.preservesPitch = true;
    m.mozPreservesPitch = true;
    m.webkitPreservesPitch = true;
    m.playbackRate = spd;
  }
}

function getDurationMs() {
  const m = activeMediaEl();
  const it = state.items.find(x => x.itemId === state.studioItemId);
  if (m && Number.isFinite(m.duration) && m.duration > 0) return Math.round(m.duration * 1000);
  return (it && it.durationMs) || 0;
}

function setPointA() {
  const m = activeMediaEl();
  if (!m) return;
  state.pointAMs = Math.round(m.currentTime * 1000);
  if (state.pointBMs != null && state.pointBMs <= state.pointAMs + 500) {
    state.pointBMs = Math.min(getDurationMs() || (state.pointAMs + 2000), state.pointAMs + 2000);
  }
  updateTimelineOverlay();
}

function setPointB() {
  const m = activeMediaEl();
  if (!m) return;
  const nowMs = Math.round(m.currentTime * 1000);
  if (state.pointAMs == null) state.pointAMs = Math.max(0, nowMs - 2000);
  state.pointBMs = Math.max(state.pointAMs + 500, nowMs);
  state.loopEnabled = true;
  m.currentTime = state.pointAMs / 1000;
  updateTimelineOverlay();
}

function toggleAbLoop() {
  if (state.pointAMs == null || state.pointBMs == null) {
    showToast('请先用 [ 和 ] 设置 A 点与 B 点');
    return;
  }
  state.loopEnabled = !state.loopEnabled;
  updateTimelineOverlay();
}

function jumpToPointA() {
  const m = activeMediaEl();
  if (m && state.pointAMs != null) m.currentTime = state.pointAMs / 1000;
}

function clearAbPoints() {
  state.pointAMs = null;
  state.pointBMs = null;
  state.loopEnabled = false;
  updateTimelineOverlay();
}

function nudgePoint(which, deltaMs) {
  const dur = getDurationMs() || 36000000;
  if (which === 'A' && state.pointAMs != null) {
    state.pointAMs = Math.max(0, Math.min((state.pointBMs != null ? state.pointBMs - 500 : dur), state.pointAMs + deltaMs));
  } else if (which === 'B' && state.pointBMs != null) {
    state.pointBMs = Math.max((state.pointAMs != null ? state.pointAMs + 500 : 500), Math.min(dur, state.pointBMs + deltaMs));
  }
  updateTimelineOverlay();
}

function seekOnTimeline(ev) {
  const m = activeMediaEl();
  const dur = getDurationMs();
  if (!m || dur <= 0) return;
  const rect = document.getElementById('studio-timeline').getBoundingClientRect();
  const ratio = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
  m.currentTime = (ratio * dur) / 1000;
  updateTimelineOverlay();
}

function updateTimelineOverlay() {
  const m = activeMediaEl();
  const dur = getDurationMs();
  const curMs = m ? Math.round((m.currentTime || 0) * 1000) : 0;
  document.getElementById('studio-cur-time').textContent = fmtMs(curMs, true);
  document.getElementById('studio-dur-time').textContent = fmtMs(dur, true);
  document.getElementById('btn-a-label').textContent = state.pointAMs != null ? fmtMs(state.pointAMs, true) : '--:--';
  document.getElementById('btn-b-label').textContent = state.pointBMs != null ? fmtMs(state.pointBMs, true) : '--:--';

  const loopBtn = document.getElementById('btn-loop-toggle');
  loopBtn.textContent = state.loopEnabled ? '🔁 循环: 开启' : '🔁 循环: 挂起';
  loopBtn.className = state.loopEnabled ? 'btn btn-primary' : 'btn btn-tonal';

  const readout = document.getElementById('studio-ab-readout');
  if (state.pointAMs != null && state.pointBMs != null) {
    readout.textContent = 'A-B: ' + fmtMs(state.pointAMs, true) + ' → ' + fmtMs(state.pointBMs, true) +
      ' (' + ((state.pointBMs - state.pointAMs) / 1000).toFixed(1) + 's)';
  } else if (state.pointAMs != null) {
    readout.textContent = '已设 A 点: ' + fmtMs(state.pointAMs, true) + '（请按 ] 设 B 点）';
  } else {
    readout.textContent = 'A-B 区间: 未设置';
  }

  const pct = dur > 0 ? Math.min(100, (curMs / dur) * 100) : 0;
  document.getElementById('tl-progress').style.width = pct + '%';
  document.getElementById('tl-playhead').style.left = pct + '%';

  const loopBox = document.getElementById('tl-loop');
  if (dur > 0 && state.pointAMs != null && state.pointBMs != null) {
    const leftPct = Math.max(0, Math.min(100, (state.pointAMs / dur) * 100));
    const widthPct = Math.max(0.5, Math.min(100 - leftPct, ((state.pointBMs - state.pointAMs) / dur) * 100));
    loopBox.style.left = leftPct + '%';
    loopBox.style.width = widthPct + '%';
    loopBox.classList.remove('hidden');
  } else {
    loopBox.classList.add('hidden');
  }

  const pinsEl = document.getElementById('tl-pins');
  pinsEl.innerHTML = dur > 0 ? state.studioBookmarks.map(b => {
    const lp = Math.max(0, Math.min(96, (b.aMs / dur) * 100));
    return '<div class="timeline-bm-pin" style="left:' + lp + '%">' + esc(b.label) + '</div>';
  }).join('') : '';
}

function renderPresetLabelChips() {
  document.getElementById('preset-label-chips').innerHTML = state.presetLabels.map(lbl =>
    '<button class="chip" style="padding:4px 10px;font-size:12px;" onclick="document.getElementById(\'bm-label-input\').value=\'' + escJs(lbl) + '\'">' + esc(lbl) + '</button>'
  ).join('');
}

function renderStudioBookmarks() {
  const el = document.getElementById('studio-bookmarks-list');
  if (!state.studioBookmarks.length) {
    el.innerHTML = '<div style="font-size:12px;color:var(--muted);">暂无段落书签。播放时按 [ 设起点、按 ] 设终点，选择标签点击「+ 保存段落书签」即可同步到手机！</div>';
    return;
  }
  el.innerHTML = state.studioBookmarks.map(b => {
    const spd = b.savedSpeed ? (' · ' + Number(b.savedSpeed).toFixed(2) + 'x') : '';
    return '<div class="list-item" style="background:var(--surface);border:1px solid var(--border);margin-bottom:6px;">' +
      '<div onclick="activateBookmark(' + b.bookmarkId + ')" style="flex:1;">' +
      '<span class="badge badge-practice">🔖 ' + esc(b.label) + '</span> ' +
      '<span class="tnum" style="font-size:12px;margin-left:6px;">' + fmtMs(b.aMs, true) + ' – ' + fmtMs(b.bMs, true) + spd + '</span></div>' +
      '<button class="btn btn-danger btn-xs" onclick="deleteBookmarkById(' + b.bookmarkId + ')">删除</button></div>';
  }).join('');
}

function activateBookmark(bookmarkId) {
  const b = state.studioBookmarks.find(x => x.bookmarkId === bookmarkId);
  if (!b) return;
  state.pointAMs = b.aMs;
  state.pointBMs = b.bMs;
  state.loopEnabled = true;
  if (b.savedSpeed) onSpeedSliderChange(b.savedSpeed);
  const m = activeMediaEl();
  if (m) { m.currentTime = b.aMs / 1000; m.play().catch(() => {}); }
  updateTimelineOverlay();
}

async function saveCurrentBookmark() {
  if (state.studioItemId == null) return;
  if (state.pointAMs == null || state.pointBMs == null) {
    alert('请先使用 [ 和 ] 按钮（或键盘快捷键）设置循环 A 点与 B 点！');
    return;
  }
  const label = (document.getElementById('bm-label-input').value || '').trim() || ('段落 ' + (state.studioBookmarks.length + 1));
  const saveSpeed = document.getElementById('bm-save-speed').checked
    ? Number(document.getElementById('practice-speed-range').value) : null;
  try {
    const res = await api('/api/practice/bookmark', {
      method: 'POST',
      json: {
        itemId: state.studioItemId,
        label: label,
        aMs: state.pointAMs,
        bMs: state.pointBMs,
        savedSpeed: saveSpeed
      }
    });
    state.studioBookmarks = res.bookmarks || [];
    document.getElementById('bm-label-input').value = '';
    renderStudioBookmarks();
    updateTimelineOverlay();
    await loadAllItems();
    await refreshStatus();
    showToast('🔖 已保存段落书签: ' + label);
  } catch (e) { alert(e.message); }
}

async function deleteBookmarkById(bookmarkId) {
  try {
    await api('/api/practice/bookmark?bookmarkId=' + encodeURIComponent(bookmarkId), { method: 'DELETE' });
    state.studioBookmarks = state.studioBookmarks.filter(b => b.bookmarkId !== bookmarkId);
    renderStudioBookmarks();
    updateTimelineOverlay();
    await loadAllItems();
    await refreshStatus();
    showToast('已删除书签');
  } catch (e) { alert(e.message); }
}

function tapTempo() {
  const now = performance.now();
  state.tapTimes.push(now);
  if (state.tapTimes.length > 5) state.tapTimes.shift();
  if (state.tapTimes.length >= 2) {
    const diffs = [];
    for (let i = 1; i < state.tapTimes.length; i++) {
      const d = state.tapTimes[i] - state.tapTimes[i - 1];
      if (d >= 180 && d <= 2500) diffs.push(d);
    }
    if (diffs.length) {
      diffs.sort((a, b) => a - b);
      const mid = diffs[Math.floor(diffs.length / 2)];
      const bpm = Math.max(30, Math.min(300, Math.round(60000 / mid)));
      document.getElementById('practice-bpm-input').value = bpm;
    }
  }
}

async function savePracticeStateToPhone() {
  if (state.studioItemId == null) return;
  try {
    const res = await api('/api/practice/state', {
      method: 'POST',
      json: {
        itemId: state.studioItemId,
        practiceSpeed: Number(document.getElementById('practice-speed-range').value),
        pitchSemitones: parseInt(document.getElementById('practice-pitch-input').value || '0', 10),
        bpm: parseInt(document.getElementById('practice-bpm-input').value || '120', 10),
        countInMode: document.getElementById('practice-countin-select').value,
        loopEnabled: state.loopEnabled,
        activeAMs: state.pointAMs,
        activeBMs: state.pointBMs,
        videoMirror: document.getElementById('studio-mirror-chk').checked
      }
    });
    state.studioPractice = res.state;
    await loadAllItems();
    showToast('💾 练习参数已同步到手机数据库');
  } catch (e) { alert(e.message); }
}

function attachMediaLoopListeners(el) {
  el.addEventListener('timeupdate', () => {
    if (state.loopEnabled && state.pointAMs != null && state.pointBMs != null) {
      if (el.currentTime * 1000 >= state.pointBMs) {
        el.currentTime = state.pointAMs / 1000;
      }
    }
    updateTimelineOverlay();
  });
  el.addEventListener('loadedmetadata', () => {
    onSpeedSliderChange(document.getElementById('practice-speed-range').value);
    updateTimelineOverlay();
  });
}

// Drag & drop upload bindings
const dz = document.getElementById('dropzone');
dz.addEventListener('dragover', (e) => { e.preventDefault(); dz.classList.add('dragover'); });
dz.addEventListener('dragleave', () => dz.classList.remove('dragover'));
dz.addEventListener('drop', (e) => {
  e.preventDefault();
  dz.classList.remove('dragover');
  if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
    uploadFilesToCurrentFolder(e.dataTransfer.files);
  }
});

// Keyboard shortcuts in Studio workspace
document.addEventListener('keydown', (e) => {
  const tag = (e.target && e.target.tagName) ? e.target.tagName.toLowerCase() : '';
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
  if (!document.getElementById('ws-studio').classList.contains('active')) return;
  const m = activeMediaEl();
  if (!m) return;
  if (e.code === 'Space') {
    e.preventDefault();
    if (m.paused) m.play().catch(() => {}); else m.pause();
  } else if (e.key === '[') {
    e.preventDefault(); setPointA();
  } else if (e.key === ']') {
    e.preventDefault(); setPointB();
  } else if (e.key === '\\') {
    e.preventDefault(); toggleAbLoop();
  } else if (e.key === 'r' || e.key === 'R') {
    e.preventDefault(); jumpToPointA();
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault();
    m.currentTime = Math.max(0, m.currentTime - (e.shiftKey ? 0.1 : 2.0));
  } else if (e.key === 'ArrowRight') {
    e.preventDefault();
    m.currentTime = Math.min(m.duration || 999999, m.currentTime + (e.shiftKey ? 0.1 : 2.0));
  }
});

async function bootstrapAll() {
  await refreshStatus();
  await loadAllItems();
  await loadRootsAndCurrentFolder();
  await loadPlaylists();
}

initTheme();
attachMediaLoopListeners(document.getElementById('studio-video'));
attachMediaLoopListeners(document.getElementById('studio-audio'));
bootstrapAll().catch(err => console.warn(err));
    """.trimIndent()
}
