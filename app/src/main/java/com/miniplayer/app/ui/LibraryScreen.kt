@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.components.GuitarIcon
import com.miniplayer.app.ui.components.MediaArtwork
import com.miniplayer.app.ui.theme.tnum

@Composable
fun LibraryScreen(
    viewModel: MiniPlayerViewModel,
    onAddFolderClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeView by viewModel.libraryView.collectAsState()
    val folderMode by viewModel.folderDisplayMode.collectAsState()
    val searchQuery by viewModel.librarySearchQuery.collectAsState()
    val safRoots by viewModel.safRoots.collectAsState()
    val folderBrowse by viewModel.folderBrowseState.collectAsState()
    val allMediaItems by viewModel.allMediaItems.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val activeAlbum by viewModel.activeAlbum.collectAsState()
    val activeAlbumTracks by viewModel.activeAlbumTracks.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val activeArtist by viewModel.activeArtistDetail.collectAsState()
    val videos by viewModel.videoItems.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val playback by viewModel.playbackSnapshot.collectAsState()

    LaunchedEffect(Unit) {
        if (searchQuery.isNotEmpty()) {
            viewModel.setLibrarySearchQuery("")
        }
    }

    // Preserve independent scroll positions per FilterChip view (§4.1)
    val folderListState = rememberLazyListState()
    val albumGridState = rememberLazyGridState()
    val artistListState = rememberLazyListState()
    val videoListState = rememberLazyListState()

    // Predictive Back handling for secondary drill-down views
    BackHandler(enabled = activeAlbum != null || activeArtist != null || folderBrowse != null) {
        when {
            activeAlbum != null -> viewModel.closeAlbum()
            activeArtist != null -> viewModel.closeArtist()
            folderBrowse != null -> viewModel.navigateFolderUp()
        }
    }

    // Secondary detail screens inside Tab 1
    activeAlbum?.let { album ->
        AlbumDetailContent(
            album = album,
            tracks = activeAlbumTracks,
            currentPlayingId = playback.currentItem?.itemId,
            onBack = { viewModel.closeAlbum() },
            onPlayTrack = { idx ->
                viewModel.playFromContext(activeAlbumTracks, idx, PlayContext.CASUAL)
            },
            onPracticePlayAll = {
                viewModel.playFromContext(activeAlbumTracks, 0, PlayContext.PRACTICE_PLAYLIST)
            },
            onEditTrack = { viewModel.openMetadataEditor(listOf(it)) },
            onAddToPlaylist = { viewModel.openAddToPlaylistPicker(listOf(it)) },
            onBatchEditAlbum = { viewModel.openMetadataEditor(activeAlbumTracks) },
            onBatchAutoMatchAlbum = { viewModel.startBatchAutoCompletion(activeAlbumTracks) },
            modifier = modifier,
        )
        return
    }

    activeArtist?.let { detail ->
        ArtistDetailContent(
            detail = detail,
            currentPlayingId = playback.currentItem?.itemId,
            onBack = { viewModel.closeArtist() },
            onOpenAlbum = { album ->
                viewModel.closeArtist()
                viewModel.openAlbum(album)
            },
            onPlayTrack = { idx ->
                viewModel.playFromContext(detail.tracks, idx, PlayContext.CASUAL)
            },
            onEditTrack = { viewModel.openMetadataEditor(listOf(it)) },
            onAddToPlaylist = { viewModel.openAddToPlaylistPicker(listOf(it)) },
            modifier = modifier,
        )
        return
    }

    val currentSort = SortOrder.NAME_ASC

    Scaffold(
        modifier = modifier,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Text("Play", style = MaterialTheme.typography.titleLarge)
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.setSettingsVisible(true) },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    },
                )

                // Mandatory Single-Select 4 FilterChips (§4.1)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chips = listOf(
                        LibraryView.FOLDERS to "文件夹",
                        LibraryView.ALBUMS to "专辑",
                        LibraryView.ARTISTS to "歌手",
                        LibraryView.VIDEOS to "视频",
                    )
                    chips.forEach { (view, label) ->
                        val selected = activeView == view
                        FilterChip(
                            selected = selected,
                            onClick = {
                                // Mandatory single-selection: re-tapping active chip keeps it selected
                                if (!selected) {
                                    viewModel.selectLibraryView(view)
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        )
                    }
                }

                if (scanProgress.isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            if (activeView == LibraryView.FOLDERS && folderBrowse == null && safRoots.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onAddFolderClick,
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "添加文件夹")
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (activeView) {
                LibraryView.FOLDERS -> {
                    val browse = folderBrowse
                    if (browse == null) {
                        FoldersRootList(
                            roots = safRoots,
                            searchQuery = searchQuery,
                            folderMode = folderMode,
                            listState = folderListState,
                            onToggleMode = { viewModel.toggleFolderDisplayMode() },
                            onAddFolder = onAddFolderClick,
                            onOpenRoot = { root ->
                                if (root.mountState == MountState.PERMISSION_REVOKED) {
                                    onAddFolderClick()
                                } else {
                                    viewModel.openFolder(root.rootId, "")
                                }
                            },
                            onTogglePractice = { root ->
                                viewModel.setFolderPracticeFlag(
                                    rootId = root.rootId,
                                    relPath = "",
                                    folderUri = root.treeUri,
                                    isPractice = !root.isPracticeFolder,
                                )
                            },
                            onToggleExcludeFromGrouping = { root ->
                                viewModel.setFolderExcludeFromGroupingFlag(
                                    rootId = root.rootId,
                                    relPath = "",
                                    folderUri = root.treeUri,
                                    isExcluded = !root.isExcludedFromAutoGrouping,
                                )
                            },
                            onAddAllRootToPlaylist = { root ->
                                viewModel.openAddFolderToPlaylistPicker(root.rootId, "")
                            },
                            onBatchAutoMatchRoot = { root ->
                                val rootItems = viewModel.allMediaItems.value.filter { it.rootId == root.rootId }
                                viewModel.startBatchAutoCompletion(rootItems)
                            },
                            onBatchEditRoot = { root ->
                                val rootItems = viewModel.allMediaItems.value.filter { it.rootId == root.rootId }
                                viewModel.openMetadataEditor(rootItems)
                            },
                            onRescanRoot = { root -> viewModel.rescanRoot(root.rootId) },
                            onRemoveRoot = { root -> viewModel.removeSafRoot(root.rootId) },
                        )
                    } else {
                        FolderBrowseView(
                            browse = browse,
                            folderMode = folderMode,
                            sortOrder = currentSort,
                            searchQuery = searchQuery,
                            currentPlayingId = playback.currentItem?.itemId,
                            onNavigateUp = { viewModel.navigateFolderUp() },
                            onBreadcrumbClick = { relPath -> viewModel.openFolder(browse.root.rootId, relPath) },
                            onAddAllCurrentFolderToPlaylist = {
                                viewModel.openAddFolderToPlaylistPicker(browse.root.rootId, browse.currentRelPath)
                            },
                            onToggleMode = { viewModel.toggleFolderDisplayMode() },
                            onOpenSubfolder = { sub -> viewModel.openFolder(sub.rootId, sub.relPath) },
                            onToggleSubfolderPractice = { sub ->
                                viewModel.setFolderPracticeFlag(
                                    rootId = sub.rootId,
                                    relPath = sub.relPath,
                                    folderUri = sub.folderUri,
                                    isPractice = !sub.isPracticeFolder,
                                )
                            },
                            onToggleSubfolderExcludeFromGrouping = { sub ->
                                viewModel.setFolderExcludeFromGroupingFlag(
                                    rootId = sub.rootId,
                                    relPath = sub.relPath,
                                    folderUri = sub.folderUri,
                                    isExcluded = !sub.isExcludedFromAutoGrouping,
                                )
                            },
                            onAddAllSubfolderToPlaylist = { sub ->
                                viewModel.openAddFolderToPlaylistPicker(sub.rootId, sub.relPath)
                            },
                            onBatchAutoMatchSubfolder = { sub ->
                                val subItems = viewModel.allMediaItems.value.filter {
                                    it.rootId == sub.rootId &&
                                        (it.parentRelPath == sub.relPath || it.parentRelPath.startsWith("${sub.relPath}/"))
                                }
                                viewModel.startBatchAutoCompletion(subItems)
                            },
                            onBatchEditSubfolder = { sub ->
                                val subItems = viewModel.allMediaItems.value.filter {
                                    it.rootId == sub.rootId &&
                                        (it.parentRelPath == sub.relPath || it.parentRelPath.startsWith("${sub.relPath}/"))
                                }
                                viewModel.openMetadataEditor(subItems)
                            },
                            onPlayMediaItem = { sortedItems, idx ->
                                val item = sortedItems[idx]
                                val ctx = if (browse.isCurrentPracticeFolder || item.isInPracticeFolder) {
                                    PlayContext.PRACTICE_FOLDER
                                } else {
                                    PlayContext.CASUAL
                                }
                                viewModel.playFromContext(sortedItems, idx, ctx)
                            },
                            onEditTrack = { viewModel.openMetadataEditor(listOf(it)) },
                            onAddToPlaylist = { viewModel.openAddToPlaylistPicker(listOf(it)) },
                        )
                    }
                }
                LibraryView.ALBUMS -> {
                    AlbumsGridContent(
                        albums = albums,
                        allMediaItems = allMediaItems,
                        sortOrder = currentSort,
                        searchQuery = searchQuery,
                        gridState = albumGridState,
                        onOpenAlbum = { viewModel.openAlbum(it) },
                        onAddFolder = onAddFolderClick,
                    )
                }
                LibraryView.ARTISTS -> {
                    ArtistsListContent(
                        artists = artists,
                        sortOrder = currentSort,
                        searchQuery = searchQuery,
                        listState = artistListState,
                        onOpenArtist = { viewModel.openArtist(it) },
                        onAddFolder = onAddFolderClick,
                    )
                }
                LibraryView.VIDEOS -> {
                    VideosListContent(
                        videos = videos,
                        sortOrder = currentSort,
                        searchQuery = searchQuery,
                        currentPlayingId = playback.currentItem?.itemId,
                        listState = videoListState,
                        onPlayVideo = { sortedVideos, idx ->
                            val target = sortedVideos[idx]
                            val ctx = if (target.isInPracticeFolder) PlayContext.PRACTICE_FOLDER else PlayContext.CASUAL
                            viewModel.playFromContext(sortedVideos, idx, ctx, openFullPlayer = true)
                        },
                        onPracticeVideo = { sortedVideos, idx ->
                            viewModel.playFromContext(sortedVideos, idx, PlayContext.PRACTICE_FOLDER, openFullPlayer = true)
                        },
                        onEditVideo = { viewModel.openMetadataEditor(listOf(it)) },
                        onAddToPlaylist = { viewModel.openAddToPlaylistPicker(listOf(it)) },
                        onAddFolder = onAddFolderClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersRootList(
    roots: List<SafRoot>,
    searchQuery: String,
    folderMode: FolderDisplayMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleMode: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenRoot: (SafRoot) -> Unit,
    onTogglePractice: (SafRoot) -> Unit,
    onToggleExcludeFromGrouping: (SafRoot) -> Unit,
    onAddAllRootToPlaylist: (SafRoot) -> Unit,
    onBatchAutoMatchRoot: (SafRoot) -> Unit,
    onBatchEditRoot: (SafRoot) -> Unit,
    onRescanRoot: (SafRoot) -> Unit,
    onRemoveRoot: (SafRoot) -> Unit,
) {
    if (roots.isEmpty()) {
        EmptyLibraryPlaceholder(
            title = "只扫描你指定的本地文件夹",
            subtitle = "绝不申请全局存储权限，绝不把系统铃声、语音碎片混入曲库。",
            actionLabel = "+ 添加本地文件夹",
            onAction = onAddFolder,
        )
        return
    }

    val filtered = remember(roots, searchQuery) {
        if (searchQuery.isBlank()) roots
        else roots.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.treeDocId.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已添加的文件夹 (${roots.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onToggleMode,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (folderMode == FolderDisplayMode.TREE) {
                            Icons.Outlined.AccountTree
                        } else {
                            Icons.AutoMirrored.Outlined.ViewList
                        },
                        contentDescription = if (folderMode == FolderDisplayMode.TREE) {
                            "当前层级目录树，点击切换平铺视图"
                        } else {
                            "当前平铺视图，点击切换层级目录树"
                        },
                    )
                }
            }
        }

        items(filtered, key = { it.rootId }) { root ->
            var menuExpanded by remember { mutableStateOf(false) }
            var confirmRemove by remember { mutableStateOf(false) }

            Surface(
                onClick = { onOpenRoot(root) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (root.isPracticeFolder) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (root.isPracticeFolder) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = root.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (root.isPracticeFolder) {
                                PracticeBadgePill("练习")
                            }
                            if (root.isExcludedFromAutoGrouping) {
                                PracticeBadgePill("不自动归类")
                            }
                            when (root.mountState) {
                                MountState.OFFLINE_UNMOUNTED -> OfflineWarningPill("未挂载")
                                MountState.PERMISSION_REVOKED -> OfflineWarningPill("需重新授权")
                                MountState.MOUNTED -> Unit
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = buildString {
                                append(root.treeDocId.replace(':', ' ').trim())
                                append(" · ")
                                append("${root.itemCount} 个媒体")
                                if (!root.isWritable) append(" · 只读")
                            },
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "文件夹菜单")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (root.isPracticeFolder) "取消练习文件夹" else "设为练习文件夹")
                                },
                                leadingIcon = { Icon(GuitarIcon, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePractice(root)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (root.isExcludedFromAutoGrouping) "取消不自动归类" else "设为不自动归类")
                                },
                                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onToggleExcludeFromGrouping(root)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("全部添加到播放列表") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onAddAllRootToPlaylist(root)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量自动补全标签与封面") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onBatchAutoMatchRoot(root)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量编辑标签") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onBatchEditRoot(root)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("增量重新扫描") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRescanRoot(root)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text("移除此根文件夹", color = MaterialTheme.colorScheme.error)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    confirmRemove = true
                                },
                            )
                        }
                    }
                }
            }

            if (confirmRemove) {
                AlertDialog(
                    onDismissRequest = { confirmRemove = false },
                    title = { Text("移除「${root.displayName}」？") },
                    text = { Text("仅从 MiniPlayer 媒体库索引移除，绝不会删除手机或存储盘上的任何物理文件。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmRemove = false
                                onRemoveRoot(root)
                            },
                        ) {
                            Text("移除索引", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRemove = false }) { Text("取消") }
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderBrowseView(
    browse: FolderBrowseState,
    folderMode: FolderDisplayMode,
    sortOrder: SortOrder,
    searchQuery: String,
    currentPlayingId: Long?,
    onNavigateUp: () -> Unit,
    onBreadcrumbClick: (String) -> Unit,
    onAddAllCurrentFolderToPlaylist: () -> Unit,
    onToggleMode: () -> Unit,
    onOpenSubfolder: (SubfolderEntry) -> Unit,
    onToggleSubfolderPractice: (SubfolderEntry) -> Unit,
    onToggleSubfolderExcludeFromGrouping: (SubfolderEntry) -> Unit,
    onAddAllSubfolderToPlaylist: (SubfolderEntry) -> Unit,
    onBatchAutoMatchSubfolder: (SubfolderEntry) -> Unit,
    onBatchEditSubfolder: (SubfolderEntry) -> Unit,
    onPlayMediaItem: (List<MediaItem>, Int) -> Unit,
    onEditTrack: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
) {
    val filteredSubfolders = remember(browse.subfolders, searchQuery, folderMode) {
        if (folderMode == FolderDisplayMode.FLAT) emptyList()
        else browse.subfolders
            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
            .sortedWith { a, b -> compareNaturalStrings(a.name, b.name) }
    }

    val sortedItems = remember(browse.mediaItems, sortOrder, searchQuery) {
        val base = if (searchQuery.isBlank()) {
            browse.mediaItems
        } else {
            browse.mediaItems.filter { it.matchesQuery(searchQuery) }
        }
        sortMediaItems(base, sortOrder)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // Breadcrumb navigation + Hierarchy/Flat toggle (§4.1A)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上级目录")
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onBreadcrumbClick("") },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(browse.root.displayName, fontWeight = FontWeight.SemiBold)
                        }
                        browse.breadcrumbs.forEach { (segmentName, relPath) ->
                            if (relPath.isNotEmpty()) {
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    onClick = { onBreadcrumbClick(relPath) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) {
                                    Text(segmentName)
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = onAddAllCurrentFolderToPlaylist,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                            contentDescription = "全部添加到播放列表",
                        )
                    }
                    IconButton(
                        onClick = onToggleMode,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (folderMode == FolderDisplayMode.TREE) {
                                Icons.Outlined.AccountTree
                            } else {
                                Icons.AutoMirrored.Outlined.ViewList
                            },
                            contentDescription = if (folderMode == FolderDisplayMode.TREE) {
                                "当前层级目录树，点击切换平铺视图"
                            } else {
                                "当前平铺视图，点击切换层级目录树"
                            },
                        )
                    }
                }

                if (browse.isCurrentPracticeFolder) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = GuitarIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "当前为练习文件夹：点播任意曲目/视频将自动展开练习工作台",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                } else if (browse.isCurrentExcludedFromAutoGrouping) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "当前文件夹已开启「不自动归类」：此目录下的文件不会显示在专辑、歌手、视频中",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // Subfolders always pinned above media files (§4.1)
        items(filteredSubfolders, key = { "sub_${it.relPath}" }) { sub ->
            var menuOpen by remember { mutableStateOf(false) }
            Surface(
                onClick = { onOpenSubfolder(sub) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (sub.isPracticeFolder) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (sub.isPracticeFolder) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = sub.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (sub.isPracticeFolder) {
                                PracticeBadgePill("练习")
                            }
                            if (sub.isExcludedFromAutoGrouping) {
                                PracticeBadgePill("不自动归类")
                            }
                        }
                        Text(
                            text = "${sub.itemCount} 首媒体",
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "子文件夹菜单")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (sub.isPracticeFolder) "取消练习文件夹" else "设为练习文件夹")
                                },
                                leadingIcon = { Icon(GuitarIcon, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onToggleSubfolderPractice(sub)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (sub.isExcludedFromAutoGrouping) "取消不自动归类" else "设为不自动归类")
                                },
                                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onToggleSubfolderExcludeFromGrouping(sub)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("全部添加到播放列表") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAddAllSubfolderToPlaylist(sub)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量自动补全标签与封面") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBatchAutoMatchSubfolder(sub)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量编辑标签") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBatchEditSubfolder(sub)
                                },
                            )
                        }
                    }
                }
            }
        }

        itemsIndexed(sortedItems, key = { _, item -> "item_${item.itemId}" }) { idx, item ->
            MediaTrackRow(
                item = item,
                isPlayingCurrent = item.itemId == currentPlayingId,
                onClick = { onPlayMediaItem(sortedItems, idx) },
                onEditMetadata = { onEditTrack(item) },
                onAddToPlaylist = { onAddToPlaylist(item) },
            )
        }

        if (filteredSubfolders.isEmpty() && sortedItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "此目录暂无匹配的本地音频或视频文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumsGridContent(
    albums: List<AlbumSummary>,
    allMediaItems: List<MediaItem>,
    sortOrder: SortOrder,
    searchQuery: String,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onAddFolder: () -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyLibraryPlaceholder(
            title = "暂无专辑归类",
            subtitle = "添加本地音乐文件夹后，将按 Album + AlbumArtist 自动聚合并分离「未知专辑」。",
            actionLabel = "+ 添加本地文件夹",
            onAction = onAddFolder,
        )
        return
    }

    val sortedAlbums = remember(albums, allMediaItems, sortOrder, searchQuery) {
        val matching = if (searchQuery.isBlank()) {
            albums
        } else {
            albums.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
        val (unknown, known) = matching.partition { it.isUnknownAlbum }
        val orderedKnown = when (sortOrder) {
            SortOrder.ARTIST_ASC -> known.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            SortOrder.YEAR_DESC -> known.sortedByDescending { it.year ?: 0 }
            SortOrder.DATE_MODIFIED_DESC -> {
                val maxByPair = HashMap<String, Long>()
                val maxByTitle = HashMap<String, Long>()
                for (item in allMediaItems) {
                    if (item.isVideo || item.isInPracticeFolder || item.isExcludedFromAutoGrouping) continue
                    val albumName = item.album?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val artistName = (item.albumArtist?.takeIf { it.isNotBlank() } ?: item.artist).trim()
                    val pairKey = "${albumName.lowercase()}|${artistName.lowercase()}"
                    val titleKey = albumName.lowercase()
                    val prevPair = maxByPair[pairKey] ?: 0L
                    if (item.lastModified > prevPair) maxByPair[pairKey] = item.lastModified
                    val prevTitle = maxByTitle[titleKey] ?: 0L
                    if (item.lastModified > prevTitle) maxByTitle[titleKey] = item.lastModified
                }
                known.sortedWith(
                    compareByDescending<AlbumSummary> { album ->
                        val pairKey = "${album.title.trim().lowercase()}|${album.artist.trim().lowercase()}"
                        maxByPair[pairKey]
                            ?: maxByPair[album.albumKey.lowercase()]
                            ?: maxByTitle[album.title.trim().lowercase()]
                            ?: 0L
                    }.thenComparator { a, b -> compareNaturalStrings(a.title, b.title) },
                )
            }
            else -> known.sortedWith { a, b -> compareNaturalStrings(a.title, b.title) }
        }
        // `未知专辑` bucket always pinned at the very end (§4.1B)
        orderedKnown + unknown
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(sortedAlbums, key = { it.albumKey }) { album ->
            Surface(
                onClick = { onOpenAlbum(album) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        MediaArtwork(
                            coverPathOrUri = album.coverPathOrUri,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxSize(),
                            iconSize = 40.dp,
                            contentDescription = album.title,
                        )
                        if (album.isUnknownAlbum) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = "待补全",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (album.isUnknownAlbum) "${album.title} (${album.trackCount} 首)" else album.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (album.isUnknownAlbum) "无内嵌专辑标签 · 点击批量识别" else "${album.artist} · ${album.trackCount} 首",
                        style = MaterialTheme.typography.bodyMedium.tnum(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistsListContent(
    artists: List<ArtistSummary>,
    sortOrder: SortOrder,
    searchQuery: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenArtist: (ArtistSummary) -> Unit,
    onAddFolder: () -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyLibraryPlaceholder(
            title = "暂无歌手索引",
            subtitle = "添加音乐文件夹后，将自动智能拆分合唱与多歌手标签。",
            actionLabel = "+ 添加本地文件夹",
            onAction = onAddFolder,
        )
        return
    }

    val sorted = remember(artists, sortOrder, searchQuery) {
        val base = if (searchQuery.isBlank()) {
            artists
        } else {
            artists.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
        when (sortOrder) {
            SortOrder.TRACK_COUNT_DESC -> base.sortedByDescending { it.trackCount }
            else -> base.sortedWith { a, b -> compareNaturalStrings(a.displayName, b.displayName) }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorted, key = { it.artistNorm }) { artist ->
            Surface(
                onClick = { onOpenArtist(artist) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaArtwork(
                        coverPathOrUri = artist.coverPathOrUri,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                        fallbackIcon = Icons.Outlined.Person,
                        contentDescription = artist.displayName,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${artist.albumCount} 张专辑 · ${artist.trackCount} 首单曲",
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideosListContent(
    videos: List<MediaItem>,
    sortOrder: SortOrder,
    searchQuery: String,
    currentPlayingId: Long?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlayVideo: (List<MediaItem>, Int) -> Unit,
    onPracticeVideo: (List<MediaItem>, Int) -> Unit,
    onEditVideo: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onAddFolder: () -> Unit,
) {
    if (videos.isEmpty()) {
        EmptyLibraryPlaceholder(
            title = "暂无本地教学或音乐视频",
            subtitle = "支持 .mp4 / .mkv / .webm / .mov，提供双指 4x 放大看指板、镜像翻转与 A-B 段落循环。",
            actionLabel = "+ 添加视频文件夹",
            onAction = onAddFolder,
        )
        return
    }

    val sortedVideos = remember(videos, sortOrder, searchQuery) {
        val base = if (searchQuery.isBlank()) {
            videos
        } else {
            videos.filter { it.matchesQuery(searchQuery) }
        }
        when (sortOrder) {
            SortOrder.RECENT_PROGRESS_DESC -> base.sortedByDescending { it.lastPositionMs }
            SortOrder.DURATION_DESC -> base.sortedByDescending { it.durationMs }
            SortOrder.DATE_MODIFIED_DESC -> base.sortedByDescending { it.lastModified }
            else -> base.sortedWith { a, b -> compareNaturalStrings(a.title, b.title) }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(sortedVideos, key = { _, v -> v.itemId }) { idx, video ->
            var menuOpen by remember { mutableStateOf(false) }
            val progressFraction = if (video.durationMs > 0L) {
                (video.lastPositionMs.toFloat() / video.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            Surface(
                onClick = { onPlayVideo(sortedVideos, idx) },
                shape = RoundedCornerShape(18.dp),
                color = if (video.itemId == currentPlayingId) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 16:9 Frame Thumbnail + Duration Overlay (§4.1B)
                    Box(
                        modifier = Modifier
                            .width(124.dp)
                            .aspectRatio(16f / 9f),
                    ) {
                        MediaArtwork(
                            coverPathOrUri = video.coverPathOrUri,
                            mediaDocumentUri = video.documentUri,
                            isVideo = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = video.title,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                        ) {
                            Text(
                                text = PracticeMath.formatTimeMs(video.durationMs),
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            video.resolutionBadge?.let { badge ->
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelMedium.tnum(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (video.bookmarkCount > 0) {
                                PracticeBadgePill("${video.bookmarkCount} 段书签")
                            } else if (video.hasPracticeRecord || video.isInPracticeFolder) {
                                PracticeBadgePill("练习记录")
                            }
                        }
                        if (progressFraction > 0.01f) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                            Text(
                                text = "已看 ${PracticeMath.formatTimeMs(video.lastPositionMs)}",
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "视频菜单")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("以练习模式打开") },
                                leadingIcon = { Icon(GuitarIcon, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onPracticeVideo(sortedVideos, idx)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("加入播放列表 / 练习列表") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAddToPlaylist(video)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑标签信息") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEditVideo(video)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    album: AlbumSummary,
    tracks: List<MediaItem>,
    currentPlayingId: Long?,
    onBack: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onPracticePlayAll: () -> Unit,
    onEditTrack: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onBatchEditAlbum: () -> Unit,
    onBatchAutoMatchAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = album.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "专辑菜单")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("批量自动联网补全") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBatchAutoMatchAlbum()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("批量编辑专辑标签") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBatchEditAlbum()
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
                    // Prominent Auto-Match Banner for `未知专辑` (§4.1B)
                    if (album.isUnknownAlbum && tracks.isNotEmpty()) {
                        Surface(
                            onClick = onBatchAutoMatchAlbum,
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "自动联网识别全部曲目",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = "免 Key 检索 MusicBrainz / iTunes / LRCLIB，一键补齐标题、专辑与高清封面",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            MediaArtwork(
                                coverPathOrUri = album.coverPathOrUri,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.size(96.dp),
                                iconSize = 36.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(album.title, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    text = buildString {
                                        append(album.artist)
                                        album.year?.let { append(" · $it") }
                                        append(" · ${tracks.size} 首")
                                    },
                                    style = MaterialTheme.typography.bodyMedium.tnum(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { if (tracks.isNotEmpty()) onPlayTrack(0) },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("全部播放")
                        }
                        FilledTonalButton(
                            onClick = onPracticePlayAll,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Icon(GuitarIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("练习播放")
                        }
                    }
                }
            }

            itemsIndexed(tracks, key = { _, t -> t.itemId }) { idx, track ->
                MediaTrackRow(
                    item = track,
                    isPlayingCurrent = track.itemId == currentPlayingId,
                    leadingNumber = track.trackNumber ?: (idx + 1),
                    onClick = { onPlayTrack(idx) },
                    onEditMetadata = { onEditTrack(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    detail: ArtistDetail,
    currentPlayingId: Long?,
    onBack: () -> Unit,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onEditTrack: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail.artist.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (detail.albums.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "参与专辑 (${detail.albums.size})",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(detail.albums, key = { it.albumKey }) { alb ->
                                Surface(
                                    onClick = { onOpenAlbum(alb) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.width(136.dp),
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        MediaArtwork(
                                            coverPathOrUri = alb.coverPathOrUri,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = alb.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${alb.trackCount} 首",
                                            style = MaterialTheme.typography.labelMedium.tnum(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "全部单曲 (${detail.tracks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            itemsIndexed(detail.tracks, key = { _, t -> t.itemId }) { idx, track ->
                MediaTrackRow(
                    item = track,
                    isPlayingCurrent = track.itemId == currentPlayingId,
                    onClick = { onPlayTrack(idx) },
                    onEditMetadata = { onEditTrack(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTrackRow(
    item: MediaItem,
    isPlayingCurrent: Boolean,
    leadingNumber: Int? = null,
    onClick: () -> Unit,
    onEditMetadata: () -> Unit,
    onAddToPlaylist: () -> Unit,
    extraMenuContent: (@Composable ColumnScope.(() -> Unit) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isPlayingCurrent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingNumber != null) {
                Text(
                    text = leadingNumber.toString(),
                    style = MaterialTheme.typography.bodyMedium.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
            } else {
                MediaArtwork(
                    coverPathOrUri = item.coverPathOrUri,
                    mediaDocumentUri = item.documentUri,
                    isVideo = item.isVideo,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(46.dp),
                    iconSize = 22.dp,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlayingCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = buildString {
                            if (item.isVideo) append("视频 · ")
                            append(item.artist)
                            if (item.durationMs > 0L) {
                                append(" · ")
                                append(PracticeMath.formatTimeMs(item.durationMs))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium.tnum(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.bookmarkCount > 0) {
                        PracticeBadgePill("${item.bookmarkCount} 段")
                    } else if (item.savedPracticeSpeed != 1.0f || item.savedSemitones != 0) {
                        PracticeBadgePill(String.format("%.2fx", item.savedPracticeSpeed).trimEnd('0').trimEnd('.'))
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("加入播放列表 / 练习列表") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑标签信息") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEditMetadata()
                        },
                    )
                    extraMenuContent?.invoke(this) { menuOpen = false }
                }
            }
        }
    }
}

@Composable
fun PracticeBadgePill(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.tnum(),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun OfflineWarningPill(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun EmptyLibraryPlaceholder(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onAction,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text(actionLabel)
        }
    }
}

internal fun MediaItem.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) ||
        artist.contains(q, ignoreCase = true) ||
        (album?.contains(q, ignoreCase = true) == true) ||
        fileName.contains(q, ignoreCase = true)
}

internal fun sortMediaItems(items: List<MediaItem>, order: SortOrder): List<MediaItem> =
    when (order) {
        SortOrder.DATE_MODIFIED_DESC -> items.sortedByDescending { it.lastModified }
        SortOrder.DURATION_DESC -> items.sortedByDescending { it.durationMs }
        else -> items.sortedWith { a, b ->
            val cmp = compareNaturalStrings(a.fileName, b.fileName)
            if (cmp != 0) cmp else compareNaturalStrings(a.title, b.title)
        }
    }

/**
 * Natural alphanumeric comparator ensuring `Track 2` sorts before `Track 10`.
 */
internal fun compareNaturalStrings(a: String, b: String): Int {
    var ia = 0
    var ib = 0
    while (ia < a.length && ib < b.length) {
        val ca = a[ia]
        val cb = b[ib]
        if (ca.isDigit() && cb.isDigit()) {
            var ea = ia
            while (ea < a.length && a[ea].isDigit()) ea++
            var eb = ib
            while (eb < b.length && b[eb].isDigit()) eb++
            val numA = a.substring(ia, ea).trimStart('0')
            val numB = b.substring(ib, eb).trimStart('0')
            if (numA.length != numB.length) return numA.length.compareTo(numB.length)
            val cmp = numA.compareTo(numB)
            if (cmp != 0) return cmp
            ia = ea
            ib = eb
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            ia++
            ib++
        }
    }
    return a.length.compareTo(b.length)
}
