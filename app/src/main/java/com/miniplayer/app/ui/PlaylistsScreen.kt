@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.components.GuitarIcon
import com.miniplayer.app.ui.components.MediaArtwork
import com.miniplayer.app.ui.theme.tnum

@Composable
fun PlaylistsScreen(
    viewModel: MiniPlayerViewModel,
    onExportM3uRequest: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter by viewModel.playlistFilter.collectAsState()
    val searchQuery by viewModel.playlistSearchQuery.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val bookmarkedTracks by viewModel.bookmarkedTracks.collectAsState()
    val activeDetail by viewModel.activePlaylistDetail.collectAsState()
    val allItems by viewModel.allMediaItems.collectAsState()
    val playback by viewModel.playbackSnapshot.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var createDialogPractice by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }

    LaunchedEffect(Unit) {
        if (searchQuery.isNotEmpty()) {
            viewModel.setPlaylistSearchQuery("")
        }
    }

    // Swipeable sub-tab pager: FilterChips <-> HorizontalPager stay in sync (§4.2). Each page keeps
    // its own hoisted scroll state so swiping away and back preserves exact positions.
    val playlistTabs = SubTabOrder.PLAYLISTS
    val subTabs = rememberSubTabPager(
        tabs = playlistTabs,
        selectedTab = filter,
        onSelectTab = { viewModel.selectPlaylistFilter(it) },
    )
    val displayedFilter = subTabs.displayedTab
    val allListState = rememberLazyListState()
    val practiceListState = rememberLazyListState()
    val bookmarksListState = rememberLazyListState()
    val openCreateDialog = {
        // Snapshot the active tab at open time so the dialog's default never flips mid-edit.
        createDialogPractice = subTabs.displayedTab == PlaylistFilter.PRACTICE_ONLY
        showCreateDialog = true
    }

    BackHandler(enabled = activeDetail != null) {
        viewModel.closePlaylist()
    }

    activeDetail?.let { detail ->
        PlaylistDetailContent(
            detail = detail,
            allLibraryItems = allItems,
            currentPlayingId = playback.currentItem?.itemId,
            onBack = { viewModel.closePlaylist() },
            onTogglePracticeMode = { enabled ->
                viewModel.setPlaylistPracticeMode(detail.summary.playlistId, enabled)
            },
            onPlayFromPlaylist = { idx ->
                val ctx = if (detail.summary.isPracticePlaylist) {
                    PlayContext.PRACTICE_PLAYLIST
                } else {
                    PlayContext.CASUAL
                }
                viewModel.playFromContext(detail.items, idx, ctx)
            },
            onAddItems = { ids ->
                viewModel.addItemsToPlaylist(detail.summary.playlistId, ids)
            },
            onRemoveItem = { itemId ->
                viewModel.removeItemFromPlaylist(detail.summary.playlistId, itemId)
            },
            onReorder = { orderedIds ->
                viewModel.reorderPlaylistItems(detail.summary.playlistId, orderedIds)
            },
            onRename = { renameTarget = detail.summary },
            onExportM3u8 = {
                onExportM3uRequest(detail.summary.playlistId, detail.summary.name)
            },
            onDeletePlaylist = { viewModel.deletePlaylist(detail.summary.playlistId) },
            onEditTrackMetadata = { viewModel.openMetadataEditor(listOf(it)) },
            modifier = modifier,
        )
        renameTarget?.let { target ->
            CreateOrRenamePlaylistDialog(
                initialName = target.name,
                initialPractice = target.isPracticePlaylist,
                isRenameOnly = true,
                onConfirm = { newName, _ ->
                    viewModel.renamePlaylist(target.playlistId, newName)
                    renameTarget = null
                },
                onDismiss = { renameTarget = null },
            )
        }
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Text("播放列表", style = MaterialTheme.typography.titleLarge)
                    },
                    actions = {},
                )

                // Mandatory Single-Select 3 FilterChips (§4.2), swipe-synced with the pager below
                SubTabChipRow(
                    tabs = playlistTabs,
                    activeTab = displayedFilter,
                    label = SubTabOrder::playlistLabel,
                    onChipClick = subTabs::onChipClick,
                )
            }
        },
        floatingActionButton = {
            if (playlistFabVisible(displayedFilter)) {
                FloatingActionButton(
                    onClick = openCreateDialog,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新建播放列表")
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = subTabs.pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            key = { page -> playlistTabs.tabAtPage(page).name },
        ) { page ->
            // Each page renders its OWN filter (never the global `filter`) so mid-swipe
            // neighbours already show their final content.
            val pageFilter = playlistTabs.tabAtPage(page)
            if (pageFilter == PlaylistFilter.WITH_BOOKMARKS) {
                BookmarkedTracksSection(
                    items = bookmarkedTracks,
                    searchQuery = searchQuery,
                    listState = bookmarksListState,
                    onJumpToBookmark = { track, bookmark ->
                        viewModel.playFromContext(
                            items = listOf(track),
                            startIndex = 0,
                            context = PlayContext.BOOKMARK_JUMP,
                            targetBookmark = bookmark,
                            openFullPlayer = true,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val pagePlaylists = remember(playlists, pageFilter, searchQuery) {
                    playlistsForPage(playlists, pageFilter, searchQuery)
                }

                if (pagePlaylists.isEmpty()) {
                    EmptyLibraryPlaceholder(
                        title = playlistPageEmptyTitle(pageFilter),
                        subtitle = "可将音频伴奏与教学视频混排在同一练习列表中，点播自动展开练习工作台。",
                        actionLabel = "新建播放列表",
                        onAction = {
                            createDialogPractice = pageFilter == PlaylistFilter.PRACTICE_ONLY
                            showCreateDialog = true
                        },
                    )
                } else {
                    LazyColumn(
                        state = if (pageFilter == PlaylistFilter.PRACTICE_ONLY) practiceListState else allListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(pagePlaylists, key = { it.playlistId }) { pl ->
                            PlaylistSummaryCard(
                                playlist = pl,
                                onClick = { viewModel.openPlaylist(pl.playlistId) },
                                onRename = { renameTarget = pl },
                                onTogglePractice = {
                                    viewModel.setPlaylistPracticeMode(pl.playlistId, !pl.isPracticePlaylist)
                                },
                                onExport = { onExportM3uRequest(pl.playlistId, pl.name) },
                                onDelete = { viewModel.deletePlaylist(pl.playlistId) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateOrRenamePlaylistDialog(
            initialName = "",
            initialPractice = createDialogPractice,
            isRenameOnly = false,
            onConfirm = { name, isPractice ->
                viewModel.createPlaylist(name, isPractice)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    renameTarget?.let { target ->
        CreateOrRenamePlaylistDialog(
            initialName = target.name,
            initialPractice = target.isPracticePlaylist,
            isRenameOnly = true,
            onConfirm = { newName, _ ->
                viewModel.renamePlaylist(target.playlistId, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun PlaylistSummaryCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePractice: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCollageCover(coverPaths = playlist.coverPaths)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playlist.isPracticePlaylist) {
                        PracticeBadgePill("练习")
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("${playlist.itemCount} 首音视频")
                        if (playlist.totalBookmarks > 0) {
                            append(" · 含 ${playlist.totalBookmarks} 个段落书签")
                        } else if (!playlist.isPracticePlaylist) {
                            append(" · 普通播放列表")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "播放列表菜单")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(if (playlist.isPracticePlaylist) "切换为普通播放列表" else "设为练习列表")
                        },
                        leadingIcon = { Icon(GuitarIcon, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onTogglePractice()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 .m3u8") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("删除列表", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCollageCover(coverPaths: List<String>) {
    if (coverPaths.size < 4) {
        MediaArtwork(
            coverPathOrUri = coverPaths.firstOrNull(),
            shape = RoundedCornerShape(14.dp),
            fallbackIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
            modifier = Modifier.size(56.dp),
        )
    } else {
        Column(
            modifier = Modifier.size(56.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MediaArtwork(coverPaths[0], shape = RoundedCornerShape(topStart = 12.dp), modifier = Modifier.weight(1f).fillMaxHeight(), iconSize = 14.dp)
                MediaArtwork(coverPaths[1], shape = RoundedCornerShape(topEnd = 12.dp), modifier = Modifier.weight(1f).fillMaxHeight(), iconSize = 14.dp)
            }
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MediaArtwork(coverPaths[2], shape = RoundedCornerShape(bottomStart = 12.dp), modifier = Modifier.weight(1f).fillMaxHeight(), iconSize = 14.dp)
                MediaArtwork(coverPaths[3], shape = RoundedCornerShape(bottomEnd = 12.dp), modifier = Modifier.weight(1f).fillMaxHeight(), iconSize = 14.dp)
            }
        }
    }
}

@Composable
private fun BookmarkedTracksSection(
    items: List<BookmarkedTrackItem>,
    searchQuery: String,
    onJumpToBookmark: (MediaItem, PracticeBookmark) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val filtered = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter { entry ->
            entry.mediaItem.matchesQuery(searchQuery) ||
                entry.bookmarks.any { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }

    if (filtered.isEmpty()) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无已保存的 A-B 段落书签。在播放页展开「练习模式」设好 A/B 点后保存书签即可在此处一键直达。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(filtered, key = { it.mediaItem.itemId }) { entry ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(
                            coverPathOrUri = entry.mediaItem.coverPathOrUri,
                            mediaDocumentUri = entry.mediaItem.documentUri,
                            isVideo = entry.mediaItem.isVideo,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.mediaItem.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${entry.mediaItem.artist} · ${entry.bookmarks.size} 个段落",
                                style = MaterialTheme.typography.bodyMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entry.bookmarks, key = { it.bookmarkId }) { bm ->
                            AssistChip(
                                onClick = { onJumpToBookmark(entry.mediaItem, bm) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.BookmarkBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = buildString {
                                            append("${bm.label} ")
                                            append(PracticeMath.formatTimeMs(bm.aMs))
                                            append("–")
                                            append(PracticeMath.formatTimeMs(bm.bMs))
                                            bm.savedSpeed?.let { sp ->
                                                if (sp != 1.0f) append(" (${String.format("%.2fx", sp).trimEnd('0').trimEnd('.')})")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelLarge.tnum(),
                                    )
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    detail: PlaylistDetail,
    allLibraryItems: List<MediaItem>,
    currentPlayingId: Long?,
    onBack: () -> Unit,
    onTogglePracticeMode: (Boolean) -> Unit,
    onPlayFromPlaylist: (Int) -> Unit,
    onAddItems: (List<Long>) -> Unit,
    onRemoveItem: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onRename: () -> Unit,
    onExportM3u8: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onEditTrackMetadata: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var topMenuOpen by remember { mutableStateOf(false) }
    var showAddTracksSheet by remember { mutableStateOf(false) }

    // Local reorderable list state for responsive drag-to-reorder
    val localOrder = remember(detail.items) { mutableStateListOf<MediaItem>().apply { addAll(detail.items) } }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(detail.summary.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { topMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "列表更多操作")
                        }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出 .m3u8") },
                                leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                                onClick = {
                                    topMenuOpen = false
                                    onExportM3u8()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("删除列表", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    topMenuOpen = false
                                    onDeletePlaylist()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Header toggle card: `练习列表模式` (§4.2)
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (detail.summary.isPracticePlaylist) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "练习列表模式",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (detail.summary.isPracticePlaylist) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = "播放列表内任意音频或教学视频时自动展开练习工作台",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (detail.summary.isPracticePlaylist) {
                                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Switch(
                                checked = detail.summary.isPracticePlaylist,
                                onCheckedChange = onTogglePracticeMode,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { if (localOrder.isNotEmpty()) onPlayFromPlaylist(0) },
                            enabled = localOrder.isNotEmpty(),
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (detail.summary.isPracticePlaylist) "全部练习播放" else "全部播放")
                        }
                        FilledTonalButton(
                            onClick = { showAddTracksSheet = true },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("添加曲目/视频")
                        }
                    }
                }
            }

            itemsIndexed(localOrder, key = { _, item -> item.itemId }) { idx, item ->
                var rowMenuOpen by remember { mutableStateOf(false) }
                val isDragged = draggedIndex == idx

                Surface(
                    onClick = { onPlayFromPlaylist(idx) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (item.itemId == currentPlayingId) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    shadowElevation = if (isDragged) 6.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 2f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragAccumulatedY else 0f
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Drag handle `≡` for drag-to-reorder
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(localOrder.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedIndex = idx
                                            dragAccumulatedY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragAccumulatedY += dragAmount.y
                                            val rowHeightPx = 68.dp.toPx()
                                            val currentIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                            if (dragAccumulatedY > rowHeightPx && currentIdx < localOrder.lastIndex) {
                                                val moved = localOrder.removeAt(currentIdx)
                                                localOrder.add(currentIdx + 1, moved)
                                                draggedIndex = currentIdx + 1
                                                dragAccumulatedY -= rowHeightPx
                                            } else if (dragAccumulatedY < -rowHeightPx && currentIdx > 0) {
                                                val moved = localOrder.removeAt(currentIdx)
                                                localOrder.add(currentIdx - 1, moved)
                                                draggedIndex = currentIdx - 1
                                                dragAccumulatedY += rowHeightPx
                                            }
                                        },
                                        onDragEnd = {
                                            draggedIndex = null
                                            dragAccumulatedY = 0f
                                            onReorder(localOrder.map { it.itemId })
                                        },
                                        onDragCancel = {
                                            draggedIndex = null
                                            dragAccumulatedY = 0f
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DragHandle,
                                contentDescription = "长按上下拖拽排序",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            text = "${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    if (item.isVideo) append("视频 · ") else append("${item.artist} · ")
                                    if (item.savedPracticeSpeed != 1.0f) {
                                        append("${String.format("%.2fx", item.savedPracticeSpeed)} · ")
                                    }
                                    if (item.savedSemitones != 0) {
                                        val sign = if (item.savedSemitones > 0) "+" else ""
                                        append("$sign${item.savedSemitones} 半音 · ")
                                    }
                                    if (item.bookmarkCount > 0) {
                                        append("${item.bookmarkCount} 段 · ")
                                    }
                                    append(PracticeMath.formatTimeMs(item.durationMs))
                                },
                                style = MaterialTheme.typography.bodyMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Box {
                            IconButton(onClick = { rowMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "曲目菜单")
                            }
                            DropdownMenu(expanded = rowMenuOpen, onDismissRequest = { rowMenuOpen = false }) {
                                if (idx > 0) {
                                    DropdownMenuItem(
                                        text = { Text("上移一位") },
                                        leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                                        onClick = {
                                            rowMenuOpen = false
                                            val moved = localOrder.removeAt(idx)
                                            localOrder.add(idx - 1, moved)
                                            onReorder(localOrder.map { it.itemId })
                                        },
                                    )
                                }
                                if (idx < localOrder.lastIndex) {
                                    DropdownMenuItem(
                                        text = { Text("下移一位") },
                                        leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                                        onClick = {
                                            rowMenuOpen = false
                                            val moved = localOrder.removeAt(idx)
                                            localOrder.add(idx + 1, moved)
                                            onReorder(localOrder.map { it.itemId })
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("编辑标签信息") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = {
                                        rowMenuOpen = false
                                        onEditTrackMetadata(item)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("从列表移除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.RemoveCircleOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        rowMenuOpen = false
                                        onRemoveItem(item.itemId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddTracksSheet) {
        MultiSelectTracksToAddSheet(
            allItems = allLibraryItems,
            existingIds = detail.items.map { it.itemId }.toSet(),
            onAddSelected = { ids ->
                onAddItems(ids)
                showAddTracksSheet = false
            },
            onDismiss = { showAddTracksSheet = false },
        )
    }
}

@Composable
private fun CreateOrRenamePlaylistDialog(
    initialName: String,
    initialPractice: Boolean,
    isRenameOnly: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var isPractice by remember { mutableStateOf(initialPractice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRenameOnly) "重命名播放列表" else "新建播放列表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("列表名称") },
                    placeholder = { Text("例如：本周乐队排练曲目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isRenameOnly) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { isPractice = !isPractice },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("设为练习列表", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "播放时自动开启练习模式",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = isPractice, onCheckedChange = { isPractice = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, isPractice) },
                enabled = name.isNotBlank(),
            ) {
                Text(if (isRenameOnly) "保存" else "创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MultiSelectTracksToAddSheet(
    allItems: List<MediaItem>,
    existingIds: Set<Long>,
    onAddSelected: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }
    val candidates = remember(allItems, existingIds, query) {
        allItems.filter { it.itemId !in existingIds && it.matchesQuery(query) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("添加音频或教学视频", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { onAddSelected(selectedIds.toList()) },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("添加 (${selectedIds.size})")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("筛选曲目或视频…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(candidates, key = { it.itemId }) { item ->
                    val checked = item.itemId in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable {
                                if (checked) selectedIds.remove(item.itemId) else selectedIds.add(item.itemId)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                if (checked) selectedIds.remove(item.itemId) else selectedIds.add(item.itemId)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = (if (item.isVideo) "视频 · " else "") + "${item.artist} · ${PracticeMath.formatTimeMs(item.durationMs)}",
                                style = MaterialTheme.typography.bodyMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable M3 Bottom Sheet for adding track(s) in 1 tap to an existing
 * normal playlist or Practice Playlist (`练习列表`), or creating a new playlist on the spot.
 */
@Composable
fun AddToPlaylistBottomSheet(
    targetItems: List<MediaItem>,
    playlists: List<PlaylistSummary>,
    onSelectPlaylist: (Long) -> Unit,
    onCreateAndAdd: (name: String, isPractice: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var creatingNew by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "加入播放列表 (${targetItems.size} 首)",
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalButton(
                onClick = { creatingNew = true },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("新建播放列表 / 练习列表")
            }

            playlists.forEach { pl ->
                Surface(
                    onClick = { onSelectPlaylist(pl.playlistId) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "${pl.itemCount} 首",
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (pl.isPracticePlaylist) {
                            PracticeBadgePill("练习")
                        }
                    }
                }
            }
        }
    }

    if (creatingNew) {
        CreateOrRenamePlaylistDialog(
            initialName = "",
            initialPractice = true,
            isRenameOnly = false,
            onConfirm = { name, isPractice ->
                creatingNew = false
                onCreateAndAdd(name, isPractice)
                onDismiss()
            },
            onDismiss = { creatingNew = false },
        )
    }
}
