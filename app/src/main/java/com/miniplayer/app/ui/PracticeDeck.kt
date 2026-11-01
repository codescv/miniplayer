@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.miniplayer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniplayer.app.core.model.*
import com.miniplayer.app.player.AbLoopUiState
import com.miniplayer.app.player.PracticeLoopEngine
import com.miniplayer.app.player.StemAnalysisState
import com.miniplayer.app.ui.theme.tnum
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `44dp` RMS Waveform + Precision A-B Loop Ruler with prominent labeled `A` / `B` pill drag handles
 * below the waveform so dragging `A` or `B` never occludes the waveform or millisecond readout.
 */
@Composable
fun WaveformLoopRuler(
    waveformBars: List<Float>,
    positionMs: Long,
    durationMs: Long,
    activeAMs: Long?,
    activeBMs: Long?,
    loopEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onDragLoopPoints: (aMs: Long, bMs: Long, commitPersist: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    beatGrid: BeatGrid? = null,
    barSnapEnabled: Boolean = false,
    onDragLoopPointsWithHandle: ((aMs: Long, bMs: Long, commitPersist: Boolean, draggingHandle: String?) -> Unit)? = null,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var widthPx by remember { mutableFloatStateOf(1f) }
    var draggingHandle by remember { mutableStateOf<String?>(null) } // "A" or "B"
    var dragTempAMs by remember { mutableLongStateOf(activeAMs ?: 0L) }
    var dragTempBMs by remember { mutableLongStateOf(activeBMs ?: safeDuration) }
    var dragGrabOffsetPx by remember { mutableFloatStateOf(0f) }

    val currentActiveAMs by rememberUpdatedState(activeAMs)
    val currentActiveBMs by rememberUpdatedState(activeBMs)
    val currentBeatGrid by rememberUpdatedState(beatGrid)
    val currentBarSnapEnabled by rememberUpdatedState(barSnapEnabled)
    val currentOnDragLoopPoints by rememberUpdatedState(onDragLoopPoints)
    val currentOnDragLoopPointsWithHandle by rememberUpdatedState(onDragLoopPointsWithHandle)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val barBoundaries = remember(beatGrid, safeDuration) {
        if (beatGrid != null && durationMs > 0L) {
            beatGrid.generateBarBoundaries(safeDuration)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(activeAMs, activeBMs, draggingHandle) {
        if (draggingHandle == null) {
            if (activeAMs != null) dragTempAMs = activeAMs
            if (activeBMs != null) dragTempBMs = activeBMs
        }
    }

    val shownAMs = if (draggingHandle != null && activeAMs != null) dragTempAMs else activeAMs
    val shownBMs = if (draggingHandle != null && activeBMs != null) dragTempBMs else activeBMs

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val inactiveBarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val loopFillColor = if (loopEnabled) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val loopEdgeColor = if (loopEnabled) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val handleLabelColor = if (loopEnabled) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textMeasurer = rememberTextMeasurer()
    val handleTextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Bold,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Coordinate & Timestamp Readout Row (`tnum`)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PracticeMath.formatTimeMs(positionMs, includeTenths = true),
                style = MaterialTheme.typography.labelMedium.tnum(),
                color = MaterialTheme.colorScheme.primary,
            )
            if (shownAMs != null) {
                val loopLabel = buildString {
                    append("A ${PracticeMath.formatTimeMs(shownAMs, includeTenths = true)}")
                    if (shownBMs != null) {
                        if (barSnapEnabled && beatGrid != null) {
                            val barCountInLoop = beatGrid.countBarsBetween(shownAMs, shownBMs)
                            if (loopEnabled) {
                                append(" ━━● ${barCountInLoop}小节 ●━━ ")
                            } else {
                                append(" ╌╌○ ${barCountInLoop}小节 ○╌╌ ")
                            }
                        } else {
                            append(if (loopEnabled) " ━━●━━ " else " ╌╌○╌╌ ")
                        }
                        append("B ${PracticeMath.formatTimeMs(shownBMs, includeTenths = true)}")
                    } else {
                        append(" · 待设 B 点")
                    }
                }
                Text(
                    text = loopLabel,
                    style = MaterialTheme.typography.labelMedium.tnum(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (loopEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = PracticeMath.formatTimeMs(durationMs, includeTenths = true),
                style = MaterialTheme.typography.labelMedium.tnum(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        // 44dp Waveform + 32dp Pill Handle Gutter when A/B markers are present
        val hasAnyPoint = shownAMs != null
        val canvasHeight = if (hasAnyPoint) 76.dp else 48.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = "音频波形与 A-B 循环标尺，当前播放位置 ${PracticeMath.formatTimeMs(positionMs, true)}"
                }
                .pointerInput(safeDuration) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / widthPx).coerceIn(0f, 1f)
                        currentOnSeek((frac * safeDuration).toLong())
                    }
                }
                .pointerInput(safeDuration) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            val aMs = currentActiveAMs
                            val bMs = currentActiveBMs
                            if (aMs != null && bMs != null) {
                                val aX = (aMs.toFloat() / safeDuration) * widthPx
                                val bX = (bMs.toFloat() / safeDuration) * widthPx
                                val hitRadius = 44.dp.toPx()
                                val distA = abs(startOffset.x - aX)
                                val distB = abs(startOffset.x - bX)
                                val chosen = when {
                                    distA <= hitRadius && distB <= hitRadius -> {
                                        if (abs(aX - bX) < 28.dp.toPx()) {
                                            if (startOffset.x < (aX + bX) / 2f) "A" else "B"
                                        } else {
                                            if (distA <= distB) "A" else "B"
                                        }
                                    }
                                    distA <= hitRadius -> "A"
                                    distB <= hitRadius -> "B"
                                    else -> null
                                }
                                draggingHandle = chosen
                                if (chosen != null) {
                                    dragTempAMs = aMs
                                    dragTempBMs = bMs
                                    val chosenHandleX = if (chosen == "A") aX else bX
                                    dragGrabOffsetPx = startOffset.x - chosenHandleX
                                }
                            } else {
                                draggingHandle = null
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            val handle = draggingHandle
                            if (handle != null) {
                                change.consume()
                                val targetX = (change.position.x - dragGrabOffsetPx).coerceIn(0f, widthPx)
                                val fingerMs = ((targetX / widthPx) * safeDuration).toLong()
                                val grid = currentBeatGrid
                                if (handle == "A") {
                                    val rawA = fingerMs.coerceIn(
                                        0L,
                                        (dragTempBMs - PracticeMath.MIN_LOOP_SPAN_MS).coerceAtLeast(0L),
                                    )
                                    if (currentBarSnapEnabled && grid != null) {
                                        val snapped = grid.snapDraggedLoopPoints(rawA, dragTempBMs, "A", safeDuration)
                                        dragTempAMs = snapped.first
                                        dragTempBMs = snapped.second
                                    } else {
                                        dragTempAMs = rawA
                                    }
                                } else if (handle == "B") {
                                    val rawB = fingerMs.coerceIn(
                                        (dragTempAMs + PracticeMath.MIN_LOOP_SPAN_MS).coerceAtMost(safeDuration),
                                        safeDuration,
                                    )
                                    if (currentBarSnapEnabled && grid != null) {
                                        val snapped = grid.snapDraggedLoopPoints(dragTempAMs, rawB, "B", safeDuration)
                                        dragTempAMs = snapped.first
                                        dragTempBMs = snapped.second
                                    } else {
                                        dragTempBMs = rawB
                                    }
                                }
                                currentOnDragLoopPointsWithHandle?.invoke(dragTempAMs, dragTempBMs, false, handle)
                                    ?: currentOnDragLoopPoints(dragTempAMs, dragTempBMs, false)
                            } else {
                                change.consume()
                                val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                                currentOnSeek((frac * safeDuration).toLong())
                            }
                        },
                        onDragEnd = {
                            val handle = draggingHandle
                            if (handle != null) {
                                currentOnDragLoopPointsWithHandle?.invoke(dragTempAMs, dragTempBMs, true, handle)
                                    ?: currentOnDragLoopPoints(dragTempAMs, dragTempBMs, true)
                                draggingHandle = null
                            }
                        },
                        onDragCancel = {
                            val handle = draggingHandle
                            if (handle != null) {
                                currentOnDragLoopPointsWithHandle?.invoke(dragTempAMs, dragTempBMs, true, handle)
                                    ?: currentOnDragLoopPoints(dragTempAMs, dragTempBMs, true)
                                draggingHandle = null
                            }
                        },
                    )
                },
        ) {
            val waveHeight = 44.dp.toPx()
            val w = size.width.coerceAtLeast(1f)
            val playFrac = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            val playX = playFrac * w

            // 1. Highlighted A-B region background
            if (shownAMs != null && shownBMs != null) {
                val aX = (shownAMs.toFloat() / safeDuration) * w
                val bX = (shownBMs.toFloat() / safeDuration) * w
                drawRoundRect(
                    color = loopFillColor,
                    topLeft = Offset(aX, 0f),
                    size = Size((bX - aX).coerceAtLeast(2f), waveHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
            }

            // 1b. Measure / bar vertical tick lines (every 4th bar taller/brighter, every 1st bar subtle)
            if (barBoundaries.isNotEmpty()) {
                // Dense grids (> 480 bars) only draw phrase-start ticks.
                val phraseOnly = barBoundaries.size > 480
                val majorColor = onSurfaceColor.copy(alpha = if (barSnapEnabled) 0.30f else 0.16f)
                val minorColor = onSurfaceColor.copy(alpha = if (barSnapEnabled) 0.14f else 0.08f)
                val majorStroke = 1.2.dp.toPx()
                val minorStroke = 0.85.dp.toPx()
                for (barIdx in barBoundaries.indices) {
                    val tMs = barBoundaries[barIdx]
                    // Bar index relative to the unfolded anchor (may be negative), from Double bar math.
                    val relBarIdx = if (beatGrid != null && beatGrid.barDurationMs > 1.0) {
                        ((tMs - beatGrid.firstDownbeatMs).toDouble() / beatGrid.barDurationMs).roundToInt()
                    } else {
                        barIdx
                    }
                    val isPhraseBar = beatGrid?.isPhraseStart(relBarIdx) ?: (Math.floorMod(relBarIdx, 4) == 0)
                    if (phraseOnly && !isPhraseBar) continue
                    val tickX = ((tMs.toFloat() / safeDuration) * w).coerceIn(0f, w)
                    drawLine(
                        color = if (isPhraseBar) majorColor else minorColor,
                        start = Offset(tickX, if (isPhraseBar) 1.dp.toPx() else waveHeight * 0.14f),
                        end = Offset(tickX, if (isPhraseBar) waveHeight - 1.dp.toPx() else waveHeight * 0.86f),
                        strokeWidth = if (isPhraseBar) majorStroke else minorStroke,
                    )
                }
            }

            // 2. 240 RMS Waveform Envelope Bars
            val bars = waveformBars
            val barCount = bars.size.coerceAtLeast(1)
            val slotW = w / barCount
            val barW = (slotW * 0.66f).coerceAtLeast(1.5f)
            for (i in bars.indices) {
                val amp = bars[i].coerceIn(0.08f, 1f)
                val barH = (amp * waveHeight * 0.90f).coerceAtLeast(3.dp.toPx())
                val x = i * slotW + (slotW - barW) * 0.5f
                val y = (waveHeight - barH) * 0.5f
                val inPlayed = x <= playX
                val inLoop = shownAMs != null && shownBMs != null &&
                    x >= (shownAMs.toFloat() / safeDuration) * w &&
                    x <= (shownBMs.toFloat() / safeDuration) * w
                val color = when {
                    inLoop && loopEnabled -> loopEdgeColor
                    inPlayed -> primaryColor
                    else -> inactiveBarColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }

            // 3. Playhead vertical cursor
            drawLine(
                color = primaryColor,
                start = Offset(playX, 0f),
                end = Offset(playX, waveHeight),
                strokeWidth = 2.5.dp.toPx(),
            )

            // 4. A & B vertical boundary lines + large rounded pill handles with "A" / "B" tags
            val pillW = 26.dp.toPx()
            val pillH = 22.dp.toPx()
            val pillHalfW = pillW / 2f
            val pillTopY = waveHeight + 8.dp.toPx()

            fun drawHandlePill(
                lineX: Float,
                thumbCenterX: Float,
                label: String,
                isDragging: Boolean,
            ) {
                drawLine(
                    color = loopEdgeColor,
                    start = Offset(lineX, 0f),
                    end = Offset(lineX, waveHeight + 4.dp.toPx()),
                    strokeWidth = if (isDragging) 3.dp.toPx() else 2.5.dp.toPx(),
                )
                val pointerPath = Path().apply {
                    moveTo(lineX, waveHeight + 1.dp.toPx())
                    lineTo(thumbCenterX - 5.dp.toPx(), pillTopY + 2.dp.toPx())
                    lineTo(thumbCenterX + 5.dp.toPx(), pillTopY + 2.dp.toPx())
                    close()
                }
                drawPath(pointerPath, color = loopEdgeColor)

                if (isDragging) {
                    drawRoundRect(
                        color = loopEdgeColor.copy(alpha = 0.28f),
                        topLeft = Offset(thumbCenterX - pillHalfW - 3.dp.toPx(), pillTopY - 3.dp.toPx()),
                        size = Size(pillW + 6.dp.toPx(), pillH + 6.dp.toPx()),
                        cornerRadius = CornerRadius((pillH + 6.dp.toPx()) / 2f, (pillH + 6.dp.toPx()) / 2f),
                    )
                }

                drawRoundRect(
                    color = loopEdgeColor,
                    topLeft = Offset(thumbCenterX - pillHalfW, pillTopY),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                )

                val measured = textMeasurer.measure(
                    text = label,
                    style = handleTextStyle.copy(color = handleLabelColor),
                )
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = thumbCenterX - measured.size.width / 2f,
                        y = pillTopY + (pillH - measured.size.height) / 2f,
                    ),
                )
            }

            if (shownAMs != null && shownBMs != null) {
                val aLineX = ((shownAMs.toFloat() / safeDuration) * w)
                    .coerceIn(1.dp.toPx(), (w - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
                val bLineX = ((shownBMs.toFloat() / safeDuration) * w)
                    .coerceIn(1.dp.toPx(), (w - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))

                val minThumbSeparation = pillW + 3.dp.toPx()
                var aThumbX = aLineX
                var bThumbX = bLineX
                if (bThumbX - aThumbX < minThumbSeparation && w >= minThumbSeparation + pillW) {
                    val mid = (aLineX + bLineX) / 2f
                    aThumbX = mid - minThumbSeparation / 2f
                    bThumbX = mid + minThumbSeparation / 2f
                }
                if (aThumbX < pillHalfW) {
                    aThumbX = pillHalfW
                    if (bThumbX - aThumbX < minThumbSeparation) {
                        bThumbX = (aThumbX + minThumbSeparation).coerceAtMost((w - pillHalfW).coerceAtLeast(pillHalfW))
                    }
                }
                if (bThumbX > w - pillHalfW) {
                    bThumbX = (w - pillHalfW).coerceAtLeast(pillHalfW)
                    if (bThumbX - aThumbX < minThumbSeparation) {
                        aThumbX = (bThumbX - minThumbSeparation).coerceAtLeast(pillHalfW)
                    }
                }

                drawHandlePill(
                    lineX = aLineX,
                    thumbCenterX = aThumbX,
                    label = "A",
                    isDragging = draggingHandle == "A",
                )
                drawHandlePill(
                    lineX = bLineX,
                    thumbCenterX = bThumbX,
                    label = "B",
                    isDragging = draggingHandle == "B",
                )
            } else if (shownAMs != null) {
                val aLineX = ((shownAMs.toFloat() / safeDuration) * w)
                    .coerceIn(1.dp.toPx(), (w - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
                val aThumbX = aLineX.coerceIn(pillHalfW, (w - pillHalfW).coerceAtLeast(pillHalfW))
                drawHandlePill(
                    lineX = aLineX,
                    thumbCenterX = aThumbX,
                    label = "A",
                    isDragging = false,
                )
            }
        }
    }
}

/**
 * Horizontal saved section bookmark strip (`[Intro] [Verse] [Solo 1]`) with long-press
 * update/rename/delete menu. Shown below the Waveform Loop Ruler when bookmarks exist.
 */
@Composable
fun SectionBookmarksRow(
    bookmarks: List<PracticeBookmark>,
    activeBookmarkId: Long?,
    hasActiveLoop: Boolean,
    onActivateBookmark: (PracticeBookmark) -> Unit,
    onUpdateBookmarkRange: (PracticeBookmark) -> Unit,
    onRenameBookmark: (PracticeBookmark, String) -> Unit,
    onDeleteBookmark: (PracticeBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bookmarks.isEmpty()) return

    var editingBookmark by remember { mutableStateOf<PracticeBookmark?>(null) }
    var renameText by remember { mutableStateOf("") }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(bookmarks, key = { it.bookmarkId }) { bm ->
            val isSelected = bm.bookmarkId == activeBookmarkId
            var menuExpanded by remember { mutableStateOf(false) }

            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onActivateBookmark(bm) },
                            onLongClick = { menuExpanded = true },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buildString {
                                append(bm.label)
                                append(" ")
                                append(PracticeMath.formatTimeMs(bm.aMs))
                                append("–")
                                append(PracticeMath.formatTimeMs(bm.bMs))
                            },
                            style = MaterialTheme.typography.labelLarge.tnum(),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (hasActiveLoop) {
                        DropdownMenuItem(
                            text = { Text("用当前 A-B 更新范围") },
                            leadingIcon = { Icon(Icons.Outlined.Update, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onUpdateBookmarkRange(bm)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名书签") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            renameText = bm.label
                            editingBookmark = bm
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除书签", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteBookmark(bm)
                        },
                    )
                }
            }
        }
    }

    editingBookmark?.let { target ->
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("重命名段落书签") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRenameBookmark(target, renameText.trim())
                            editingBookmark = null
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun BookmarkPresetSaveSheet(
    onSaveNewBookmark: (label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customLabel by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("保存段落书签（单手点选预设即刻保存）", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PracticeMath.PRESET_SECTION_LABELS.chunked(4).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onSaveNewBookmark(preset)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) {
                                Text(preset, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    placeholder = { Text("或输入自定义备注（如：扫拨难点）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val name = customLabel.trim()
                        if (name.isNotEmpty()) {
                            onDismiss()
                            onSaveNewBookmark(name)
                        }
                    },
                    enabled = customLabel.isNotBlank(),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

private enum class ExpandedDeckDrawer {
    NONE,
    PITCH_SHIFT,
    STEM_MIXER,
}

/**
 * Compact 2-Row `练习工作台 (Practice Deck)` (`surfaceContainer`, `24dp` corners):
 * - Row 1: Unified A-B Loop strip (`[A]`, `[B]`, Loop toggle, Clear loop, Save Bookmark).
 * - Row 2: `[-5%] [1.00x] [+5%]` speed stepper + Pitch Shift, 5-Stem Mixer, Beat-Only Count-In (`4拍`),
 *   BPM & Bar-Snap drawer chip, icon-only Keep-Screen-On toggle, and Video Mirror.
 */
@Composable
fun PracticeDeck(
    loopEngine: PracticeLoopEngine,
    practiceState: PracticeState,
    currentPositionMs: Long,
    keepScreenOn: Boolean = true,
    isVideo: Boolean = false,
    detectedBpm: Int? = null,
    isDetectingBpm: Boolean = false,
    stemAnalysisState: StemAnalysisState = StemAnalysisState.Idle,
    onAutoDetectBpm: () -> Unit = {},
    onDownloadStemModel: () -> Unit = {},
    onAnalyzeTrackStems: () -> Unit = {},
    onCancelStemOperation: () -> Unit = {},
    onToggleKeepScreenOn: () -> Unit = {},
    onSaveNewBookmark: (label: String) -> Unit = {},
    beatGridControls: BeatGridControls = BeatGridControls(),
    modifier: Modifier = Modifier,
) {
    var expandedDrawer by remember { mutableStateOf(ExpandedDeckDrawer.NONE) }
    var showBpmDrawer by remember { mutableStateOf(false) }
    var showPresetSaveSheet by remember { mutableStateOf(false) }

    val bpmBarVisible = showBpmDrawer

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // === Row 1: Unified A-B Loop & Save Bookmark Strip ===
            AbControlStrip(
                abUiState = loopEngine.abUiState,
                activeAMs = practiceState.activeAMs,
                activeBMs = practiceState.activeBMs,
                loopEnabled = practiceState.loopEnabled,
                currentPositionMs = currentPositionMs,
                fillWidth = true,
                onTapA = {
                    val aMs = practiceState.activeAMs
                    val bMs = practiceState.activeBMs
                    if (aMs != null && bMs != null && currentPositionMs <= bMs - PracticeMath.MIN_LOOP_SPAN_MS) {
                        loopEngine.setLoopPointsFromDrag(
                            aMs = currentPositionMs.coerceAtLeast(0L),
                            bMs = bMs,
                            commitPersist = true,
                            draggingHandle = "A",
                        )
                    } else {
                        loopEngine.markPointA(currentPositionMs)
                    }
                },
                onTapB = {
                    val aMs = practiceState.activeAMs
                    val bMs = practiceState.activeBMs
                    if (aMs != null && bMs != null && currentPositionMs >= aMs + PracticeMath.MIN_LOOP_SPAN_MS) {
                        loopEngine.setLoopPointsFromDrag(
                            aMs = aMs,
                            bMs = currentPositionMs,
                            commitPersist = true,
                            draggingHandle = "B",
                        )
                    } else {
                        loopEngine.markPointBAndLoop(currentPositionMs)
                    }
                },
                onToggleLoop = { loopEngine.toggleLoopSuspended() },
                onClearLoop = { loopEngine.clearLoop() },
                onSaveBookmarkClick = { showPresetSaveSheet = true },
            )

            // === Row 2: Speed Stepper + Secondary Controls ===
            SpeedAndSecondaryStrip(
                practiceState = practiceState,
                keepScreenOn = keepScreenOn,
                isVideo = isVideo,
                pitchDrawerOpen = expandedDrawer == ExpandedDeckDrawer.PITCH_SHIFT,
                stemDrawerOpen = expandedDrawer == ExpandedDeckDrawer.STEM_MIXER,
                bpmDrawerOpen = bpmBarVisible,
                onStepSpeed = { loopEngine.stepPracticeSpeed(it) },
                onResetSpeed = { loopEngine.setPracticeSpeed(1.0f) },
                onTogglePitchDrawer = {
                    expandedDrawer = if (expandedDrawer == ExpandedDeckDrawer.PITCH_SHIFT) {
                        ExpandedDeckDrawer.NONE
                    } else {
                        ExpandedDeckDrawer.PITCH_SHIFT
                    }
                },
                onToggleStemDrawer = {
                    expandedDrawer = if (expandedDrawer == ExpandedDeckDrawer.STEM_MIXER) {
                        ExpandedDeckDrawer.NONE
                    } else {
                        ExpandedDeckDrawer.STEM_MIXER
                    }
                },
                onCycleCountIn = { loopEngine.cycleCountInMode() },
                onToggleBpmDrawer = { showBpmDrawer = !showBpmDrawer },
                onToggleKeepScreenOn = onToggleKeepScreenOn,
                onToggleVideoMirror = { loopEngine.toggleVideoMirror() },
            )

            // Inline BPM, Tap Tempo, Auto-Detect & Bar-Snap Calibration Bar
            AnimatedVisibility(visible = bpmBarVisible) {
                BpmQuickBar(
                    bpm = practiceState.bpm,
                    practiceSpeed = practiceState.practiceSpeed,
                    detectedBpm = detectedBpm,
                    isDetectingBpm = isDetectingBpm,
                    barSnapEnabled = practiceState.barSnapEnabled,
                    firstDownbeatMs = practiceState.firstDownbeatMs,
                    onAdjustBpm = { delta -> loopEngine.adjustBpmBy(delta) },
                    onTapTempo = { loopEngine.tapTempo() },
                    onAutoDetectBpm = onAutoDetectBpm,
                    onToggleBarSnap = { loopEngine.toggleBarSnap() },
                    onAlignDownbeat = { loopEngine.alignDownbeatToCurrentPosition(currentPositionMs) },
                    bpmPrecise = practiceState.exactBpm,
                    beatsPerBar = practiceState.beatsPerBar,
                    beatGridSource = practiceState.beatGridSource,
                    beatGridControls = beatGridControls,
                    onShiftDownbeatBeats = { loopEngine.shiftDownbeatByBeats(it) },
                    onNudgeDownbeatMs = { loopEngine.nudgeDownbeatMs(it) },
                )
            }

            // Inline Pure Sonic Semitone & Fine Cents Pitch Shift Panel
            AnimatedVisibility(visible = expandedDrawer == ExpandedDeckDrawer.PITCH_SHIFT) {
                PitchShiftInlinePanel(
                    semitones = practiceState.pitchSemitones,
                    cents = practiceState.pitchCents,
                    onUpdatePitch = { st, ct -> loopEngine.setPitchShift(st, ct) },
                )
            }

            // Inline HTDemucs 5-Stem Separation & Independent Instrument Volume Mixer Panel
            AnimatedVisibility(visible = expandedDrawer == ExpandedDeckDrawer.STEM_MIXER) {
                StemMixerInlinePanel(
                    itemId = practiceState.itemId,
                    stemMix = practiceState.stemMix,
                    stemAnalysisState = stemAnalysisState,
                    onDownloadStemModel = onDownloadStemModel,
                    onAnalyzeTrackStems = onAnalyzeTrackStems,
                    onCancelStemOperation = onCancelStemOperation,
                    onStemVolumeChange = { stem, gain, commit ->
                        loopEngine.setStemVolume(stem, gain, commitPersist = commit)
                    },
                    onToggleStemMute = { stem -> loopEngine.toggleStemMute(stem) },
                    onToggleStemSolo = { stem -> loopEngine.toggleStemSolo(stem) },
                    onResetStemMix = { loopEngine.resetStemMix() },
                )
            }
        }
    }

    if (showPresetSaveSheet) {
        BookmarkPresetSaveSheet(
            onSaveNewBookmark = onSaveNewBookmark,
            onDismiss = { showPresetSaveSheet = false },
        )
    }
}

@Composable
internal fun AbControlStrip(
    abUiState: AbLoopUiState,
    activeAMs: Long?,
    activeBMs: Long?,
    loopEnabled: Boolean,
    currentPositionMs: Long,
    fillWidth: Boolean,
    onTapA: () -> Unit,
    onTapB: () -> Unit,
    onToggleLoop: () -> Unit,
    onClearLoop: () -> Unit,
    onSaveBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasA = activeAMs != null
    val hasB = activeBMs != null
    val hasBoth = hasA && hasB
    val aText = if (activeAMs == null) "A" else "A ${PracticeMath.formatTimeMs(activeAMs, includeTenths = true)}"
    val bText = if (activeBMs == null) "B" else "B ${PracticeMath.formatTimeMs(activeBMs, includeTenths = true)}"

    Row(
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val aButtonModifier = if (fillWidth) {
            Modifier.weight(1f).height(44.dp)
        } else {
            Modifier.widthIn(min = 76.dp).height(44.dp)
        }
        val bButtonModifier = if (fillWidth) {
            Modifier.weight(1f).height(44.dp)
        } else {
            Modifier.widthIn(min = 76.dp).height(44.dp)
        }

        // [A] Button
        if (abUiState == AbLoopUiState.IDLE) {
            Button(
                onClick = onTapA,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = aButtonModifier,
            ) {
                Text(
                    text = aText,
                    style = MaterialTheme.typography.titleMedium.tnum(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            FilledTonalButton(
                onClick = onTapA,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (hasBoth && loopEnabled) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (hasBoth && loopEnabled) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = aButtonModifier.semantics {
                    contentDescription = "设当前位置为 A 起点，当前 $aText"
                },
            ) {
                Text(
                    text = aText,
                    style = MaterialTheme.typography.labelLarge.tnum(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // [B] Button
        if (abUiState == AbLoopUiState.A_SET_WAITING) {
            Button(
                onClick = onTapB,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = bButtonModifier,
            ) {
                Text(
                    text = bText,
                    style = MaterialTheme.typography.titleMedium.tnum(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            val bEnabled = hasA || currentPositionMs >= PracticeMath.MIN_LOOP_SPAN_MS
            FilledTonalButton(
                onClick = onTapB,
                enabled = bEnabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (hasBoth && loopEnabled) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (hasBoth && loopEnabled) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = bButtonModifier.semantics {
                    contentDescription = "设当前位置为 B 终点，当前 $bText"
                },
            ) {
                Text(
                    text = bText,
                    style = if (hasB) MaterialTheme.typography.labelLarge.tnum() else MaterialTheme.typography.titleMedium.tnum(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Loop Toggle (`Icons.Outlined.Repeat`)
        FilledTonalIconButton(
            onClick = onToggleLoop,
            enabled = hasBoth,
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (hasBoth && loopEnabled) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (hasBoth && loopEnabled) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            modifier = Modifier.size(width = 40.dp, height = 44.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Repeat,
                contentDescription = if (loopEnabled) "挂起循环" else "恢复循环",
                modifier = Modifier.size(20.dp),
            )
        }

        // Clear Loop (`Icons.Outlined.Close`)
        FilledTonalIconButton(
            onClick = onClearLoop,
            enabled = hasA || hasB,
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.size(width = 40.dp, height = 44.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "清除循环",
                modifier = Modifier.size(20.dp),
            )
        }

        // Save Bookmark (`Icons.Outlined.BookmarkAdd`)
        FilledTonalIconButton(
            onClick = onSaveBookmarkClick,
            enabled = hasBoth,
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.size(width = 40.dp, height = 44.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkAdd,
                contentDescription = "保存段落书签",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SpeedAndSecondaryStrip(
    practiceState: PracticeState,
    keepScreenOn: Boolean,
    isVideo: Boolean,
    pitchDrawerOpen: Boolean,
    stemDrawerOpen: Boolean,
    bpmDrawerOpen: Boolean,
    onStepSpeed: (Float) -> Unit,
    onResetSpeed: () -> Unit,
    onTogglePitchDrawer: () -> Unit,
    onToggleStemDrawer: () -> Unit,
    onCycleCountIn: () -> Unit,
    onToggleBpmDrawer: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleVideoMirror: () -> Unit,
) {
    val toggleWidth = if (isVideo) 32.dp else 36.dp
    val spacing = if (isVideo) 3.dp else 4.dp
    val stepperTextStyle = MaterialTheme.typography.labelMedium.tnum().copy(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: `[-5%]` · `[1.00x]` (tap to reset 1.0x) · `[+5%]`
        val speedModified = abs(practiceState.practiceSpeed - 1.0f) >= 0.005f
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (isVideo) 2.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { onStepSpeed(-0.05f) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(0.9f)
                    .height(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "-5%",
                        style = stepperTextStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Surface(
                onClick = onResetSpeed,
                shape = RoundedCornerShape(12.dp),
                color = if (speedModified) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .weight(1.22f)
                    .height(44.dp)
                    .semantics {
                        contentDescription = "当前练习倍速 ${String.format("%.2f", practiceState.practiceSpeed)} 倍，点击恢复 1.0 倍原速"
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = String.format("%.2fx", practiceState.practiceSpeed),
                        style = stepperTextStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = if (speedModified) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Surface(
                onClick = { onStepSpeed(0.05f) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(0.9f)
                    .height(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "+5%",
                        style = stepperTextStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // Right: Pitch, 5-Stem Mixer, Beat-Only Count-In (`4拍`), BPM, Keep Screen On, and optional Video Mirror
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pitchShifted = practiceState.pitchSemitones != 0 || practiceState.pitchCents != 0
            DeckIconToggleButton(
                icon = Icons.Outlined.MusicNote,
                contentDescription = "调整音调",
                highlighted = pitchShifted || pitchDrawerOpen,
                onClick = onTogglePitchDrawer,
                modifier = Modifier.size(width = toggleWidth, height = 44.dp),
            )

            DeckIconToggleButton(
                icon = Icons.Outlined.Tune,
                contentDescription = "分轨乐器音量 (人声/吉他/Bass/鼓/其他)",
                highlighted = !practiceState.stemMix.isUnity || stemDrawerOpen,
                onClick = onToggleStemDrawer,
                modifier = Modifier.size(width = toggleWidth, height = 44.dp),
            )

            val countInActive = practiceState.countInMode != CountInMode.OFF
            DeckIconToggleButton(
                icon = Icons.Outlined.Timer,
                contentDescription = if (countInActive) "预备拍：4拍（已开启）" else "预备拍：已关闭",
                highlighted = countInActive,
                badgeText = if (countInActive) "4拍" else null,
                onClick = onCycleCountIn,
                onLongClick = onToggleBpmDrawer,
                modifier = Modifier.size(width = toggleWidth, height = 44.dp),
            )

            BpmCompactChipButton(
                bpm = practiceState.bpm,
                highlighted = bpmDrawerOpen,
                onClick = onToggleBpmDrawer,
                modifier = Modifier.height(44.dp),
            )

            DeckIconToggleButton(
                icon = Icons.Outlined.LightMode,
                contentDescription = if (keepScreenOn) "练习常亮：已开启" else "练习常亮：已关闭",
                highlighted = keepScreenOn,
                onClick = onToggleKeepScreenOn,
                modifier = Modifier.size(width = toggleWidth, height = 44.dp),
            )

            if (isVideo) {
                DeckIconToggleButton(
                    icon = Icons.Outlined.Flip,
                    contentDescription = if (practiceState.videoMirror) "视频水平镜像：已开启" else "视频水平镜像：已关闭",
                    highlighted = practiceState.videoMirror,
                    onClick = onToggleVideoMirror,
                    modifier = Modifier.size(width = toggleWidth, height = 44.dp),
                )
            }
        }
    }
}

@Composable
internal fun BpmCompactChipButton(
    bpm: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier
            .defaultMinSize(minWidth = 36.dp, minHeight = 44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "BPM 速度 $bpm，点击展开或收起 BPM 与自动测速栏"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "BPM",
                style = TextStyle(
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Medium,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "$bpm",
                style = MaterialTheme.typography.labelMedium.tnum().copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun BpmQuickBar(
    bpm: Int,
    practiceSpeed: Float,
    detectedBpm: Int?,
    isDetectingBpm: Boolean,
    barSnapEnabled: Boolean = true,
    firstDownbeatMs: Long = 0L,
    onAdjustBpm: (Int) -> Unit,
    onTapTempo: () -> Unit,
    onAutoDetectBpm: () -> Unit,
    onToggleBarSnap: () -> Unit = {},
    onAlignDownbeat: () -> Unit = {},
    modifier: Modifier = Modifier,
    bpmPrecise: Double = bpm.toDouble(),
    beatsPerBar: Int = 4,
    beatGridSource: BeatGridSource = BeatGridSource.AUTO_LEGACY,
    beatGridControls: BeatGridControls = BeatGridControls(),
    onShiftDownbeatBeats: (Int) -> Unit = {},
    onNudgeDownbeatMs: (Long) -> Unit = {},
) {
    val confidence = beatGridControls.detectedGrid?.confidence
    val halfBarAmbiguous = confidence?.halfBarAmbiguous == true &&
        beatGridSource != BeatGridSource.MANUAL
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val effectiveBpm = (bpmPrecise * practiceSpeed).roundToInt()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${formatBpmForDisplay(bpmPrecise)} BPM" +
                            if (beatsPerBar != 4) " · $beatsPerBar/4" else "",
                        style = MaterialTheme.typography.labelLarge.tnum(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            append("慢放 ≈ $effectiveBpm")
                            if (detectedBpm != null && detectedBpm != bpm) {
                                append(" · 测得 $detectedBpm")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.tnum(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedIconButton(
                        onClick = { onAdjustBpm(-1) },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "BPM 减 1", modifier = Modifier.size(18.dp))
                    }
                    OutlinedIconButton(
                        onClick = { onAdjustBpm(1) },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "BPM 加 1", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalButton(
                        onClick = onTapTempo,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text("Tap", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = onAutoDetectBpm,
                        enabled = !isDetectingBpm,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text(
                            text = if (isDetectingBpm) "检测中…" else "自动",
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // Measure / Bar-Snap & First-Downbeat Calibration Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = barSnapEnabled,
                    onClick = onToggleBarSnap,
                    label = {
                        Text(
                            text = "小节对齐",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                    modifier = Modifier.height(34.dp),
                )
                FilledTonalButton(
                    onClick = onAlignDownbeat,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .semantics {
                            contentDescription = "将当前播放位置校准为小节第1拍起点"
                        },
                ) {
                    Text(
                        text = "对齐第1拍",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "首拍 ${PracticeMath.formatTimeMs(
                        firstNonNegativeDownbeatMs(firstDownbeatMs, bpmPrecise, beatsPerBar),
                        includeTenths = true,
                    )}" + beatGridSourceLabel(beatGridSource),
                    style = MaterialTheme.typography.labelSmall.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // BeatGrid v2 one-tap Beat-1 insurance: ±1 beat, half-bar flip (±2), ±10 ms nudge.
            DownbeatFixRow(
                halfBarAmbiguous = halfBarAmbiguous,
                onShiftDownbeatBeats = onShiftDownbeatBeats,
                onNudgeDownbeatMs = onNudgeDownbeatMs,
            )

            val pendingGrid = beatGridControls.pendingStemsGrid
            if (pendingGrid != null) {
                PendingStemsGridChip(
                    pendingGrid = pendingGrid,
                    onApply = beatGridControls.onApplyPendingStemsGrid,
                    onDismiss = beatGridControls.onDismissPendingStemsGrid,
                )
            }
        }
    }
}

/**
 * BeatGrid v2 inputs for the BPM drawer that live outside [PracticeState]: the latest detector grid
 * (for its [BeatGridConfidence]) and an offered `.stems` grid (§3.9) with its accept/dismiss actions.
 */
@Immutable
data class BeatGridControls(
    val detectedGrid: BeatGrid? = null,
    val pendingStemsGrid: BeatGrid? = null,
    val onApplyPendingStemsGrid: () -> Unit = {},
    val onDismissPendingStemsGrid: () -> Unit = {},
)

/** "120.4" for fractional tempos, "120" for integral ones. */
internal fun formatBpmForDisplay(bpm: Double): String {
    val rounded2 = (bpm * 100.0).roundToInt() / 100.0
    return if (abs(rounded2 - rounded2.roundToInt()) < 0.005) {
        rounded2.roundToInt().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.2f", rounded2).trimEnd('0').trimEnd('.')
    }
}

/** First bar boundary at or after 0 ms for display (the stored anchor is unfolded and may be < 0). */
internal fun firstNonNegativeDownbeatMs(anchorMs: Long, bpm: Double, beatsPerBar: Int): Long {
    val barMs = 60_000.0 / PracticeMath.clampBpmPrecise(bpm) * beatsPerBar.coerceIn(2, 8)
    if (barMs <= 1.0) return anchorMs.coerceAtLeast(0L)
    val k = kotlin.math.ceil(-anchorMs / barMs - 1e-9)
    return (anchorMs + k * barMs).roundToInt().toLong().coerceAtLeast(0L)
}

private fun beatGridSourceLabel(source: BeatGridSource): String = when (source) {
    BeatGridSource.MANUAL -> " · 手动"
    BeatGridSource.AUTO_STEMS -> " · 分轨"
    BeatGridSource.EMBEDDED_TAG -> " · 标签"
    BeatGridSource.AUTO_MIX, BeatGridSource.AUTO_LEGACY -> ""
}

@Composable
private fun DownbeatFixRow(
    halfBarAmbiguous: Boolean,
    onShiftDownbeatBeats: (Int) -> Unit,
    onNudgeDownbeatMs: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "第1拍",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        DownbeatFixButton(label = "-1拍", description = "第一拍提前1拍") { onShiftDownbeatBeats(-1) }
        DownbeatFixButton(label = "+1拍", description = "第一拍推后1拍") { onShiftDownbeatBeats(1) }
        DownbeatFixButton(
            label = "±2拍",
            description = if (halfBarAmbiguous) {
                "第1拍与第3拍难以区分，点按换到第3拍"
            } else {
                "第一拍换到第3拍（半小节翻转）"
            },
            highlighted = halfBarAmbiguous,
        ) { onShiftDownbeatBeats(2) }
        Spacer(Modifier.width(6.dp))
        DownbeatFixButton(label = "-10ms", description = "第一拍提前10毫秒") { onNudgeDownbeatMs(-10L) }
        DownbeatFixButton(label = "+10ms", description = "第一拍推后10毫秒") { onNudgeDownbeatMs(10L) }
    }
}

@Composable
private fun DownbeatFixButton(
    label: String,
    description: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .height(32.dp)
        .semantics { contentDescription = description }
    if (highlighted) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            modifier = modifier,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = modifier,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun PendingStemsGridChip(
    pendingGrid: BeatGrid,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistChip(
            onClick = onApply,
            label = {
                Text(
                    text = "检测到更准的节拍网格 (${formatBpmForDisplay(pendingGrid.bpmPrecise)}) — 应用?",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.height(32.dp)) {
            Text("忽略", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
internal fun DeckIconToggleButton(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(44.dp),
    badgeText: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    if (onLongClick != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
        ) {
            DeckIconBadgeContent(
                icon = icon,
                contentDescription = null,
                contentColor = contentColor,
                badgeText = badgeText,
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            modifier = modifier,
        ) {
            DeckIconBadgeContent(
                icon = icon,
                contentDescription = contentDescription,
                contentColor = contentColor,
                badgeText = badgeText,
            )
        }
    }
}

@Composable
private fun DeckIconBadgeContent(
    icon: ImageVector,
    contentDescription: String?,
    contentColor: androidx.compose.ui.graphics.Color,
    badgeText: String?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier
                .size(if (badgeText != null) 18.dp else 20.dp)
                .offset(y = if (badgeText != null) (-2).dp else 0.dp),
        )
        if (badgeText != null) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.tnum().copy(
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun PitchShiftInlinePanel(
    semitones: Int,
    cents: Int,
    onUpdatePitch: (Int, Int) -> Unit,
) {
    val isShifted = semitones != 0 || cents != 0
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { onUpdatePitch(semitones - 1, cents) },
                    enabled = semitones > -6,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text("-1 半音")
                }
                TextButton(
                    onClick = { onUpdatePitch(0, 0) },
                    enabled = isShifted,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    val stSign = if (semitones > 0) "+" else ""
                    Text(
                        text = if (!isShifted) {
                            "原调 (0 st)"
                        } else {
                            "${stSign}${semitones} st · 恢复原调"
                        },
                        style = MaterialTheme.typography.labelLarge.tnum(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FilledTonalButton(
                    onClick = { onUpdatePitch(semitones + 1, cents) },
                    enabled = semitones < 6,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text("+1 半音")
                }
            }

            Text(
                text = when (semitones) {
                    -1 -> "提示：将标准调原曲降半音至 Eb（无需改调弦）"
                    1 -> "提示：+1 半音 = 用标准调弦吉他跟弹 Eb 降半音原曲"
                    else -> "范围 ±6 半音；下方可做 ±50 ct 音分微调校准"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onUpdatePitch(semitones, (cents - 5).coerceAtLeast(-50)) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("-5c", style = MaterialTheme.typography.labelMedium.tnum())
                }
                Slider(
                    value = cents.toFloat(),
                    onValueChange = { next ->
                        onUpdatePitch(semitones, next.roundToInt().coerceIn(-50, 50))
                    },
                    valueRange = -50f..50f,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "音分微调 $cents ct"
                        },
                )
                Text(
                    text = "${if (cents > 0) "+$cents" else "$cents"} ct",
                    style = MaterialTheme.typography.labelMedium.tnum(),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
                OutlinedButton(
                    onClick = { onUpdatePitch(semitones, (cents + 5).coerceAtMost(50)) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("+5c", style = MaterialTheme.typography.labelMedium.tnum())
                }
            }
        }
    }
}

@Composable
private fun StemMixerInlinePanel(
    itemId: Long,
    stemMix: StemMixConfig,
    stemAnalysisState: StemAnalysisState,
    onDownloadStemModel: () -> Unit,
    onAnalyzeTrackStems: () -> Unit,
    onCancelStemOperation: () -> Unit,
    onStemVolumeChange: (StemType, Float, Boolean) -> Unit,
    onToggleStemMute: (StemType) -> Unit,
    onToggleStemSolo: (StemType) -> Unit,
    onResetStemMix: () -> Unit,
) {
    val isTrackReady = stemAnalysisState is StemAnalysisState.Ready &&
        (itemId <= 0L || stemAnalysisState.itemId == itemId)
    val isModelMissing = stemAnalysisState is StemAnalysisState.ModelNotInstalled ||
        stemAnalysisState is StemAnalysisState.DownloadingModel ||
        (stemAnalysisState is StemAnalysisState.Error && stemAnalysisState.modelMissing)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isModelMissing) {
                // Step 1: Prominent card when htdemucs_6s.onnx is not yet downloaded
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "首次使用需下载 HTDemucs 6s 专业分离模型 (~100 MB)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = if (stemAnalysisState is StemAnalysisState.Error) {
                                stemAnalysisState.message
                            } else {
                                "Meta HTDemucs 混合 Transformer 端侧离线分离 5 轨音频（下载一次后永久本地可用）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stemAnalysisState is StemAnalysisState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
                            },
                        )

                        if (stemAnalysisState is StemAnalysisState.DownloadingModel) {
                            val pct = (stemAnalysisState.progress * 100f).roundToInt().coerceIn(0, 100)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "正在下载 HTDemucs 模型 $pct%...",
                                    style = MaterialTheme.typography.labelMedium.tnum(),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(
                                    onClick = onCancelStemOperation,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("取消", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            LinearProgressIndicator(
                                progress = { stemAnalysisState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                        } else {
                            Button(
                                onClick = onDownloadStemModel,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "下载 HTDemucs 模型",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            } else if (!isTrackReady) {
                // Step 2: Model is downloaded, current track not yet separated (or currently separating)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (stemAnalysisState is StemAnalysisState.AnalyzingTrack &&
                            (itemId <= 0L || stemAnalysisState.itemId == itemId)
                        ) {
                            val pct = (stemAnalysisState.progress * 100f).roundToInt().coerceIn(0, 100)
                            val statusText = if (stemAnalysisState.totalSegments > 0 && stemAnalysisState.currentSegment > 0) {
                                "正在分离音轨 ${stemAnalysisState.currentSegment}/${stemAnalysisState.totalSegments} ($pct%)..."
                            } else {
                                "正在解码音频准备分离 ($pct%)..."
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelMedium.tnum(),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                }
                                TextButton(
                                    onClick = onCancelStemOperation,
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("取消", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            LinearProgressIndicator(
                                progress = { stemAnalysisState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                        } else {
                            if (stemAnalysisState is StemAnalysisState.Error) {
                                Text(
                                    text = stemAnalysisState.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = onAnalyzeTrackStems,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "使用 HTDemucs 分离当前曲目 (5轨)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                if (!stemMix.isUnity) {
                                    Spacer(Modifier.width(6.dp))
                                    TextButton(
                                        onClick = onResetStemMix,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(38.dp),
                                    ) {
                                        Text(
                                            text = "重置",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Step 3: Track separated & cached on disk -> instant 0s real-time 5-stem mixer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "HTDemucs 5轨已就绪",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }

                    TextButton(
                        onClick = onResetStemMix,
                        enabled = !stemMix.isUnity,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(
                            text = "重置",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 5 Independent Stem Rows: 人声 / 吉他 / Bass / 鼓 / 其他 (0%–150%, Mute M, Solo S)
            val controlsEnabled = !isModelMissing
            StemType.entries.forEach { stem ->
                val sliderGain = stemMix.sliderGain(stem)
                val effectiveGain = stemMix.effectiveGain(stem)
                val isMuted = stem in stemMix.mutedStems
                val isSolo = stemMix.soloStem == stem
                val isAudible = effectiveGain > 0.001f && controlsEnabled

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stem.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSolo || abs(sliderGain - 1.0f) >= 0.01f) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (isAudible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(34.dp),
                    )

                    Slider(
                        value = sliderGain,
                        enabled = controlsEnabled,
                        onValueChange = { next ->
                            onStemVolumeChange(stem, next.coerceIn(0f, 1.5f), false)
                        },
                        onValueChangeFinished = {
                            onStemVolumeChange(stem, stemMix.sliderGain(stem), true)
                        },
                        valueRange = 0f..1.5f,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "${stem.label} 音量 ${(sliderGain * 100f).roundToInt()}%"
                            },
                    )

                    Text(
                        text = "${(effectiveGain * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall.tnum(),
                        color = if (isAudible) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(38.dp),
                    )

                    // Mute (`M`) pill button
                    Surface(
                        onClick = { onToggleStemMute(stem) },
                        enabled = controlsEnabled,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isMuted) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .size(width = 30.dp, height = 30.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = if (isMuted) "${stem.label} 取消静音" else "${stem.label} 静音"
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "M",
                                style = MaterialTheme.typography.labelSmall.tnum(),
                                fontWeight = FontWeight.Bold,
                                color = if (isMuted) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    // Solo (`S`) pill button
                    Surface(
                        onClick = { onToggleStemSolo(stem) },
                        enabled = controlsEnabled,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSolo) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .size(width = 30.dp, height = 30.dp)
                            .semantics {
                                role = Role.Button
                                contentDescription = if (isSolo) "${stem.label} 取消独奏" else "${stem.label} 独奏"
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "S",
                                style = MaterialTheme.typography.labelSmall.tnum(),
                                fontWeight = FontWeight.Bold,
                                color = if (isSolo) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
