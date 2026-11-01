package com.miniplayer.app.ui

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miniplayer.app.core.model.*
import com.miniplayer.app.player.AbLoopUiState
import com.miniplayer.app.player.CountInProgress
import com.miniplayer.app.player.PlaybackSnapshot
import com.miniplayer.app.player.PlayerController
import com.miniplayer.app.ui.components.GuitarIcon
import com.miniplayer.app.ui.theme.tnum
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Interactive video canvas supporting:
 * - Smooth `1.0x – 4.0x` pinch-to-zoom & single-finger pan on guitar fretboard / scrolling tab
 * - Gesture isolation (`scale == 1.0f` lets parent sheet swipe down; `scale > 1.0f` pans viewport)
 * - `[2.4x · 重置]` 1-tap reset pill
 * - Horizontal mirror flip (`scaleX = -zoom` on the video layer only, never flipping UI labels)
 */
@Composable
fun VideoPracticeSurface(
    playerController: PlayerController,
    aspectRatio: Float,
    zoom: Float,
    mirrorHorizontal: Boolean,
    countInProgress: CountInProgress?,
    onZoomChanged: (Float, Boolean) -> Unit,
    onSkipCountIn: () -> Unit,
    onSingleTapCanvas: () -> Unit = {},
    cornerRadiusDp: Int = 18,
    modifier: Modifier = Modifier,
) {
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(zoom) {
        if (zoom <= 1.01f) {
            panOffset = Offset.Zero
        }
    }

    fun clampOffset(raw: Offset, currentZoom: Float): Offset {
        if (currentZoom <= 1.0f || containerSize.width <= 0 || containerSize.height <= 0) {
            return Offset.Zero
        }
        val maxX = (containerSize.width * (currentZoom - 1f)) / 2f
        val maxY = (containerSize.height * (currentZoom - 1f)) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(zoom) {
                awaitEachGesture {
                    var currentScale = zoom
                    var currentPan = panOffset
                    var movedSignificantly = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (abs(zoomChange - 1f) > 0.005f || panChange.getDistance() > 1f) {
                                movedSignificantly = true
                                currentScale = (currentScale * zoomChange).coerceIn(1.0f, 4.0f)
                                currentPan = clampOffset(currentPan + panChange, currentScale)
                                panOffset = currentPan
                                onZoomChanged(currentScale, false)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressedCount == 1 && currentScale > 1.02f) {
                            // Gesture isolation (§5.3 #1): when zoomed > 1.0x, single-finger drag pans viewport!
                            val panChange = event.calculatePan()
                            if (panChange.getDistance() > 2f) {
                                movedSignificantly = true
                                currentPan = clampOffset(currentPan + panChange, currentScale)
                                panOffset = currentPan
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressedCount == 1 && event.calculateCentroidSize(useCurrent = false) > 0f) {
                            val panChange = event.calculatePan()
                            if (panChange.getDistance() > 12f) {
                                movedSignificantly = true
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (movedSignificantly) {
                        onZoomChanged(currentScale, true)
                    } else {
                        onSingleTapCanvas()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(aspectRatio.coerceIn(0.45f, 2.4f))
                .fillMaxWidth()
                .graphicsLayer {
                    val effectiveScaleX = if (mirrorHorizontal) -zoom else zoom
                    scaleX = effectiveScaleX
                    scaleY = zoom
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        tv.isOpaque = false
                        playerController.attachVideoTextureView(tv)
                    }
                },
                onRelease = { tv ->
                    playerController.detachVideoTextureView(tv)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Top-left `[2.4x · 重置]` pill when zoomed (§5.3 #1)
        if (zoom > 1.03f) {
            Surface(
                onClick = {
                    panOffset = Offset.Zero
                    onZoomChanged(1.0f, true)
                },
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.68f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .defaultMinSize(minHeight = 40.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = String.format("%.1fx · 重置", zoom),
                        style = MaterialTheme.typography.labelLarge.tnum(),
                        color = Color.White,
                    )
                }
            }
        }

        if (mirrorHorizontal) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                Text(
                    text = "镜像中",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Visual Count-In Ring Overlay on Video Canvas (§5.2 #6 & §7.1 #2)
        if (countInProgress != null) {
            CountInOverlayBadge(
                progress = countInProgress,
                onSkip = onSkipCountIn,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Full-screen landscape video player supporting two distinct ergonomics (§4.3B):
 * 1. Casual Landscape: Ultra-clean M3 chrome auto-hiding after 3s, with icon-only Practice Mode toggle + Sleep Timer.
 * 2. Practice Landscape HUD: Translucent dockable HUD (top/bottom dockable to avoid covering
 *    bottom-burned guitar tabs), auto-fading after 4s into a minimal corner status capsule.
 */
@Composable
fun LandscapeVideoPlayerView(
    playerController: PlayerController,
    snapshot: PlaybackSnapshot,
    practiceModeActive: Boolean,
    practiceState: PracticeState,
    bookmarks: List<PracticeBookmark>,
    activeBookmarkId: Long?,
    completedLoopCount: Int,
    waveformBars: List<Float>,
    countInProgress: CountInProgress?,
    keepScreenOn: Boolean = true,
    detectedBpm: Int? = null,
    isDetectingBpm: Boolean = false,
    onAutoDetectBpm: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onBack: () -> Unit,
    onTogglePracticeMode: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onExitFullscreenOrientation: () -> Unit,
    onSaveQuickBookmark: (String) -> Unit,
) {
    val item = snapshot.currentItem ?: return
    val loopEngine = playerController.loopEngine
    var hudVisible by remember { mutableStateOf(true) }
    var lastInteractionTick by remember { mutableIntStateOf(0) }
    var showBpmDrawer by remember { mutableStateOf(false) }
    var showPresetSaveSheet by remember { mutableStateOf(false) }

    fun bumpInteraction() {
        hudVisible = true
        lastInteractionTick++
    }

    LaunchedEffect(hudVisible, lastInteractionTick, practiceModeActive, snapshot.isPlaying) {
        if (hudVisible && snapshot.isPlaying) {
            delay(if (practiceModeActive) 4_000L else 3_000L)
            hudVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoPracticeSurface(
            playerController = playerController,
            aspectRatio = snapshot.videoAspectRatio,
            zoom = practiceState.videoZoom,
            mirrorHorizontal = practiceState.videoMirror,
            countInProgress = countInProgress,
            onZoomChanged = { z, persist ->
                bumpInteraction()
                loopEngine.setVideoZoom(z, persist)
            },
            onSkipCountIn = { playerController.metronome.skipImmediately() },
            onSingleTapCanvas = { hudVisible = !hudVisible },
            cornerRadiusDp = 0,
            modifier = Modifier.fillMaxSize(),
        )

        if (!practiceModeActive) {
            // 1. Casual Landscape Chrome (§4.3B #1)
            AnimatedVisibility(
                visible = hudVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.52f))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        val hasPractice = item.hasPracticeRecord || bookmarks.isNotEmpty() || practiceState.isNonDefault
                        FilledTonalIconButton(
                            onClick = {
                                bumpInteraction()
                                onTogglePracticeMode()
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (hasPractice) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (hasPractice) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            ),
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = GuitarIcon,
                                contentDescription = "练习模式",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = onOpenSleepTimer, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Bedtime, contentDescription = "睡眠定时", tint = Color.White)
                        }
                        IconButton(onClick = onExitFullscreenOrientation, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
                        }
                    }

                    // Bottom bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.58f))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = PracticeMath.formatTimeMs(snapshot.positionMs),
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = Color.White,
                            )
                            Slider(
                                value = if (snapshot.durationMs > 0L) {
                                    (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                },
                                onValueChange = { frac ->
                                    bumpInteraction()
                                    playerController.seekTo((frac * snapshot.durationMs).toLong())
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            )
                            Text(
                                text = PracticeMath.formatTimeMs(snapshot.durationMs),
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = Color.White,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { bumpInteraction(); playerController.skipToPreviousOrRestartA() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.SkipPrevious, contentDescription = "上一首", tint = Color.White)
                            }
                            IconButton(onClick = { bumpInteraction(); playerController.seekByDelta(-10_000L) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Replay10, contentDescription = "快退 10 秒", tint = Color.White)
                            }
                            FilledIconButton(onClick = { bumpInteraction(); playerController.togglePlayPause() }, modifier = Modifier.size(54.dp)) {
                                val showPause = snapshot.isPlaying || countInProgress != null
                                Icon(
                                    if (showPause) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = if (showPause) "暂停" else "播放",
                                )
                            }
                            IconButton(onClick = { bumpInteraction(); playerController.seekByDelta(10_000L) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Forward10, contentDescription = "快进 10 秒", tint = Color.White)
                            }
                            IconButton(onClick = { bumpInteraction(); playerController.skipToNext() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.SkipNext, contentDescription = "下一首", tint = Color.White)
                            }
                        }
                    }
                }
            }
        } else {
            // 2. Practice Landscape Translucent Dockable HUD (§4.3B #2)
            AnimatedVisibility(
                visible = hudVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Header bar (opposite or thin strip)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(if (practiceState.hudDockTop) Alignment.BottomCenter else Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (practiceState.videoZoom > 1.03f) {
                            AssistChip(
                                onClick = { bumpInteraction(); loopEngine.setVideoZoom(1.0f, true) },
                                label = { Text(String.format("%.1fx 重置", practiceState.videoZoom), color = Color.White) },
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { bumpInteraction(); loopEngine.toggleVideoMirror() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (practiceState.videoMirror) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (practiceState.videoMirror) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Flip,
                                contentDescription = if (practiceState.videoMirror) "关闭视频镜像" else "开启视频镜像",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { bumpInteraction(); loopEngine.toggleHudDockTop() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (practiceState.hudDockTop) {
                                    Icons.Outlined.VerticalAlignBottom
                                } else {
                                    Icons.Outlined.VerticalAlignTop
                                },
                                contentDescription = if (practiceState.hudDockTop) "置底避让" else "移至顶部",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { bumpInteraction(); onTogglePracticeMode() },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = GuitarIcon,
                                contentDescription = "退出练习模式",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = onExitFullscreenOrientation, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.FullscreenExit, contentDescription = "竖屏模式", tint = Color.White)
                        }
                    }

                    // Dockable Translucent Practice HUD (84% opacity surfaceContainer)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.84f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(if (practiceState.hudDockTop) Alignment.TopCenter else Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { bumpInteraction() },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WaveformLoopRuler(
                                waveformBars = waveformBars,
                                positionMs = snapshot.positionMs,
                                durationMs = snapshot.durationMs,
                                activeAMs = practiceState.activeAMs,
                                activeBMs = practiceState.activeBMs,
                                loopEnabled = practiceState.loopEnabled,
                                onSeek = { bumpInteraction(); playerController.seekTo(it) },
                                onDragLoopPoints = { a, b, commit ->
                                    bumpInteraction()
                                    loopEngine.setLoopPointsFromDrag(a, b, commit)
                                },
                                beatGrid = practiceState.beatGrid(),
                                barSnapEnabled = practiceState.barSnapEnabled,
                                onDragLoopPointsWithHandle = { a, b, commit, handle ->
                                    bumpInteraction()
                                    loopEngine.setLoopPointsFromDrag(a, b, commit, handle)
                                },
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                item {
                                    FilledIconButton(
                                        onClick = { bumpInteraction(); playerController.togglePlayPause() },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        val showPause = snapshot.isPlaying || countInProgress != null
                                        Icon(
                                            if (showPause) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                            contentDescription = if (showPause) "暂停" else "播放",
                                        )
                                    }
                                }
                                item {
                                    AbControlStrip(
                                        abUiState = loopEngine.abUiState,
                                        activeAMs = practiceState.activeAMs,
                                        activeBMs = practiceState.activeBMs,
                                        loopEnabled = practiceState.loopEnabled,
                                        currentPositionMs = snapshot.positionMs,
                                        fillWidth = false,
                                        onTapA = {
                                            bumpInteraction()
                                            val aMs = practiceState.activeAMs
                                            val bMs = practiceState.activeBMs
                                            val pos = snapshot.positionMs
                                            if (aMs != null && bMs != null && pos <= bMs - PracticeMath.MIN_LOOP_SPAN_MS) {
                                                loopEngine.setLoopPointsFromDrag(
                                                    aMs = pos.coerceAtLeast(0L),
                                                    bMs = bMs,
                                                    commitPersist = true,
                                                    draggingHandle = "A",
                                                )
                                            } else {
                                                loopEngine.markPointA(pos)
                                            }
                                        },
                                        onTapB = {
                                            bumpInteraction()
                                            val aMs = practiceState.activeAMs
                                            val bMs = practiceState.activeBMs
                                            val pos = snapshot.positionMs
                                            if (aMs != null && bMs != null && pos >= aMs + PracticeMath.MIN_LOOP_SPAN_MS) {
                                                loopEngine.setLoopPointsFromDrag(
                                                    aMs = aMs,
                                                    bMs = pos,
                                                    commitPersist = true,
                                                    draggingHandle = "B",
                                                )
                                            } else {
                                                loopEngine.markPointBAndLoop(pos)
                                            }
                                        },
                                        onToggleLoop = { bumpInteraction(); loopEngine.toggleLoopSuspended() },
                                        onClearLoop = { bumpInteraction(); loopEngine.clearLoop() },
                                        onSaveBookmarkClick = { bumpInteraction(); showPresetSaveSheet = true },
                                    )
                                }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = { bumpInteraction(); loopEngine.stepPracticeSpeed(-0.05f) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(44.dp),
                                        ) { Text("-5%", style = MaterialTheme.typography.labelLarge.tnum()) }
                                        FilledTonalButton(
                                            onClick = { bumpInteraction(); loopEngine.setPracticeSpeed(1.0f) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(44.dp),
                                        ) {
                                            Text(
                                                text = String.format("%.2fx", practiceState.practiceSpeed),
                                                style = MaterialTheme.typography.labelLarge.tnum(),
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { bumpInteraction(); loopEngine.stepPracticeSpeed(0.05f) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(44.dp),
                                        ) { Text("+5%", style = MaterialTheme.typography.labelLarge.tnum()) }
                                    }
                                }
                                item {
                                    val countInActive = practiceState.countInMode != CountInMode.OFF
                                    DeckIconToggleButton(
                                        icon = Icons.Outlined.Timer,
                                        contentDescription = if (countInActive) "预备拍：4拍（已开启）" else "预备拍：已关闭",
                                        highlighted = countInActive,
                                        badgeText = if (countInActive) "4拍" else null,
                                        onClick = { bumpInteraction(); loopEngine.cycleCountInMode() },
                                        onLongClick = { bumpInteraction(); showBpmDrawer = !showBpmDrawer },
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                                item {
                                    BpmCompactChipButton(
                                        bpm = practiceState.bpm,
                                        highlighted = showBpmDrawer,
                                        onClick = { bumpInteraction(); showBpmDrawer = !showBpmDrawer },
                                        modifier = Modifier.height(44.dp),
                                    )
                                }
                                item {
                                    DeckIconToggleButton(
                                        icon = Icons.Outlined.LightMode,
                                        contentDescription = if (keepScreenOn) "练习常亮：已开启" else "练习常亮：已关闭",
                                        highlighted = keepScreenOn,
                                        onClick = { bumpInteraction(); onToggleKeepScreenOn() },
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                                items(bookmarks, key = { it.bookmarkId }) { bm ->
                                    FilterChip(
                                        selected = bm.bookmarkId == activeBookmarkId,
                                        onClick = { bumpInteraction(); loopEngine.activateBookmark(bm) },
                                        label = { Text(bm.label) },
                                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showBpmDrawer) {
                                BpmQuickBar(
                                    bpm = practiceState.bpm,
                                    practiceSpeed = practiceState.practiceSpeed,
                                    detectedBpm = detectedBpm,
                                    isDetectingBpm = isDetectingBpm,
                                    barSnapEnabled = practiceState.barSnapEnabled,
                                    firstDownbeatMs = practiceState.firstDownbeatMs,
                                    onAdjustBpm = { delta ->
                                        bumpInteraction()
                                        loopEngine.setBpm(practiceState.bpm + delta)
                                    },
                                    onTapTempo = {
                                        bumpInteraction()
                                        loopEngine.tapTempo()
                                    },
                                    onAutoDetectBpm = {
                                        bumpInteraction()
                                        onAutoDetectBpm()
                                    },
                                    onToggleBarSnap = {
                                        bumpInteraction()
                                        loopEngine.toggleBarSnap()
                                    },
                                    onAlignDownbeat = {
                                        bumpInteraction()
                                        loopEngine.alignDownbeatToCurrentPosition(snapshot.positionMs)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Corner micro status capsule when HUD auto-fades (§4.3B)
            if (!hudVisible) {
                val activeBmLabel = bookmarks.firstOrNull { it.bookmarkId == activeBookmarkId }?.label
                Surface(
                    onClick = { bumpInteraction() },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.56f),
                    modifier = Modifier
                        .align(if (practiceState.hudDockTop) Alignment.TopEnd else Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(12.dp),
                ) {
                    Text(
                        text = buildString {
                            if (activeBmLabel != null) append("$activeBmLabel · ")
                            append(String.format("%.2fx", practiceState.practiceSpeed))
                            if (completedLoopCount > 0) append(" (第 ${completedLoopCount + 1} 遍)")
                        },
                        style = MaterialTheme.typography.labelMedium.tnum(),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (showPresetSaveSheet) {
        BookmarkPresetSaveSheet(
            onSaveNewBookmark = onSaveQuickBookmark,
            onDismiss = { showPresetSaveSheet = false },
        )
    }
}

@Composable
fun CountInOverlayBadge(
    progress: CountInProgress,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSkip,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                progress = { progress.fractionRemaining },
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
            Column {
                val headline = when (progress.mode) {
                    CountInMode.OFF -> ""
                    else -> "预备拍 ${progress.currentBeat.coerceAtLeast(1)} / 4"
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium.tnum(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "轻触跳过立即起播",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

internal fun countInLabel(mode: CountInMode): String = when (mode) {
    CountInMode.OFF -> "关"
    else -> "4拍"
}
