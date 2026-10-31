@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.miniplayer.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.model.*
import com.miniplayer.app.player.AbLoopUiState
import com.miniplayer.app.player.PracticeLoopEngine
import com.miniplayer.app.ui.theme.tnum
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * `44dp` RMS Waveform + Precision A-B Loop Ruler with `48×48dp` bottom waterdrop drag handles
 * (`DESIGN.md` §5.2 #1) so dragging `A` or `B` never occludes the waveform or millisecond readout.
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
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var widthPx by remember { mutableFloatStateOf(1f) }
    var draggingHandle by remember { mutableStateOf<Char?>(null) } // 'A' or 'B'
    var dragTempAMs by remember(activeAMs) { mutableLongStateOf(activeAMs ?: 0L) }
    var dragTempBMs by remember(activeBMs) { mutableLongStateOf(activeBMs ?: safeDuration) }

    val shownAMs = if (draggingHandle != null && activeAMs != null) dragTempAMs else activeAMs
    val shownBMs = if (draggingHandle != null && activeBMs != null) dragTempBMs else activeBMs

    val primaryColor = MaterialTheme.colorScheme.primary
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
                        append(if (loopEnabled) " ━━●━━ " else " ╌╌○╌╌ ")
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
                )
            }
            Text(
                text = PracticeMath.formatTimeMs(durationMs, includeTenths = true),
                style = MaterialTheme.typography.labelMedium.tnum(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        // 44dp Waveform + 28dp Waterdrop Handle Gutter (total interactive height 72dp, handles >= 48x48dp hit zone)
        val hasBothPoints = shownAMs != null && shownBMs != null
        val canvasHeight = if (hasBothPoints) 70.dp else 48.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = "音频波形与 A-B 循环标尺，当前播放位置 ${PracticeMath.formatTimeMs(positionMs, true)}"
                }
                .pointerInput(safeDuration, activeAMs, activeBMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / widthPx).coerceIn(0f, 1f)
                        onSeek((frac * safeDuration).toLong())
                    }
                }
                .pointerInput(safeDuration, activeAMs, activeBMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            val a = activeAMs
                            val b = activeBMs
                            if (a != null && b != null) {
                                val aX = (a.toFloat() / safeDuration) * widthPx
                                val bX = (b.toFloat() / safeDuration) * widthPx
                                val hitRadius = 32.dp.toPx()
                                val distA = abs(startOffset.x - aX)
                                val distB = abs(startOffset.x - bX)
                                draggingHandle = when {
                                    distA <= hitRadius && distA <= distB -> 'A'
                                    distB <= hitRadius -> 'B'
                                    else -> null
                                }
                                dragTempAMs = a
                                dragTempBMs = b
                            } else {
                                draggingHandle = null
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            val handle = draggingHandle
                            if (handle != null) {
                                change.consume()
                                val deltaMs = ((dragAmount / widthPx) * safeDuration).toLong()
                                if (handle == 'A') {
                                    dragTempAMs = (dragTempAMs + deltaMs)
                                        .coerceIn(0L, (dragTempBMs - PracticeMath.MIN_LOOP_SPAN_MS).coerceAtLeast(0L))
                                } else {
                                    dragTempBMs = (dragTempBMs + deltaMs)
                                        .coerceIn((dragTempAMs + PracticeMath.MIN_LOOP_SPAN_MS).coerceAtMost(safeDuration), safeDuration)
                                }
                                onDragLoopPoints(dragTempAMs, dragTempBMs, false)
                            } else {
                                change.consume()
                                val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                                onSeek((frac * safeDuration).toLong())
                            }
                        },
                        onDragEnd = {
                            if (draggingHandle != null) {
                                onDragLoopPoints(dragTempAMs, dragTempBMs, true)
                            }
                            draggingHandle = null
                        },
                        onDragCancel = {
                            draggingHandle = null
                        },
                    )
                },
        ) {
            val waveHeight = 44.dp.toPx()
            val w = size.width
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

            // 4. A & B vertical boundary lines + bottom waterdrop handles (`48x48dp` interactive zone)
            fun drawWaterdropMarker(markerMs: Long) {
                val x = ((markerMs.toFloat() / safeDuration) * w).coerceIn(8.dp.toPx(), w - 8.dp.toPx())
                drawLine(
                    color = loopEdgeColor,
                    start = Offset(x, 0f),
                    end = Offset(x, waveHeight + 6.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx(),
                )
                if (hasBothPoints) {
                    val dropRadius = 9.dp.toPx()
                    val dropCenterY = waveHeight + 15.dp.toPx()
                    val path = Path().apply {
                        moveTo(x, waveHeight + 2.dp.toPx())
                        lineTo(x - dropRadius, dropCenterY)
                        lineTo(x + dropRadius, dropCenterY)
                        close()
                    }
                    drawPath(path, color = loopEdgeColor)
                    drawCircle(
                        color = loopEdgeColor,
                        radius = dropRadius,
                        center = Offset(x, dropCenterY),
                    )
                }
            }

            if (shownAMs != null) drawWaterdropMarker(shownAMs)
            if (shownBMs != null) drawWaterdropMarker(shownBMs)
        }
    }
}

/**
 * Horizontal section bookmark strip (`[+ 存书签] [Intro] [Verse] [★ Solo 1]`) with 1-tap preset
 * naming sheet and long-press edit/delete menu (`DESIGN.md` §5.2 #3).
 */
@Composable
fun SectionBookmarksRow(
    bookmarks: List<PracticeBookmark>,
    activeBookmarkId: Long?,
    hasActiveLoop: Boolean,
    onActivateBookmark: (PracticeBookmark) -> Unit,
    onSaveNewBookmark: (label: String) -> Unit,
    onUpdateBookmarkRange: (PracticeBookmark) -> Unit,
    onRenameBookmark: (PracticeBookmark, String) -> Unit,
    onDeleteBookmark: (PracticeBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPresetSaveSheet by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<PracticeBookmark?>(null) }
    var renameText by remember { mutableStateOf("") }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            AssistChip(
                onClick = { showPresetSaveSheet = true },
                enabled = hasActiveLoop,
                label = { Text("+ 存书签") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            )
        }

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
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onActivateBookmark(bm) },
                            onLongClick = { menuExpanded = true },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = buildString {
                                if (isSelected) append("★ ")
                                append(bm.label)
                                append(" ")
                                append(PracticeMath.formatTimeMs(bm.aMs))
                                append("–")
                                append(PracticeMath.formatTimeMs(bm.bMs))
                            },
                            style = MaterialTheme.typography.labelLarge.tnum(),
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

    if (showPresetSaveSheet) {
        var customLabel by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { showPresetSaveSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("保存段落书签（单手点选预设即刻保存）", style = MaterialTheme.typography.titleMedium)
                // 1-Tap Preset Chips (`Intro / Verse / Pre-Chorus / Chorus / Bridge / Solo 1 / Solo 2 / Outro`)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PracticeMath.PRESET_SECTION_LABELS.chunked(4).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowPresets.forEach { preset ->
                                FilledTonalButton(
                                    onClick = {
                                        showPresetSaveSheet = false
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
                                showPresetSaveSheet = false
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

private enum class ExpandedDeckDrawer {
    NONE,
    SPEED_SLIDER,
    SPEED_TRAINER,
    PITCH_SHIFT,
}

/**
 * Guitar-pick-friendly `练习工作台 (Practice Deck)` (`surfaceContainer`, `24dp` corners) strictly
 * verified for `360dp` narrow portrait screens so every touch target is `>= 48dp` (`DESIGN.md` §5.2).
 */
@Composable
fun PracticeDeck(
    loopEngine: PracticeLoopEngine,
    practiceState: PracticeState,
    speedTrainer: SpeedTrainerConfig,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
) {
    var expandedDrawer by remember { mutableStateOf(ExpandedDeckDrawer.NONE) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Disambiguated 3-State A-B Loop Controls + Nudge Row (§5.2 #2)
            when (loopEngine.abUiState) {
                AbLoopUiState.IDLE -> {
                    Button(
                        onClick = { loopEngine.markPointA(currentPositionMs) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Text(
                            text = "设 A 点 (${PracticeMath.formatTimeMs(currentPositionMs, includeTenths = true)})",
                            style = MaterialTheme.typography.titleMedium.tnum(),
                        )
                    }
                }
                AbLoopUiState.A_SET_WAITING -> {
                    val aMs = practiceState.activeAMs ?: 0L
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.height(56.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    text = "A: ${PracticeMath.formatTimeMs(aMs, true)}",
                                    style = MaterialTheme.typography.labelLarge.tnum(),
                                )
                            }
                        }
                        Button(
                            onClick = { loopEngine.markPointBAndLoop(currentPositionMs) },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Text("设 B 点并循环", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalIconButton(
                            onClick = { loopEngine.clearLoop() },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.size(width = 52.dp, height = 56.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除 A 点")
                        }
                    }
                }
                AbLoopUiState.ACTIVE_OR_SUSPENDED -> {
                    // Row 1 (52dp): `[↺ 回A]` · `[循环: 开 / 挂起]` · `[✕ 清除]`
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { loopEngine.restartFromA() },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) {
                            Text("↺ 回A", maxLines = 1)
                        }
                        Button(
                            onClick = { loopEngine.toggleLoopSuspended() },
                            shape = RoundedCornerShape(14.dp),
                            colors = if (practiceState.loopEnabled) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(1.25f)
                                .height(52.dp),
                        ) {
                            Text(
                                text = if (practiceState.loopEnabled) "循环: 开" else "循环: 挂起",
                                maxLines = 1,
                            )
                        }
                        OutlinedButton(
                            onClick = { loopEngine.clearLoop() },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .height(52.dp),
                        ) {
                            Text("✕ 清除", maxLines = 1)
                        }
                    }

                    // Row 2 (48dp): `[A -0.5 | +0.5]` & `[B -0.5 | +0.5]` (>= 74x48dp per half, long-press ±0.1s)
                    val aLabel = PracticeMath.formatTimeMs(practiceState.activeAMs ?: 0L, true)
                    val bLabel = PracticeMath.formatTimeMs(practiceState.activeBMs ?: 0L, true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SplitNudgeControl(
                            prefix = "A",
                            currentFormatted = aLabel,
                            onCoarseMinus = { loopEngine.nudgePointA(-PracticeMath.COARSE_NUDGE_MS) },
                            onFineMinus = { loopEngine.nudgePointA(-PracticeMath.FINE_NUDGE_MS) },
                            onCoarsePlus = { loopEngine.nudgePointA(PracticeMath.COARSE_NUDGE_MS) },
                            onFinePlus = { loopEngine.nudgePointA(PracticeMath.FINE_NUDGE_MS) },
                            modifier = Modifier.weight(1f),
                        )
                        SplitNudgeControl(
                            prefix = "B",
                            currentFormatted = bLabel,
                            onCoarseMinus = { loopEngine.nudgePointB(-PracticeMath.COARSE_NUDGE_MS) },
                            onFineMinus = { loopEngine.nudgePointB(-PracticeMath.FINE_NUDGE_MS) },
                            onCoarsePlus = { loopEngine.nudgePointB(PracticeMath.COARSE_NUDGE_MS) },
                            onFinePlus = { loopEngine.nudgePointB(PracticeMath.FINE_NUDGE_MS) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 2. Pitch-Preserving Speed Control: Upper Stepper (64dp / center / 64dp) + Lower LazyRow Presets (>= 58x48dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { loopEngine.stepPracticeSpeed(-0.05f) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(width = 64.dp, height = 48.dp),
                ) {
                    Text("-5%", style = MaterialTheme.typography.labelLarge.tnum())
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = { loopEngine.setPracticeSpeed(1.0f) },
                            onLongClick = {
                                expandedDrawer = if (expandedDrawer == ExpandedDeckDrawer.SPEED_SLIDER) {
                                    ExpandedDeckDrawer.NONE
                                } else {
                                    ExpandedDeckDrawer.SPEED_SLIDER
                                }
                            },
                        )
                        .semantics {
                            contentDescription = "当前练习倍速 ${String.format("%.2f", practiceState.practiceSpeed)} 倍，单击恢复原速，长按展开连续滑杆"
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val pitchTag = if (practiceState.pitchSemitones == 0 && practiceState.pitchCents == 0) {
                            "原调"
                        } else {
                            val sign = if (practiceState.pitchSemitones > 0) "+" else ""
                            "${sign}${practiceState.pitchSemitones}st"
                        }
                        Text(
                            text = String.format("● %.2fx (%s)", practiceState.practiceSpeed, pitchTag),
                            style = MaterialTheme.typography.labelLarge.tnum(),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { loopEngine.stepPracticeSpeed(0.05f) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(width = 64.dp, height = 48.dp),
                ) {
                    Text("+5%", style = MaterialTheme.typography.labelLarge.tnum())
                }
            }

            AnimatedVisibility(visible = expandedDrawer == ExpandedDeckDrawer.SPEED_SLIDER) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Slider(
                        value = practiceState.practiceSpeed,
                        onValueChange = { loopEngine.setPracticeSpeed(it) },
                        valueRange = 0.25f..2.00f,
                    )
                }
            }

            // Preset Pills Row: each >= 58x48dp (§4.3A & §5.2 #4)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(PracticeMath.SPEED_PRESETS) { preset ->
                    val selected = abs(practiceState.practiceSpeed - preset) < 0.008f
                    FilterChip(
                        selected = selected,
                        onClick = { loopEngine.setPracticeSpeed(preset) },
                        label = {
                            val txt = String.format("%.2fx", preset)
                                .replace(".00x", ".0x")
                                .replace("0.50x", "0.5x")
                                .replace("0.90x", "0.9x")
                                .replace("1.50x", "1.5x")
                            Text(txt, style = MaterialTheme.typography.labelLarge.tnum())
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 48.dp),
                    )
                }
            }

            // 3. Practice Helpers Row: `[⏱ 预备]` · `[📈 加速]` · `[🎵 升降调]` (all >= 48dp high)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Count-In / Pre-Roll pill (tap cycles OFF->1s->2s->4拍, long-press toggles Pre-Roll)
                val countInActive = practiceState.countInMode != CountInMode.OFF || practiceState.preRollEnabled
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (countInActive) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { loopEngine.cycleCountInMode() },
                            onLongClick = { loopEngine.togglePreRoll() },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            text = buildString {
                                append("⏱ 预备:${countInLabel(practiceState.countInMode)}")
                                if (practiceState.preRollEnabled) append("+前导")
                            },
                            style = MaterialTheme.typography.labelMedium.tnum(),
                            color = if (countInActive) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                        )
                    }
                }

                // Speed Trainer pill
                Surface(
                    onClick = {
                        expandedDrawer = if (expandedDrawer == ExpandedDeckDrawer.SPEED_TRAINER) {
                            ExpandedDeckDrawer.NONE
                        } else {
                            ExpandedDeckDrawer.SPEED_TRAINER
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (speedTrainer.enabled) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                        val label = if (speedTrainer.enabled) {
                            String.format(
                                "📈 %.2fx→%.2fx (%d/%d)",
                                practiceState.practiceSpeed,
                                speedTrainer.targetSpeed,
                                speedTrainer.currentLoopInStep + 1,
                                speedTrainer.loopsPerStep,
                            )
                        } else {
                            "📈 渐进加速"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.tnum(),
                            color = if (speedTrainer.enabled) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                        )
                    }
                }

                // Semitone / Cents Pitch Shift pill (warm highlight whenever non-zero per §5.2 #7)
                val pitchShifted = practiceState.pitchSemitones != 0 || practiceState.pitchCents != 0
                Surface(
                    onClick = {
                        expandedDrawer = if (expandedDrawer == ExpandedDeckDrawer.PITCH_SHIFT) {
                            ExpandedDeckDrawer.NONE
                        } else {
                            ExpandedDeckDrawer.PITCH_SHIFT
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (pitchShifted) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                        val pitchText = if (!pitchShifted) {
                            "🎵 原调"
                        } else {
                            val stSign = if (practiceState.pitchSemitones > 0) "+" else ""
                            buildString {
                                append("🎵 ${stSign}${practiceState.pitchSemitones}半音")
                                if (practiceState.pitchCents != 0) {
                                    val cSign = if (practiceState.pitchCents > 0) "+" else ""
                                    append(" ${cSign}${practiceState.pitchCents}c")
                                }
                            }
                        }
                        Text(
                            text = pitchText,
                            style = MaterialTheme.typography.labelMedium.tnum(),
                            color = if (pitchShifted) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            // Inline `4拍` BPM & Tap Tempo Bar (`[🥁 120 BPM [-] [+] [点按测速 Tap]]` per §5.2 #6)
            AnimatedVisibility(visible = practiceState.countInMode == CountInMode.BEAT_4) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val effectiveBpm = (practiceState.bpm * practiceState.practiceSpeed).roundToInt()
                        Column {
                            Text(
                                text = "🥁 ${practiceState.bpm} BPM",
                                style = MaterialTheme.typography.labelLarge.tnum(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "慢放联动拍速 ≈ $effectiveBpm BPM",
                                style = MaterialTheme.typography.labelMedium.tnum(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedIconButton(
                                onClick = { loopEngine.setBpm(practiceState.bpm - 1) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Outlined.Remove, contentDescription = "BPM 减 1")
                            }
                            OutlinedIconButton(
                                onClick = { loopEngine.setBpm(practiceState.bpm + 1) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = "BPM 加 1")
                            }
                            Button(
                                onClick = { loopEngine.tapTempo() },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text("点按测速 Tap")
                            }
                        }
                    }
                }
            }

            // Inline Speed Trainer Configuration Panel (§5.2 #5)
            AnimatedVisibility(visible = expandedDrawer == ExpandedDeckDrawer.SPEED_TRAINER) {
                SpeedTrainerInlinePanel(
                    config = speedTrainer,
                    currentSpeed = practiceState.practiceSpeed,
                    onUpdateConfig = { loopEngine.configureSpeedTrainer(it) },
                )
            }

            // Inline Semitone & Cents Pitch Shift Panel (§5.2 #7)
            AnimatedVisibility(visible = expandedDrawer == ExpandedDeckDrawer.PITCH_SHIFT) {
                PitchShiftInlinePanel(
                    semitones = practiceState.pitchSemitones,
                    cents = practiceState.pitchCents,
                    onUpdatePitch = { st, ct -> loopEngine.setPitchShift(st, ct) },
                )
            }
        }
    }
}

@Composable
private fun SplitNudgeControl(
    prefix: String,
    currentFormatted: String,
    onCoarseMinus: () -> Unit,
    onFineMinus: () -> Unit,
    onCoarsePlus: () -> Unit,
    onFinePlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RepeatingNudgeHalf(
                label = "$prefix -0.5",
                talkBackLabel = "将循环点 $prefix 提前 0.5 秒（长按精细微调 0.1 秒），当前 $currentFormatted",
                onTap = onCoarseMinus,
                onLongRepeat = onFineMinus,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minWidth = 74.dp, minHeight = 48.dp),
            )
            VerticalDivider(modifier = Modifier.height(24.dp))
            RepeatingNudgeHalf(
                label = "+0.5",
                talkBackLabel = "将循环点 $prefix 延后 0.5 秒（长按精细微调 0.1 秒），当前 $currentFormatted",
                onTap = onCoarsePlus,
                onLongRepeat = onFinePlus,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minWidth = 74.dp, minHeight = 48.dp),
            )
        }
    }
}

@Composable
private fun RepeatingNudgeHalf(
    label: String,
    talkBackLabel: String,
    onTap: () -> Unit,
    onLongRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnRepeat by rememberUpdatedState(onLongRepeat)

    LaunchedEffect(interactionSource) {
        var isHeld = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isHeld = true
                    delay(360L)
                    if (isHeld) {
                        while (isHeld) {
                            currentOnRepeat()
                            delay(110L)
                        }
                    }
                }
                is PressInteraction.Release -> {
                    if (isHeld) {
                        isHeld = false
                    }
                }
                is PressInteraction.Cancel -> {
                    isHeld = false
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { contentDescription = talkBackLabel }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        val startMs = android.os.SystemClock.elapsedRealtime()
                        interactionSource.emit(press)
                        val released = tryAwaitRelease()
                        val heldMs = android.os.SystemClock.elapsedRealtime() - startMs
                        if (released) {
                            interactionSource.emit(PressInteraction.Release(press))
                            if (heldMs < 360L) {
                                currentOnTap()
                            }
                        } else {
                            interactionSource.emit(PressInteraction.Cancel(press))
                        }
                    },
                )
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.tnum(),
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeedTrainerInlinePanel(
    config: SpeedTrainerConfig,
    currentSpeed: Float,
    onUpdateConfig: (SpeedTrainerConfig) -> Unit,
) {
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
                Text(
                    text = String.format(
                        "渐进加速：%.2fx → %.2fx",
                        if (config.enabled) currentSpeed else config.startSpeed,
                        config.targetSpeed,
                    ),
                    style = MaterialTheme.typography.labelLarge.tnum(),
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateConfig(
                            config.copy(
                                enabled = enabled,
                                startSpeed = if (enabled) currentSpeed else config.startSpeed,
                            ),
                        )
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每遍数:", style = MaterialTheme.typography.labelMedium)
                listOf(2, 4, 8).forEach { n ->
                    FilterChip(
                        selected = config.loopsPerStep == n,
                        onClick = { onUpdateConfig(config.copy(loopsPerStep = n)) },
                        label = { Text("${n}遍", style = MaterialTheme.typography.labelMedium.tnum()) },
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("步进:", style = MaterialTheme.typography.labelMedium)
                listOf(0.02f to "+2%", 0.05f to "+5%", 0.10f to "+10%").forEach { (step, txt) ->
                    FilterChip(
                        selected = abs(config.stepSpeed - step) < 0.005f,
                        onClick = { onUpdateConfig(config.copy(stepSpeed = step)) },
                        label = { Text(txt, style = MaterialTheme.typography.labelMedium.tnum()) },
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("目标倍速:", style = MaterialTheme.typography.labelMedium)
                listOf(0.85f, 1.00f, 1.10f, 1.25f).forEach { target ->
                    FilterChip(
                        selected = abs(config.targetSpeed - target) < 0.008f,
                        onClick = { onUpdateConfig(config.copy(targetSpeed = target)) },
                        label = { Text(String.format("%.2fx", target), style = MaterialTheme.typography.labelMedium.tnum()) },
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PitchShiftInlinePanel(
    semitones: Int,
    cents: Int,
    onUpdatePitch: (Int, Int) -> Unit,
) {
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
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("-1 半音")
                }
                TextButton(
                    onClick = { onUpdatePitch(0, 0) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    val stSign = if (semitones > 0) "+" else ""
                    Text(
                        text = if (semitones == 0 && cents == 0) "原调 (0 st)" else "${stSign}${semitones} st · 复位",
                        style = MaterialTheme.typography.labelLarge.tnum(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FilledTonalButton(
                    onClick = { onUpdatePitch(semitones + 1, cents) },
                    enabled = semitones < 6,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("+1 半音")
                }
            }

            Text(
                text = when (semitones) {
                    -1 -> "提示：将标准调原曲降半音至 Eb（无需改调弦）"
                    1 -> "提示：+1 半音 = 用标准调弦吉他跟弹 Eb 降半音原曲"
                    else -> "范围 ±6 半音；下方可做 ±50 音分微偏校准"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onUpdatePitch(semitones, (cents - 5).coerceAtLeast(-50)) },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text("-5c", style = MaterialTheme.typography.labelMedium.tnum())
                }
                Text(
                    text = "音分校准: ${if (cents > 0) "+$cents" else "$cents"} cents",
                    style = MaterialTheme.typography.labelMedium.tnum(),
                )
                OutlinedButton(
                    onClick = { onUpdatePitch(semitones, (cents + 5).coerceAtMost(50)) },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Text("+5c", style = MaterialTheme.typography.labelMedium.tnum())
                }
            }
        }
    }
}
