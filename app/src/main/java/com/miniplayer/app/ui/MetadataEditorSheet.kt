@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.components.MediaArtwork
import com.miniplayer.app.ui.theme.tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 6-Field Simple Metadata Editor Bottom Sheet (`DESIGN.md` §6.3 & §6.4):
 * - Supports both Single-Track edit and Batch Multi-Track edit with strict Dirty-Field tracking:
 *   In Batch mode, `Title` and `Track #` are locked with `(保持各曲原值 — 批量模式不覆盖)` so batch-editing
 *   Album/Artist/Cover NEVER clobbers individual song titles!
 * - Integrates 1-Tap Zero-Key Online Auto-Match (`MusicBrainz` + `CAA 3s fallback` + `iTunes` + `LRCLIB`)
 *   with candidate preview cards and `(伴奏)` suffix preservation.
 */
@Composable
fun MetadataEditorSheet(
    targets: List<MediaItem>,
    defaultWriteBackToFile: Boolean,
    candidates: List<MetadataCandidate>,
    isSearchingOnline: Boolean,
    onSearchOnline: (MediaItem, String?, String?) -> Unit,
    onApplyCandidateDirectly: (MediaItem, MetadataCandidate, Boolean, Boolean) -> Unit,
    onSaveDraft: (MetadataDraft) -> Unit,
    onResetOverrides: (List<MediaItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (targets.isEmpty()) return
    val isBatch = targets.size > 1
    val first = targets.first()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun uniformStringOrEmpty(selector: (MediaItem) -> String?): Pair<String, Boolean> {
        val distinct = targets.map { selector(it)?.trim().orEmpty() }.distinct()
        return if (distinct.size == 1) distinct.first() to false else "" to true
    }

    val (initArtist, mixedArtist) = remember(targets) { uniformStringOrEmpty { it.artist } }
    val (initAlbum, mixedAlbum) = remember(targets) { uniformStringOrEmpty { it.album } }
    val (initAlbumArtist, mixedAlbumArtist) = remember(targets) { uniformStringOrEmpty { it.albumArtist } }
    val (initYear, mixedYear) = remember(targets) {
        val yrs = targets.map { it.year?.toString().orEmpty() }.distinct()
        if (yrs.size == 1) yrs.first() to false else "" to true
    }

    var title by remember(targets) { mutableStateOf(if (isBatch) "" else first.title) }
    var artist by remember(targets) { mutableStateOf(initArtist) }
    var album by remember(targets) { mutableStateOf(initAlbum) }
    var albumArtist by remember(targets) { mutableStateOf(initAlbumArtist) }
    var trackNumText by remember(targets) { mutableStateOf(if (isBatch) "" else (first.trackNumber?.toString().orEmpty())) }
    var yearText by remember(targets) { mutableStateOf(initYear) }

    var pickedCoverBytes by remember(targets) { mutableStateOf<ByteArray?>(null) }
    var pickedCoverMime by remember(targets) { mutableStateOf<String?>(null) }
    var previewCoverUri by remember(targets) { mutableStateOf(first.coverPathOrUri) }

    var dirtyFields by remember(targets) {
        mutableStateOf(
            if (isBatch) {
                emptySet()
            } else {
                setOf(
                    MetadataField.TITLE,
                    MetadataField.ARTIST,
                    MetadataField.ALBUM,
                    MetadataField.ALBUM_ARTIST,
                    MetadataField.TRACK_NUMBER,
                    MetadataField.YEAR,
                )
            },
        )
    }

    val anyWritableAudio = remember(targets) { targets.any { it.isRootWritable && !it.isVideo } }
    var writeBackToFile by remember(targets) { mutableStateOf(defaultWriteBackToFile && anyWritableAudio) }
    var preserveBackingSuffix by remember(targets) { mutableStateOf(first.isBackingTrack) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val pair = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes to mime
                }
                if (pair.first != null) {
                    pickedCoverBytes = pair.first
                    pickedCoverMime = pair.second
                    previewCoverUri = uri.toString()
                    dirtyFields = dirtyFields + MetadataField.COVER
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isBatch) "批量编辑标签 (${targets.size} 首)" else "编辑标签信息",
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            onSaveDraft(
                                MetadataDraft(
                                    title = title.trim().takeIf { !isBatch },
                                    artist = artist.trim(),
                                    album = album.trim(),
                                    albumArtist = albumArtist.trim(),
                                    trackNumber = trackNumText.trim().toIntOrNull()?.takeIf { !isBatch },
                                    year = yearText.trim().toIntOrNull(),
                                    coverBytes = pickedCoverBytes,
                                    coverMimeType = pickedCoverMime,
                                    dirtyFields = dirtyFields.toSet(),
                                    writeBackToFile = writeBackToFile && anyWritableAudio,
                                ),
                            )
                        },
                        enabled = !isBatch || dirtyFields.isNotEmpty(),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("保存")
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }
            }

            // Cover Art & Online Auto-Match Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaArtwork(
                    coverPathOrUri = previewCoverUri,
                    mediaDocumentUri = if (isBatch) null else first.documentUri,
                    isVideo = first.isVideo,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(88.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isBatch && !first.isVideo) {
                        FilledTonalButton(
                            onClick = { onSearchOnline(first, title, artist) },
                            enabled = !isSearchingOnline,
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                        ) {
                            if (isSearchingOnline) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在检索开放曲库…")
                            } else {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("自动联网匹配标签/封面")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("从相册选图", maxLines = 1)
                        }
                        if (targets.any { it.hasMetadataOverride }) {
                            OutlinedButton(
                                onClick = { onResetOverrides(targets) },
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("恢复默认", maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Single-Track Online Candidate Cards (§6.4 Stage 3)
            if (!isBatch && candidates.isNotEmpty()) {
                if (first.isBackingTrack) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { preserveBackingSuffix = !preserveBackingSuffix },
                    ) {
                        Checkbox(
                            checked = preserveBackingSuffix,
                            onCheckedChange = { preserveBackingSuffix = it },
                        )
                        Text("标题保留「(伴奏)」后缀，避免与原唱混淆", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(candidates) { cand ->
                        Surface(
                            onClick = {
                                onApplyCandidateDirectly(
                                    first,
                                    cand,
                                    preserveBackingSuffix,
                                    writeBackToFile && anyWritableAudio,
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.width(248.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MediaArtwork(
                                    coverPathOrUri = cand.coverUrl,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(56.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cand.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${cand.artist} · ${cand.album ?: "单曲"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = if (cand.source == OnlineSource.MUSICBRAINZ) "MB" else "iTunes",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = "${cand.confidenceScore}%",
                                            style = MaterialTheme.typography.labelMedium.tnum(),
                                        )
                                        if (!cand.syncedLrc.isNullOrBlank()) {
                                            Text("歌词✓", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6 Core Fields (`Title` & `Track #` strictly locked in Batch Mode per §6.3!)
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    dirtyFields = dirtyFields + MetadataField.TITLE
                },
                enabled = !isBatch,
                label = { Text(if (isBatch) "标题 (Title) [批量编辑时锁定]" else "标题 (Title)") },
                placeholder = {
                    if (isBatch) Text("(保持各曲原值 — 批量模式不覆盖)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = artist,
                onValueChange = {
                    artist = it
                    dirtyFields = dirtyFields + MetadataField.ARTIST
                },
                label = { Text("歌手 (Artist)") },
                placeholder = {
                    if (isBatch && mixedArtist) Text("(多项不同 — 未改动则保持原值)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = album,
                onValueChange = {
                    album = it
                    dirtyFields = dirtyFields + MetadataField.ALBUM
                },
                label = { Text("专辑 (Album)") },
                placeholder = {
                    if (isBatch && mixedAlbum) Text("(多项不同 — 未改动则保持原值)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = albumArtist,
                onValueChange = {
                    albumArtist = it
                    dirtyFields = dirtyFields + MetadataField.ALBUM_ARTIST
                },
                label = { Text("专辑歌手 (Album Artist)") },
                placeholder = {
                    if (isBatch && mixedAlbumArtist) Text("(多项不同 — 未改动则保持原值)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = trackNumText,
                    onValueChange = {
                        trackNumText = it.filter(Char::isDigit)
                        dirtyFields = dirtyFields + MetadataField.TRACK_NUMBER
                    },
                    enabled = !isBatch,
                    label = { Text(if (isBatch) "音轨号 (批量锁定)" else "音轨号 (Track #)") },
                    placeholder = { if (isBatch) Text("(保持各曲原值)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = yearText,
                    onValueChange = {
                        yearText = it.filter(Char::isDigit).take(4)
                        dirtyFields = dirtyFields + MetadataField.YEAR
                    },
                    label = { Text("年份 (Year)") },
                    placeholder = { if (isBatch && mixedYear) Text("(保持原值)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            // Dual-Layer Persistence Option (§6.3)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = anyWritableAudio) { writeBackToFile = !writeBackToFile },
            ) {
                Checkbox(
                    checked = writeBackToFile && anyWritableAudio,
                    onCheckedChange = { writeBackToFile = it },
                    enabled = anyWritableAudio,
                )
                Text(
                    text = if (anyWritableAudio) {
                        "同时安全写入原音频文件标签 (ID3v2 / Vorbis / MP4 ilst)"
                    } else {
                        "仅保存至本地数据库覆盖层（视频格式或只读目录免改原文件）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Batch Folder / `未知专辑` Online Auto-Completion Confirmation Sheet (`DESIGN.md` §6.4 Stage 3):
 * - `>= 85%` confidence auto-checked
 * - `50%–84%` manual candidate selection
 * - `< 50%` cleaned filename fallback
 */
@Composable
fun BatchMetadataCompletionSheet(
    previewItems: List<BatchMatchItem>?,
    progress: Pair<Int, Int>?,
    isCommitting: Boolean,
    defaultWriteBack: Boolean,
    onSelectCandidate: (itemIndex: Int, candidateIndex: Int) -> Unit,
    onTogglePreserveBacking: (itemIndex: Int) -> Unit,
    onCommit: (writeBackToFile: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (previewItems == null && progress == null) return
    var writeBack by remember { mutableStateOf(defaultWriteBack) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("批量自动联网补全预览", style = MaterialTheme.typography.titleLarge)

            if (progress != null) {
                val (done, total) = progress
                val frac = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在并发检索 MusicBrainz / iTunes / LRCLIB：$done / $total 首…",
                        style = MaterialTheme.typography.bodyMedium.tnum(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (previewItems != null) {
                val selectedCount = previewItems.count { it.selectedCandidateIndex >= 0 || it.cleanedTitle.isNotBlank() }

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(previewItems, key = { _, it -> it.mediaItem.itemId }) { idx, match ->
                        val activeCand = match.candidates.getOrNull(match.selectedCandidateIndex)
                        var showAlternatives by remember { mutableStateOf(false) }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = match.selectedCandidateIndex >= 0,
                                        onCheckedChange = { checked ->
                                            onSelectCandidate(
                                                idx,
                                                if (checked && match.candidates.isNotEmpty()) 0 else -1,
                                            )
                                        },
                                        enabled = match.candidates.isNotEmpty(),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = match.mediaItem.fileName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (activeCand != null) {
                                            Text(
                                                text = "→ ${activeCand.title} · ${activeCand.artist} (${activeCand.album ?: "单曲"})",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2,
                                            )
                                        } else {
                                            Text(
                                                text = "清洗标题回退：${match.cleanedTitle}",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                    val topScore = match.candidates.firstOrNull()?.confidenceScore ?: 0
                                    if (topScore > 0) {
                                        PracticeBadgePill("${topScore}%")
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (match.candidates.size > 1) {
                                        TextButton(onClick = { showAlternatives = !showAlternatives }) {
                                            Text("切换候选 (${match.candidates.size})")
                                        }
                                    }
                                    if (match.mediaItem.isBackingTrack) {
                                        FilterChip(
                                            selected = match.preserveBackingSuffix,
                                            onClick = { onTogglePreserveBacking(idx) },
                                            label = { Text("保留(伴奏)后缀") },
                                        )
                                    }
                                }

                                if (showAlternatives) {
                                    match.candidates.forEachIndexed { cIdx, cand ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onSelectCandidate(idx, cIdx)
                                                    showAlternatives = false
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = match.selectedCandidateIndex == cIdx,
                                                onClick = {
                                                    onSelectCandidate(idx, cIdx)
                                                    showAlternatives = false
                                                },
                                            )
                                            Text(
                                                text = "${cand.title} - ${cand.artist} · ${cand.album ?: ""} (${cand.confidenceScore}%)",
                                                style = MaterialTheme.typography.bodyMedium.tnum(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = writeBack, onCheckedChange = { writeBack = it })
                    Text("具备写权限时同步写入原音频文件", style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = { onCommit(writeBack) },
                    enabled = !isCommitting && selectedCount > 0,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                ) {
                    Text(if (isCommitting) "正在写入…" else "确认应用 (${previewItems.size} 首)")
                }
            }
        }
    }
}
