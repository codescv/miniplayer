package com.miniplayer.app.lan

import com.miniplayer.app.core.i18n.AppLocaleHolder
import com.miniplayer.app.core.i18n.AppStrings
import com.miniplayer.app.core.i18n.LanWebStrings
import com.miniplayer.app.core.i18n.ResolvedLanguage

/**
 * Self-contained Material Design 3 Single-Page Application served by [LanStudioServer] at `GET /`.
 * Zero external CDN dependencies — runs 100% offline over Wi-Fi / Hotspot / USB Tethering LAN,
 * and mirrors the active app UI language (`zh`, `en`, `ja`).
 */
internal object LanStudioWebApp {

    fun renderHtml(
        language: ResolvedLanguage = AppLocaleHolder.activeLanguage,
        serverTitle: String? = null,
    ): String {
        val s = LanWebStrings.forLanguage(language)
        val appStrings = AppStrings.forLanguage(language)
        val effectiveTitle = serverTitle ?: s.pageTitle
        val safeTitle = escapeHtml(effectiveTitle)
        return buildString(56_000) {
            append("<!DOCTYPE html>\n<html lang=\"").append(language.htmlLang).append("\">\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>").append(safeTitle).append("</title>\n")
            append("<style>\n")
            append(CSS)
            append("\n</style>\n</head>\n<body>\n")
            append(renderBodyHtml(s, effectiveTitle))
            append("\n<script>\n")
            append("const I18N = ").append(encodeJsonObject(s.toJsMap())).append(";\n")
            append("const DEFAULT_PRESET_LABELS = ").append(encodeJsonArray(appStrings.presetSectionLabels)).append(";\n")
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

    private fun escapeJsJsonString(text: String): String = buildString(text.length + 16) {
        append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun encodeJsonObject(map: Map<String, String>): String = buildString(map.size * 48) {
        append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) append(',')
            first = false
            append(escapeJsJsonString(k)).append(':').append(escapeJsJsonString(v))
        }
        append('}')
    }

    private fun encodeJsonArray(items: List<String>): String = buildString(items.size * 16) {
        append('[')
        for ((idx, item) in items.withIndex()) {
            if (idx > 0) append(',')
            append(escapeJsJsonString(item))
        }
        append(']')
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
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans", "Noto Sans SC", "Noto Sans JP", Roboto, sans-serif;
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

    private fun renderBodyHtml(s: LanWebStrings, title: String): String {
        val h = ::escapeHtml
        return """
<div class="app-shell">
  <header class="topbar">
    <div class="brand">
      <div class="brand-logo">MP</div>
      <div>
        <h1>${h(title)}</h1>
        <div class="brand-sub">
          <span><span class="dot-live"></span> <span id="stat-summary">${h(s.connectingLibrary)}</span></span>
          <span id="stat-scan" class="badge badge-muted hidden">${h(s.scanning)}</span>
        </div>
      </div>
    </div>

    <nav class="nav-tabs" role="tablist">
      <button class="nav-tab active" data-tab="folders" onclick="switchTab('folders')">${h(s.tabFolders)}</button>
      <button class="nav-tab" data-tab="metadata" onclick="switchTab('metadata')">${h(s.tabMetadata)}</button>
      <button class="nav-tab" data-tab="playlists" onclick="switchTab('playlists')">${h(s.tabPlaylists)}</button>
      <button class="nav-tab" data-tab="studio" onclick="switchTab('studio')">${h(s.tabStudio)}</button>
    </nav>

    <div class="top-actions">
      <button class="btn btn-outline" onclick="triggerRescanAll()" title="${h(s.rescanLibraryTooltip)}">${h(s.rescanLibraryBtn)}</button>
      <button class="btn btn-outline" onclick="toggleTheme()" id="theme-btn" title="${h(s.themeTooltip)}">${h(s.themeBtn)}</button>
    </div>
  </header>

  <!-- WORKSPACE 1: WIRELESS UPLOAD & FOLDERS -->
  <section id="ws-folders" class="workspace active">
    <div class="grid-sidebar">
      <aside class="card">
        <div class="card-title">
          <span>${h(s.mountedFoldersTitle)}</span>
          <span class="badge badge-muted tnum" id="roots-count">0</span>
        </div>
        <div id="roots-list"></div>
        <p style="font-size:12px;color:var(--muted);margin:12px 0 0;">
          ${h(s.safTip)}
        </p>
      </aside>

      <main class="card">
        <div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;">
          <div class="crumbs" id="folder-crumbs" style="margin-bottom:0;"></div>
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
            <label style="display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:600;cursor:pointer;" class="badge badge-practice">
              <input type="checkbox" id="folder-practice-toggle" onchange="toggleCurrentFolderPractice(this.checked)">
              ${h(s.practiceFolderToggleLabel)}
            </label>
            <button class="btn btn-tonal" onclick="promptCreateSubfolder()">${h(s.newSubfolderBtn)}</button>
            <label class="btn btn-primary" for="upload-input">${h(s.uploadMediaBtn)}</label>
            <input id="upload-input" type="file" multiple hidden
                   accept=".mp3,.flac,.wav,.m4a,.aac,.ogg,.opus,.mp4,.mkv,.webm,.mov,.lrc,.jpg,.jpeg,.png,.m3u,.m3u8"
                   onchange="uploadFilesToCurrentFolder(this.files); this.value='';">
          </div>
        </div>

        <div id="dropzone" class="dropzone">
          <div style="font-size:15px;font-weight:600;margin-bottom:4px;">
            ${h(s.dropzoneTitle)}
          </div>
          <div style="font-size:12px;color:var(--muted);">
            ${h(s.dropzoneSubtitle)}
          </div>
          <div id="upload-progress-box" class="hidden" style="margin-top:10px;text-align:left;">
            <div style="display:flex;justify-content:space-between;font-size:12px;" class="tnum">
              <span id="upload-status-text">${h(s.uploadPreparing)}</span>
              <span id="upload-pct-text">0%</span>
            </div>
            <div class="progress-bar"><div id="upload-bar-fill" class="progress-fill"></div></div>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>${h(s.colName)}</th>
                <th>${h(s.colTypeSpec)}</th>
                <th>${h(s.colArtistAlbum)}</th>
                <th>${h(s.colPracticeTags)}</th>
                <th style="text-align:right;">${h(s.colActions)}</th>
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
        <button class="chip active" data-filter="all" onclick="setMetaFilter('all')">${h(s.filterAllLibrary)}</button>
        <button class="chip" data-filter="folder" onclick="setMetaFilter('folder')">${h(s.filterByFolder)}</button>
        <button class="chip" data-filter="album" onclick="setMetaFilter('album')">${h(s.filterByAlbum)}</button>
        <button class="chip" data-filter="unknown_album" onclick="setMetaFilter('unknown_album')">${h(s.filterUnknownAlbum)}</button>
        <button class="chip" data-filter="artist" onclick="setMetaFilter('artist')">${h(s.filterByArtist)}</button>
        <button class="chip" data-filter="video" onclick="setMetaFilter('video')">${h(s.filterVideo)}</button>

        <select id="meta-subfilter" class="hidden" onchange="onMetaSubfilterChange()"></select>
        <input id="meta-search" class="input" style="min-width:240px;margin-left:auto;"
               placeholder="${h(s.metaSearchPlaceholder)}" oninput="renderMetadataTable()">
        <button class="btn btn-tertiary" onclick="runBatchAutoCompletePreview()">${h(s.batchAutoCompleteListBtn)}</button>
      </div>

      <div id="meta-batch-bar" class="batch-bar hidden">
        <div>${h(s.batchSelectedPrefix)}<strong id="meta-sel-count" class="tnum">0</strong>${h(s.batchSelectedSuffix)}</div>
        <div style="display:flex;flex-wrap:wrap;gap:8px;">
          <button class="btn btn-primary btn-xs" onclick="openBatchEditModal()">${h(s.batchEditBtn)}</button>
          <button class="btn btn-tertiary btn-xs" onclick="runBatchAutoCompleteSelected()">${h(s.batchAutoCompleteSelectedBtn)}</button>
          <button class="btn btn-outline btn-xs" onclick="openAddSelectedToPlaylistModal()">${h(s.batchAddToPlaylistBtn)}</button>
          <button class="btn btn-danger btn-xs" onclick="resetSelectedMetadata()">${h(s.batchResetMetadataBtn)}</button>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th style="width:36px;"><input type="checkbox" id="meta-select-all" onchange="toggleSelectAllMeta(this.checked)"></th>
              <th style="width:60px;">${h(s.colTrackNum)}</th>
              <th>${h(s.colTitleInline)}</th>
              <th>${h(s.colArtist)}</th>
              <th>${h(s.colAlbum)}</th>
              <th style="width:68px;">${h(s.colYear)}</th>
              <th style="width:96px;">${h(s.colDurationFormat)}</th>
              <th style="text-align:right;width:230px;">${h(s.colActions)}</th>
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
          <span>${h(s.playlistsTitle)}</span>
          <div style="display:flex;gap:6px;">
            <button class="btn btn-tonal btn-xs" onclick="openCreatePlaylistModal()">${h(s.newPlaylistBtn)}</button>
            <label class="btn btn-outline btn-xs" for="m3u-import-input">${h(s.importM3uBtn)}</label>
            <input id="m3u-import-input" type="file" accept=".m3u,.m3u8" hidden onchange="importM3uFile(this.files[0]); this.value='';">
          </div>
        </div>
        <div class="chip-row" style="margin-bottom:8px;">
          <button class="chip active" id="pl-filter-all" onclick="setPlaylistFilter('all')">${h(s.filterAll)}</button>
          <button class="chip" id="pl-filter-practice" onclick="setPlaylistFilter('practice')">${h(s.filterPracticeLists)}</button>
        </div>
        <div id="playlists-sidebar"></div>
      </aside>

      <main class="card" id="playlist-detail-pane">
        <div style="padding:36px;text-align:center;color:var(--muted);">${h(s.playlistPaneEmptyInitial)}</div>
      </main>
    </div>
  </section>

  <!-- WORKSPACE 4: PC PREVIEW & A-B BOOKMARK STUDIO -->
  <section id="ws-studio" class="workspace">
    <div class="grid-sidebar">
      <aside class="card">
        <div class="card-title">
          <span>${h(s.selectTrackVideoTitle)}</span>
          <span class="badge badge-muted tnum" id="studio-track-count">0</span>
        </div>
        <input id="studio-search" class="input" style="width:100%;margin-bottom:10px;"
               placeholder="${h(s.studioSearchPlaceholder)}" oninput="renderStudioTrackList()">
        <div id="studio-track-list" style="max-height:560px;overflow-y:auto;"></div>
      </aside>

      <main class="card">
        <div id="studio-empty" style="padding:40px;text-align:center;color:var(--muted);">
          ${h(s.studioEmptyHint)}
        </div>

        <div id="studio-main" class="hidden">
          <div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px;">
            <div>
              <h2 id="studio-title" style="margin:0;font-size:18px;">—</h2>
              <div id="studio-sub" style="font-size:13px;color:var(--muted);">—</div>
            </div>
            <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
              <label id="studio-mirror-wrap" class="btn btn-outline btn-xs hidden" style="cursor:pointer;">
                <input type="checkbox" id="studio-mirror-chk" onchange="toggleVideoMirror(this.checked)"> ${h(s.videoMirrorLabel)}
              </label>
              <span style="font-size:12px;color:var(--muted);">
                ${h(s.shortcutsLabel)} <span class="kbd">Space</span> ${h(s.shortcutPlayPause)} · <span class="kbd">[</span> ${h(s.shortcutSetA)} · <span class="kbd">]</span> ${h(s.shortcutSetB)} · <span class="kbd">\</span> ${h(s.shortcutLoopToggle)} · <span class="kbd">R</span> ${h(s.shortcutBackToA)}
              </span>
            </div>
          </div>

          <div class="player-stage">
            <video id="studio-video" class="hidden" controls playsinline></video>
            <div id="studio-audio-box" class="audio-hero">
              <div style="font-size:13px;font-weight:600;letter-spacing:0.08em;opacity:0.75;margin-bottom:10px;">${h(s.audioPreviewBanner)}</div>
              <audio id="studio-audio" controls style="width:min(560px,92%);"></audio>
            </div>
          </div>

          <!-- Interactive A-B Timeline -->
          <div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px;" class="tnum">
            <span>${h(s.curTimeLabel)} <strong id="studio-cur-time">00:00.0</strong></span>
            <span id="studio-ab-readout">${h(s.abRangeUnset)}</span>
            <span>${h(s.durTimeLabel)} <span id="studio-dur-time">00:00.0</span></span>
          </div>
          <div id="studio-timeline" class="timeline-box" onclick="seekOnTimeline(event)">
            <div id="tl-progress" class="timeline-progress"></div>
            <div id="tl-loop" class="timeline-loop hidden"></div>
            <div id="tl-pins"></div>
            <div id="tl-playhead" class="timeline-playhead"></div>
          </div>

          <!-- A-B Loop Controls & Nudge -->
          <div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:14px;">
            <button class="btn btn-tertiary" onclick="setPointA()">[ A (<span id="btn-a-label" class="tnum">--:--</span>)</button>
            <button class="btn btn-tertiary" onclick="setPointB()">] B (<span id="btn-b-label" class="tnum">--:--</span>)</button>
            <button class="btn btn-tonal" id="btn-loop-toggle" onclick="toggleAbLoop()">${h(s.loopOff)}</button>
            <button class="btn btn-outline" onclick="jumpToPointA()">${h(s.jumpToABtn)}</button>
            <button class="btn btn-outline" onclick="clearAbPoints()">${h(s.clearAbBtn)}</button>
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
              <div class="card-title"><span>${h(s.bookmarksCardTitle)}</span></div>
              <div class="chip-row" id="preset-label-chips"></div>
              <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px;">
                <input id="bm-label-input" class="input" placeholder="${h(s.bookmarkInputPlaceholder)}" style="flex:1;min-width:130px;">
                <label style="font-size:12px;display:flex;align-items:center;gap:4px;">
                  <input type="checkbox" id="bm-save-speed" checked> ${h(s.bookmarkSaveSpeedLabel)}
                </label>
                <button class="btn btn-primary" onclick="saveCurrentBookmark()">${h(s.saveBookmarkBtn)}</button>
              </div>
              <div id="studio-bookmarks-list"></div>
            </div>

            <!-- Practice Speed / Pitch / BPM State Card -->
            <div class="card" style="background:var(--surface-low);box-shadow:none;">
              <div class="card-title"><span>${h(s.practiceStateCardTitle)}</span></div>
              <div class="form-grid">
                <div class="form-field full">
                  <label>${h(s.speedSliderLabelPrefix)}<strong id="speed-val" class="tnum">1.00x</strong>${h(s.speedSliderLabelSuffix)}</label>
                  <input type="range" id="practice-speed-range" min="0.25" max="2.00" step="0.05" value="1.00"
                         oninput="onSpeedSliderChange(this.value)">
                  <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:4px;">
                    <button class="btn btn-outline btn-xs" onclick="stepSpeedSlider(-0.05)">-5%</button>
                    <button class="btn btn-outline btn-xs" onclick="onSpeedSliderChange(1.0)">${h(s.speedResetBtn)}</button>
                    <button class="btn btn-outline btn-xs" onclick="stepSpeedSlider(0.05)">+5%</button>
                  </div>
                </div>
                <div class="form-field">
                  <label>${h(s.pitchSemitonesLabel)}</label>
                  <input type="number" id="practice-pitch-input" class="input tnum" min="-6" max="6" value="0">
                </div>
                <div class="form-field">
                  <label>${h(s.bpmLabel)}</label>
                  <div style="display:flex;gap:4px;">
                    <input type="number" id="practice-bpm-input" class="input tnum" min="30" max="300" step="0.01" value="120" style="width:82px;">
                    <button class="btn btn-outline btn-xs" onclick="tapTempo()">${h(s.tapTempoBtn)}</button>
                  </div>
                </div>
                <div class="form-field full">
                  <label>${h(s.countInLabel)}</label>
                  <select id="practice-countin-select">
                    <option value="OFF">${h(s.countInOffOption)}</option>
                    <option value="BEAT_4">${h(s.countInBeat4Option)}</option>
                  </select>
                </div>
              </div>
              <button class="btn btn-primary" style="width:100%;" onclick="savePracticeStateToPhone()">${h(s.savePracticeStateBtn)}</button>
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
    }

    private val APP_JS = """
const UNKNOWN_ALBUM_SENTINEL = '\u672a\u77e5\u4e13\u8f91';
const UNKNOWN_ARTIST_SENTINEL = '\u672a\u77e5\u6b4c\u624b';
const VARIOUS_ARTISTS_SENTINEL = 'Various Artists';

function t(key) {
  let out = I18N[key] != null ? I18N[key] : key;
  for (let i = 1; i < arguments.length; i++) {
    out = out.split('{' + (i - 1) + '}').join(String(arguments[i]));
  }
  return out;
}

function displayAlbum(raw) {
  const s = String(raw == null ? '' : raw).trim();
  if (!s || s === UNKNOWN_ALBUM_SENTINEL) return t('unknownAlbum');
  return s;
}

function displayArtist(raw) {
  const s = String(raw == null ? '' : raw).trim();
  if (!s || s === UNKNOWN_ARTIST_SENTINEL) return t('unknownArtist');
  if (s === VARIOUS_ARTISTS_SENTINEL) return t('variousArtists');
  return s;
}

function displayMountState(raw) {
  if (raw === 'MOUNTED') return t('mountMounted');
  if (raw === 'OFFLINE_UNMOUNTED') return t('mountOffline');
  if (raw === 'PERMISSION_REVOKED') return t('mountPermissionRevoked');
  return String(raw || '');
}

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
  presetLabels: DEFAULT_PRESET_LABELS.slice()
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
    throw new Error(t('pinRequiredError'));
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.ok === false) {
    throw new Error(data.error || t('requestFailedTpl', res.status));
  }
  return data;
}

function promptPinAuth() {
  const box = document.getElementById('modal-box');
  box.innerHTML = '<h3 style="margin-top:0;">' + esc(t('pinModalTitle')) + '</h3>' +
    '<p style="font-size:13px;color:var(--muted);">' + esc(t('pinModalHint')) + '</p>' +
    '<input id="pin-modal-input" class="input tnum" style="width:100%;font-size:18px;margin-bottom:14px;" placeholder="' + esc(t('pinModalPlaceholder')) + '">' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-primary" onclick="submitPinAuth()">' + esc(t('pinSubmitBtn')) + '</button></div>';
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
    showToast(t('pinUnlockedToast'));
  } catch (e) {
    alert(e.message || t('pinWrongError'));
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
    t('statSummaryTpl', st.itemCount, st.videoCount, st.playlistCount, st.bookmarkedTrackCount);
  const scanBadge = document.getElementById('stat-scan');
  if (st.scan && st.scan.isScanning) {
    scanBadge.textContent = t('scanningCountTpl', st.scan.scannedCount);
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
    document.getElementById('folder-crumbs').innerHTML = '<span style="color:var(--muted);">' + esc(t('noFolderAddedCrumbs')) + '</span>';
    document.getElementById('folder-entries-tbody').innerHTML =
      '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">' + esc(t('noFolderAddedEmpty')) + '</td></tr>';
  }
}

function renderRootsSidebar() {
  const container = document.getElementById('roots-list');
  if (!state.roots.length) {
    container.innerHTML = '<div style="color:var(--muted);font-size:13px;">' + esc(t('noMountedFolders')) + '</div>';
    return;
  }
  container.innerHTML = state.roots.map(r => {
    const sel = r.rootId === state.currentRootId ? ' selected' : '';
    const ro = r.isWritable ? '' : ' <span class="badge badge-warn">' + esc(t('badgeReadOnly')) + '</span>';
    const pf = r.isPracticeFolder ? ' <span class="badge badge-practice">' + esc(t('badgePracticeShort')) + '</span>' : '';
    return '<div class="list-item' + sel + '" onclick="selectRoot(' + r.rootId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
      esc(r.displayName) + pf + ro + '</div>' +
      '<div style="font-size:11px;opacity:0.78;" class="tnum">' + esc(t('itemsCountTpl', r.itemCount)) + ' · ' + esc(displayMountState(r.mountState)) + '</div></div></div>';
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
  const rootName = (data.root && data.root.displayName) || t('rootFolderFallback');
  let crumbsHtml = '<button class="crumb-link" onclick="browseFolder(' + rootId + ', \'\')">' + esc(rootName) + '</button>';
  const parts = state.currentRelPath ? state.currentRelPath.split('/').filter(Boolean) : [];
  let acc = '';
  for (const seg of parts) {
    acc = acc ? (acc + '/' + seg) : seg;
    const safeAcc = escJs(acc);
    crumbsHtml += '<span>/</span><button class="crumb-link" onclick="browseFolder(' + rootId + ', \'' + safeAcc + '\')">' + esc(seg) + '</button>';
  }
  document.getElementById('folder-crumbs').innerHTML = crumbsHtml;

  const subRows = (data.subfolders || []).map(sf => {
    const safeRel = escJs(sf.relPath || '');
    const pfBadge = sf.isPracticeFolder
      ? '<span class="badge badge-practice">' + esc(t('badgePracticeDir')) + '</span>'
      : '<span class="badge badge-muted">' + esc(t('badgeNormalDir')) + '</span>';
    return '<tr>' +
      '<td><a href="javascript:void(0)" onclick="browseFolder(' + rootId + ', \'' + safeRel + '\')" style="color:var(--primary);font-weight:600;text-decoration:none;">' + esc(sf.name) + ' /</a></td>' +
      '<td class="tnum">' + esc(t('subfolderCountTpl', sf.itemCount || 0)) + '</td>' +
      '<td>—</td>' +
      '<td>' + pfBadge + '</td>' +
      '<td style="text-align:right;"><button class="btn btn-outline btn-xs" onclick="toggleSubfolderPractice(' + rootId + ', \'' + safeRel + '\', ' + (!sf.isPracticeFolder) + ')">' +
      esc(sf.isPracticeFolder ? t('unsetPracticeDirBtn') : t('setPracticeDirBtn')) + '</button></td></tr>';
  });

  const itemRows = (data.mediaItems || []).map(it => {
    const kind = it.isVideo ? (t('video') + (it.resolutionBadge ? ' ' + it.resolutionBadge : '')) : t('audio');
    const badges = [];
    if (it.isInPracticeFolder) badges.push('<span class="badge badge-practice">' + esc(t('badgePracticeDir')) + '</span>');
    if (it.bookmarkCount > 0) badges.push('<span class="badge badge-ok">' + esc(t('bookmarksCountBadgeTpl', it.bookmarkCount)) + '</span>');
    return '<tr>' +
      '<td><div style="font-weight:600;">' + esc(it.title) + '</div><div style="font-size:11px;color:var(--muted);">' + esc(it.fileName) + '</div></td>' +
      '<td class="tnum">' + esc(kind) + ' · ' + fmtMs(it.durationMs, false) + ' · ' + fmtBytes(it.sizeBytes) + '</td>' +
      '<td>' + esc(displayArtist(it.artist)) + ' · <span style="color:var(--muted);">' + esc(displayAlbum(it.displayAlbum)) + '</span></td>' +
      '<td>' + (badges.join(' ') || '—') + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">' + esc(t('previewMarkBtn')) + '</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="openEditItemModal(' + it.itemId + ')">' + esc(t('tagsBtn')) + '</button></td></tr>';
  });

  const allRows = subRows.concat(itemRows);
  document.getElementById('folder-entries-tbody').innerHTML = allRows.length
    ? allRows.join('')
    : '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">' + esc(t('folderEmptyHint')) + '</td></tr>';
}

async function promptCreateSubfolder() {
  if (state.currentRootId == null) { alert(t('alertAddFolderFirst')); return; }
  const name = window.prompt(t('promptNewSubfolderName'));
  if (!name || !name.trim()) return;
  try {
    await api('/api/folders/mkdir', {
      method: 'POST',
      json: { rootId: state.currentRootId, relPath: state.currentRelPath, name: name.trim() }
    });
    showToast(t('toastFolderCreatedTpl', name.trim()));
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
    showToast(checked ? t('toastMarkedPracticeFolder') : t('toastUnmarkedPracticeFolder'));
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
    showToast(isPractice ? t('toastSubfolderMarkedPractice') : t('toastSubfolderUnmarkedPractice'));
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
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else if (xhr.status === 401) {
        promptPinAuth();
        reject(new Error(t('pinRequiredError')));
      } else {
        let msg = t('uploadFailedStatusTpl', xhr.status);
        try { msg = JSON.parse(xhr.responseText).error || msg; } catch (_) {}
        reject(new Error(msg));
      }
    };
    xhr.onerror = () => reject(new Error(t('uploadNetworkError')));
    xhr.send(file);
  });
}

async function uploadFilesToCurrentFolder(fileList) {
  if (!fileList || !fileList.length) return;
  if (state.currentRootId == null) { alert(t('alertMountWritableFirst')); return; }
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
      txt.textContent = t('uploadingProgressTpl', i + 1, files.length, f.name);
      await sendSingleUpload(state.currentRootId, state.currentRelPath, f, (loaded) => {
        const pct = totalBytes > 0 ? Math.min(100, Math.round(((doneBytes + loaded) / totalBytes) * 100)) : 100;
        pctEl.textContent = pct + '%';
        bar.style.width = pct + '%';
      });
      doneBytes += (f.size || 0);
    }
    showToast(t('toastUploadedCountTpl', files.length));
    await bootstrapAll();
  } catch (e) {
    alert(e.message || t('uploadFailedGeneric'));
  } finally {
    box.classList.add('hidden');
    bar.style.width = '0%';
  }
}

async function triggerRescanAll() {
  try {
    await api('/api/folders/rescan', { method: 'POST', json: {} });
    await bootstrapAll();
    showToast(t('toastRescanComplete'));
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
    sub.innerHTML = '<option value="">' + esc(t('subfilterAllFolders')) + '</option>' + folders.map(k => {
      const idx = k.indexOf('::');
      const rId = Number(k.slice(0, idx));
      const rel = k.slice(idx + 2);
      const rName = (state.roots.find(r => r.rootId === rId) || {}).displayName || t('rootFallbackTpl', rId);
      return '<option value="' + esc(k) + '">' + esc(rName + (rel ? '/' + rel : '')) + '</option>';
    }).join('');
    sub.classList.remove('hidden');
  } else if (filter === 'album') {
    sub.innerHTML = '<option value="">' + esc(t('subfilterAllAlbums')) + '</option>' + state.albums.map(a =>
      '<option value="' + esc(a.albumKey) + '">' + esc(displayAlbum(a.title)) + ' (' + a.trackCount + ')</option>'
    ).join('');
    sub.classList.remove('hidden');
  } else if (filter === 'artist') {
    sub.innerHTML = '<option value="">' + esc(t('subfilterAllArtists')) + '</option>' + state.artists.map(a =>
      '<option value="' + esc(a.artistNorm) + '">' + esc(displayArtist(a.displayName)) + ' (' + a.trackCount + ')</option>'
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
    if (state.metaFilter === 'video') {
      if (!it.isVideo || it.isInPracticeFolder) return false;
    }
    if (state.metaFilter === 'unknown_album') {
      if (it.isVideo || it.isInPracticeFolder) return false;
      if (it.album && it.album.trim() !== '' && it.album !== UNKNOWN_ALBUM_SENTINEL) return false;
    }
    if (state.metaFilter === 'folder' && state.metaSubKey) {
      if ((it.rootId + '::' + (it.parentRelPath || '')) !== state.metaSubKey) return false;
    }
    if (state.metaFilter === 'album') {
      if (it.isVideo || it.isInPracticeFolder) return false;
      if (state.metaSubKey) {
        const matchedAlbum = state.albums.find(a => a.albumKey === state.metaSubKey);
        if (matchedAlbum) {
          if (matchedAlbum.isUnknownAlbum) {
            if (it.album && it.album.trim() !== '') return false;
          } else if (it.displayAlbum !== matchedAlbum.title) return false;
        }
      }
    }
    if (state.metaFilter === 'artist') {
      if (it.isVideo || it.isInPracticeFolder) return false;
      if (state.metaSubKey) {
        const artistObj = state.artists.find(a => a.artistNorm === state.metaSubKey);
        const needle = (artistObj ? artistObj.displayName : state.metaSubKey).toLowerCase();
        if (!(it.artist || '').toLowerCase().includes(needle) && !(it.albumArtist || '').toLowerCase().includes(needle)) return false;
      }
    }
    if (q) {
      const hay = ((it.title || '') + ' ' + (it.artist || '') + ' ' + displayArtist(it.artist) + ' ' + (it.displayAlbum || '') + ' ' + displayAlbum(it.displayAlbum) + ' ' + (it.fileName || '')).toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

function renderMetadataTable() {
  const rows = getFilteredMetaItems();
  const tbody = document.getElementById('meta-tbody');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:30px;color:var(--muted);">' + esc(t('metaEmptyHint')) + '</td></tr>';
    updateBatchBar();
    return;
  }
  tbody.innerHTML = rows.map(it => {
    const checked = state.selectedItemIds.has(it.itemId) ? ' checked' : '';
    const unknownAlb = !it.album || !it.album.trim() || it.album === UNKNOWN_ALBUM_SENTINEL;
    const albumHtml = unknownAlb
      ? '<span class="badge badge-warn editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'album\',\'\')">' + esc(t('unknownAlbum')) + '</span>'
      : '<span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'album\',\'' + escJs(it.album) + '\')">' + esc(it.album) + '</span>';
    const editableArtist = (!it.artist || it.artist === UNKNOWN_ARTIST_SENTINEL) ? '' : it.artist;
    const overrideDot = it.hasMetadataOverride ? ' <span class="badge badge-ok" title="' + esc(t('badgeEditedTooltip')) + '">' + esc(t('badgeEdited')) + '</span>' : '';
    const backingDot = it.isBackingTrack ? ' <span class="badge badge-practice">' + esc(t('badgeBacking')) + '</span>' : '';
    const practiceDirDot = it.isInPracticeFolder ? ' <span class="badge badge-practice">' + esc(t('badgePracticeDir')) + '</span>' : '';
    return '<tr>' +
      '<td><input type="checkbox" onchange="toggleSelectItem(' + it.itemId + ', this.checked)"' + checked + '></td>' +
      '<td class="tnum"><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'trackNumber\',\'' + (it.trackNumber || '') + '\')">' + (it.trackNumber || '—') + '</span></td>' +
      '<td><span class="editable-cell" style="font-weight:600;" ondblclick="inlineEditCell(this,' + it.itemId + ',\'title\',\'' + escJs(it.title) + '\')">' + esc(it.title) + '</span>' + overrideDot + backingDot + practiceDirDot +
      '<div style="font-size:11px;color:var(--muted);">' + esc((it.parentRelPath ? it.parentRelPath + '/' : '') + it.fileName) + '</div></td>' +
      '<td><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'artist\',\'' + escJs(editableArtist) + '\')">' + esc(displayArtist(it.artist)) + '</span></td>' +
      '<td>' + albumHtml + '</td>' +
      '<td class="tnum"><span class="editable-cell" ondblclick="inlineEditCell(this,' + it.itemId + ',\'year\',\'' + (it.year || '') + '\')">' + (it.year || '—') + '</span></td>' +
      '<td class="tnum">' + esc(it.isVideo ? t('video') : t('audio')) + ' ' + fmtMs(it.durationMs, false) + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-tertiary btn-xs" onclick="openEditItemModal(' + it.itemId + ', true)">' + esc(t('autoMatchOnlineBtn')) + '</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="openEditItemModal(' + it.itemId + ', false)">' + esc(t('edit')) + '</button> ' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">' + esc(t('previewBtn')) + '</button></td></tr>';
  }).join('');
  updateBatchBar();
}

function escJs(s) {
  const jsEsc = String(s == null ? '' : s)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/\r/g, '\\r')
    .replace(/\n/g, ' ')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
  return esc(jsEsc);
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
      showToast(t('toastTagUpdated'));
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

function applyBackingSuffixJs(baseTitle, isBacking) {
  const clean = String(baseTitle || '').trim();
  if (!isBacking || !clean) return clean;
  if (/\((\u4f34\u594f|backing(\s*track)?|instrumental|off\s*vocal|karaoke)\)/i.test(clean) || /\uff08\u4f34\u594f\uff09/.test(clean)) {
    return clean;
  }
  return clean + t('backingTitleSuffix');
}

function openEditItemModal(itemId, autoSearch) {
  const it = state.items.find(x => x.itemId === itemId);
  if (!it) return;
  state._editModalItemId = itemId;
  state._editCandidates = [];
  state._stagedEditCandidate = null;
  state._stagedEditCandidateIdx = -1;

  const backingToggleHtml = it.isBackingTrack
    ? '<label style="display:flex;align-items:center;gap:6px;font-size:12px;margin-bottom:8px;">' +
      '<input type="checkbox" id="ed-preserve-backing" checked onchange="onEditPreserveBackingChanged(' + it.itemId + ', this.checked)"> ' +
      esc(t('preserveBackingCheckboxTpl', t('backingTitleSuffix').trim())) + '</label>'
    : '';

  const artistVal = (!it.artist || it.artist === UNKNOWN_ARTIST_SENTINEL) ? '' : it.artist;
  const albumVal = (!it.album || it.album === UNKNOWN_ALBUM_SENTINEL) ? '' : it.album;

  openModal(
    '<h3 style="margin-top:0;">' + esc(t('editModalTitle')) + '</h3>' +
    '<div style="font-size:12px;color:var(--muted);margin-bottom:10px;">' + esc(t('editModalFileTpl', it.fileName)) + '</div>' +
    '<div style="margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;">' +
    '<button type="button" id="ed-auto-match-btn" class="btn btn-tonal btn-sm" onclick="searchEditModalCandidates(' + it.itemId + ')">' + esc(t('editModalAutoMatchBtn')) + '</button>' +
    '<span id="ed-staged-hint" style="font-size:12px;color:var(--primary);font-weight:600;"></span>' +
    '</div>' +
    backingToggleHtml +
    '<div id="ed-candidates-box" style="margin-bottom:12px;"></div>' +
    '<div class="form-grid">' +
    '<div class="form-field full"><label>' + esc(t('fieldTitle')) + '</label><input id="ed-title" class="input" value="' + esc(it.title) + '"></div>' +
    '<div class="form-field"><label>' + esc(t('fieldArtist')) + '</label><input id="ed-artist" class="input" value="' + esc(artistVal) + '"></div>' +
    '<div class="form-field"><label>' + esc(t('fieldAlbum')) + '</label><input id="ed-album" class="input" value="' + esc(albumVal) + '"></div>' +
    '<div class="form-field"><label>' + esc(t('fieldAlbumArtist')) + '</label><input id="ed-album-artist" class="input" value="' + esc(it.albumArtist || '') + '"></div>' +
    '<div class="form-field"><label>' + esc(t('fieldTrackYear')) + '</label><div style="display:flex;gap:6px;">' +
    '<input id="ed-track" type="number" class="input tnum" placeholder="' + esc(t('placeholderTrackNum')) + '" value="' + (it.trackNumber || '') + '" style="width:50%;">' +
    '<input id="ed-year" type="number" class="input tnum" placeholder="' + esc(t('placeholderYear')) + '" value="' + (it.year || '') + '" style="width:50%;"></div></div>' +
    '</div>' +
    '<label style="display:flex;align-items:center;gap:6px;font-size:12px;margin-bottom:16px;">' +
    '<input type="checkbox" id="ed-writeback"' + (it.isRootWritable ? '' : ' disabled') + '> ' + esc(t('writebackCheckbox')) + '</label>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">' + esc(t('cancel')) + '</button>' +
    '<button class="btn btn-primary" onclick="submitSingleMetadata(' + it.itemId + ')">' + esc(t('saveChanges')) + '</button></div>'
  );
  if (autoSearch) {
    searchEditModalCandidates(itemId);
  }
}

function onEditPreserveBackingChanged(itemId, checked) {
  const it = state.items.find(x => x.itemId === itemId);
  const cand = state._stagedEditCandidate;
  const titleEl = document.getElementById('ed-title');
  if (!it || !cand || !titleEl || !it.isBackingTrack) return;
  titleEl.value = checked ? applyBackingSuffixJs(cand.title, true) : String(cand.title || '').trim();
}

function selectEditModalCandidate(itemId, idx) {
  const cands = state._editCandidates || [];
  const cand = cands[idx];
  if (!cand) return;
  const it = state.items.find(x => x.itemId === itemId);
  const preserveEl = document.getElementById('ed-preserve-backing');
  const preserveBacking = !!(it && it.isBackingTrack && (!preserveEl || preserveEl.checked));

  state._stagedEditCandidate = cand;
  state._stagedEditCandidateIdx = idx;

  const titleEl = document.getElementById('ed-title');
  const artistEl = document.getElementById('ed-artist');
  const albumEl = document.getElementById('ed-album');
  const albumArtistEl = document.getElementById('ed-album-artist');
  const trackEl = document.getElementById('ed-track');
  const yearEl = document.getElementById('ed-year');
  const hintEl = document.getElementById('ed-staged-hint');

  if (titleEl) titleEl.value = applyBackingSuffixJs(cand.title || '', preserveBacking);
  if (artistEl) artistEl.value = cand.artist || '';
  if (albumEl) albumEl.value = cand.album || '';
  if (albumArtistEl) albumArtistEl.value = cand.albumArtist || cand.artist || '';
  if (trackEl && cand.trackNumber != null && cand.trackNumber > 0) trackEl.value = String(cand.trackNumber);
  if (yearEl && cand.year != null && cand.year > 0) yearEl.value = String(cand.year);
  if (hintEl) {
    const extras = [];
    if (cand.coverUrl) extras.push(t('extraCover'));
    if (cand.syncedLrc || cand.plainLyrics) extras.push(t('extraLyrics'));
    hintEl.textContent = extras.length
      ? t('stagedHintWithExtrasTpl', extras.join('+'))
      : t('stagedHintPlain');
  }
  renderEditModalCandidates(itemId);
}

function renderEditModalCandidates(itemId) {
  const box = document.getElementById('ed-candidates-box');
  if (!box) return;
  const cands = state._editCandidates || [];
  if (!cands.length) {
    box.innerHTML = '';
    return;
  }
  box.innerHTML =
    '<div style="font-size:12px;color:var(--muted);margin-bottom:6px;">' + esc(t('candidatesMatchedHeaderTpl', cands.length)) + '</div>' +
    '<div style="max-height:190px;overflow-y:auto;display:flex;flex-direction:column;gap:6px;">' +
    cands.map((c, idx) => {
      const active = idx === state._stagedEditCandidateIdx;
      const borderStyle = active ? 'border:1.5px solid var(--primary);background:rgba(99,102,241,0.10);' : 'border:1px solid var(--border);';
      const score = c.confidence != null ? c.confidence : (c.confidenceScore || 0);
      const lrcTag = c.syncedLrc ? t('candidateLyricsTag') : '';
      const coverTag = c.coverUrl ? t('candidateCoverTag') : '';
      const stagedPill = active ? '<span class="badge badge-ok" style="margin-left:6px;">' + esc(t('candidateFilledBadge')) + '</span>' : '';
      return '<div class="list-item" style="' + borderStyle + 'cursor:pointer;padding:8px 10px;" onclick="selectEditModalCandidate(' + itemId + ',' + idx + ')">' +
        '<div><div style="font-weight:600;">' + esc(c.title) + ' — ' + esc(c.artist) + stagedPill + '</div>' +
        '<div style="font-size:12px;color:var(--muted);">' + esc(t('candidateMetaLineTpl', c.album || '—', c.year || '—', c.source)) + esc(coverTag + lrcTag) + '</div></div>' +
        '<span class="badge badge-ok tnum">' + score + '%</span></div>';
    }).join('') +
    '</div>';
}

async function searchEditModalCandidates(itemId) {
  const box = document.getElementById('ed-candidates-box');
  const btn = document.getElementById('ed-auto-match-btn');
  const qTitle = (document.getElementById('ed-title') && document.getElementById('ed-title').value.trim()) || '';
  const qArtist = (document.getElementById('ed-artist') && document.getElementById('ed-artist').value.trim()) || '';
  if (btn) btn.disabled = true;
  if (box) box.innerHTML = '<div style="font-size:12px;color:var(--muted);padding:6px 0;">' + esc(t('searchingOnlineProviders')) + '</div>';
  try {
    const qs = 'itemId=' + encodeURIComponent(itemId) +
      (qTitle ? '&queryTitle=' + encodeURIComponent(qTitle) : '') +
      (qArtist ? '&queryArtist=' + encodeURIComponent(qArtist) : '');
    const res = await api('/api/metadata/candidates?' + qs);
    const cands = res.candidates || [];
    state._editCandidates = cands;
    if (!cands.length) {
      if (box) box.innerHTML = '<div style="font-size:12px;color:var(--muted);padding:6px 0;">' + esc(t('noOnlineCandidatesRetry')) + '</div>';
      return;
    }
    selectEditModalCandidate(itemId, 0);
  } catch (e) {
    if (box) box.innerHTML = '';
    alert(e.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function submitSingleMetadata(itemId) {
  const trk = document.getElementById('ed-track').value.trim();
  const yr = document.getElementById('ed-year').value.trim();
  const staged = state._stagedEditCandidate;
  const payload = {
    itemId: itemId,
    title: document.getElementById('ed-title').value.trim(),
    artist: document.getElementById('ed-artist').value.trim(),
    album: document.getElementById('ed-album').value.trim(),
    albumArtist: document.getElementById('ed-album-artist').value.trim(),
    trackNumber: trk ? parseInt(trk, 10) : null,
    year: yr ? parseInt(yr, 10) : null,
    writeBackToFile: document.getElementById('ed-writeback').checked
  };
  if (staged) {
    if (staged.coverUrl) payload.coverRemoteUrl = staged.coverUrl;
    if (staged.syncedLrc) payload.syncedLrc = staged.syncedLrc;
    if (staged.plainLyrics) payload.plainLyrics = staged.plainLyrics;
  }
  try {
    await api('/api/metadata/update', {
      method: 'POST',
      json: payload
    });
    closeModal();
    showToast(t('toastMetadataSaved'));
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

function openBatchEditModal() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length) return;
  openModal(
    '<h3 style="margin-top:0;">' + esc(t('batchEditTitleTpl', ids.length)) + '</h3>' +
    '<p style="font-size:12px;color:var(--muted);">' + esc(t('batchEditSubtitle')) + '</p>' +
    '<div class="form-grid">' +
    '<div class="form-field full"><label><input type="checkbox" id="b-chk-artist"> ' + esc(t('batchFieldArtist')) + '</label><input id="b-artist" class="input" placeholder="' + esc(t('batchPlaceholderArtist')) + '"></div>' +
    '<div class="form-field full"><label><input type="checkbox" id="b-chk-album" checked> ' + esc(t('batchFieldAlbum')) + '</label><input id="b-album" class="input" placeholder="' + esc(t('batchPlaceholderAlbum')) + '"></div>' +
    '<div class="form-field"><label><input type="checkbox" id="b-chk-aa"> ' + esc(t('batchFieldAlbumArtist')) + '</label><input id="b-aa" class="input" placeholder="' + esc(t('batchPlaceholderAlbumArtist')) + '"></div>' +
    '<div class="form-field"><label><input type="checkbox" id="b-chk-year"> ' + esc(t('batchFieldYear')) + '</label><input id="b-year" type="number" class="input tnum" placeholder="' + esc(t('batchPlaceholderYear')) + '"></div>' +
    '</div>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">' + esc(t('cancel')) + '</button>' +
    '<button class="btn btn-primary" onclick="submitBatchMetadata()">' + esc(t('batchApplyBtn')) + '</button></div>'
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
  if (!anyField) { alert(t('alertSelectAtLeastOneField')); return; }
  try {
    const res = await api('/api/metadata/batch-update', { method: 'POST', json: payload });
    closeModal();
    showToast(t('toastBatchUpdatedTpl', res.updatedCount));
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function resetSelectedMetadata() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length || !confirm(t('confirmResetMetadataTpl', ids.length))) return;
  try {
    await api('/api/metadata/reset', { method: 'POST', json: { itemIds: ids } });
    showToast(t('toastMetadataReset'));
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function autoMatchSingle(itemId, previewOnly) {
  showToast(t('searchingOnlineProviders'));
  try {
    const res = await api('/api/metadata/auto-match?itemId=' + encodeURIComponent(itemId), {
      method: 'POST',
      json: { itemId: itemId, previewOnly: !!previewOnly }
    });
    if (!previewOnly) {
      if (res.applied) {
        const matched = res.selectedCandidate ? (res.selectedCandidate.artist + ' - ' + res.selectedCandidate.title) : t('autoMatchSuccessFallback');
        showToast(t('toastAutoMatchedTpl', matched));
        await loadAllItems();
      } else {
        showToast(t('toastNoHighConfidenceCandidate'));
      }
      return;
    }
    const cands = res.candidates || [];
    if (!cands.length) {
      alert(t('alertNoOnlineCandidates'));
      return;
    }
    state._lastCandidates = cands;
    openModal(
      '<h3 style="margin-top:0;">' + esc(t('pickCandidateTitle')) + '</h3>' +
      cands.map((c, idx) =>
        '<div class="list-item" style="border:1px solid var(--border);margin-bottom:8px;" onclick="applyPickedCandidate(' + itemId + ',' + idx + ')">' +
        '<div><div style="font-weight:600;">' + esc(c.title) + ' — ' + esc(c.artist) + '</div>' +
        '<div style="font-size:12px;color:var(--muted);">' + esc(t('candidateSourceLineTpl', c.album || '—', c.year || '—', c.source)) + '</div></div>' +
        '<span class="badge badge-ok tnum">' + c.confidenceScore + '%</span></div>'
      ).join('') +
      '<div style="text-align:right;"><button class="btn btn-outline" onclick="closeModal()">' + esc(t('close')) + '</button></div>'
    );
  } catch (e) { alert(e.message); }
}

async function applyPickedCandidate(itemId, idx) {
  try {
    const picked = (state._lastCandidates && state._lastCandidates[idx]) || undefined;
    await api('/api/metadata/auto-match?itemId=' + encodeURIComponent(itemId), {
      method: 'POST',
      json: { itemId: itemId, candidateIndex: idx, candidate: picked, previewOnly: false }
    });
    closeModal();
    showToast(t('toastAppliedOnlineTags'));
    await loadAllItems();
  } catch (e) { alert(e.message); }
}

async function runBatchAutoCompleteSelected() {
  const ids = Array.from(state.selectedItemIds);
  await executeBatchAutoComplete(ids);
}

async function runBatchAutoCompletePreview() {
  const ids = getFilteredMetaItems().map(i => i.itemId);
  if (!ids.length) { alert(t('alertFilteredListEmpty')); return; }
  await executeBatchAutoComplete(ids);
}

async function executeBatchAutoComplete(itemIds) {
  showToast(t('toastBatchSearchingTpl', itemIds.length));
  try {
    const res = await api('/api/metadata/batch-auto-complete', {
      method: 'POST',
      json: { itemIds: itemIds, mode: 'auto' }
    });
    showToast(t('toastBatchAutoCompleteDoneTpl', res.committedCount || 0));
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
      '<div style="padding:36px;text-align:center;color:var(--muted);">' + esc(t('playlistPaneEmptyNone')) + '</div>';
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
    el.innerHTML = '<div style="font-size:13px;color:var(--muted);padding:12px 0;">' + esc(t('playlistsEmptyFiltered')) + '</div>';
    return;
  }
  el.innerHTML = list.map(p => {
    const sel = p.playlistId === state.activePlaylistId ? ' selected' : '';
    const badge = p.isPracticePlaylist ? '<span class="badge badge-practice">' + esc(t('badgePracticeShort')) + '</span>' : '';
    return '<div class="list-item' + sel + '" onclick="selectPlaylist(' + p.playlistId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
      esc(p.name) + ' ' + badge + '</div>' +
      '<div style="font-size:11px;opacity:0.78;" class="tnum">' + esc(t('playlistSidebarMetaTpl', p.itemCount, p.totalBookmarks || 0)) + '</div></div></div>';
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
    const bmTag = it.bookmarkCount > 0 ? '<span class="badge badge-ok tnum">' + esc(t('bookmarksCountBadgeTpl', it.bookmarkCount)) + '</span>' : '';
    return '<tr class="drag-row" draggable="true" data-itemid="' + it.itemId + '" ' +
      'ondragstart="onPlDragStart(event,' + idx + ')" ondragover="event.preventDefault()" ondrop="onPlDrop(event,' + idx + ')">' +
      '<td class="tnum" style="width:44px;color:var(--muted);">' + (idx + 1) + '</td>' +
      '<td><div style="font-weight:600;">' + (it.isVideo ? esc(t('videoTagPrefix')) : '') + esc(it.title) + '</div>' +
      '<div style="font-size:11px;color:var(--muted);">' + esc(displayArtist(it.artist)) + ' · ' + esc(displayAlbum(it.displayAlbum)) + '</div></td>' +
      '<td>' + speedTag + bmTag + '</td>' +
      '<td class="tnum">' + fmtMs(it.durationMs, false) + '</td>' +
      '<td style="text-align:right;white-space:nowrap;">' +
      '<button class="btn btn-outline btn-xs" onclick="movePlItem(' + idx + ',-1)"' + (idx === 0 ? ' disabled' : '') + '>↑</button> ' +
      '<button class="btn btn-outline btn-xs" onclick="movePlItem(' + idx + ',1)"' + (idx === items.length - 1 ? ' disabled' : '') + '>↓</button> ' +
      '<button class="btn btn-tonal btn-xs" onclick="openInStudio(' + it.itemId + ')">' + esc(t('markBtnShort')) + '</button> ' +
      '<button class="btn btn-danger btn-xs" onclick="removePlItem(' + s.playlistId + ',' + it.itemId + ')">' + esc(t('remove')) + '</button></td></tr>';
  }).join('') : '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--muted);">' + esc(t('playlistTracksEmpty')) + '</td></tr>';

  pane.innerHTML =
    '<div style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px;margin-bottom:14px;">' +
    '<div><h2 style="margin:0;font-size:19px;">' + esc(s.name) + '</h2>' +
    '<div style="font-size:12px;color:var(--muted);" class="tnum">' + esc(t('playlistTrackCountHintTpl', items.length)) + '</div></div>' +
    '<div style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">' +
    '<label class="badge badge-practice" style="cursor:pointer;padding:6px 12px;font-size:12px;">' +
    '<input type="checkbox" ' + (s.isPracticePlaylist ? 'checked' : '') + ' onchange="togglePlaylistPractice(' + s.playlistId + ', this.checked)"> ' + esc(t('playlistPracticeToggleLabel')) + '</label>' +
    '<button class="btn btn-primary btn-xs" onclick="openAddTracksToActivePlaylist()">' + esc(t('addTracksBtn')) + '</button>' +
    '<button class="btn btn-outline btn-xs" onclick="renameActivePlaylist(' + s.playlistId + ', \'' + escJs(s.name) + '\')">' + esc(t('rename')) + '</button>' +
    '<a class="btn btn-tonal btn-xs" href="/api/playlists/export?id=' + s.playlistId + '">' + esc(t('exportM3uBtn')) + '</a>' +
    '<button class="btn btn-danger btn-xs" onclick="deleteActivePlaylist(' + s.playlistId + ')">' + esc(t('delete')) + '</button>' +
    '</div></div>' +
    '<div class="table-wrap"><table><thead><tr><th>' + esc(t('colOrder')) + '</th><th>' + esc(t('colTrackVideo')) + '</th><th>' + esc(t('colPracticeTags')) + '</th><th>' + esc(t('colDuration')) + '</th><th style="text-align:right;">' + esc(t('colReorderActions')) + '</th></tr></thead>' +
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
    '<h3 style="margin-top:0;">' + esc(t('createPlaylistTitle')) + '</h3>' +
    '<div class="form-field" style="margin-bottom:12px;"><label>' + esc(t('playlistNameLabel')) + '</label>' +
    '<input id="new-pl-name" class="input" placeholder="' + esc(t('playlistNamePlaceholder')) + '"></div>' +
    '<label style="display:flex;align-items:center;gap:8px;font-size:13px;font-weight:600;margin-bottom:16px;cursor:pointer;">' +
    '<input type="checkbox" id="new-pl-practice" checked> ' + esc(t('newPlaylistPracticeCheckbox')) + '</label>' +
    '<div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">' + esc(t('cancel')) + '</button>' +
    '<button class="btn btn-primary" onclick="submitCreatePlaylist()">' + esc(t('create')) + '</button></div>'
  );
}

async function submitCreatePlaylist() {
  const name = document.getElementById('new-pl-name').value.trim();
  const isPractice = document.getElementById('new-pl-practice').checked;
  if (!name) { alert(t('alertEnterPlaylistName')); return; }
  try {
    const res = await api('/api/playlists', { method: 'POST', json: { name: name, isPracticePlaylist: isPractice } });
    closeModal();
    state.activePlaylistId = res.playlistId;
    await loadPlaylists();
    await refreshStatus();
    showToast(t('toastPlaylistCreatedTpl', name));
  } catch (e) { alert(e.message); }
}

async function renameActivePlaylist(id, oldName) {
  const name = prompt(t('promptRenamePlaylist'), oldName);
  if (!name || !name.trim() || name.trim() === oldName) return;
  try {
    await api('/api/playlists/rename', { method: 'POST', json: { playlistId: id, name: name.trim() } });
    await loadPlaylists();
  } catch (e) { alert(e.message); }
}

async function togglePlaylistPractice(id, isPractice) {
  try {
    await api('/api/playlists/practice-mode', { method: 'POST', json: { playlistId: id, isPracticePlaylist: isPractice } });
    showToast(isPractice ? t('toastSetPracticePlaylist') : t('toastSetNormalPlaylist'));
    await loadPlaylists();
  } catch (e) { alert(e.message); }
}

async function deleteActivePlaylist(id) {
  if (!confirm(t('confirmDeletePlaylist'))) return;
  try {
    await api('/api/playlists?id=' + encodeURIComponent(id), { method: 'DELETE' });
    state.activePlaylistId = null;
    await loadPlaylists();
    await refreshStatus();
    showToast(t('toastPlaylistDeleted'));
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
    '<h3 style="margin-top:0;">' + esc(t('addTracksModalTitle')) + '</h3>' +
    '<div style="max-height:360px;overflow-y:auto;border:1px solid var(--border);border-radius:12px;padding:8px;margin-bottom:14px;">' +
    (candidates.length ? candidates.map(it =>
      '<label style="display:flex;align-items:center;gap:8px;padding:6px 8px;cursor:pointer;">' +
      '<input type="checkbox" class="pl-add-chk" value="' + it.itemId + '"> ' +
      '<span>' + (it.isVideo ? esc(t('videoTagPrefix')) : '') + '<strong>' + esc(it.title) + '</strong> — ' + esc(displayArtist(it.artist)) + '</span></label>'
    ).join('') : '<div style="padding:18px;text-align:center;color:var(--muted);">' + esc(t('allTracksAlreadyInPlaylist')) + '</div>') +
    '</div><div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">' + esc(t('cancel')) + '</button>' +
    '<button class="btn btn-primary" onclick="submitAddTracksToActivePlaylist()">' + esc(t('addSelectedBtn')) + '</button></div>'
  );
}

async function submitAddTracksToActivePlaylist() {
  const ids = Array.from(document.querySelectorAll('.pl-add-chk:checked')).map(el => Number(el.value));
  if (!ids.length) { closeModal(); return; }
  try {
    await api('/api/playlists/items/add', { method: 'POST', json: { playlistId: state.activePlaylistId, itemIds: ids } });
    closeModal();
    await loadPlaylists();
    showToast(t('toastAddedTracksTpl', ids.length));
  } catch (e) { alert(e.message); }
}

function openAddSelectedToPlaylistModal() {
  const ids = Array.from(state.selectedItemIds);
  if (!ids.length) return;
  if (!state.playlists.length) { alert(t('alertCreatePlaylistFirst')); return; }
  openModal(
    '<h3 style="margin-top:0;">' + esc(t('batchAddPlaylistTitleTpl', ids.length)) + '</h3>' +
    '<select id="batch-pl-target" style="width:100%;margin-bottom:16px;">' +
    state.playlists.map(p => '<option value="' + p.playlistId + '">' + (p.isPracticePlaylist ? esc(t('practiceTagPrefix')) : '') + esc(p.name) + '</option>').join('') +
    '</select><div style="display:flex;justify-content:flex-end;gap:8px;">' +
    '<button class="btn btn-outline" onclick="closeModal()">' + esc(t('cancel')) + '</button>' +
    '<button class="btn btn-primary" onclick="submitBatchAddToPlaylist()">' + esc(t('addToListConfirmBtn')) + '</button></div>'
  );
}

async function submitBatchAddToPlaylist() {
  const plId = Number(document.getElementById('batch-pl-target').value);
  const ids = Array.from(state.selectedItemIds);
  try {
    await api('/api/playlists/items/add', { method: 'POST', json: { playlistId: plId, itemIds: ids } });
    closeModal();
    await loadPlaylists();
    showToast(t('toastAddedToPlaylist'));
  } catch (e) { alert(e.message); }
}

async function importM3uFile(file) {
  if (!file) return;
  try {
    const content = await file.text();
    const fallback = file.name.replace(/\.(m3u8|m3u)$/i, '') || t('importedPlaylistFallback');
    const res = await api('/api/playlists/import', {
      method: 'POST',
      json: { name: fallback, content: content }
    });
    state.activePlaylistId = res.playlistId;
    await loadPlaylists();
    await refreshStatus();
    showToast(t('toastImportedPlaylistTpl', res.playlistName, res.matchedCount, res.unmatchedCount));
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
  const filtered = state.items.filter(it => !q || ((it.title + ' ' + it.artist + ' ' + displayArtist(it.artist) + ' ' + it.fileName).toLowerCase().includes(q)));
  const el = document.getElementById('studio-track-list');
  el.innerHTML = filtered.map(it => {
    const sel = it.itemId === state.studioItemId ? ' selected' : '';
    const bm = it.bookmarkCount > 0 ? ' <span class="badge badge-ok tnum">' + esc(t('studioBookmarkShortBadgeTpl', it.bookmarkCount)) + '</span>' : '';
    return '<div class="list-item' + sel + '" onclick="loadStudioItem(' + it.itemId + ')">' +
      '<div style="min-width:0;"><div style="font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' +
      (it.isVideo ? esc(t('videoTagPrefix')) : '') + esc(it.title) + bm + '</div>' +
      '<div style="font-size:11px;opacity:0.78;">' + esc(displayArtist(it.artist)) + ' · ' + fmtMs(it.durationMs, false) + '</div></div></div>';
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
  document.getElementById('studio-sub').textContent = displayArtist(it.artist) + ' · ' + displayAlbum(it.displayAlbum) + ' · ' + it.fileName;

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
  const loadedBpm = Number(pData.state.bpmPrecise || pData.state.bpm || 120);
  state.loadedBpmText = String(Math.round(loadedBpm * 100) / 100);
  document.getElementById('practice-bpm-input').value = state.loadedBpmText;
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

function stepSpeedSlider(delta) {
  const cur = Number(document.getElementById('practice-speed-range').value) || 1.0;
  onSpeedSliderChange(cur + delta);
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
    showToast(t('toastSetAbFirst'));
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
  loopBtn.textContent = state.loopEnabled ? t('loopOn') : t('loopPaused');
  loopBtn.className = state.loopEnabled ? 'btn btn-primary' : 'btn btn-tonal';

  const readout = document.getElementById('studio-ab-readout');
  if (state.pointAMs != null && state.pointBMs != null) {
    readout.textContent = 'A-B: ' + fmtMs(state.pointAMs, true) + ' → ' + fmtMs(state.pointBMs, true) +
      ' (' + ((state.pointBMs - state.pointAMs) / 1000).toFixed(1) + 's)';
  } else if (state.pointAMs != null) {
    readout.textContent = t('abPointASetTpl', fmtMs(state.pointAMs, true));
  } else {
    readout.textContent = t('abRangeUnset');
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
    el.innerHTML = '<div style="font-size:12px;color:var(--muted);">' + esc(t('bookmarksEmptyHint')) + '</div>';
    return;
  }
  el.innerHTML = state.studioBookmarks.map(b => {
    const spd = b.savedSpeed ? (' · ' + Number(b.savedSpeed).toFixed(2) + 'x') : '';
    return '<div class="list-item" style="background:var(--surface);border:1px solid var(--border);margin-bottom:6px;">' +
      '<div onclick="activateBookmark(' + b.bookmarkId + ')" style="flex:1;">' +
      '<span class="badge badge-practice">' + esc(b.label) + '</span> ' +
      '<span class="tnum" style="font-size:12px;margin-left:6px;">' + fmtMs(b.aMs, true) + ' – ' + fmtMs(b.bMs, true) + spd + '</span></div>' +
      '<button class="btn btn-danger btn-xs" onclick="deleteBookmarkById(' + b.bookmarkId + ')">' + esc(t('delete')) + '</button></div>';
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
    alert(t('alertSetAbPointsFirst'));
    return;
  }
  const label = (document.getElementById('bm-label-input').value || '').trim() || t('defaultSectionNumberTpl', state.studioBookmarks.length + 1);
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
    showToast(t('toastBookmarkSavedTpl', label));
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
    showToast(t('toastBookmarkDeleted'));
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
    const payload = {
      itemId: state.studioItemId,
      practiceSpeed: Number(document.getElementById('practice-speed-range').value),
      pitchSemitones: parseInt(document.getElementById('practice-pitch-input').value || '0', 10),
      countInMode: document.getElementById('practice-countin-select').value,
      loopEnabled: state.loopEnabled,
      activeAMs: state.pointAMs,
      activeBMs: state.pointBMs,
      videoMirror: document.getElementById('studio-mirror-chk').checked
    };
    // Only send the tempo when the user changed it, so a detected 120.3871 BPM is never truncated.
    const bpmText = String(document.getElementById('practice-bpm-input').value || '').trim();
    const bpmValue = parseFloat(bpmText);
    if (bpmText !== state.loadedBpmText && isFinite(bpmValue) && bpmValue > 0) {
      payload.bpmPrecise = Math.max(30, Math.min(300, bpmValue));
      payload.bpm = Math.round(payload.bpmPrecise);
    }
    const res = await api('/api/practice/state', {
      method: 'POST',
      json: payload
    });
    if (res.state) {
      const savedBpm = Number(res.state.bpmPrecise || res.state.bpm || 120);
      state.loadedBpmText = String(Math.round(savedBpm * 100) / 100);
      document.getElementById('practice-bpm-input').value = state.loadedBpmText;
    }
    state.studioPractice = res.state;
    await loadAllItems();
    showToast(t('toastPracticeStateSaved'));
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

function syncUrlToken() {
  try {
    const params = new URLSearchParams(window.location.search || '');
    const urlToken = (params.get('token') || '').trim();
    if (urlToken) {
      sessionStorage.setItem('mp_lan_pin', urlToken);
      params.delete('token');
      const rest = params.toString();
      const cleanUrl = window.location.pathname + (rest ? ('?' + rest) : '') + (window.location.hash || '');
      if (window.history && window.history.replaceState) {
        window.history.replaceState(null, '', cleanUrl);
      }
    }
  } catch (_) {}
}

initTheme();
syncUrlToken();
attachMediaLoopListeners(document.getElementById('studio-video'));
attachMediaLoopListeners(document.getElementById('studio-audio'));
bootstrapAll().catch(err => console.warn(err));
    """.trimIndent()
}
