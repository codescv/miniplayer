@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.model.*
import com.miniplayer.app.ui.components.MediaArtwork
import com.miniplayer.app.ui.theme.AlbumPaletteExtractor
import com.miniplayer.app.ui.theme.rememberAnimatedColorScheme
import com.miniplayer.app.ui.theme.tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingSheet(
    viewModel: MiniPlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by viewModel.playbackSnapshot.collectAsState()
    val item = snapshot.currentItem ?: run {
        onDismiss()
        return
    }
    val practiceModeActive by viewModel.isPracticeModeActive.collectAsState()
    val practiceState by viewModel.practiceState.collectAsState()
    val speedTrainer by viewModel.speedTrainer.collectAsState()
    val completedLoops by viewModel.completedLoopCount.collectAsState()
    val usingFastVideoSeek by viewModel.usingFastVideoSeek.collectAsState()
    val bookmarks by viewModel.currentTrackBookmarks.collectAsState()
    val activeBookmarkId by viewModel.activeBookmarkId.collectAsState()
    val countInProgress by viewModel.countInProgress.collectAsState()
    val waveformBars by viewModel.waveformBars.collectAsState()
    val lyrics by viewModel.currentLyrics.collectAsState()
    val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()

    val playerController = viewModel.playerController
    val loopEngine = playerController.loopEngine

    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showLyricsView by remember(item.itemId) { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showFileDetailsDialog by remember { mutableStateOf(false) }
    var forcedLandscape by remember { mutableStateOf(false) }

    // Scoped Album Tonal Palette with 350ms smooth animated transition (§3.1)
    val darkTheme = isSystemInDarkTheme()
    val fallbackScheme = MaterialTheme.colorScheme
    var seedColorArgb by remember(item.itemId) { mutableStateOf<Int?>(null) }
    var loadedCoverBitmap by remember(item.itemId) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(loadedCoverBitmap) {
        val bmp = loadedCoverBitmap
        seedColorArgb = if (bmp != null) {
            withContext(Dispatchers.Default) { AlbumPaletteExtractor.extractSeedColor(bmp) }
        } else {
            null
        }
    }

    val targetPlayerScheme = remember(seedColorArgb, darkTheme, fallbackScheme) {
        val seed = seedColorArgb
        if (seed != null) {
            AlbumPaletteExtractor.buildTonalSpotScheme(seed, darkTheme, fallbackScheme)
        } else {
            fallbackScheme
        }
    }
    val animatedScheme = rememberAnimatedColorScheme(targetPlayerScheme, durationMillis = 350)

    DisposableEffect(Unit) {
        onDispose {
            if (forcedLandscape) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Predictive Back: exit forced landscape or close NowPlayingSheet instead of exiting app (§3.1 #4)
    BackHandler {
        if (forcedLandscape) {
            forcedLandscape = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            onDismiss()
        }
    }

    MaterialTheme(colorScheme = animatedScheme, typography = MaterialTheme.typography) {
        // Landscape Video Mode (Casual Landscape vs Practice Landscape HUD per §4.3B)
        if (item.isVideo && isLandscape) {
            LandscapeVideoPlayerView(
                playerController = playerController,
                snapshot = snapshot,
                practiceModeActive = practiceModeActive,
                practiceState = practiceState,
                bookmarks = bookmarks,
                activeBookmarkId = activeBookmarkId,
                completedLoopCount = completedLoops,
                waveformBars = waveformBars,
                countInProgress = countInProgress,
                onBack = {
                    if (forcedLandscape) {
                        forcedLandscape = false
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        onDismiss()
                    }
                },
                onTogglePracticeMode = { playerController.togglePracticeMode() },
                onOpenSleepTimer = { showSleepTimerSheet = true },
                onExitFullscreenOrientation = {
                    forcedLandscape = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity?.window?.decorView?.postDelayed({
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }, 1500L)
                },
                onSaveQuickBookmark = { label ->
                    val a = practiceState.activeAMs
                    val b = practiceState.activeBMs
                    if (a != null && b != null) {
                        viewModel.savePracticeBookmark(label, a, b)
                    }
                },
            )
        } else {
            // Portrait Unified Player (Casual Mode vs Progressive Disclosure Practice Mode)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = modifier
                    .fillMaxSize()
                    .pointerInput(item.isVideo, practiceState.videoZoom) {
                        // Swipe down to collapse when not zoomed into a video (§5.3 #1)
                        if (!item.isVideo || practiceState.videoZoom <= 1.02f) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 36f) {
                                    onDismiss()
                                }
                            }
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    // Top Bar
                    NowPlayingTopBar(
                        item = item,
                        practiceModeActive = practiceModeActive,
                        practiceState = practiceState,
                        usingFastVideoSeek = usingFastVideoSeek,
                        sleepTimerActive = snapshot.sleepTimerRemainingMs != null || snapshot.pauseAtEndOfCurrentTrack,
                        onCollapse = onDismiss,
                        onTogglePracticeMode = { playerController.togglePracticeMode() },
                        onToggleVideoMirror = { loopEngine.toggleVideoMirror() },
                        onEnterLandscapeVideo = {
                            forcedLandscape = true
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        },
                        onOpenSleepTimer = { showSleepTimerSheet = true },
                        onOpenQueue = { showQueueSheet = true },
                        onEditMetadata = { viewModel.openMetadataEditor(listOf(item)) },
                        onAddToPlaylist = { viewModel.openAddToPlaylistPicker(listOf(item)) },
                        onShowFileDetails = { showFileDetailsDialog = true },
                        onSetVideoSeekMode = { loopEngine.setVideoSeekMode(it) },
                    )

                    if (!practiceModeActive) {
                        // === LAYER 1: CASUAL MODE (Ultra-Clean M3 Player, §4.3A Left) ===
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Cover Art / Video Canvas / Synced .lrc Lyrics View
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (showLyricsView && !item.isVideo) {
                                    SyncedLyricsCanvas(
                                        lyrics = lyrics,
                                        positionMs = snapshot.positionMs,
                                        isLoading = isLoadingLyrics,
                                        onSeekToLine = { playerController.seekTo(it) },
                                        onFetchOnline = { viewModel.fetchLyricsOnlineIfNeeded() },
                                        onTapBackground = { showLyricsView = false },
                                    )
                                } else if (item.isVideo) {
                                    VideoPracticeSurface(
                                        playerController = playerController,
                                        aspectRatio = snapshot.videoAspectRatio,
                                        zoom = practiceState.videoZoom,
                                        mirrorHorizontal = practiceState.videoMirror,
                                        countInProgress = countInProgress,
                                        onZoomChanged = { z, persist -> loopEngine.setVideoZoom(z, persist) },
                                        onSkipCountIn = { playerController.metronome.skipImmediately() },
                                        cornerRadiusDp = 20,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    MediaArtwork(
                                        coverPathOrUri = item.coverPathOrUri,
                                        mediaDocumentUri = item.documentUri,
                                        isVideo = false,
                                        shape = RoundedCornerShape(24.dp),
                                        maxDimensionPx = 800,
                                        iconSize = 72.dp,
                                        onBitmapLoaded = { loadedCoverBitmap = it },
                                        modifier = Modifier
                                            .fillMaxWidth(0.88f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp))
                                            .clickable { showLyricsView = true },
                                    )
                                }
                            }

                            // Track Title & Artist
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${item.artist} · ${item.displayAlbum}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // Action Pill Row: `[ 🎯 练习模式 (· 含 N 段) ]` · `[ 词 ]` · `[ + 列表 ]` (§4.3A & §5.1)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val practiceHintLabel = when {
                                    bookmarks.isNotEmpty() -> "🎯 练习模式 · 含 ${bookmarks.size} 段"
                                    practiceState.isNonDefault || item.hasPracticeRecord -> "🎯 练习模式 · 含记录"
                                    else -> "🎯 练习模式"
                                }
                                val hasHistory = bookmarks.isNotEmpty() || practiceState.isNonDefault || item.hasPracticeRecord
                                Button(
                                    onClick = { playerController.togglePracticeMode() },
                                    colors = if (hasHistory) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    } else {
                                        ButtonDefaults.filledTonalButtonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .defaultMinSize(minHeight = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Text(practiceHintLabel, maxLines = 1)
                                }

                                if (!item.isVideo) {
                                    FilterChip(
                                        selected = showLyricsView,
                                        onClick = { showLyricsView = !showLyricsView },
                                        label = { Text("词") },
                                        modifier = Modifier.defaultMinSize(minWidth = 54.dp, minHeight = 48.dp),
                                    )
                                }

                                OutlinedButton(
                                    onClick = { viewModel.openAddToPlaylistPicker(listOf(item)) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("列表")
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Scrubbing Timeline (`tnum`)
                            val sliderValue = if (snapshot.durationMs > 0L) {
                                (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { frac ->
                                        playerController.seekTo((frac * snapshot.durationMs).toLong())
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = PracticeMath.formatTimeMs(snapshot.positionMs),
                                        style = MaterialTheme.typography.labelMedium.tnum(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = PracticeMath.formatTimeMs(snapshot.durationMs),
                                        style = MaterialTheme.typography.labelMedium.tnum(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Symmetric 5-Icon Casual Transport Row: `🔀 ⏮ ⏸ ⏭ 🔁` (§4.3A)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { playerController.toggleShuffle() },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Shuffle,
                                        contentDescription = if (snapshot.shuffleEnabled) "随机播放：已开启" else "随机播放：已关闭",
                                        tint = if (snapshot.shuffleEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = { playerController.skipToPreviousOrRestartA() },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.SkipPrevious,
                                        contentDescription = "上一首",
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                FilledIconButton(
                                    onClick = { playerController.togglePlayPause() },
                                    modifier = Modifier.size(68.dp),
                                ) {
                                    Icon(
                                        imageVector = if (snapshot.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { playerController.skipToNext() },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.SkipNext,
                                        contentDescription = "下一首",
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { playerController.cycleCasualRepeatMode() },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    val repeatIcon = when (snapshot.repeatMode) {
                                        CasualRepeatMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
                                        else -> Icons.Outlined.Repeat
                                    }
                                    val repeatActive = snapshot.repeatMode != CasualRepeatMode.PLAY_ONCE
                                    Icon(
                                        imageVector = repeatIcon,
                                        contentDescription = when (snapshot.repeatMode) {
                                            CasualRepeatMode.REPEAT_ALL -> "列表循环"
                                            CasualRepeatMode.REPEAT_ONE -> "单曲循环"
                                            CasualRepeatMode.PLAY_ONCE -> "顺序播完即停"
                                        },
                                        tint = if (repeatActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        // === LAYER 2: PRACTICE MODE (`练习工作台`, §4.3A Right & §5.2) ===
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (item.isVideo) {
                                VideoPracticeSurface(
                                    playerController = playerController,
                                    aspectRatio = snapshot.videoAspectRatio,
                                    zoom = practiceState.videoZoom,
                                    mirrorHorizontal = practiceState.videoMirror,
                                    countInProgress = countInProgress,
                                    onZoomChanged = { z, persist -> loopEngine.setVideoZoom(z, persist) },
                                    onSkipCountIn = { playerController.metronome.skipImmediately() },
                                    cornerRadiusDp = 18,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "${item.title} · ${item.artist}",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                // Compact audio track banner + Count-In badge when counting in
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    MediaArtwork(
                                        coverPathOrUri = item.coverPathOrUri,
                                        mediaDocumentUri = item.documentUri,
                                        isVideo = false,
                                        shape = RoundedCornerShape(14.dp),
                                        onBitmapLoaded = { loadedCoverBitmap = it },
                                        modifier = Modifier.size(56.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${item.artist} · ${item.displayAlbum}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (completedLoops > 0) {
                                        PracticeBadgePill("已练 $completedLoops 遍")
                                    }
                                }

                                countInProgress?.let { progress ->
                                    CountInOverlayBadge(
                                        progress = progress,
                                        onSkip = { playerController.metronome.skipImmediately() },
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                    )
                                }
                            }

                            // 44dp Waveform + Precision A-B Waterdrop Handles
                            WaveformLoopRuler(
                                waveformBars = waveformBars,
                                positionMs = snapshot.positionMs,
                                durationMs = snapshot.durationMs,
                                activeAMs = practiceState.activeAMs,
                                activeBMs = practiceState.activeBMs,
                                loopEnabled = practiceState.loopEnabled,
                                onSeek = { playerController.seekTo(it) },
                                onDragLoopPoints = { a, b, commit ->
                                    loopEngine.setLoopPointsFromDrag(a, b, commit)
                                },
                            )

                            // Section Bookmarks Strip (`[+ 存书签] [Intro] [Verse] [★ Solo 1]`)
                            val hasValidLoop = practiceState.activeAMs != null && practiceState.activeBMs != null
                            SectionBookmarksRow(
                                bookmarks = bookmarks,
                                activeBookmarkId = activeBookmarkId,
                                hasActiveLoop = hasValidLoop,
                                onActivateBookmark = { loopEngine.activateBookmark(it) },
                                onSaveNewBookmark = { label ->
                                    val a = practiceState.activeAMs
                                    val b = practiceState.activeBMs
                                    if (a != null && b != null) {
                                        viewModel.savePracticeBookmark(label, a, b)
                                    }
                                },
                                onUpdateBookmarkRange = { bm ->
                                    val a = practiceState.activeAMs
                                    val b = practiceState.activeBMs
                                    if (a != null && b != null) {
                                        viewModel.savePracticeBookmark(
                                            label = bm.label,
                                            aMs = a,
                                            bMs = b,
                                            existingBookmarkId = bm.bookmarkId,
                                        )
                                    }
                                },
                                onRenameBookmark = { bm, newLabel ->
                                    viewModel.savePracticeBookmark(
                                        label = newLabel,
                                        aMs = bm.aMs,
                                        bMs = bm.bMs,
                                        existingBookmarkId = bm.bookmarkId,
                                    )
                                },
                                onDeleteBookmark = { bm ->
                                    viewModel.deletePracticeBookmark(bm.bookmarkId)
                                },
                            )

                            // Ergonomic Practice Deck (`surfaceContainer`, 24dp corners)
                            PracticeDeck(
                                loopEngine = loopEngine,
                                practiceState = practiceState,
                                speedTrainer = speedTrainer,
                                currentPositionMs = snapshot.positionMs,
                            )

                            // Practice Transport Bar: `⏮(回A)` (400ms double-tap guard) · `⟲5s` · `⏸` · `5s⟳` · `⏭`
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val loopActive = practiceState.loopEnabled && hasValidLoop
                                IconButton(
                                    onClick = { playerController.skipToPreviousOrRestartA() },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.SkipPrevious,
                                        contentDescription = if (loopActive) "单击回到 A 点重练，双击跳回曲首" else "上一首",
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { playerController.seekByDelta(-5_000L) },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(Icons.Outlined.Replay5, contentDescription = "快退 5 秒", modifier = Modifier.size(28.dp))
                                }
                                FilledIconButton(
                                    onClick = { playerController.togglePlayPause() },
                                    modifier = Modifier.size(64.dp),
                                ) {
                                    Icon(
                                        imageVector = if (snapshot.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { playerController.seekByDelta(5_000L) },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(Icons.Outlined.Forward5, contentDescription = "快进 5 秒", modifier = Modifier.size(28.dp))
                                }
                                IconButton(
                                    onClick = { playerController.skipToNext() },
                                    modifier = Modifier.size(52.dp),
                                ) {
                                    Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Queue Bottom Sheet (`☰`)
        if (showQueueSheet) {
            QueueBottomSheet(
                queue = snapshot.queue,
                currentIndex = snapshot.currentQueueIndex,
                onSelectTrack = { idx ->
                    playerController.jumpToQueueIndex(idx)
                    showQueueSheet = false
                },
                onDismiss = { showQueueSheet = false },
            )
        }

        // Sleep Timer Bottom Sheet (`⏾`)
        if (showSleepTimerSheet) {
            SleepTimerBottomSheet(
                remainingMs = snapshot.sleepTimerRemainingMs,
                pauseAtTrackEnd = snapshot.pauseAtEndOfCurrentTrack,
                onSelectMinutes = { mins ->
                    playerController.setSleepTimerMinutes(mins)
                    showSleepTimerSheet = false
                },
                onSelectTrackEnd = { enabled ->
                    playerController.setSleepAtEndOfCurrentTrack(enabled)
                    showSleepTimerSheet = false
                },
                onDismiss = { showSleepTimerSheet = false },
            )
        }

        // File Details Dialog
        if (showFileDetailsDialog) {
            AlertDialog(
                onDismissRequest = { showFileDetailsDialog = false },
                title = { Text("媒体文件详情") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("文件名：${item.fileName}", style = MaterialTheme.typography.bodyMedium)
                        Text("相对路径：${item.parentRelPath.ifEmpty { "/" }}", style = MaterialTheme.typography.bodyMedium)
                        Text("格式：${item.mimeType}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "大小：${MiniPlayerViewModel.formatByteSize(item.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                        )
                        if (item.isVideo && item.width > 0 && item.height > 0) {
                            Text(
                                text = "视频分辨率：${item.width} × ${item.height}",
                                style = MaterialTheme.typography.bodyMedium.tnum(),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileDetailsDialog = false }) { Text("关闭") }
                },
            )
        }
    }
}

@Composable
private fun NowPlayingTopBar(
    item: MediaItem,
    practiceModeActive: Boolean,
    practiceState: PracticeState,
    usingFastVideoSeek: Boolean,
    sleepTimerActive: Boolean,
    onCollapse: () -> Unit,
    onTogglePracticeMode: () -> Unit,
    onToggleVideoMirror: () -> Unit,
    onEnterLandscapeVideo: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    onEditMetadata: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onShowFileDetails: () -> Unit,
    onSetVideoSeekMode: (VideoSeekMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "收起播放面板", modifier = Modifier.size(28.dp))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (practiceModeActive) {
                Surface(
                    onClick = onTogglePracticeMode,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "✓ 🎯 练习模式",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            } else {
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (item.isVideo) {
            if (practiceModeActive) {
                IconButton(onClick = onToggleVideoMirror, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Flip,
                        contentDescription = if (practiceState.videoMirror) "关闭视频水平镜像" else "开启视频水平镜像",
                        tint = if (practiceState.videoMirror) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            IconButton(onClick = onEnterLandscapeVideo, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = "横屏全屏播放")
            }
        } else if (!practiceModeActive) {
            IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Bedtime,
                    contentDescription = "睡眠定时",
                    tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = "当前播放队列")
        }

        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "更多选项")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑标签信息") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEditMetadata()
                    },
                )
                DropdownMenuItem(
                    text = { Text("加入播放列表 / 🎯 练习列表") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onAddToPlaylist()
                    },
                )
                DropdownMenuItem(
                    text = { Text("睡眠定时") },
                    leadingIcon = { Icon(Icons.Outlined.Bedtime, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenSleepTimer()
                    },
                )
                if (item.isVideo) {
                    HorizontalDivider()
                    val autoSelected = practiceState.videoSeekMode == VideoSeekMode.AUTO
                    DropdownMenuItem(
                        text = {
                            val sub = if (usingFastVideoSeek) "（已启用关键帧极速防卡顿）" else ""
                            Text((if (autoSelected) "✓ " else "") + "视频循环寻帧：自动极速$sub")
                        },
                        onClick = {
                            menuOpen = false
                            onSetVideoSeekMode(VideoSeekMode.AUTO)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text((if (!autoSelected) "✓ " else "") + "视频循环寻帧：强制精准帧")
                        },
                        onClick = {
                            menuOpen = false
                            onSetVideoSeekMode(VideoSeekMode.EXACT)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("文件详情") },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onShowFileDetails()
                    },
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricsCanvas(
    lyrics: LyricsData?,
    positionMs: Long,
    isLoading: Boolean,
    onSeekToLine: (Long) -> Unit,
    onFetchOnline: () -> Unit,
    onTapBackground: () -> Unit,
) {
    val listState = rememberLazyListState()
    val lines = lyrics?.lines.orEmpty()
    val activeLineIdx = remember(lines, positionMs) {
        if (lines.isEmpty()) -1
        else lines.indexOfLast { it.timeMs <= positionMs + 150L }.coerceAtLeast(0)
    }

    LaunchedEffect(activeLineIdx) {
        if (activeLineIdx >= 0 && activeLineIdx < lines.size) {
            listState.animateScrollToItem((activeLineIdx - 2).coerceAtLeast(0))
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxSize()
            .clickable { onTapBackground() },
    ) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            lines.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(lines) { idx, line ->
                        val isCurrent = idx == activeLineIdx
                        Text(
                            text = line.text,
                            style = if (isCurrent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeekToLine(line.timeMs) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
            !lyrics?.plainText.isNullOrBlank() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = lyrics?.plainText.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "未检测到内嵌或同名 .lrc 歌词",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    FilledTonalButton(
                        onClick = onFetchOnline,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("从 LRCLIB 免 Key 获取同步歌词")
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueBottomSheet(
    queue: List<MediaItem>,
    currentIndex: Int,
    onSelectTrack: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "当前播放队列 (${queue.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(queue, key = { idx, item -> "${idx}_${item.itemId}" }) { idx, item ->
                    val active = idx == currentIndex
                    Surface(
                        onClick = { onSelectTrack(idx) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.artist} · ${PracticeMath.formatTimeMs(item.durationMs)}",
                                    style = MaterialTheme.typography.labelMedium.tnum(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerBottomSheet(
    remainingMs: Long?,
    pauseAtTrackEnd: Boolean,
    onSelectMinutes: (Int?) -> Unit,
    onSelectTrackEnd: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = buildString {
                    append("⏾ 睡眠定时")
                    if (remainingMs != null) {
                        append(" · 剩余 ${PracticeMath.formatTimeMs(remainingMs)}")
                    } else if (pauseAtTrackEnd) {
                        append(" · 播完当前曲目后暂停")
                    }
                },
                style = MaterialTheme.typography.titleMedium.tnum(),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            listOf(15, 30, 45, 60).forEach { mins ->
                TextButton(
                    onClick = { onSelectMinutes(mins) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("$mins 分钟后暂停", modifier = Modifier.fillMaxWidth())
                }
            }
            TextButton(
                onClick = { onSelectTrackEnd(true) },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            ) {
                Text("播完当前曲目后暂停", modifier = Modifier.fillMaxWidth())
            }
            if (remainingMs != null || pauseAtTrackEnd) {
                OutlinedButton(
                    onClick = { onSelectMinutes(null) },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("关闭睡眠定时")
                }
            }
        }
    }
}
